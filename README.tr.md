# Rust-Java REST Framework

[English](README.md) | [Türkçe](README.tr.md)

Rust-Java REST, iş mantığını Java'da tutan düşük gecikmeli bir REST framework'üdür. HTTP bağlantısı,
request parse, response yazma, dosya stream, WebSocket ve backpressure işleri Rust Hyper tarafında
çalışır. Handler, service, component, validation ve veri tabanı kodunuz Java'da kalır.

## Hızlı Başlangıç

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-rest</artifactId>
  <version>4.0.0</version>
</dependency>
```

```java
public final class Application {
    public static void main(String[] args) {
        RestApplication.run(context -> context.handlers(new HealthHandler()));
    }
}

public final class HealthHandler {
    @GetMapping(value = "/app/health", responseType = RawResponse.class)
    public ResponseEntity<RawResponse> health() {
        return ResponseEntity.ok(RawResponse.text(
                "{\"status\":\"UP\"}", "application/json"));
    }
}
```

```properties
server.host=0.0.0.0
server.port=8080
reactor.runtime.profile=micro-rest
```

## Hangi Response Yolu Seçilir?

| İhtiyaç | Seçim | Etkisi |
| --- | --- | --- |
| Küçük record JSON | Normal record veya generated direct writer | Java kodu sade kalır. Generated writer reflection ve ara buffer maliyetini azaltır. |
| Büyük dinamik JSON | `JsonProducerResponse` | Büyük DTO listesi kurmadan JSON doğrudan native buffer'a yazılır. |
| Hazır veya değişmeyen JSON | `RawResponse` ya da `@NativeStaticRoute` | Tekrar serialize edilmez. Static route Java'ya girmeden Rust'tan dönebilir. |
| Büyük dosya | `FileResponse` | Dosya Java heap ve JNI body üzerinden taşınmaz. Rust dosyayı backpressure ile stream eder. |
| WebSocket | `@WebSocket` | Session queue ve frame limiti Rust tarafında sınırlı tutulur. |

## Profil Seçimi

| Profil | Ne zaman seçilir? |
| --- | --- |
| `micro-rest` | Küçük pod, az thread ve düşük RSS önceliği varsa başlangıç seçeneğidir. |
| `micro-rest-plus` | Ağır JSON endpoint'leri c256/c512 yükte daha fazla kapasite istiyorsa ölçerek seçilir. |
| `balanced` | Daha yüksek eşzamanlılık gerekir ve ek memory bütçesi kabul edilebilirse kullanılır. |
| `throughput` | RPS önceliklidir. Daha büyük queue, pool ve thread bütçesi kabul edilir. |

Profil bir garanti değildir. Kendi endpoint setinizle p99, `503` oranı ve container RSS ölçün.

## 4.0.0 Geçiş Notu

Handler, service, record, validation ve REST annotation kullanımı değişmedi. `4.0.0`, artık gerekli
olmayan eski uyumluluk sınıflarını kaldırdığı için major sürümdür.

| Kaldırılan API | Yeni kullanım |
| --- | --- |
| `FastMapV2` | `RequestValueMap` veya typed/direct route parametreleri |
| Elle çalışan `StartupIndexGenerator` | `codegen` classifier içindeki `ReactorStartupProcessor` |
| `RestApplication.sleepForever()` | `RestApplication.run(...)`, `start(...)` veya `startAsync(...)` |
| Eski allocation tabanlı primitive parser yardımcıları | Typed/direct path ve query binding |

Bu sınıfları doğrudan import etmeyen projelerde normal geçiş, Maven sürümünü güncellemek ve clean
build almaktır.

## Derleme Zamanı Üretimi

`rust-java-rest:codegen` yalnız derleme sırasında kullanılır. Runtime JAR içine girmez.

```xml
<annotationProcessorPaths>
  <path>
    <groupId>com.reactor</groupId>
    <artifactId>rust-java-rest</artifactId>
    <version>4.0.0</version>
    <classifier>codegen</classifier>
  </path>
</annotationProcessorPaths>
<annotationProcessors>
  <annotationProcessor>com.reactor.rust.codegen.ReactorStartupProcessor</annotationProcessor>
  <annotationProcessor>com.reactor.rust.codegen.DirectJsonWriterProcessor</annotationProcessor>
</annotationProcessors>
```

Processor şu dosyaları üretir:

- `components.idx`: component listesi.
- `routes.idx`: HTTP method ve path listesi.
- `properties.idx`: `@RustProperty` metadata listesi.
- `ReactorApplicationDescriptor`: component factory ve startup descriptor.
- `@GenerateDirectJsonWriter` ile işaretlenen scalar record'lar için direct JSON writer.

Bu yaklaşım runtime classpath taramasını ve tekrar eden reflection maliyetini azaltır. Processor
çalışmazsa framework mevcut uyumlu fallback yolunu kullanabilir. Production'da diagnostics üzerinden
fallback sayısını takip edin ve strict gate'i yalnız temiz ölçümden sonra açın.

## Yeni Proje Oluşturma

```powershell
.\scripts\new-reactor-project.ps1 `
  -Mode rest `
  -Artifact customer-api `
  -Output C:\work\customer-api `
  -Group com.example
```

Desteklenen biçimler: `rest`, `cache-reader`, `cache-writer`, `dubbo-static` ve
`dubbo-zookeeper`. Generator dolu bir klasörün üzerine yazmaz.

## Ayrı Örnekler

`examples` dizinindeki her proje ayrı artifact olarak derlenir:

- `minimal-rest`
- `crud`
- `upload`
- `streaming`
- `websocket`
- `benchmark`

Tümünü doğrulamak için:

```powershell
mvn -f examples/pom.xml clean package
```

Detaylı İngilizce referans için [README.md](README.md) dosyasını kullanın. Türkçe kullanım akışı için
[deklaratif geliştirme rehberine](docs/declarative-development.tr.md) bakın.
