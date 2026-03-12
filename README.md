# Rust-Java REST Framework

[![Version](https://img.shields.io/badge/version-2.0.0-blue.svg)](https://github.com/esasmer-dou/rust-java-rest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Rust Hyper HTTP sunucusu + Java handler'lar ile ultra hızlı REST API framework.

## v2.0.0 - Zero-Overhead Dependency Injection

Bu sürüm, Spring Boot benzeri **sıfır-overhead Dependency Injection** desteği ekler:
- `@Component`, `@Service`, `@Repository`, `@Configuration`
- `@Autowired` ile bağımlılık enjeksiyonu
- `@PostConstruct` ve `@PreDestroy` lifecycle callback'ler
- `@Bean` metodları ile bean üretimi
- **O(1) lookup** - Runtime'da reflection YOK

**Özellikler:**
- ~27 MB memory (Spring Boot: ~94 MB)
- 3,257 RPS (Spring Boot: ~1,150 RPS)
- 33 ms latency (Spring Boot: ~144 ms)
- Spring Boot benzeri annotation-based API
- ResponseEntity<T> dönüş tipi desteği
- Otomatik parametre çözümleme (@PathVariable, @RequestParam, @HeaderParam, @RequestBody)
- **Zero-overhead Dependency Injection** (@Service, @Autowired, @PostConstruct)

---

## Kurulum

### 1. Repository Ekle

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/esasmer-dou/rust-java-rest</url>
    </repository>
</repositories>
```

### 2. Dependency Ekle

```xml
<dependency>
    <groupId>com.reactor</groupId>
    <artifactId>rust-java-rest</artifactId>
    <version>2.0.0</version>
</dependency>
```

> **Not:** GitHub Packages erişimi için `~/.m2/settings.xml` dosyanıza GitHub token eklemeniz gerekebilir.
> Detaylı bilgi: https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry

### 2. DSL-JSON Annotation Processor Ekle

```xml
<build>
    <plugins>
        <plugin>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.12.1</version>
            <configuration>
                <release>21</release>
                <annotationProcessorPaths>
                    <path>
                        <groupId>com.dslplatform</groupId>
                        <artifactId>dsl-json</artifactId>
                        <version>2.0.2</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

---

## Kullanım

### Adım 1: Request DTO Oluştur

```java
import com.reactor.rust.annotations.Request;
import com.dslplatform.json.CompiledJson;

@Request
@CompiledJson
public record SiparisRequest(
    String siparisId,
    double tutar
) {}
```

### Adım 2: Response DTO Oluştur

```java
import com.reactor.rust.annotations.Response;
import com.dslplatform.json.CompiledJson;

@Response
@CompiledJson
public record SiparisResponse(
    int durum,
    String mesaj
) {}
```

### Adım 3: Handler Oluştur

#### Yeni Stil (Önerilen) - Annotation-Based Parametreler

```java
import com.reactor.rust.annotations.*;
import com.reactor.rust.http.ResponseEntity;
import com.reactor.rust.http.HttpStatus;
import com.reactor.rust.http.MediaType;

@RequestMapping("/siparis")
public class SiparisHandler {

    @PostMapping(value = "/olustur", requestType = SiparisRequest.class, responseType = SiparisResponse.class)
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<SiparisResponse> olustur(
            @RequestBody SiparisRequest request,
            @HeaderParam("X-Request-ID") String requestId) {

        // İş mantığı
        System.out.println("Sipariş: " + request.siparisId());
        System.out.println("Request ID: " + requestId);

        // ResponseEntity ile response döndür
        SiparisResponse response = new SiparisResponse(1, "Başarılı");
        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/{id}", responseType = SiparisResponse.class)
    public ResponseEntity<SiparisResponse> getir(@PathVariable("id") String id) {
        SiparisResponse response = new SiparisResponse(1, "Bulundu: " + id);
        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/ara", responseType = SiparisResponse.class)
    public ResponseEntity<SiparisResponse> ara(@RequestParam("durum") String durum) {
        SiparisResponse response = new SiparisResponse(1, "Durum: " + durum);
        return ResponseEntity.ok(response);
    }
}
```

#### Eski Stil - ByteBuffer İmzası (Backward Compatible)

```java
import com.reactor.rust.annotations.RustRoute;
import com.reactor.rust.json.DslJsonService;
import java.nio.ByteBuffer;

public class SiparisHandler {

    @RustRoute(
        method = "POST",
        path = "/siparis/olustur",
        requestType = SiparisRequest.class,
        responseType = SiparisResponse.class
    )
    public int olustur(ByteBuffer out, int offset, byte[] body) {
        // JSON'dan nesneye çevir
        SiparisRequest request = DslJsonService.parse(body, SiparisRequest.class);

        // İş mantığı
        System.out.println("Sipariş: " + request.siparisId());

        // Response oluştur
        SiparisResponse response = new SiparisResponse(1, "Başarılı");

        // JSON'a çevir ve buffer'a yaz
        return DslJsonService.writeToBuffer(response, out, offset);
    }
}
```

### Adım 4: Main Sınıfı

```java
import com.reactor.rust.bridge.HandlerRegistry;
import com.reactor.rust.bridge.NativeBridge;
import com.reactor.rust.bridge.RouteScanner;

public class Uygulama {
    public static void main(String[] args) throws InterruptedException {
        // Handler'ı kaydet
        HandlerRegistry registry = HandlerRegistry.getInstance();
        registry.registerBean(new SiparisHandler());

        // Route'ları tara
        RouteScanner.scanAndRegister();

        // Sunucuyu başlat
        NativeBridge.startHttpServer(8080);
        System.out.println("Sunucu çalışıyor: http://localhost:8080");

        // JVM canlı tut
        Thread.sleep(Long.MAX_VALUE);
    }
}
```

### Adım 5: Çalıştır

```bash
mvn clean package -DskipTests
java -cp target/rust-java-rest-2.0.0.jar:target/lib/* Uygulama
```

### Adım 6: Test Et

```bash
curl -X POST http://localhost:8080/siparis/olustur \
  -H "Content-Type: application/json" \
  -H "X-Request-ID: REQ-001" \
  -d '{"siparisId":"SIP-001", "tutar":150.50}'
```

---

## HTTP Metod Annotation'ları

Framework Spring Boot benzeri HTTP metod annotation'ları destekler:

### @GetMapping

```java
@GetMapping(value = "/urun/{id}", responseType = UrunResponse.class)
public ResponseEntity<UrunResponse> getir(@PathVariable("id") String id) {
    return ResponseEntity.ok(urunService.bul(id));
}
```

### @PostMapping

```java
@PostMapping(value = "/urun/ekle", requestType = UrunRequest.class, responseType = UrunResponse.class)
@ResponseStatus(HttpStatus.CREATED)
public ResponseEntity<UrunResponse> ekle(@RequestBody UrunRequest request) {
    return ResponseEntity.created(urunService.kaydet(request));
}
```

### @PutMapping

```java
@PutMapping(value = "/urun/guncelle", requestType = UrunRequest.class, responseType = UrunResponse.class)
public ResponseEntity<UrunResponse> guncelle(@RequestBody UrunRequest request) {
    return ResponseEntity.ok(urunService.guncelle(request));
}
```

### @PatchMapping

```java
@PatchMapping(value = "/urun/fiyat", requestType = FiyatGuncelleRequest.class, responseType = UrunResponse.class)
public ResponseEntity<UrunResponse> fiyatGuncelle(@RequestBody FiyatGuncelleRequest request) {
    return ResponseEntity.ok(urunService.fiyatGuncelle(request));
}
```

### @DeleteMapping

```java
@DeleteMapping(value = "/urun/{id}", responseType = UrunResponse.class)
public ResponseEntity<UrunResponse> sil(@PathVariable("id") String id) {
    urunService.sil(id);
    return ResponseEntity.ok(new UrunResponse(1, "Silindi"));
}
```

### @RequestMapping (Class-Level)

```java
@RequestMapping("/api/v1")
public class ApiHandler {

    @GetMapping(value = "/urunler", responseType = UrunListResponse.class)
    public ResponseEntity<UrunListResponse> tumUrunler() {
        // GET /api/v1/urunler
        return ResponseEntity.ok(urunService.tumunuGetir());
    }
}
```

---

## Parametre Annotation'ları

### @PathVariable - Path Parametresi

```java
@GetMapping(value = "/siparis/{id}", responseType = SiparisResponse.class)
public ResponseEntity<SiparisResponse> getir(@PathVariable("id") String siparisId) {
    // GET /siparis/SIP-001 -> siparisId = "SIP-001"
    return ResponseEntity.ok(siparisService.bul(siparisId));
}

@GetMapping(value = "/siparis/{id}/urun/{urunId}", responseType = UrunResponse.class)
public ResponseEntity<UrunResponse> urunGetir(
        @PathVariable("id") String siparisId,
        @PathVariable("urunId") String urunId) {
    // GET /siparis/SIP-001/urun/URUN-123
    return ResponseEntity.ok(siparisService.urunBul(siparisId, urunId));
}
```

### @RequestParam - Query Parametresi

```java
@GetMapping(value = "/siparis/ara", responseType = SiparisListResponse.class)
public ResponseEntity<SiparisListResponse> ara(
        @RequestParam("durum") String durum,
        @RequestParam(value = "sayfa", defaultValue = "1") int sayfa) {
    // GET /siparis/ara?durum=bekliyor&sayfa=2
    return ResponseEntity.ok(siparisService.ara(durum, sayfa));
}

@GetMapping(value = "/urun/liste", responseType = UrunListResponse.class)
public ResponseEntity<UrunListResponse> liste(
        @RequestParam(value = "sirala", required = false) String sirala,
        @RequestParam(value = "limit", defaultValue = "10") int limit) {
    // GET /urun/liste?sirala=fiyat&limit=20
    return ResponseEntity.ok(urunService.liste(sirala, limit));
}
```

### @HeaderParam - Header Değeri

```java
@PostMapping(value = "/siparis/olustur", requestType = SiparisRequest.class, responseType = SiparisResponse.class)
public ResponseEntity<SiparisResponse> olustur(
        @RequestBody SiparisRequest request,
        @HeaderParam("X-Request-ID") String requestId,
        @HeaderParam("Authorization") String token) {
    // X-Request-ID ve Authorization header'larını al
    return ResponseEntity.ok(siparisService.olustur(request, requestId, token));
}
```

### @RequestBody - Request Body

```java
@PostMapping(value = "/urun/ekle", requestType = UrunRequest.class, responseType = UrunResponse.class)
public ResponseEntity<UrunResponse> ekle(@RequestBody UrunRequest request) {
    // Body otomatik olarak UrunRequest'e deserialize edilir
    return ResponseEntity.ok(urunService.kaydet(request));
}
```

### @CookieValue - Cookie Değeri

```java
@GetMapping(value = "/kullanici/bilgi", responseType = KullaniciResponse.class)
public ResponseEntity<KullaniciResponse> bilgi(@CookieValue("sessionId") String sessionId) {
    // Cookie'den sessionId değerini al
    return ResponseEntity.ok(kullaniciService.oturumBul(sessionId));
}
```

---

## ResponseEntity Kullanımı

ResponseEntity, HTTP response'ları için tip-güvenli bir wrapper sağlar:

```java
import com.reactor.rust.http.ResponseEntity;
import com.reactor.rust.http.HttpStatus;

// 200 OK
@GetMapping(value = "/urun/{id}", responseType = UrunResponse.class)
public ResponseEntity<UrunResponse> getir(@PathVariable("id") String id) {
    return ResponseEntity.ok(urunService.bul(id));
}

// 201 Created
@PostMapping(value = "/urun/ekle", requestType = UrunRequest.class, responseType = UrunResponse.class)
public ResponseEntity<UrunResponse> ekle(@RequestBody UrunRequest request) {
    return ResponseEntity.created(urunService.kaydet(request));
}

// 404 Not Found
@GetMapping(value = "/urun/{id}", responseType = UrunResponse.class)
public ResponseEntity<UrunResponse> getir(@PathVariable("id") String id) {
    UrunResponse urun = urunService.bul(id);
    if (urun == null) {
        return ResponseEntity.notFound();
    }
    return ResponseEntity.ok(urun);
}

// 400 Bad Request
@PostMapping(value = "/urun/ekle", requestType = UrunRequest.class, responseType = UrunResponse.class)
public ResponseEntity<UrunResponse> ekle(@RequestBody UrunRequest request) {
    if (request.ad() == null || request.ad().isEmpty()) {
        return ResponseEntity.badRequest();
    }
    return ResponseEntity.ok(urunService.kaydet(request));
}

// Custom Status
@DeleteMapping(value = "/urun/{id}", responseType = Void.class)
public ResponseEntity<Void> sil(@PathVariable("id") String id) {
    urunService.sil(id);
    return ResponseEntity.status(HttpStatus.NO_CONTENT);
}
```

---

## @ResponseStatus Annotation

Handler metodları için HTTP status code belirtmek için kullanılır:

```java
import com.reactor.rust.annotations.ResponseStatus;
import com.reactor.rust.http.HttpStatus;

@PostMapping(value = "/siparis/olustur", requestType = SiparisRequest.class, responseType = SiparisResponse.class)
@ResponseStatus(201)  // veya HttpStatus.CREATED = 201
public ResponseEntity<SiparisResponse> olustur(@RequestBody SiparisRequest request) {
    return ResponseEntity.ok(siparisService.olustur(request));
}

@DeleteMapping(value = "/siparis/{id}", responseType = Void.class)
@ResponseStatus(204)  // veya HttpStatus.NO_CONTENT = 204
public ResponseEntity<Void> sil(@PathVariable("id") String id) {
    siparisService.sil(id);
    return null;
}
```

---

## HttpStatus Enum

Yaygın HTTP status code'ları için enum:

```java
import com.reactor.rust.http.HttpStatus;

// Kullanım
HttpStatus.OK           // 200
HttpStatus.CREATED      // 201
HttpStatus.NO_CONTENT   // 204
HttpStatus.BAD_REQUEST  // 400
HttpStatus.UNAUTHORIZED // 401
HttpStatus.FORBIDDEN    // 403
HttpStatus.NOT_FOUND    // 404
HttpStatus.INTERNAL_SERVER_ERROR // 500

// ResponseEntity ile
return ResponseEntity.status(HttpStatus.CREATED);
```

---

## MediaType Constants

Content-type için sabitler:

```java
import com.reactor.rust.http.MediaType;

MediaType.APPLICATION_JSON   // "application/json"
MediaType.TEXT_PLAIN         // "text/plain"
MediaType.TEXT_HTML          // "text/html"
MediaType.APPLICATION_XML    // "application/xml"
MediaType.TEXT_CSV           // "text/csv"
MediaType.APPLICATION_OCTET_STREAM // "application/octet-stream"

// Kullanım
@PostMapping(value = "/siparis/olustur", requestType = SiparisRequest.class, responseType = SiparisResponse.class)
public ResponseEntity<SiparisResponse> olustur(
        @RequestBody SiparisRequest request,
        @HeaderParam("Content-Type") String contentType) {

    if (contentType == null || !contentType.contains(MediaType.APPLICATION_JSON)) {
        return ResponseEntity.badRequest();
    }

    return ResponseEntity.ok(siparisService.olustur(request));
}
```

---

## Eski Stil (V4 İmza) - Backward Compatible

Annotation-based parametreler istemezseniz, eski V4 imzasını kullanmaya devam edebilirsiniz:

### Sadece Body

```java
@RustRoute(
    method = "POST",
    path = "/siparis/olustur",
    requestType = SiparisRequest.class,
    responseType = SiparisResponse.class
)
public int olustur(ByteBuffer out, int offset, byte[] body) {
    SiparisRequest request = DslJsonService.parse(body, SiparisRequest.class);
    SiparisResponse response = new SiparisResponse(1, "Başarılı");
    return DslJsonService.writeToBuffer(response, out, offset);
}
```

### Path Parametresi

```java
@RustRoute(
    method = "GET",
    path = "/siparis/{id}",
    requestType = Void.class,
    responseType = SiparisResponse.class
)
public int getir(ByteBuffer out, int offset, byte[] body, String pathParams) {
    // pathParams = "id=SIP-001"
    String id = parametreAl(pathParams, "id");
    SiparisResponse response = new SiparisResponse(1, "Bulundu: " + id);
    return DslJsonService.writeToBuffer(response, out, offset);
}

// Yardımcı metod
private String parametreAl(String params, String key) {
    if (params == null) return null;
    for (String pair : params.split("&")) {
        String[] kv = pair.split("=", 2);
        if (kv[0].equals(key)) return kv[1];
    }
    return null;
}
```

### Path + Query Parametresi

```java
@RustRoute(
    method = "GET",
    path = "/siparis/ara",
    requestType = Void.class,
    responseType = SiparisResponse.class
)
public int ara(ByteBuffer out, int offset, byte[] body,
               String pathParams, String queryString) {
    // queryString = "durum=bekliyor&sira=1"
    String durum = parametreAl(queryString, "durum");
    SiparisResponse response = new SiparisResponse(1, "Durum: " + durum);
    return DslJsonService.writeToBuffer(response, out, offset);
}
```

### Tam İmza (Path + Query + Headers)

```java
@RustRoute(
    method = "POST",
    path = "/siparis/olustur",
    requestType = SiparisRequest.class,
    responseType = SiparisResponse.class
)
public int olustur(ByteBuffer out, int offset, byte[] body,
                   String pathParams, String queryString, String headers) {
    // headers = "Content-Type=application/json&X-Request-ID=REQ-001"
    String requestId = parametreAl(headers, "X-Request-ID");

    SiparisRequest request = DslJsonService.parse(body, SiparisRequest.class);
    SiparisResponse response = siparisService.olustur(request, requestId);
    return DslJsonService.writeToBuffer(response, out, offset);
}
```

---

## Handler Method İmzaları

### Yeni Stil (Annotation-Based)

| İmza | Açıklama |
|------|----------|
| `ResponseEntity<T> method(@PathVariable String id)` | Path parametresi |
| `ResponseEntity<T> method(@RequestParam String q)` | Query parametresi |
| `ResponseEntity<T> method(@RequestBody Request req)` | Request body |
| `ResponseEntity<T> method(@HeaderParam String h)` | Header |
| `T method(...)` | Otomatik serialize edilir |

### Eski Stil (V4 - ByteBuffer)

| İhtiyaç | İmza |
|---------|------|
| Sadece body | `int method(ByteBuffer out, int offset, byte[] body)` |
| Path parametresi | `int method(ByteBuffer out, int offset, byte[] body, String pathParams)` |
| Path + Query | `int method(ByteBuffer out, int offset, byte[] body, String pathParams, String queryString)` |
| Tam imza | `int method(ByteBuffer out, int offset, byte[] body, String pathParams, String queryString, String headers)` |

---

## Desteklenen Platformlar

| Platform | Native Library | Durum |
|----------|----------------|-------|
| Linux x64 | `librust_hyper.so` | ✅ |
| Windows x64 | `rust_hyper.dll` | ✅ |
| macOS x64 | `librust_hyper.dylib` | 🚧 Yakında |
| macOS ARM64 | `librust_hyper.dylib` | 🚧 Yakında |

---

## Native Library Kullanımı

Framework, Rust Hyper HTTP sunucusu için native library gerektirir. Bu library **otomatik olarak JAR içine gömülüdür** ve runtime'da otomatik yüklenir.

### Otomatik Yükleme (Varsayılan)

```java
// Native library otomatik yüklenir - ek işlem gerekmez
NativeBridge.startHttpServer(8080);
```

### Manuel Yükleme

```bash
# Özel library yolu belirtmek için
java -Drust.lib.path=/path/to/rust_hyper.dll -jar myapp.jar

# veya java.library.path kullanarak
java -Djava.library.path=/path/to/native/dir -jar myapp.jar
```

### Native Library Dosyaları

| Platform | Dosya | Konum (JAR içinde) |
|----------|-------|-------------------|
| Windows x64 | `rust_hyper.dll` | `native/windows-x64/` |
| Linux x64 | `librust_hyper.so` | `native/linux-x64/` |

---

## Docker

Framework, production için optimize edilmiş Docker image desteği sunar.

### GitHub Container Registry'den Çek

```bash
docker pull ghcr.io/esasmer-dou/rust-java-rest:2.0.0
docker run -p 8080:8080 --memory=50m ghcr.io/esasmer-dou/rust-java-rest:2.0.0
```

### Build

```bash
# Proje dizininde
cd rust-java-rest

# Image oluştur
docker build -t rust-java-rest:2.0.0 -f src/main/resources/container/Dockerfile .
```

### Çalıştır

```bash
# 40MB memory limit ile
docker run -d -p 8080:8080 --memory=40m --name rust-java-app rust-java-rest:2.0.0

# Health check ile
docker run -d -p 8080:8080 --memory=40m --health-cmd="curl -f http://localhost:8080/health" rust-java-rest:2.0.0
```

### Dockerfile Özellikleri

| Özellik | Değer |
|---------|-------|
| Base Image | `eclipse-temurin:21-jre-jammy` |
| Multi-stage Build | ✅ (JDK → JRE) |
| Non-root User | ✅ (`appuser`) |
| Health Check | ✅ (10s interval) |
| Memory Limit | 40MB |
| JVM Heap | 4-20MB |

### JVM Ayarları

```bash
# Dockerfile içindeki varsayılan ayarlar
-Xms4m                          # Minimum heap
-Xmx20m                         # Maximum heap
-XX:+UseSerialGC                # En düşük memory GC
-XX:MaxMetaspaceSize=24m        # Metaspace limit
-XX:+TieredCompilation          # Hızlı startup
-XX:TieredStopAtLevel=1         # C1 compiler only
```

### Docker Compose

```yaml
version: '3.8'
services:
  rust-java-rest:
    image: rust-java-rest:2.0.0
    ports:
      - "8080:8080"
    deploy:
      resources:
        limits:
          memory: 40M
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/health"]
      interval: 10s
      timeout: 3s
      retries: 3
```

---

## Gereksinimler

- Java 21+
- Maven 3.8+

---

## Proje Yapısı

```
com.myapp/
├── Uygulama.java           # Main sınıfı
├── dto/
│   ├── SiparisRequest.java
│   └── SiparisResponse.java
└── handler/
    └── SiparisHandler.java
```

---

## Annotation Özeti

### REST API Annotation'ları

| Annotation | Açıklama |
|------------|----------|
| `@RequestMapping` | Class-level base path |
| `@GetMapping` | GET request handler |
| `@PostMapping` | POST request handler |
| `@PutMapping` | PUT request handler |
| `@PatchMapping` | PATCH request handler |
| `@DeleteMapping` | DELETE request handler |
| `@PathVariable` | Path parametresi al |
| `@RequestParam` | Query parametresi al |
| `@HeaderParam` | Header değeri al |
| `@RequestBody` | Request body deserialize |
| `@CookieValue` | Cookie değeri al |
| `@ResponseStatus` | HTTP status code belirt |
| `@RustRoute` | Legacy annotation (V4 imza) |
| `@Request` | Request DTO işaretle |
| `@Response` | Response DTO işaretle |

### DI Annotation'ları

| Annotation | Açıklama |
|------------|----------|
| `@Component` | Genel bileşen işaretle |
| `@Service` | İş mantığı servisi |
| `@Repository` | Veri erişim katmanı |
| `@Configuration` | Konfigürasyon sınıfı |
| `@Bean` | Bean üreten metod |
| `@Autowired` | Bağımlılık enjeksiyonu |
| `@PostConstruct` | Başlatma callback'i |
| `@PreDestroy` | Temizleme callback'i |
| `@Primary` | Primary bean |
| `@Qualifier` | Bean seçimi |

---

## Dependency Injection (DI)

Framework, Spring Boot benzeri sıfır-overhead Dependency Injection desteği sunar. Tüm bağımlılıklar startup'ta çözülür, runtime'da reflection YOK.

### DI Annotation'ları

| Annotation | Açıklama |
|------------|----------|
| `@Component` | Genel bileşen |
| `@Service` | İş mantığı servisi |
| `@Repository` | Veri erişim katmanı |
| `@Configuration` | Konfigürasyon sınıfı |
| `@Bean` | Bean üreten metod |
| `@Autowired` | Bağımlılık enjeksiyonu |
| `@PostConstruct` | Başlatma callback'i |
| `@PreDestroy` | Temizleme callback'i |
| `@Primary` | Primary bean |
| `@Qualifier` | Bean seçimi |

### Servis Tanımlama

```java
import com.reactor.rust.di.annotation.Service;
import com.reactor.rust.di.annotation.Autowired;
import com.reactor.rust.di.annotation.PostConstruct;

@Service
public class OrderService {

    @Autowired(required = false)
    private NotificationService notificationService;

    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        System.out.println("[OrderService] Initialized");
    }

    public Order createOrder(OrderRequest request) {
        Order order = new Order(generateId(), request);
        orders.put(order.id(), order);

        if (notificationService != null) {
            notificationService.notify("Order created: " + order.id());
        }

        return order;
    }
}
```

### @Configuration ve @Bean

```java
import com.reactor.rust.di.annotation.Configuration;
import com.reactor.rust.di.annotation.Bean;

@Configuration
public class AppConfiguration {

    @Bean
    public ExecutorService taskExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean("appMetadata")
    public AppMetadata appMetadata() {
        return new AppMetadata("my-app", "1.0.0");
    }

    public record AppMetadata(String name, String version) {}
}
```

### Handler'da Servis Kullanımı

```java
import com.reactor.rust.di.annotation.Autowired;

@RequestMapping("/siparis")
public class SiparisHandler {

    @Autowired
    private OrderService orderService;  // Otomatik enjekte edilir

    @PostMapping(value = "/olustur", requestType = SiparisRequest.class, responseType = SiparisResponse.class)
    public ResponseEntity<SiparisResponse> olustur(@RequestBody SiparisRequest request) {
        // orderService otomatik olarak inject edilmiş
        Order order = orderService.createOrder(request);
        return ResponseEntity.ok(new SiparisResponse(1, "OK"));
    }
}
```

### DI Container Kullanımı

```java
import com.reactor.rust.di.BeanContainer;
import com.reactor.rust.bridge.HandlerRegistry;
import com.reactor.rust.bridge.RouteScanner;
import com.reactor.rust.bridge.NativeBridge;

public class Application {
    public static void main(String[] args) throws InterruptedException {
        // 1. DI Container'ı başlat
        BeanContainer container = BeanContainer.getInstance();

        // 2. Component scanning
        container.scan("com.myapp");

        // 3. Container'ı başlat (tüm bağımlılıklar çözülür)
        container.start();

        // 4. Route'ları tara
        RouteScanner.scanAndRegister();

        // 5. Handler'ları kaydet
        HandlerRegistry registry = HandlerRegistry.getInstance();
        registry.registerBean(new SiparisHandler());

        // 6. Sunucuyu başlat
        NativeBridge.startHttpServer(8080);

        System.out.println("Sunucu çalışıyor: http://localhost:8080");
        Thread.sleep(Long.MAX_VALUE);
    }
}
```

### Lifecycle Callback'ler

```java
import com.reactor.rust.di.annotation.Service;
import com.reactor.rust.di.annotation.PostConstruct;
import com.reactor.rust.di.annotation.PreDestroy;

@Service
public class NotificationService {

    private ExecutorService executor;

    @PostConstruct
    public void init() {
        // Başlatma
        executor = Executors.newSingleThreadExecutor();
        System.out.println("[NotificationService] Ready");
    }

    @PreDestroy
    public void cleanup() {
        // Temizleme
        executor.shutdown();
        System.out.println("[NotificationService] Shutdown");
    }

    public void notify(String message) {
        executor.submit(() -> sendNotification(message));
    }
}
```

### Primary ve Qualifier

```java
// Birden fazla aynı tipte bean varsa
@Service
@Primary  // Varsayılan olarak bu kullanılır
public class DefaultEmailService implements EmailService { ... }

@Service
public class SmtpEmailService implements EmailService { ... }

// Kullanım
@Service
public class UserService {

    @Autowired
    private EmailService emailService;  // DefaultEmailService enjekte edilir

    @Autowired
    @Qualifier("smtpEmailService")  // Belirli bir bean
    private EmailService smtpService;
}
```

### DI Performans Özellikleri

| Metric | Value |
|--------|-------|
| Bean Lookup | O(1) ConcurrentHashMap |
| Lookup Time | ~0.4 microseconds |
| Memory Overhead | ~50-100 bytes/bean |
| Runtime Reflection | **NONE** |

### DI vs Spring Boot Karşılaştırması

| Özellik | Rust-Java REST | Spring Boot |
|---------|----------------|-------------|
| Startup Time | ~100ms | ~2-5s |
| Memory Overhead | ~1-2 MB | ~30-50 MB |
| Bean Lookup | O(1) direct | O(1) + proxy |
| Runtime Reflection | Hayır | Evet |
| AOP Support | Hayır | Evit |
| Proxy Overhead | Hayır | Evit |

---

## Lisans

MIT
