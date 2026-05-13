#!/usr/bin/env node

const http = require("http");
const https = require("https");
const { URL } = require("url");

function parseArgs(argv) {
  const args = {
    method: "GET",
    concurrency: 1,
    durationSec: 10,
    timeoutMs: 10000,
    contentType: "application/json",
  };

  for (let i = 2; i < argv.length; i++) {
    const key = argv[i];
    const value = argv[i + 1];
    switch (key) {
      case "--url":
        args.url = value;
        i++;
        break;
      case "--method":
        args.method = value.toUpperCase();
        i++;
        break;
      case "--concurrency":
        args.concurrency = Math.max(1, Number.parseInt(value, 10));
        i++;
        break;
      case "--duration-sec":
        args.durationSec = Math.max(1, Number.parseInt(value, 10));
        i++;
        break;
      case "--timeout-ms":
        args.timeoutMs = Math.max(100, Number.parseInt(value, 10));
        i++;
        break;
      case "--content-type":
        args.contentType = value;
        i++;
        break;
      case "--body":
        args.body = Buffer.from(value);
        i++;
        break;
      default:
        throw new Error(`unknown argument: ${key}`);
    }
  }

  if (!args.url) {
    throw new Error("missing --url");
  }
  return args;
}

function percentile(sorted, p) {
  if (sorted.length === 0) return 0;
  const idx = Math.min(sorted.length - 1, Math.round((sorted.length - 1) * p));
  return sorted[idx];
}

function average(values) {
  if (values.length === 0) return 0;
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}

function stddev(values, avg) {
  if (values.length < 2) return 0;
  const variance = values.reduce((sum, value) => {
    const delta = value - avg;
    return sum + delta * delta;
  }, 0) / values.length;
  return Math.sqrt(variance);
}

function requestOnce(target, args, agent) {
  const started = process.hrtime.bigint();
  const transport = target.protocol === "https:" ? https : http;

  return new Promise((resolve) => {
    const req = transport.request({
      protocol: target.protocol,
      hostname: target.hostname,
      port: target.port,
      path: `${target.pathname}${target.search}`,
      method: args.method,
      agent,
      timeout: args.timeoutMs,
      headers: args.body ? {
        "content-type": args.contentType,
        "content-length": args.body.length,
      } : undefined,
    }, (res) => {
      let bytes = 0;
      res.on("data", (chunk) => {
        bytes += chunk.length;
      });
      res.on("end", () => {
        const elapsedUs = Number((process.hrtime.bigint() - started) / 1000n);
        resolve({ ok: true, status: res.statusCode || 0, bytes, elapsedUs });
      });
    });

    req.on("timeout", () => {
      req.destroy(new Error("timeout"));
    });
    req.on("error", (error) => {
      resolve({ ok: false, error: error.message || error.code || "request_error" });
    });
    if (args.body) {
      req.write(args.body);
    }
    req.end();
  });
}

async function worker(target, args, agent, deadlineMs, stats) {
  while (Date.now() < deadlineMs) {
    const result = await requestOnce(target, args, agent);
    if (result.ok) {
      stats.latenciesUs.push(result.elapsedUs);
      stats.bytes += result.bytes;
      stats.statuses[result.status] = (stats.statuses[result.status] || 0) + 1;
    } else {
      stats.errors[result.error] = (stats.errors[result.error] || 0) + 1;
    }
  }
}

async function main() {
  const args = parseArgs(process.argv);
  const target = new URL(args.url);
  const Agent = target.protocol === "https:" ? https.Agent : http.Agent;
  const agent = new Agent({
    keepAlive: true,
    maxSockets: args.concurrency,
    maxFreeSockets: args.concurrency,
    timeout: args.timeoutMs,
  });
  const stats = { latenciesUs: [], statuses: {}, errors: {}, bytes: 0 };
  const started = Date.now();
  const deadlineMs = started + args.durationSec * 1000;

  const tasks = [];
  for (let i = 0; i < args.concurrency; i++) {
    tasks.push(worker(target, args, agent, deadlineMs, stats));
  }
  await Promise.all(tasks);
  agent.destroy();

  const elapsedSec = Math.max(0.001, (Date.now() - started) / 1000);
  stats.latenciesUs.sort((a, b) => a - b);
  const avgUs = average(stats.latenciesUs);
  const errorsTotal = Object.values(stats.errors).reduce((sum, count) => sum + count, 0);

  console.log(JSON.stringify({
    url: args.url,
    method: args.method,
    concurrency: args.concurrency,
    duration_sec: args.durationSec,
    elapsed_sec: elapsedSec,
    requests: stats.latenciesUs.length,
    errors_total: errorsTotal,
    rps: stats.latenciesUs.length / elapsedSec,
    transfer_bytes_per_sec: stats.bytes / elapsedSec,
    latency_us: {
      avg: avgUs,
      stdev: stddev(stats.latenciesUs, avgUs),
      min: stats.latenciesUs[0] || 0,
      p50: percentile(stats.latenciesUs, 0.50),
      p90: percentile(stats.latenciesUs, 0.90),
      p95: percentile(stats.latenciesUs, 0.95),
      p99: percentile(stats.latenciesUs, 0.99),
      p999: percentile(stats.latenciesUs, 0.999),
      max: stats.latenciesUs[stats.latenciesUs.length - 1] || 0,
    },
    statuses: stats.statuses,
    errors: stats.errors,
    bytes_read: stats.bytes,
  }));
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exit(2);
});
