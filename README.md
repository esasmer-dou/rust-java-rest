# Rust-Java REST Framework

Rust Hyper HTTP sunucusu + Java handler'lar ile ultra hızlı REST API framework.

**Özellikler:**
- 27 MB memory (Spring Boot: 94 MB)
- 3,257 RPS (Spring Boot: 1,150 RPS)
- 33 ms latency (Spring Boot: 144 ms)
- Spring Boot benzeri annotation-based API
- ResponseEntity<T> dönüş tipi desteği
- Otomatik parametre çözümleme (@PathVariable, @RequestParam, @HeaderParam, @RequestBody)

---

## Kurulum

### 1. pom.xml'e Ekle

```xml
<dependency>
    <groupId>com.reactor</groupId>
    <artifactId>rust-java-rest</artifactId>
    <version>1.0.0</version>
</dependency>
```

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
java -cp target/rust-java-rest-1.0.0.jar:target/lib/* Uygulama
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

| Platform | Durum |
|----------|-------|
| Linux x64 | ✅ |
| Windows x64 | ✅ |
| macOS | 🚧 Yakında |

---

## Docker

```bash
# Image oluştur
docker build -t rust-java-rest:latest -f docker/rust-java-rest/Dockerfile .

# Çalıştır (40MB limit)
docker run -p 8080:8080 --memory=40m rust-java-rest:latest
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

---

## Lisans

MIT
