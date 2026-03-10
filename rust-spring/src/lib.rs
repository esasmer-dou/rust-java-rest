
use hyper::{Request, Response, StatusCode, body::Incoming};
use hyper::service::service_fn;

use http_body_util::BodyExt;
use hyper_util::rt::TokioExecutor;
use hyper_util::server::conn::auto::Builder;

use jni::JNIEnv;
use jni::JavaVM;
use jni::objects::{GlobalRef, JString, JValue, JClass, JObject};
use jni::sys::jint;

use tokio::net::TcpListener;
use tokio::task;

use std::collections::HashMap;
use std::sync::{OnceLock, RwLock};

use crossbeam_queue::ArrayQueue;

// Buffer sizes
const SMALL_CAP: usize = 16 * 1024;    // 16 KB
const MEDIUM_CAP: usize = 64 * 1024;   // 64 KB
const LARGE_CAP: usize = 256 * 1024;   // 256 KB
const HUGE_CAP: usize = 1024 * 1024;   // 1 MB

// Pool sizes (max buffers per bucket)
const POOL_SIZE: usize = 64;

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
enum Bucket {
    Small,
    Medium,
    Large,
    Huge,
    Oversized, // Not pooled
}

/// Lock-free buffer pools using crossbeam ArrayQueue
/// Each bucket is a separate lock-free queue
struct BufferPools {
    small: ArrayQueue<Vec<u8>>,
    medium: ArrayQueue<Vec<u8>>,
    large: ArrayQueue<Vec<u8>>,
    huge: ArrayQueue<Vec<u8>>,
}

static BUFFER_POOL: OnceLock<BufferPools> = OnceLock::new();

fn buffer_pool() -> &'static BufferPools {
    BUFFER_POOL.get_or_init(|| {
        BufferPools {
            small: ArrayQueue::new(POOL_SIZE),
            medium: ArrayQueue::new(POOL_SIZE),
            large: ArrayQueue::new(POOL_SIZE),
            huge: ArrayQueue::new(POOL_SIZE),
        }
    })
}

/// Pre-warm buffer pools on startup (minimal for low memory)
fn warmup_pools() {
    let pools = buffer_pool();
    // Minimal warmup: 4 small buffers = 64 KB
    // Medium/large buffers allocated on-demand
    for _ in 0..4 {
        let _ = pools.small.push(Vec::with_capacity(SMALL_CAP));
    }
    println!("[RUST] Buffer pools warmed up (minimal mode)");
}

fn bucket_for_size(size: usize) -> (Bucket, usize) {
    if size <= SMALL_CAP {
        (Bucket::Small, SMALL_CAP)
    } else if size <= MEDIUM_CAP {
        (Bucket::Medium, MEDIUM_CAP)
    } else if size <= LARGE_CAP {
        (Bucket::Large, LARGE_CAP)
    } else if size <= HUGE_CAP {
        (Bucket::Huge, HUGE_CAP)
    } else {
        (Bucket::Oversized, size)
    }
}

/// Rent buffer from lock-free pool
/// No mutex contention - uses lock-free operations
fn rent_buffer(min_capacity: usize) -> Vec<u8> {
    let (bucket, target_cap) = bucket_for_size(min_capacity);
    let pools = buffer_pool();

    // Try to get from pool (lock-free)
    let pooled = match bucket {
        Bucket::Small => pools.small.pop(),
        Bucket::Medium => pools.medium.pop(),
        Bucket::Large => pools.large.pop(),
        Bucket::Huge => pools.huge.pop(),
        Bucket::Oversized => None,
    };

    match pooled {
        Some(mut buf) => {
            buf.clear();
            buf
        }
        None => Vec::with_capacity(target_cap),
    }
}

/// Return buffer to lock-free pool
/// No mutex contention - uses lock-free operations
fn return_buffer(mut buf: Vec<u8>) {
    let cap = buf.capacity();
    let (bucket, _) = bucket_for_size(cap);

    if bucket == Bucket::Oversized {
        return; // Don't pool oversized buffers
    }

    buf.clear();
    let pools = buffer_pool();

    // Try to return to pool (lock-free, fails silently if full)
    let _ = match bucket {
        Bucket::Small => pools.small.push(buf),
        Bucket::Medium => pools.medium.push(buf),
        Bucket::Large => pools.large.push(buf),
        Bucket::Huge => pools.huge.push(buf),
        Bucket::Oversized => Ok(()),
    };
}


fn encode_headers(headers: &hyper::HeaderMap) -> String {
    let mut s = String::new();

    for (name, value) in headers.iter() {
        if let Ok(v) = value.to_str() {
            s.push_str(name.as_str());
            s.push_str(": ");
            s.push_str(v);
            s.push('\n');
        }
    }
    s
}


fn encode_path_params(params: &[(String, String)]) -> String {
    let mut s = String::new();
    for (i, (k, v)) in params.iter().enumerate() {
        if i > 0 {
            s.push('&');
        }
        s.push_str(k);
        s.push('=');
        s.push_str(v);
    }
    s
}


static JVM: OnceLock<JavaVM> = OnceLock::new();

#[derive(Debug, Clone)]
#[allow(dead_code)]
struct RouteMeta {
    method: String,
    path: String,
    handler_id: i32,
    request_type: String,
    response_type: String,
}

#[derive(Clone, Debug)]
enum Seg {
    Static(String),
    Var(String),
}

#[derive(Clone, Debug)]
struct PatternRoute {
    method: String,
    raw: String,        // "/order/{id}"
    segments: Vec<Seg>, // ["order", Var("id")]
    handler_id: i32,
}

static EXACT_ROUTES: OnceLock<RwLock<HashMap<(String, String), RouteMeta>>> = OnceLock::new();
fn exact_routes() -> &'static RwLock<HashMap<(String, String), RouteMeta>> {
    EXACT_ROUTES.get_or_init(|| RwLock::new(HashMap::new()))
}

static PATTERN_ROUTES: OnceLock<RwLock<HashMap<String, Vec<PatternRoute>>>> = OnceLock::new();
fn pattern_routes() -> &'static RwLock<HashMap<String, Vec<PatternRoute>>> {
    PATTERN_ROUTES.get_or_init(|| RwLock::new(HashMap::new()))
}

fn is_pattern_path(path: &str) -> bool {
    // Fast check: contains "{...}"
    let bytes = path.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'{' {
            let mut j = i + 1;
            while j < bytes.len() && bytes[j] != b'}' {
                j += 1;
            }
            if j < bytes.len() && bytes[j] == b'}' {
                return true;
            }
        }
        i += 1;
    }
    false
}

fn parse_pattern(path: &str) -> Vec<Seg> {
    path.trim_start_matches('/')
        .split('/')
        .filter(|s| !s.is_empty())
        .map(|seg| {
            if seg.starts_with('{') && seg.ends_with('}') && seg.len() >= 3 {
                let name = &seg[1..seg.len() - 1];
                Seg::Var(name.to_string())
            } else {
                Seg::Static(seg.to_string())
            }
        })
        .collect()
}

fn split_path(path: &str) -> Vec<&str> {
    path.trim_start_matches('/')
        .split('/')
        .filter(|s| !s.is_empty())
        .collect()
}

/// Match pattern and extract params
fn match_pattern(
    pattern: &[Seg],
    actual: &[&str]
) -> Option<Vec<(String, String)>> {
    if pattern.len() != actual.len() {
        return None;
    }

    let mut params: Vec<(String, String)> = Vec::new();

    for (pseg, aseg) in pattern.iter().zip(actual.iter()) {
        match pseg {
            Seg::Static(s) => {
                if s != aseg {
                    return None;
                }
            }
            Seg::Var(name) => {
                if aseg.is_empty() {
                    return None;
                }
                params.push((name.clone(), (*aseg).to_string()));
            }
        }
    }

    Some(params)
}


static NATIVEBRIDGE_CLASS: OnceLock<GlobalRef> = OnceLock::new();

#[no_mangle]
pub extern "system" fn Java_com_reactor_rust_bridge_NativeBridge_startHttpServer(
    env: JNIEnv,
    _class: JObject,
    port: jint,
) {
    let jvm = env.get_java_vm().expect("Cannot get JVM");
    let _ = JVM.set(jvm);

    // Warm up buffer pools
    warmup_pools();

    std::thread::spawn(move || {
        println!("[RUST] Starting Hyper server thread...");

        // Get CPU count
        let cpu_count = std::thread::available_parallelism()
            .map(|n| n.get())
            .unwrap_or(4);

        // Optimized thread counts
        let worker_threads = cpu_count;
        let max_blocking = cpu_count * 8;

        println!(
            "[RUST] Runtime config → worker_threads={} max_blocking_threads={} (cpu={})",
            worker_threads, max_blocking, cpu_count
        );

        let rt = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(worker_threads)
            .max_blocking_threads(max_blocking)
            .enable_io()
            .enable_time()
            .build()
            .unwrap();

        rt.block_on(async move {
            run_server(port as i32).await;
        });
    });
}


#[no_mangle]
pub extern "system" fn Java_com_reactor_rust_bridge_NativeBridge_passNativeBridgeClass(
    env: JNIEnv,
    _caller: JClass,
    native_bridge_class: JClass,
) {
    let global = env
        .new_global_ref(native_bridge_class)
        .expect("Failed to create GlobalRef");

    let _ = NATIVEBRIDGE_CLASS.set(global);

    println!("[RUST] NativeBridge class stored");
}


#[no_mangle]
pub extern "system" fn Java_com_reactor_rust_bridge_NativeBridge_registerRoutes(
    mut env: JNIEnv,
    _class: JObject,
    jroute_list: JObject,
) {
    let size = env
        .call_method(&jroute_list, "size", "()I", &[])
        .expect("List.size failed")
        .i()
        .unwrap();

    let mut exact_map = exact_routes().write().unwrap();
    let mut pattern_map = pattern_routes().write().unwrap();

    for i in 0..size {
        let route_obj = env
            .call_method(
                &jroute_list,
                "get",
                "(I)Ljava/lang/Object;",
                &[JValue::Int(i)],
            )
            .expect("List.get failed")
            .l()
            .unwrap();

        let http_method = env
            .get_field(&route_obj, "httpMethod", "Ljava/lang/String;")
            .expect("httpMethod field")
            .l()
            .unwrap();
        let path = env
            .get_field(&route_obj, "path", "Ljava/lang/String;")
            .expect("path field")
            .l()
            .unwrap();
        let handler_id = env
            .get_field(&route_obj, "handlerId", "I")
            .expect("handlerId field")
            .i()
            .unwrap();
        let request_type = env
            .get_field(&route_obj, "requestType", "Ljava/lang/String;")
            .expect("requestType field")
            .l()
            .unwrap();
        let response_type = env
            .get_field(&route_obj, "responseType", "Ljava/lang/String;")
            .expect("responseType field")
            .l()
            .unwrap();

        let http_method: String = env.get_string(&JString::from(http_method)).unwrap().into();
        let path: String = env.get_string(&JString::from(path)).unwrap().into();
        let request_type: String = env.get_string(&JString::from(request_type)).unwrap().into();
        let response_type: String = env.get_string(&JString::from(response_type)).unwrap().into();

        let meta = RouteMeta {
            method: http_method.clone(),
            path: path.clone(),
            handler_id,
            request_type,
            response_type,
        };

        // Exact vs pattern routes
        if is_pattern_path(&path) {
            let segs = parse_pattern(&path);
            let pr = PatternRoute {
                method: http_method.clone(),
                raw: path.clone(),
                segments: segs,
                handler_id,
            };

            pattern_map.entry(http_method.clone()).or_default().push(pr);

            // Sort by specificity (more static segments = higher priority)
            if let Some(list) = pattern_map.get_mut(&http_method) {
                list.sort_by(|a, b| {
                    let sa = a.segments.iter().filter(|s| matches!(s, Seg::Static(_))).count();
                    let sb = b.segments.iter().filter(|s| matches!(s, Seg::Static(_))).count();
                    sb.cmp(&sa).then_with(|| b.segments.len().cmp(&a.segments.len()))
                });
            }

            println!(
                "[RUST] Pattern route registered → {} {} → handlerId={}",
                http_method, path, handler_id
            );
        } else {
            exact_map.insert((http_method.clone(), path.clone()), meta);

            println!(
                "[RUST] Exact route registered → {} {} → handlerId={}",
                http_method, path, handler_id
            );
        }
    }

    let pattern_count: usize = pattern_map.values().map(|v| v.len()).sum();
    println!(
        "[RUST] Routes registered: exact={} pattern={}",
        exact_map.len(),
        pattern_count
    );
}

async fn run_server(port: i32) {
    let addr = format!("0.0.0.0:{}", port);
    let listener = TcpListener::bind(&addr)
        .await
        .expect("bind failed");

    println!("Hyper running at http://{}", addr);

    loop {
        let (stream, _) = listener.accept().await.unwrap();
        let io = hyper_util::rt::TokioIo::new(stream);

        task::spawn(async move {
            let builder = Builder::new(TokioExecutor::new());
            let service = service_fn(router);
            let conn = builder.serve_connection(io, service);

            if let Err(e) = conn.await {
                eprintln!("Connection error: {}", e);
            }
        });
    }
}

async fn router(req: Request<Incoming>) -> Result<Response<String>, hyper::Error> {
    let method = req.method().to_string();
    let path = req.uri().path().to_string();
    let query_string = req.uri().query().unwrap_or("").to_string();
    let header_string = encode_headers(req.headers());

    // Health check - handled entirely in Rust
    if method == "GET" && path == "/health" {
        return Ok(Response::new("{\"status\":\"OK\"}".into()));
    }

    let whole_body = req.collect().await?.to_bytes();

    // Route matching
    let handler_match: Option<(i32, Option<Vec<(String, String)>>)> = {
        let exact = exact_routes().read().unwrap();
        if let Some(rm) = exact.get(&(method.clone(), path.clone())) {
            Some((rm.handler_id, None))
        } else {
            drop(exact);

            let actual_segs = split_path(&path);
            let patterns = pattern_routes().read().unwrap();

            let mut found: Option<(i32, Option<Vec<(String, String)>>)> = None;

            if let Some(list) = patterns.get(&method) {
                for pr in list {
                    if let Some(params) = match_pattern(&pr.segments, &actual_segs) {
                        found = Some((pr.handler_id, Some(params)));
                        break;
                    }
                }
            }

            found
        }
    };

    if let Some((handler_id, path_params_opt)) = handler_match {
        // Rent buffer from lock-free pool
        let mut buf = rent_buffer(SMALL_CAP);

        let (buf, len) = tokio::task::spawn_blocking(move || {
            let body_slice: &[u8] = whole_body.as_ref();

            let path_params_encoded = path_params_opt
                .as_ref()
                .map(|v| encode_path_params(v));

            // Call Java handler
            let written = call_java_handler(
                handler_id,
                body_slice,
                &mut buf,
                path_params_encoded.as_deref().unwrap_or(""),
                &query_string,
                &header_string,
            );

            (buf, written)
        })
        .await
        .unwrap_or_else(|_| {
            let mut b = Vec::new();
            b.extend_from_slice(b"{\"error\":\"join_failed\"}");
            let len = b.len();
            (b, len)
        });

        let body = String::from_utf8_lossy(&buf[..len]).to_string();
        return_buffer(buf); // Return to lock-free pool

        return Ok(Response::new(body));
    }

    // 404 Not Found
    let mut r = Response::new("Not Found".into());
    *r.status_mut() = StatusCode::NOT_FOUND;
    Ok(r)
}


/// Call Java handler via JNI
fn call_java_handler(
    handler_id: i32,
    body_slice: &[u8],
    buf: &mut Vec<u8>,
    path_params: &str,
    query_string: &str,
    headers: &str,
) -> usize {
    let jvm = JVM.get().expect("JVM not initialized");

    let mut env = match jvm.attach_current_thread() {
        Ok(env) => env,
        Err(_) => {
            let err = b"{\"error\":\"attach_failed\"}";
            buf.clear();
            buf.extend_from_slice(err);
            return err.len();
        }
    };

    let class_global = NATIVEBRIDGE_CLASS.get().expect("NativeBridge missing");
    let raw_obj = class_global.as_obj().as_raw();
    let class_jclass = unsafe { JClass::from_raw(raw_obj as jni::sys::jclass) };

    let jbyte_array = match env.byte_array_from_slice(body_slice) {
        Ok(arr) => arr,
        Err(_) => {
            let err = b"{\"error\":\"byte_array_failed\"}";
            buf.clear();
            buf.extend_from_slice(err);
            return err.len();
        }
    };
    let jbyte_array_obj = JObject::from(jbyte_array);

    let j_path = env.new_string(path_params).unwrap();
    let j_query = env.new_string(query_string).unwrap();
    let j_headers = env.new_string(headers).unwrap();

    // Ensure minimum 16KB capacity
    if buf.capacity() < SMALL_CAP {
        *buf = Vec::with_capacity(SMALL_CAP);
    }

    let mut capacity = buf.capacity();

    for _attempt in 0..3 {
        let j_buf = match unsafe { env.new_direct_byte_buffer(buf.as_mut_ptr(), capacity) } {
            Ok(bb) => bb,
            Err(_) => {
                let err = b"{\"error\":\"direct_buffer_failed\"}";
                buf.clear();
                buf.extend_from_slice(err);
                return err.len();
            }
        };

        let result_len = match env.call_static_method(
            &class_jclass,
            "handleRustRequestIntoBuffer",
            "(ILjava/nio/ByteBuffer;II[BLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)I",
            &[
                JValue::Int(handler_id),
                JValue::Object(JObject::from(j_buf).as_ref()),
                JValue::Int(0),
                JValue::Int(capacity as i32),
                JValue::Object(jbyte_array_obj.as_ref()),
                JValue::Object(j_path.as_ref()),
                JValue::Object(j_query.as_ref()),
                JValue::Object(j_headers.as_ref()),
            ],
        ) {
            Ok(v) => v.i().unwrap_or(-999),
            Err(_) => {
                let err = b"{\"error\":\"java_call_failed\"}";
                buf.clear();
                buf.extend_from_slice(err);
                return err.len();
            }
        };

        // Negative = buffer too small, retry with larger buffer
        if result_len < 0 {
            let needed = (-result_len) as usize;
            if needed > capacity {
                *buf = Vec::with_capacity(needed);
                capacity = buf.capacity();
                continue;
            }
        }

        // Success
        let len = result_len as usize;
        unsafe { buf.set_len(len); }
        return len;
    }

    let err = b"{\"error\":\"retry_exceeded\"}";
    buf.clear();
    buf.extend_from_slice(err);
    err.len()
}


// Platform-specific memory cleanup

#[cfg(any(
    all(target_os = "linux", target_env = "gnu"),
    target_os = "android"
))]
extern "C" {
    fn malloc_trim(pad: usize) -> i32;
}

#[cfg(all(target_os = "linux", target_env = "musl"))]
extern "C" {
    fn malloc_stats_print(
        write_cb: Option<extern "C" fn(*mut core::ffi::c_void, *const u8)>,
        cbopaque: *mut core::ffi::c_void,
        opts: *const core::ffi::c_char,
    );
}

#[cfg(target_os = "windows")]
use windows_sys::Win32::System::ProcessStatus::K32EmptyWorkingSet;
#[cfg(target_os = "windows")]
use windows_sys::Win32::System::Threading::GetCurrentProcess;

#[no_mangle]
pub extern "system" fn Java_com_reactor_rust_bridge_NativeBridge_releaseNativeMemory(
    _env: JNIEnv,
    _class: JClass,
) {
    // glibc malloc_trim
    #[cfg(all(target_os = "linux", target_env = "gnu"))]
    unsafe {
        malloc_trim(0);
    }

    // Android
    #[cfg(target_os = "android")]
    unsafe {
        malloc_trim(0);
    }

    // Alpine / musl
    #[cfg(all(target_os = "linux", target_env = "musl"))]
    unsafe {
        malloc_stats_print(None, core::ptr::null_mut(), core::ptr::null());
    }

    // Windows
    #[cfg(target_os = "windows")]
    unsafe {
        let handle = GetCurrentProcess();
        K32EmptyWorkingSet(handle);
    }

    // macOS → no-op
    #[cfg(target_os = "macos")]
    {
        // nothing
    }

    println!("[RUST] releaseNativeMemory() executed");
}
