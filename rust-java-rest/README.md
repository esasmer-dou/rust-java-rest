# Rust-Java REST Framework

Ultra-low latency REST API framework combining Rust Hyper server with Java handlers via JNI.

---

## Hızlı Başlangıç Rehberi

Bu rehber, library'i kendi projenize ekleyip sıfırdan bir REST API oluşturmayı adım adım anlatır.

### Adım 1: Maven Dependency Ekle

`pom.xml` dosyanıza şu dependency'yi ekleyin:

```xml
<dependency>
    <groupId>com.reactor</groupId>
    <artifactId>rust-java-rest</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Adım 2: Request DTO Oluştur

Gelen istek verisi için bir Record sınıfı oluşturun:

```java
package com.myapp.dto;

import com.reactor.rust.annotations.Request;
import com.dslplatform.json.CompiledJson;

@Request
@CompiledJson  // DSL-JSON için gerekli
public record OrderCreateRequest(
    String orderId,
    double amount
) {}
```

### Adım 3: Response DTO Oluştur

Dönüş verisi için bir Record sınıfı oluşturun:

```java
package com.myapp.dto;

import com.reactor.rust.annotations.Response;
import com.dslplatform.json.CompiledJson;

@Response
@CompiledJson
public record OrderCreateResponse(
    int status,
    String message,
    long timestamp
) {}
```

### Adım 4: Handler Sınıfı Oluştur

İstekleri işleyecek handler sınıfını oluşturun:

```java
package com.myapp.handler;

import com.myapp.dto.*;
import com.reactor.rust.annotations.RustRoute;
import com.reactor.rust.json.DslJsonService;
import java.nio.ByteBuffer;

public class OrderHandler {

    @RustRoute(
        method = "POST",
        path = "/order/create",
        requestType = OrderCreateRequest.class,
        responseType = OrderCreateResponse.class
    )
    public int createOrder(ByteBuffer out, int offset, byte[] body) {
        // 1. JSON'u parse et
        OrderCreateRequest request = DslJsonService.parse(body, OrderCreateRequest.class);

        // 2. İş mantığını çalıştır
        System.out.println("Sipariş alındı: " + request.orderId() + ", Tutar: " + request.amount());

        // 3. Response oluştur
        OrderCreateResponse response = new OrderCreateResponse(
            1,
            "Sipariş oluşturuldu",
            System.currentTimeMillis()
        );

        // 4. Response'u buffer'a yaz
        return DslJsonService.writeToBuffer(response, out, offset);
    }
}
```

### Adım 5: Main Sınıfı Oluştur

Uygulamayı başlatacak main sınıfını oluşturun:

```java
package com.myapp;

import com.myapp.handler.OrderHandler;
import com.reactor.rust.bridge.HandlerRegistry;
import com.reactor.rust.bridge.NativeBridge;
import com.reactor.rust.bridge.RouteScanner;

public class Application {
    public static void main(String[] args) {
        System.out.println("Uygulama başlatılıyor...");

        // 1. Handler'ları kaydet
        HandlerRegistry registry = HandlerRegistry.getInstance();
        registry.registerBean(new OrderHandler());

        // 2. Route'ları tara ve kaydet
        RouteScanner.scanAndRegister();

        // 3. HTTP sunucusunu başlat
        NativeBridge.startHttpServer(8080);

        System.out.println("Sunucu 8080 portunda çalışıyor!");

        // 4. JVM'yi canlı tut
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

### Adım 6: Properties Dosyası (Opsiyonel)

`src/main/resources/rust-spring.properties` dosyasını oluşturun:

```properties
server.port=8080
server.host=0.0.0.0
```

### Adım 7: Çalıştır

```bash
# Maven ile derle
mvn clean package -DskipTests

# Çalıştır
java -cp target/rust-java-rest-1.0.0.jar:target/lib/* com.myapp.Application
```

### Adım 8: Test Et

```bash
# POST isteği at
curl -X POST http://localhost:8080/order/create \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-001", "amount":150.50}'
```

**Beklenen Response:**
```json
{"status":1,"message":"Sipariş oluşturuldu","timestamp":1709876543210}
```

---

## Tam Proje Yapısı

```
my-app/
├── pom.xml
└── src/main/
    ├── java/com/myapp/
    │   ├── Application.java          # Main sınıfı
    │   ├── dto/
    │   │   ├── OrderCreateRequest.java
    │   │   └── OrderCreateResponse.java
    │   └── handler/
    │       └── OrderHandler.java
    └── resources/
        └── rust-spring.properties    # Opsiyonel
```

---

## Handler Method İmzaları

### Temel İmza (Sadece Body)
```java
public int methodName(ByteBuffer out, int offset, byte[] body)
```

### Path Parametresi ile (`/order/{id}`)
```java
public int methodName(ByteBuffer out, int offset, byte[] body, String pathParams)
```

### Path + Query Parametresi ile (`/order/search?status=active`)
```java
public int methodName(ByteBuffer out, int offset, byte[] body,
                      String pathParams, String queryString)
```

### Full İmza (Headers dahil)
```java
public int methodName(ByteBuffer out, int offset, byte[] body,
                      String pathParams, String queryString, String headers)
```

---

## Örnek: Path Parametresi Kullanımı

```java
@RustRoute(
    method = "GET",
    path = "/order/{id}",
    requestType = Void.class,
    responseType = OrderResponse.class
)
public int getOrderById(ByteBuffer out, int offset, byte[] body, String pathParams) {
    // pathParams = "id=123"
    String orderId = parseParam(pathParams, "id");

    OrderResponse response = new OrderResponse(orderId, 100.0, "PENDING");
    return DslJsonService.writeToBuffer(response, out, offset);
}

// Yardımcı metod
private String parseParam(String params, String key) {
    if (params == null || params.isEmpty()) return null;
    for (String pair : params.split("&")) {
        String[] kv = pair.split("=", 2);
        if (kv[0].equals(key)) return kv[1];
    }
    return null;
}
```

---

## Desteklenen Platformlar

| Platform | Mimari | Durum |
|----------|--------|-------|
| Linux | x64 | ✅ Destekleniyor |
| Windows | x64 | ✅ Destekleniyor |
| macOS | x64 | 🚧 Yakında |
| macOS | ARM64 (M1/M2) | 🚧 Yakında |

> **macOS Kullanıcıları**: Native library'yi kaynak koddan derleyebilirsiniz.

---

## Performans Karşılaştırması

| Metrik | Spring Boot | Rust-Java REST | İyileştirme |
|--------|-------------|----------------|-------------|
| Memory | 94 MB | 27 MB | -71% |
| RPS | 1,150 | 3,257 | +183% |
| Latency (Avg) | 144 ms | 33 ms | -77% |
| Latency (P99) | 1,520 ms | 134 ms | -91% |

---

## Gereksinimler

- **Java 21+** (gerekli)
- **Maven 3.8+** (derleme için)
- **Rust 1.82+** (opsiyonel - sadece native library derlemek için)

---

## Docker ile Çalıştırma

```bash
# Image oluştur
docker build -t rust-java-rest:minimal -f docker/rust-spring-perf/Dockerfile .

# Container çalıştır (40MB memory limit)
docker run -p 8080:8080 --memory=40m rust-java-rest:minimal
```

---

## License

MIT License
