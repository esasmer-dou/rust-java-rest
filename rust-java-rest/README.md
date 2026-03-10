# Rust-Java REST Framework

Rust Hyper HTTP sunucusu + Java handler'lar ile ultra hızlı REST API framework.

**Özellikler:**
- 27 MB memory (Spring Boot: 94 MB)
- 3,257 RPS (Spring Boot: 1,150 RPS)
- 33 ms latency (Spring Boot: 144 ms)

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
  -d '{"siparisId":"SIP-001", "tutar":150.50}'
```

---

## Path Parametresi Örneği

`/siparis/{id}` gibi URL'lerde path parametresi almak:

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

---

## Query Parametresi Örneği

`/siparis/ara?durum=bekliyor` gibi URL'lerde query parametresi almak:

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

---

## HTTP Metodları Örnekleri

### GET - Veri Getirme

```java
@RustRoute(
    method = "GET",
    path = "/urun/{id}",
    requestType = Void.class,
    responseType = UrunResponse.class
)
public int getir(ByteBuffer out, int offset, byte[] body, String pathParams) {
    String id = parametreAl(pathParams, "id");
    UrunResponse response = urunService.bul(id);
    return DslJsonService.writeToBuffer(response, out, offset);
}
```

### POST - Yeni Kayıt

```java
@RustRoute(
    method = "POST",
    path = "/urun/ekle",
    requestType = UrunRequest.class,
    responseType = UrunResponse.class
)
public int ekle(ByteBuffer out, int offset, byte[] body) {
    UrunRequest request = DslJsonService.parse(body, UrunRequest.class);
    UrunResponse response = urunService.kaydet(request);
    return DslJsonService.writeToBuffer(response, out, offset);
}
```

### PUT - Tam Güncelleme

```java
@RustRoute(
    method = "PUT",
    path = "/urun/guncelle",
    requestType = UrunRequest.class,
    responseType = UrunResponse.class
)
public int guncelle(ByteBuffer out, int offset, byte[] body) {
    UrunRequest request = DslJsonService.parse(body, UrunRequest.class);
    UrunResponse response = urunService.guncelle(request);
    return DslJsonService.writeToBuffer(response, out, offset);
}
```

### PATCH - Kısmi Güncelleme

```java
@RustRoute(
    method = "PATCH",
    path = "/urun/fiyat",
    requestType = FiyatGuncelleRequest.class,
    responseType = UrunResponse.class
)
public int fiyatGuncelle(ByteBuffer out, int offset, byte[] body) {
    FiyatGuncelleRequest request = DslJsonService.parse(body, FiyatGuncelleRequest.class);
    UrunResponse response = urunService.fiyatGuncelle(request);
    return DslJsonService.writeToBuffer(response, out, offset);
}
```

### DELETE - Silme

```java
@RustRoute(
    method = "DELETE",
    path = "/urun/{id}",
    requestType = Void.class,
    responseType = UrunResponse.class
)
public int sil(ByteBuffer out, int offset, byte[] body, String pathParams) {
    String id = parametreAl(pathParams, "id");
    urunService.sil(id);
    UrunResponse response = new UrunResponse(1, "Silindi");
    return DslJsonService.writeToBuffer(response, out, offset);
}
```

---

## Content-Type (MIME Type) Örnekleri

### JSON Response (Varsayılan)

```java
@RustRoute(
    method = "GET",
    path = "/api/urun/{id}",
    requestType = Void.class,
    responseType = UrunResponse.class
)
public int jsonGetir(ByteBuffer out, int offset, byte[] body, String pathParams) {
    UrunResponse response = new UrunResponse(1, "Ürün", 150.0);
    return DslJsonService.writeToBuffer(response, out, offset);
}
// Content-Type: application/json
```

### Plain Text Response

```java
@RustRoute(
    method = "GET",
    path = "/metin/selam",
    requestType = Void.class,
    responseType = Void.class
)
public int selam(ByteBuffer out, int offset, byte[] body) {
    String mesaj = "Merhaba Dünya!";
    byte[] bytes = mesaj.getBytes(StandardCharsets.UTF_8);
    out.position(offset);
    out.put(bytes);
    return bytes.length;
}
// Content-Type: text/plain
```

### HTML Response

```java
@RustRoute(
    method = "GET",
    path = "/sayfa/hakkinda",
    requestType = Void.class,
    responseType = Void.class
)
public int hakkinda(ByteBuffer out, int offset, byte[] body) {
    String html = """
        <!DOCTYPE html>
        <html>
        <head><title>Hakkında</title></head>
        <body>
            <h1>Rust-Java REST Framework</h1>
            <p>Ultra hızlı, düşük bellek tüketimi</p>
        </body>
        </html>
        """;
    byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
    out.position(offset);
    out.put(bytes);
    return bytes.length;
}
// Content-Type: text/html
```

### XML Response

```java
@RustRoute(
    method = "GET",
    path = "/xml/urun/{id}",
    requestType = Void.class,
    responseType = Void.class
)
public int xmlGetir(ByteBuffer out, int offset, byte[] body, String pathParams) {
    String id = parametreAl(pathParams, "id");
    String xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <urun>
            <id>%s</id>
            <ad>Telefon</ad>
            <fiyat>15000.00</fiyat>
        </urun>
        """.formatted(id);
    byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
    out.position(offset);
    out.put(bytes);
    return bytes.length;
}
// Content-Type: application/xml
```

### CSV Response

```java
@RustRoute(
    method = "GET",
    path = "/rapor/urunler",
    requestType = Void.class,
    responseType = Void.class
)
public int urunlerCsv(ByteBuffer out, int offset, byte[] body) {
    String csv = """
        id,ad,fiyat,stok
        1,Telefon,15000,100
        2,Bilgisayar,25000,50
        3,Tablet,8000,75
        """;
    byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
    out.position(offset);
    out.put(bytes);
    return bytes.length;
}
// Content-Type: text/csv
```

### Binary Response (Dosya İndirme)

```java
@RustRoute(
    method = "GET",
    path = "/dosya/indir/{ad}",
    requestType = Void.class,
    responseType = Void.class
)
public int dosyaIndir(ByteBuffer out, int offset, byte[] body, String pathParams) {
    String ad = parametreAl(pathParams, "ad");
    byte[] dosyaIcerik = dosyaService.icerikGetir(ad);
    out.position(offset);
    out.put(dosyaIcerik);
    return dosyaIcerik.length;
}
// Content-Type: application/octet-stream
```

---

## Handler Method İmzaları

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

## Lisans

MIT
