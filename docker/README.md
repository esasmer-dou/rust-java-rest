# Docker - Spring Boot vs Rust-Spring Performance Comparison

OpenJ9 (IBM Semeru) ile ultra-minimal Docker image'ları.

## Yapı

```
docker/
├── docker-compose.yml          # Her iki servisi çalıştırır
├── build-images.sh             # Image'ları build eder
├── benchmark.sh                # wrk ile benchmark
├── spring-boot-standard/
│   ├── Dockerfile              # Multi-stage build
│   └── Dockerfile.local        # Pre-built JAR ile
└── rust-spring-perf/
    ├── Dockerfile              # Multi-stage build (Rust + Java)
    └── Dockerfile.local        # Pre-built artifacts ile
```

## Hızlı Başlangıç

### 1. Local Build (Hızlı)

```bash
# Spring Boot JAR build
cd spring-boot-simple-rest-api/com.divit.spring-boot-simple-rest-api
mvn package -DskipTests

# Rust-Spring JAR + native library build
cd ../../../rust-spring
cargo build --release
cd ../rust-spring-boot
mvn package -DskipTests
cp ../rust-spring/target/release/librust_hyper.so native/

# Docker image'ları build et
cd ../docker
docker build -t spring-boot-standard:local -f spring-boot-standard/Dockerfile.local ../spring-boot-simple-rest-api/com.divit.spring-boot-simple-rest-api
docker build -t rust-spring-perf:local -f rust-spring-perf/Dockerfile.local ../rust-spring-boot
```

### 2. Docker Compose ile Çalıştır

```bash
# Her iki servisi başlat
docker-compose up -d

# Sadece birini
docker-compose up -d spring-boot
docker-compose up -d rust-spring
```

### 3. Benchmark

```bash
# wrk kurulu olmalı
sudo apt install wrk

# Benchmark çalıştır
./benchmark.sh
```

## Port'lar

| Service | Port | Server |
|---------|------|--------|
| Spring Boot | 8888 | Undertow |
| Rust-Spring | 8080 | Hyper |

## Memory Limit'ler

| Service | Heap | Container Limit |
|---------|------|-----------------|
| Spring Boot | 16-48MB | 100MB |
| Rust-Spring | 8-32MB | 50MB |

## OpenJ9 JVM Options

### Spring Boot (Undertow)
```
-Xms16m -Xmx48m
-Xquickstart
-Xshareclasses:name=spring-boot,cacheDir=/tmp
-Xscmx50m
-Xtune:virtualized
```

### Rust-Spring (Hyper)
```
-Xms8m -Xmx32m
-Xquickstart
-Xshareclasses:name=rust-spring,cacheDir=/tmp
-Xscmx50m
-Xtune:virtualized
--enable-native-access=ALL-UNNAMED
```

## Endpoint'ler

Her iki uygulamada aynı endpoint'ler mevcut:

| Endpoint | Method | Açıklama |
|----------|--------|----------|
| /health | GET | Health check |
| /order/create | POST | Sipariş oluştur |
| /order/cancel | POST | Sipariş iptal |
| /order/order | GET | 19 item'lı sipariş |
| /order/{id} | GET | ID ile sipariş |
| /order/search | GET | Sipariş ara |
| /api/v1/candidates | GET | Benchmark endpoint |
| /api/v1/echo | POST | Echo request |

## Docker Commands

```bash
# Container'ları görüntüle
docker ps

# Memory kullanımı
docker stats --no-stream

# Log'ları görüntüle
docker logs spring-boot-standard
docker logs rust-spring-perf

# Durdur
docker-compose down

# Temizle
docker system prune -f
```
