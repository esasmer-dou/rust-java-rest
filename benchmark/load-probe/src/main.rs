use bytes::Bytes;
use reqwest::{Client, Method};
use std::collections::BTreeMap;
use std::env;
use std::fs;
use std::sync::Arc;
use std::time::{Duration, Instant};

#[derive(Clone)]
struct Config {
    url: String,
    method: Method,
    concurrency: usize,
    duration: Duration,
    timeout: Duration,
    body: Option<Bytes>,
    content_type: String,
}

#[derive(Default)]
struct WorkerStats {
    latencies_us: Vec<u64>,
    statuses: BTreeMap<u16, u64>,
    errors: BTreeMap<String, u64>,
    bytes_read: u64,
}

impl WorkerStats {
    fn merge(&mut self, mut other: WorkerStats) {
        self.latencies_us.append(&mut other.latencies_us);
        self.bytes_read += other.bytes_read;
        for (status, count) in other.statuses {
            *self.statuses.entry(status).or_insert(0) += count;
        }
        for (error, count) in other.errors {
            *self.errors.entry(error).or_insert(0) += count;
        }
    }

    fn error_count(&self) -> u64 {
        self.errors.values().sum()
    }
}

#[tokio::main(flavor = "multi_thread")]
async fn main() {
    let config = match parse_args() {
        Ok(config) => config,
        Err(message) => {
            eprintln!("{message}");
            std::process::exit(2);
        }
    };

    let client = match Client::builder()
        .pool_max_idle_per_host(config.concurrency)
        .tcp_nodelay(true)
        .timeout(config.timeout)
        .connect_timeout(config.timeout)
        .build()
    {
        Ok(client) => client,
        Err(error) => {
            eprintln!("client_build_error: {error}");
            std::process::exit(2);
        }
    };

    let deadline = Instant::now() + config.duration;
    let started = Instant::now();
    let config = Arc::new(config);
    let client = Arc::new(client);

    let mut tasks = Vec::with_capacity(config.concurrency);
    for _ in 0..config.concurrency {
        let client = Arc::clone(&client);
        let config = Arc::clone(&config);
        tasks.push(tokio::spawn(async move {
            run_worker(client, config, deadline).await
        }));
    }

    let mut stats = WorkerStats::default();
    for task in tasks {
        match task.await {
            Ok(worker_stats) => stats.merge(worker_stats),
            Err(error) => {
                *stats
                    .errors
                    .entry(format!("worker_join_error:{error}"))
                    .or_insert(0) += 1;
            }
        }
    }

    print_report(stats, started.elapsed());
}

async fn run_worker(client: Arc<Client>, config: Arc<Config>, deadline: Instant) -> WorkerStats {
    let mut stats = WorkerStats::default();

    while Instant::now() < deadline {
        let start = Instant::now();
        let mut request = client.request(config.method.clone(), &config.url);
        if let Some(body) = &config.body {
            request = request
                .header("content-type", config.content_type.as_str())
                .body(body.clone());
        }

        match request.send().await {
            Ok(response) => {
                let status = response.status().as_u16();
                match response.bytes().await {
                    Ok(bytes) => {
                        stats.bytes_read += bytes.len() as u64;
                        stats.latencies_us.push(elapsed_us(start));
                        *stats.statuses.entry(status).or_insert(0) += 1;
                    }
                    Err(error) => {
                        *stats
                            .errors
                            .entry(format!("body_error:{}", error_kind(&error)))
                            .or_insert(0) += 1;
                    }
                }
            }
            Err(error) => {
                *stats
                    .errors
                    .entry(format!("request_error:{}", error_kind(&error)))
                    .or_insert(0) += 1;
            }
        }
    }

    stats
}

fn parse_args() -> Result<Config, String> {
    let mut args = env::args().skip(1);
    let mut url = None;
    let mut method = Method::GET;
    let mut concurrency = 1usize;
    let mut duration = Duration::from_secs(10);
    let mut timeout = Duration::from_secs(10);
    let mut body_file = None;
    let mut content_type = "application/json".to_string();

    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--url" => url = Some(next_value(&mut args, "--url")?),
            "--method" => {
                let value = next_value(&mut args, "--method")?;
                method = value
                    .parse::<Method>()
                    .map_err(|_| format!("invalid --method: {value}"))?;
            }
            "--concurrency" => {
                concurrency = parse_usize(&next_value(&mut args, "--concurrency")?, "--concurrency")?;
            }
            "--duration" => {
                duration = parse_duration(&next_value(&mut args, "--duration")?)?;
            }
            "--timeout-ms" => {
                let millis = parse_u64(&next_value(&mut args, "--timeout-ms")?, "--timeout-ms")?;
                timeout = Duration::from_millis(millis);
            }
            "--body-file" => body_file = Some(next_value(&mut args, "--body-file")?),
            "--content-type" => content_type = next_value(&mut args, "--content-type")?,
            "--threads" => {
                let _ = args.next();
            }
            "--help" | "-h" => return Err(help_text()),
            unknown => return Err(format!("unknown argument: {unknown}\n\n{}", help_text())),
        }
    }

    let body = match body_file {
        Some(path) => Some(Bytes::from(
            fs::read(&path).map_err(|error| format!("cannot read --body-file {path}: {error}"))?,
        )),
        None => None,
    };

    Ok(Config {
        url: url.ok_or_else(help_text)?,
        method,
        concurrency: concurrency.max(1),
        duration,
        timeout,
        body,
        content_type,
    })
}

fn next_value(args: &mut impl Iterator<Item = String>, name: &str) -> Result<String, String> {
    args.next()
        .ok_or_else(|| format!("missing value for {name}\n\n{}", help_text()))
}

fn parse_usize(value: &str, name: &str) -> Result<usize, String> {
    value
        .parse::<usize>()
        .map_err(|_| format!("invalid {name}: {value}"))
}

fn parse_u64(value: &str, name: &str) -> Result<u64, String> {
    value
        .parse::<u64>()
        .map_err(|_| format!("invalid {name}: {value}"))
}

fn parse_duration(value: &str) -> Result<Duration, String> {
    if let Some(stripped) = value.strip_suffix("ms") {
        return Ok(Duration::from_millis(parse_u64(stripped, "--duration")?));
    }
    if let Some(stripped) = value.strip_suffix('s') {
        return Ok(Duration::from_secs(parse_u64(stripped, "--duration")?));
    }
    if let Some(stripped) = value.strip_suffix('m') {
        return Ok(Duration::from_secs(parse_u64(stripped, "--duration")? * 60));
    }
    Ok(Duration::from_secs(parse_u64(value, "--duration")?))
}

fn print_report(mut stats: WorkerStats, elapsed: Duration) {
    stats.latencies_us.sort_unstable();
    let total = stats.latencies_us.len() as u64;
    let elapsed_secs = elapsed.as_secs_f64().max(0.001);
    let rps = total as f64 / elapsed_secs;
    let transfer = stats.bytes_read as f64 / elapsed_secs;

    println!("Running custom load-probe");
    println!("  completed requests: {total}");
    println!("  elapsed: {:.3}s", elapsed_secs);
    println!("  bytes read: {}", stats.bytes_read);
    println!("  errors total: {}", stats.error_count());
    println!("  Thread Stats   Avg      Stdev     Max");
    println!(
        "    Latency   {}   {}   {}",
        fmt_us(avg_us(&stats.latencies_us)),
        fmt_us(stddev_us(&stats.latencies_us)),
        fmt_us(stats.latencies_us.last().copied().unwrap_or(0)),
    );
    println!("  Latency Distribution");
    println!("     50%  {}", fmt_us(percentile_us(&stats.latencies_us, 0.50)));
    println!("     75%  {}", fmt_us(percentile_us(&stats.latencies_us, 0.75)));
    println!("     90%  {}", fmt_us(percentile_us(&stats.latencies_us, 0.90)));
    println!("     95%  {}", fmt_us(percentile_us(&stats.latencies_us, 0.95)));
    println!("     99%  {}", fmt_us(percentile_us(&stats.latencies_us, 0.99)));
    println!("   99.9%  {}", fmt_us(percentile_us(&stats.latencies_us, 0.999)));
    println!("Requests/sec:   {:.2}", rps);
    println!("Transfer/sec:   {}/s", fmt_bytes(transfer));

    for (status, count) in stats.statuses {
        println!("Status {status}: {count}");
    }
    for (error, count) in stats.errors {
        println!("Error {error}: {count}");
    }
}

fn percentile_us(values: &[u64], percentile: f64) -> u64 {
    if values.is_empty() {
        return 0;
    }
    let idx = ((values.len() as f64 - 1.0) * percentile).round() as usize;
    values[idx.min(values.len() - 1)]
}

fn avg_us(values: &[u64]) -> u64 {
    if values.is_empty() {
        return 0;
    }
    values.iter().sum::<u64>() / values.len() as u64
}

fn stddev_us(values: &[u64]) -> u64 {
    if values.len() < 2 {
        return 0;
    }
    let avg = avg_us(values) as f64;
    let variance = values
        .iter()
        .map(|value| {
            let delta = *value as f64 - avg;
            delta * delta
        })
        .sum::<f64>()
        / values.len() as f64;
    variance.sqrt() as u64
}

fn elapsed_us(start: Instant) -> u64 {
    start.elapsed().as_micros().min(u64::MAX as u128) as u64
}

fn fmt_us(us: u64) -> String {
    if us >= 1_000_000 {
        format!("{:.2}s", us as f64 / 1_000_000.0)
    } else if us >= 1_000 {
        format!("{:.2}ms", us as f64 / 1_000.0)
    } else {
        format!("{us}us")
    }
}

fn fmt_bytes(bytes_per_sec: f64) -> String {
    if bytes_per_sec >= 1024.0 * 1024.0 {
        format!("{:.2}MB", bytes_per_sec / 1024.0 / 1024.0)
    } else if bytes_per_sec >= 1024.0 {
        format!("{:.2}KB", bytes_per_sec / 1024.0)
    } else {
        format!("{:.0}B", bytes_per_sec)
    }
}

fn error_kind(error: &reqwest::Error) -> &'static str {
    if error.is_timeout() {
        "timeout"
    } else if error.is_connect() {
        "connect"
    } else if error.is_body() {
        "body"
    } else if error.is_decode() {
        "decode"
    } else if error.is_request() {
        "request"
    } else {
        "other"
    }
}

fn help_text() -> String {
    "usage: load-probe --url URL [--method GET|POST] [--body-file PATH] --concurrency N --duration 10s [--timeout-ms 10000]".to_string()
}
