# Rust-Spring Performance Project Memory

## Proje Ozeti

Bu proje, **Rust (Hyper) + Java (Spring)** entegrasyonu ile yuksek performansli bir HTTP sunucusu olusturmaktadir.
Rust HTTP katmani, JNI uzerinden Java handler'lari cagirarak isler.

---

## Proje Yapisi

```
rust-spring-performance/
├── rust-spring/           # Rust projesi (Hyper HTTP server)
│   ├── Cargo.toml
│   └── src/lib.rs         # JNI bindings + Hyper server
│
├── rust-spring-boot/      # Java Spring Boot projesi
│   ├── pom.xml
│   └── src/main/java/com/reactor/rust/
│       ├── ReactorRustHyperApplication.java  # Main class
│       ├── annotations/
│       │   ├── RustRoute.java               # Route annotation
│       │   └── HttpMethod.java
│       ├── bridge/
│       │   ├── NativeBridge.java            # JNI native method'lar
│       │   ├── HandlerRegistry.java         # Handler yonetimi
│       │   ├── RouteDef.java                # Route tanimi
│       │   ├── RustHttpHandler.java         # Handler interface
│       │   └── HandlerDetectorBeanPostProcessor.java
│       ├── json/
│       │   ├── MiniJsonWriter.java          # Zero-copy JSON yazici
│       │   ├── ZeroCopyJsonParser.java      # Zero-copy JSON parser
│       │   ├── GetterCache.java             # MethodHandle cache
│       │   └── ClassMetadata.java
│       ├── handler/
│       │   └── OrderHandler.java            # Ornek handler
│       ├── dto/                             # Data Transfer Objects
│       └── component/
│           └── CleanerUtils.java
│
├── test/                  # Test scriptleri (olusturulacak)
└── MEMORY.md              # Bu dosya
```

---

## Onemli Dosyalar

### Rust Tarafi
| Dosya | Aciklama |
|-------|----------|
| `lib.rs` | Ana Rust kodu - Hyper server, JNI bindings, buffer pool |
| `Cargo.toml` | Rust dependencies (hyper, tokio, jni, serde, bytes) |

### Java Tarafi
| Dosya | Aciklama |
|-------|----------|
| `NativeBridge.java` | JNI native method tanimlari |
| `HandlerRegistry.java` | Handler kaydi ve cagrilari |
| `MiniJsonWriter.java` | Zero-copy JSON serialization |
| `OrderHandler.java` | Ornek handler implementasyonu |
| `ReactorRustHyperApplication.java` | Spring context baslatma |

---

## Mimari Detaylari

### 1. Rust HTTP Server (Hyper)
- **Port:** 8080
- **Runtime:** Tokio multi-thread (worker_threads = CPU count, max_blocking = CPU * 8)
- **Route tipleri:**
  - Exact routes: `/order/create`
  - Pattern routes: `/order/{id}`
- **Buffer Pool:** 4 bucket (16KB, 64KB, 256KB, 1MB) - zero allocation

### 2. JNI Entegrasyonu
```
Rust (Hyper) → JNI → Java (NativeBridge) → HandlerRegistry → Handler Method
```

**Native Method'lar:**
- `startHttpServer(int port)` - HTTP sunucusunu baslat
- `registerRoutes(List<RouteDef>)` - Route'lari kaydet
- `passNativeBridgeClass(Class<?>)` - NativeBridge class referansi
- `releaseNativeMemory()` - Native memory temizleme

**Java Callback'ler (Rust → Java):**
- `handleRustRequestIntoBuffer()` - Temel versiyon
- `handleRustRequestIntoBufferV2()` - Path params destegi
- `handleRustRequestIntoBufferV3()` - Query string destegi
- `handleRustRequestIntoBufferV4()` - Headers destegi

### 3. Handler Signature'lari
```java
// V1 - Basit
int handler(ByteBuffer out, int offset, byte[] body)

// V2 - Path params
int handler(ByteBuffer out, int offset, byte[] body, String pathParams)

// V3 - Query string
int handler(ByteBuffer out, int offset, byte[] body, String pathParams, String queryString)

// V4 - Headers
int handler(ByteBuffer out, int offset, byte[] body, String pathParams, String queryString, String headers)
```

### 4. Zero-Copy JSON
- `MiniJsonWriter`: MethodHandle ile zero-reflection JSON yazma
- `ZeroCopyJsonParser`: Zero-copy JSON parsing
- `GetterCache`: Class getter'larini cache'leme

---

## Technologiler

### Rust
- hyper 1.x (HTTP server)
- tokio 1.x (async runtime)
- jni 0.21 (JNI bindings)
- serde/serde_json (serialization)
- bytes (buffer management)

### Java
- Java 21
- Spring Framework 6.2.5 (NOT Spring Boot Web)
- Minimal Spring (core, beans, context only)

### Platform Destegi
- Windows (rust_hyper.dll)
- Linux glibc (rust_hyper.so)
- Linux musl/Alpine (rust_hyper.so)
- Android (rust_hyper.so)

---

## Endpoint'ler

| Method | Path | Handler | Aciklama |
|--------|------|---------|----------|
| GET | `/health` | Rust tarafinda | Health check |
| POST | `/order/create` | `OrderHandler.create()` | Siparis olustur |
| GET | `/order/order` | `OrderHandler.getOrderInfo()` | Siparis bilgisi |
| GET | `/order/{id}` | `OrderHandler.getOrderById()` | ID ile siparis |
| GET | `/order/search` | `OrderHandler.search()` | Siparis arama |

---

## Build ve Run

### Rust Build
```bash
cd rust-spring
cargo build --release
# Output: target/release/rust_hyper.dll (Windows) veya .so (Linux)
```

### Java Build
```bash
cd rust-spring-boot
mvn clean package
# Output: target/rust-spring-1.0.0.jar
```

### Run (Local)
```bash
java -jar rust-spring-boot/target/rust-spring-1.0.0.jar
```

### Docker Build
```bash
# Container dosyalari: rust-spring-boot/target/container/
# - Dockerfile
# - librust_hyper.so
# - rust-spring-1.0.0.jar
# - config.json
```

---

## Kritik Notlar

1. **DLL/SO Yolu:** `NativeBridge.java` icinde sabit kodlanmis
   - Local: `E:\ReactorRepository\rust-spring\target\release\rust_hyper.dll`
   - Container: `/app/native/rust_hyper.so`

2. **Memory Yonetimi:** Her 5000 request'te `releaseNativeMemory()` cagriliyor

3. **Route Registration:** Spring context baslatildiginda otomatik yapiliyor

4. **Handler Detection:** `@RustRoute` annotation ile otomatik tespit

---

## Detayli Istek Akisi

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              CLIENT REQUEST                                  │
│                          (curl, browser, etc.)                               │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           RUST - HYPER SERVER                                │
│                           (lib.rs - Port 8080)                               │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  TcpListener.accept() → TokioIo::new(stream)                         │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                      │                                        │
│                                      ▼                                        │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  router(req)                                                        │    │
│  │  - method = req.method()                                            │    │
│  │  - path = req.uri().path()                                          │    │
│  │  - query_string = req.uri().query()                                 │    │
│  │  - headers = encode_headers(req.headers())                          │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                      │                                        │
│                                      ▼                                        │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  Route Matching                                                     │    │
│  │  1. Exact match: EXACT_ROUTES.get((method, path))                   │    │
│  │  2. Pattern match: match_pattern(segments, actual)                  │    │
│  │     - /order/{id} → Seg::Var("id")                                  │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                      │                                        │
│                                      ▼                                        │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  Buffer Pool                                                        │    │
│  │  rent_buffer(16KB) → Vec<u8>                                        │    │
│  │  Bucket: Small(16KB), Medium(64KB), Large(256KB), Huge(1MB)         │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                      │                                        │
└──────────────────────────────────────┼────────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          JNI BOUNDARY                                        │
│                                                                              │
│  spawn_blocking(|| {                                                        │
│      call_java_with_handler_zero_copy_v4(                                   │
│          handler_id,                                                        │
│          body_slice,                                                        │
│          &mut buf,                                                          │
│          path_params_encoded,    // "id=123"                                │
│          query_string,           // "status=pending&page=1"                 │
│          header_string           // "content-type: application/json\n..."   │
│      )                                                                       │
│  })                                                                          │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          JAVA - NATIVE BRIDGE                                │
│                          (NativeBridge.java)                                 │
│                                                                              │
│  handleRustRequestIntoBufferV4(                                             │
│      int handlerId,                                                         │
│      ByteBuffer outBuffer,       // DirectByteBuffer from Rust              │
│      int offset,                                                            │
│      int capacity,                                                          │
│      byte[] inBytes,             // Request body                            │
│      String pathParams,                                                      │
│      String queryString,                                                     │
│      String headers                                                         │
│  )                                                                           │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          HANDLER REGISTRY                                    │
│                          (HandlerRegistry.java)                              │
│                                                                              │
│  invokeBufferedV4(handlerId, out, offset, inBytes, pathParams, query, hdr)  │
│                                                                              │
│  1. HandlerDescriptor bul (ConcurrentHashMap)                               │
│  2. Method signature kontrol et                                             │
│  3. MethodHandle.invoke() - ZERO REFLECTION                                 │
│                                                                              │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          HANDLER METHOD                                      │
│                          (OrderHandler.java)                                 │
│                                                                              │
│  @RustRoute(method="POST", path="/order/create")                            │
│  public int create(                                                         │
│      ByteBuffer out,             // Direkt yazılacak buffer                 │
│      int offset,                                                            │
│      byte[] body,                // Request body (JSON)                     │
│      String pathParams,          // null (exact route)                      │
│      String queryString,         // null veya query params                  │
│      String headers              // HTTP headers                            │
│  ) {                                                                         │
│      // 1. Parse request                                                     │
│      OrderCreateRequest req = ZeroCopyJsonParser.parse(body, ...);          │
│                                                                              │
│      // 2. Business logic                                                    │
│      OrderCreateResponse resp = new OrderCreateResponse(1, "OK", 15);       │
│                                                                              │
│      // 3. Write response - ZERO COPY                                        │
│      return MiniJsonWriter.writeToBuffer(resp, out, offset);                │
│  }                                                                           │
│                                                                              │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          ZERO-COPY JSON WRITER                               │
│                          (MiniJsonWriter.java)                               │
│                                                                              │
│  writeToBuffer(Object obj, ByteBuffer out, int offset)                      │
│                                                                              │
│  1. GetterCache.getGetters(class) → Map<String, MethodHandle>               │
│  2. Her field için:                                                          │
│     - MethodHandle.invoke(obj) → value (ZERO REFLECTION)                    │
│     - writeValue(value, out) → direkt ByteBuffer'a yaz                       │
│  3. Return: written byte count                                              │
│                                                                              │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          RESPONSE FLOW (RETURN PATH)                         │
│                                                                              │
│  Handler → return written_bytes                                             │
│      ↓                                                                       │
│  NativeBridge → return written_bytes                                        │
│      ↓                                                                       │
│  Rust JNI → buf.set_len(written_bytes)                                      │
│      ↓                                                                       │
│  Rust router → String::from_utf8_lossy(&buf[..len])                         │
│      ↓                                                                       │
│  return_buffer(buf) → pool'e iade                                           │
│      ↓                                                                       │
│  Response::new(body) → Hyper client'a gonder                                │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Zero-Copy Teknikleri

### 1. DirectByteBuffer
- Rust tarafinda `Vec<u8>` allocate edilir
- JNI ile `new_direct_byte_buffer(ptr, capacity)` ile Java'ya pass edilir
- Java direkt bu buffer'a yazar (copy yok)

### 2. MethodHandle
- Reflection yerine `MethodHandles.lookup().unreflect(method).bindTo(bean)`
- Ilk cagridan sonra JIT optimize eder
- Reflection'dan 10-100x daha hizli

### 3. GetterCache
- Class getter'lari bir kez keşfedilir ve cache'lenir
- Concurrent map ile thread-safe
- Sonraki cagrilarda sadece MethodHandle.invoke()

### 4. Buffer Pool
- 4 bucket: 16KB, 64KB, 256KB, 1MB
- Request basina allocation yok
- `rent_buffer()` → pool'dan al
- `return_buffer()` → pool'a iade

---

## JSON Serializer (DSL-JSON)

**KARAR:** DSL-JSON library direkt kullanilacak

### Maven Dependency
```xml
<dependency>
    <groupId>com.dslplatform</groupId>
    <artifactId>dsl-json</artifactId>
    <version>1.10.0</version>
</dependency>
```

### Annotation Processor (pom.xml)
```xml
<annotationProcessorPath>
    <path>
        <groupId>com.dslplatform</groupId>
        <artifactId>dsl-json</artifactId>
        <version>1.10.0</version>
    </path>
</annotationProcessorPath>
```

### Özellikleri
- Byte-level serialization (char intermediate yok)
- Compile-time code generation (runtime reflection yok)
- ~450KB runtime memory footprint
- Thread-local JsonWriter/JsonReader reuse
- Zero-allocation mode desteği

---

## Rust Zero Overhead (Constraint #13)

**KESIN KURAL:** Rust'ta ZERO OVERHEAD - Maksimum verimlilik

### Yasaklar
- Overhead yaratan karar ALINAMAZ
- Overhead yaratan kod KODLANAMAZ
- Overhead yaratan implementasyon UYGULANAMAZ

### Rust Tarafi Optimizasyonlari
- Zero-cost abstractions
- Minimal heap allocation (stack preferred)
- No unnecessary cloning
- Inline fonksiyonlar (hot path)
- SIMD optimizasyonları (mümkünse)

### JNI Boundary Optimizasyonlari
- Minimal JNI call overhead
- DirectByteBuffer ile zero-copy
- Batch operations (tek call'da multiple items)
- Cache JNI references (global refs)

### Tokio/Hyper Optimizasyonlari
- Optimal thread pool sizing
- Zero-copy body handling
- Efficient buffer pooling

### Memory Layout
- Cache-friendly data structures
- Alignment optimizations
- Avoid false sharing

---

## Gelistirme Gecmisi

| Tarih | Degisiklik |
|-------|------------|
| 2024-12 | Proje baslangici |
| 2025-01 | Zero-copy JSON, buffer pool |
| 2025-03 | Pattern route destegi (`/order/{id}`) |

---

## Test Scriptleri

Test scriptleri `test/` dizini altinda tutulacak:
- `test.sh` - Linux test script
- `test.ps1` - Windows PowerShell test script
- `benchmark.sh` - Performans testi
- `curl_tests.sh` - cURL ile API testleri
