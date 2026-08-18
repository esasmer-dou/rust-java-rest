# Rust-Java REST Framework

[English](README.md) | [Türkçe](README.tr.md)

[![Sürüm](https://img.shields.io/badge/sürüm-4.5.5-blue.svg)](https://github.com/esasmer-dou/rust-java-rest/releases/tag/v4.5.5)
[![Java](https://img.shields.io/badge/Java-21-green.svg)](#beş-dakikada-başlangıç)
[![Runtime](https://img.shields.io/badge/runtime-Rust%20Hyper%20%2B%20Java-green.svg)](https://github.com/esasmer-dou/rust-spring)
[![Durum](https://img.shields.io/badge/durum-stable-blue.svg)](https://github.com/esasmer-dou/rust-java-rest/releases/tag/v4.5.5)

Rust-Java REST, Java ile REST servisi geliştirmek için hazırlanmış düşük gecikmeli bir framework'tür.
İş mantığınız Java'da kalır. Rust Hyper; HTTP bağlantısını, request okuma işlemini, response yazmayı,
dosya aktarımını, WebSocket transport'unu ve native backpressure mekanizmasını yönetir.

Temel ayrım nettir:

| Sorumluluk | Çalıştığı taraf |
| --- | --- |
| Handler, service, component ve business rule | Java |
| Record, validation, veri tabanı ve RPC çağrısı | Java |
| HTTP accept, parse, response write ve file stream | Rust |
| Bounded queue, native buffer ve connection yönetimi | Rust |
| Component graph ve route invoker üretimi | Maven build sırasında Java codegen |

Framework, Spring Boot kopyası değildir. Benzer REST annotation'ları sunar. Ancak daha küçük ve daha
öngörülebilir bir runtime yüzeyi hedefler.

Bu framework şu durumda doğru seçimdir: HTTP servisinizin handler, service ve iş kuralları Java'da
kalacak; bağlantı, sınırlı I/O ve seçilmiş ağır response yolları Rust tarafından yönetilecektir.
Uygulamanız Spring application context, runtime bean discovery veya Spring ekosistemine sıkı bağlı
kütüphaneler gerektiriyorsa bu framework doğru başlangıç değildir.

## Buradan Başlayın

| İhtiyacınız | Açmanız gereken bölüm |
| --- | --- |
| Hemen yeni servis oluşturmak | [Beş Dakikada Başlangıç](#beş-dakikada-başlangıç) |
| Çalışan GET, POST, PATCH, DELETE, upload veya WebSocket örneği görmek | [Derlenen örnekler](examples/README.tr.md) |
| Yalnız REST, Dubbo, cache, scheduler veya WebSocket bağımlılığını seçmek | [Platform ve starter rehberi](platform/README.tr.md) |
| Record, direct writer, hazır JSON veya file streaming arasında karar vermek | [Response Yolunu Seçin](#response-yolunu-seçin) |
| Pod belleği, eşzamanlılık ve endpoint limitlerini ayarlamak | [Production runtime rehberi](docs/production-runtime.md) |
| Bir property'nin anlamını bulmak | [Konfigürasyon referansı](docs/configuration.tr.md) |
| Startup, fallback, native yükleme veya `503` sorununu çözmek | [Sorun giderme](docs/troubleshooting.tr.md) |

## 4.5.5 ile Neler Değişti?

Bu sürüm, `4.5.0` ile gelen sınırlı telemetri profillerini korur. Telemetri açıkken request başına
tutulan capture durumunu tek bir 32-bit değere indirir. Exporter'ın CPU izolasyonunu iyileştirir.
Java iş mantığı ve endpoint davranışı değişmez.

- Windows ve Linux native dosyaları temiz `rust-spring v4.5.5` CI build'inden alınır.
- Embedded REST telemetrisi başlangıçta collector erişimini doğrular ve bu bağlantıyı kapatır.
  Sınırlı h2 bağlantısını yalnız export penceresinde açar. Hyper data plane yanında idle collector
  bağlantısı tutmaz.
- Ayrı Spring agent, standalone Rust runtime içinde tek sınırlı collector bağlantısını yeniden
  kullanmaya devam eder.
- Sampling penceresi daha az native işlemle çalışır. Her tam penceredeki örnek sayısı ve periyodik
  route kapsaması korunur. 5xx cevaplar eksiksiz sayılır.
- İzole exporter Linux'ta düşük öncelikli batch işi, Windows'ta en düşük normal thread önceliğiyle
  çalışır. Hyper executor'ını kullanmaz.
- `micro`, `jvm`, `sql`, `full` ve `diagnostic` profilleri çalışma sırasında değiştirilebilir.
- Düşük profile dönüldüğünde profile ait kuyruklar, SQL slot'ları, diagnostic state ve JNI
  referansları kontrol çağrısı tamamlanmadan bırakılır.
- REST ABI `29`, Dubbo ABI `7`, Redis ABI `6` ve Glowroot ABI `3` kullanılır.
- Native DLL/SO, `4.5.5` Maven artifact'i içinde gelen dosya olmalıdır.

## İçindekiler

- [Beş Dakikada Başlangıç](#beş-dakikada-başlangıç)
- [En Küçük Starter Setini Seçin](#en-küçük-starter-setini-seçin)
- [Platform ve Starter Rehberi](platform/README.tr.md)
- [REST Endpoint Örneği](#rest-endpoint-örneği)
- [Build Sırasında Neler Üretilir?](#build-sırasında-neler-üretilir)
- [Response Yolunu Seçin](#response-yolunu-seçin)
- [Runtime Profilini Seçin](#runtime-profilini-seçin)
- [Endpoint Kapasitesi ve Kontrollü 503](#endpoint-kapasitesi-ve-kontrollü-503)
- [Request, Response ve Timeout Sınırları](#request-response-ve-timeout-sınırları)
- [Gözlemlenebilirlik](#gözlemlenebilirlik)
- [Startup ve OpenJ9](#startup-ve-openj9)
- [Konfigürasyon Önceliği](#konfigürasyon-önceliği)
- [Production Kontrol Listesi](#production-kontrol-listesi)
- [Örnek Projeler](#örnek-projeler)
- [Sürüm ve Native ABI](#sürüm-ve-native-abi)

## Beş Dakikada Başlangıç

Yeni projede platform parent ve yalnız ihtiyacınız olan starter'ı kullanın. Parent; framework,
codegen, Maven plugin ve ek kütüphane sürümlerini hizalar. Annotation processor sınıfları derleme
yolunda kalır. Production runtime JAR'ına girmez.

```xml
<parent>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-platform-parent</artifactId>
  <version>4.5.5</version>
</parent>

<dependencies>
  <dependency>
    <groupId>com.reactor</groupId>
    <artifactId>rust-java-starter-rest</artifactId>
  </dependency>
</dependencies>
```

Application sınıfını oluşturun:

```java
package com.example.catalog;

import com.reactor.rust.annotations.ReactorApplication;
import com.reactor.rust.app.RestApplication;

@ReactorApplication(scanBasePackages = "com.example.catalog")
public final class CatalogApplication {
    public static void main(String[] args) {
        RestApplication.run(CatalogApplication.class, args);
    }
}
```

Bir service ve handler ekleyin:

```java
package com.example.catalog;

import com.reactor.rust.annotations.Response;
import com.reactor.rust.di.annotation.Component;

@Component
final class CatalogService {
    CatalogItem find(long id) {
        return new CatalogItem(id, "READY");
    }
}

@Response
record CatalogItem(long id, String status) {}
```

```java
package com.example.catalog;

import com.reactor.rust.annotations.GetMapping;
import com.reactor.rust.annotations.PathVariable;
import com.reactor.rust.annotations.RestController;

@RestController("/api/v1/catalog")
final class CatalogHandler {
    private final CatalogService catalog;

    CatalogHandler(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/{id}")
    CatalogItem get(@PathVariable("id") long id) {
        return catalog.find(id);
    }
}
```

Küçük bir property dosyası yeterlidir:

```properties
server.host=0.0.0.0
server.port=8080
reactor.runtime.profile=micro-rest
```

Build alın ve çalıştırın:

```powershell
mvn clean verify
mvn exec:java
curl http://localhost:8080/api/v1/catalog/1
```

`scanBasePackages` verilmezse application sınıfının paketi kullanılır. Handler ve service sınıfları
kardeş paketlerdeyse ortak paket kökünü yazın.

## En Küçük Starter Setini Seçin

Her ihtimal için tüm starter'ları eklemeyin. Sürecin yaptığı işe göre seçim yapın.

| Uygulama tipi | Eklenecek starter | Eklenmemesi gereken gereksiz yüzey |
| --- | --- | --- |
| Yalnız REST API | `rust-java-starter-rest` | Dubbo, Redis, scheduler ve WebSocket |
| REST + native Dubbo consumer | `rust-java-starter-dubbo` | Static discovery kullanırken resmi Dubbo, Netty ve ZooKeeper |
| REST + Redis reader | `rust-java-starter-cache-reader` | Redis writer lifecycle'ı |
| PostgreSQL'den Redis'e yazan scheduler | `rust-java-starter-cache-writer` | REST runtime |
| WebSocket servisi | `rust-java-starter-websocket` | WebSocket kullanmayan servislerde bu starter |
| REST + OpenAPI | `rust-java-starter-rest`, `rust-java-starter-openapi` | Runtime controller taraması |
| REST + JWT | `rust-java-starter-rest`, `rust-java-starter-security` | Her request'te dinamik policy arama |
| REST + dış HTTP çağrısı | `rust-java-starter-rest`, `rust-java-starter-http-client` | Dynamic proxy ve sınırsız executor |

Kapalı bir özellik request yolunda çalışmayabilir. Ancak gereksiz dependency yine classpath'i
büyütür. Bu nedenle kullanılmayan starter'ı eklememek en doğru memory optimizasyonudur.

Tüm starter'ların açıklaması için [Platform Rehberi](platform/README.tr.md) dosyasına bakın.

## REST Endpoint Örneği

Aşağıdaki örnek temel verb'leri aynı handler içinde gösterir. JSON sözleşmelerinde immutable record
kullanın. Validation işini request record üzerinde açıkça tanımlayın.

```java
@Request
public record ProductCommand(
        @NotBlank String name,
        @Positive long priceCents) {}

public record ProductResponse(long id, String name, long priceCents) {}
```

```java
@RestController("/api/v1/products")
final class ProductHandler {
    private final ProductService products;

    ProductHandler(ProductService products) {
        this.products = products;
    }

    @GetMapping("/{id}")
    ProductResponse get(@PathVariable("id") long id) {
        return products.get(id);
    }

    @GetMapping("")
    List<ProductResponse> list(
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return products.list(limit);
    }

    @PostMapping("")
    ResponseEntity<ProductResponse> create(@RequestBody @Valid ProductCommand command) {
        return ResponseEntity.created(products.create(command));
    }

    @PutMapping("/{id}")
    ProductResponse replace(
            @PathVariable("id") long id,
            @RequestBody @Valid ProductCommand command) {
        return products.replace(id, command);
    }

    @PatchMapping("/{id}")
    ProductResponse patch(
            @PathVariable("id") long id,
            @RequestBody @Valid ProductCommand command) {
        return products.patch(id, command);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable("id") long id) {
        products.delete(id);
        return ResponseEntity.noContent();
    }
}
```

Business logic handler içinde büyümemelidir. Handler HTTP sözleşmesini yönetir. Service iş kararını
uygular. Repository veri tabanı erişimini yönetir.

### Response ve Hata Sözleşmesi

Normal `200` sonucu için DTO'yu doğrudan döndürmek en küçük yoldur. Status veya header değişecekse
`HttpResponse<T>` kullanın. Tutarlı hata body için RFC 9457 uyumlu `ProblemDetail` kullanın.

```java
@Component
final class ApiErrors {
    @ExceptionHandler(NotFoundException.class)
    HttpResponse<ProblemDetail> notFound(NotFoundException error) {
        return HttpResponse.notFound(
                ProblemDetail.of(HttpStatus.NOT_FOUND, error.getMessage())
                        .withCode("catalog_not_found"));
    }
}
```

Exception handler'lar indexlenir ve generated kod üzerinden çağrılır. Her route içinde aynı
`try/catch` kodunu tekrarlamayın. Dependency hata metnini doğrudan client'a açmayın.

## Build Sırasında Neler Üretilir?

Framework, runtime reflection yerine build-time üretimi tercih eder.

| Sizin tanımınız | Üretilen çıktı | Runtime etkisi |
| --- | --- | --- |
| `@ReactorApplication` | Application descriptor ve startup index | Kontrollü ve doğrudan startup |
| Constructor parametreleri | Bean factory ve dependency graph | Reflective DI araması yok |
| REST annotation'ları | Route'a özel invoker | Reflective method çağrısı yok |
| `@ConfigurationProperties` | Tip güvenli property binder | Hatalı değer startup'ta reddedilir |
| `@Response` | Response DTO metadata'sı | Uyumlu DSL-JSON serialization; kendiliğinden writer üretmez |
| `@GenerateDirectJsonWriter` | Zorunlu tipe özel JSON writer | Trafik başlamadan bir kez bağlanır; desteklenmeyen yapı build hatası verir |
| `@GenerateJdbcMapper` | Doğrudan `ResultSet` mapper | Runtime record incelemesi yok |
| `@ReactorHttpClient` | Typed HTTP client | Dynamic proxy yok |
| `@Scheduled` | Bounded task kaydı | Scheduler lifecycle framework tarafından kapatılır |
| `@RequiresProperty` ve `@Profile` | Koşullu bean ve route planı | Koşul her request'te değil, startup'ta değerlendirilir |

Platform parent, `mvn clean verify` sırasında AOT ve native ABI gate'lerini çalıştırır. Generated
dosyalar `target/generated-sources/annotations` ve `target/classes/META-INF/reactor` altında bulunur.
Bu dosyaları elle yazmayın.

Compatibility scanning yalnız eski projelerin geçişi içindir. Yeni production uygulamasında strict
AOT yolunu kullanın. Fallback tüm çağrıların sessizce yavaş yola düşmesine izin vermemelidir.

Framework metrics ve diagnostics endpoint'leri isteğe bağlıdır. Bu endpoint'lere ihtiyacınız varsa
application annotation'ında açın:

```java
@ReactorApplication(
        scanBasePackages = "com.example.catalog",
        metrics = true)
public final class CatalogApplication { /* main metodu değişmez */ }
```

Varsayılan değer `metrics = false` olur. Bu durumda metrics handler ve diagnostics route'ları
kaydedilmez. Küçük REST servisi kullanılmayan bir gözlem yüzeyini memory içinde taşımaz.

`GET /diagnostics/routes` çıktısında `generated_route_metadata=true`, route bilgisinin build sırasında
üretildiğini gösterir. `generated_response_writer=true`, route'un doğrudan writer'ı bağladığını
gösterir. Önceden kayıtlı writer route planı hazırlanırken bağlanır. Sonradan kaydedilen writer için
ilk null olmayan object response sırasında yalnız bir kez daha arama yapılır. Producer, raw, native
ve file route'ları writer araması yapmaz.

`generated_response_writer_state` alanını şöyle okuyun:

- `unresolved`: Route planı hazırlanırken writer bulunamadı. İlk normal object response sırasında
  son bir arama yapılır.
- `bound`: Tipe özel generated writer bağlandı ve kullanılıyor.
- `miss`: Bildirilen response tipi için writer bulunamadı. Bu durumda üst seviyedeki
  `direct_json_writer_enabled` ve `direct_json_writer_providers` alanlarını kontrol edin.
- `disabled`: Direct writer desteği konfigürasyon ile kapatıldı.
- `not_applicable`: Primitive, producer, raw, native veya file route bu writer'a ihtiyaç duymaz.

Production'da startup reflection istemiyorsanız diagnostics temizlendikten sonra şu gate'i açın:

```properties
reactor.optimizer.fail-on-reflection-route-metadata=true
```

## Response Yolunu Seçin

Response tipi yalnız kod stili değildir. Allocation, JNI copy ve RSS davranışını değiştirir.

| Veri biçimi | Seçim | Ne zaman kullanılır? | Maliyet |
| --- | --- | --- | --- |
| Küçük dinamik JSON | Record veya `ResponseEntity<Record>` | Normal business API | En sade yol; küçük object graph kabul edilir |
| Scalar ve sık kullanılan record | `@GenerateDirectJsonWriter` | Aynı tip yoğun serialize ediliyorsa | Build-time writer; daha az reflection ve ara buffer |
| Büyük dinamik JSON | `JsonProducerResponse` | Büyük liste üretilecekse | DTO listesi kurmadan native buffer'a yazar |
| JSON zaten hazır | `RawResponse` | Redis, DB veya provider hazır JSON veriyorsa | Tekrar serialize etmez |
| Değişmeyen response | `@NativeStaticRoute` | Health, metadata veya sabit sözlük | Java handler'a girmeden Rust dönebilir |
| Büyük dosya | `FileResponse` | CSV, rapor veya indirme | Dosya Java heap'e alınmaz |
| Native provider JSON'u | Native response handle | Dubbo/cache body yalnız iletilecekse | Java `byte[]` materialization kaldırılabilir |

`RawResponse` genel amaçlı bir cache değildir. Yalnız elinizde hazır body varsa kullanılır.
`NativeStaticRoute` ise restart'a kadar değişmeyen bilinçli bir response içindir.

## Runtime Profilini Seçin

Önce en küçük uygun profille başlayın. Yalnız ölçüm sonucu limit değiştirin.

| Profil | Uygun kullanım | Beklenen davranış |
| --- | --- | --- |
| `micro-rest` | Küçük pod, düşük veya orta trafik | Az worker ve küçük pool; overload durumunda kontrollü `503` olabilir |
| `micro-rest-plus` | Ağır producer/direct JSON ve daha yüksek eşzamanlılık | Daha geniş route bütçesi; RSS artışı ölçülmelidir |
| `micro-dubbo` | Memory öncelikli Dubbo consumer | Az bağlantı ve worker; burst yükte fail-fast davranır |
| `balanced` | Sürekli orta-yüksek trafik | Latency, başarı oranı ve memory arasında denge |
| `throughput` | RPS öncelikli servis | Daha fazla thread, queue ve retained memory |

`p99`, isteklerin yüzde 99'unun tamamlandığı gecikme sınırıdır. `503`, servis kapasitesi dolduğunda
isteğin kontrollü biçimde reddedildiğini gösterir. Sınırsız queue kullanmak `503` sayısını gizleyebilir;
ancak p99 değerini ve memory kullanımını kötüleştirir.

Profil seçimi performans garantisi değildir. Kendi endpoint karışımınızla en az c64 ve c256 yükte
RPS, p99, `503` oranı ve container RSS ölçün. Ağır route için global worker artırmak yerine
`@RouteAdmission` veya workload bazlı route budget kullanın.

Anon belleği daha düşük bir container image gerekiyorsa Maven `runtime-image` goal ile `8m`
ROM-only OpenJ9 shared cache üretebilirsiniz. Bu cache AOT metodu tutmaz. Java business logic ve JIT
akışı değişmez. Bu bir runtime profili değil, image seçimidir. Güncel minimal ölçümde final cgroup
anon `31,027 MiB` değerinden `28,207 MiB` değerine düştü. Uzun c64 small-route gate'inde useful RPS
`%11,43` arttı, p99 `%35,18` düştü ve hata oluşmadı. Yine de kendi endpoint karışımınızla c64/c256
testi yapmadan açmayın. Copy-paste Maven ayarı
[Production Runtime rehberinde](docs/production-runtime.md#rom-only-openj9-shared-cache) bulunur.
Üretilen çok aşamalı imajda `binutils` yalnız build katmanında kalır. Uygulama `10001` kullanıcısıyla
çalışır. Runtime yazma alanı `/app/.reactor/native` ve `/app/work` dizinleriyle sınırlandırılır.

## Endpoint Kapasitesi ve Kontrollü 503

Pahalı bir endpoint'in bütün pod'u yavaşlatmasına izin vermeyin. `@RouteAdmission`, aynı anda çalışan
çağrı sayısını ve kısa bekleme süresini endpoint üzerinde sınırlar:

```java
@GetMapping(value = "/reports/daily", responseType = JsonBodyProducer.class)
@RouteWorkload(value = RouteWorkload.Type.HEAVY_JSON, budget = "daily-report")
@RouteAdmission(maxConcurrent = 80, queueTimeoutMs = 150)
JsonBodyProducer dailyReport() {
    return reportService::writeDailyJson;
}
```

Bu limit dolduğunda sınırsız queue büyütmek yerine kontrollü `503` dönebilir. `503`, framework'ün
bozulduğu anlamına gelmez. Pod'un bellek ve gecikme sınırını koruduğunu gösterir. Değeri artırmadan
önce provider, veritabanı pool'u, response boyutu, useful `200` RPS ve p99 birlikte ölçülmelidir.

Property ile endpoint'e özel override yapılabilir:

```properties
reactor.rust.route-admission.get.reports.daily.max-concurrent=80
reactor.rust.route-admission.get.reports.daily.queue-timeout-ms=150
```

Global worker veya queue değerini büyütmek ilk çözüm değildir. Tek ağır endpoint için endpoint'e özel
bütçe kullanın.

## Request, Response ve Timeout Sınırları

```properties
reactor.rust.http.max-request-body-bytes=1048576
reactor.rust.http.max-response-body-bytes=8388608
reactor.rust.http.max-inflight-body-bytes=33554432
reactor.rust.http.max-inflight-response-bytes=67108864
reactor.rust.http.header-read-timeout-ms=5000
reactor.rust.http.request-body-timeout-ms=10000
reactor.rust.http.idle-timeout-ms=30000
```

- Tek request limitini yükseltirken toplam in-flight byte limitini de kontrol edin.
- Büyük dosya için `byte[]` yerine `FileResponse` kullanın.
- Büyük dinamik JSON için `List<DTO>` kurmak yerine producer/direct writer yolunu değerlendirin.
- Timeout'u yalnız hata kaybolsun diye büyütmeyin. Önce beklemenin DB, RPC, queue veya network
  kaynaklı olduğunu belirleyin.

## Gözlemlenebilirlik

`4.5.5` sürümü, uyumlu `java-rust-glowroot-agent:0.4.0` paketiyle kullanılabilir. Mikro ajan şu
verileri Glowroot Central'a gönderir:

- HTTP route çağrı sayısı, süre dağılımı ve `5xx` sayısı;
- native Dubbo ve native Redis çağrı süreleri;
- process RSS ve thread sayısı;
- exporter bağlantı, hata, reconnect ve drop sayaçları;
- sınırlandırılmış yavaş ve hatalı HTTP trace örnekleri.

Java agent bytecode weaving yapmaz. Runtime dependency eklemez. Protobuf encode ve plaintext
HTTP/2 gönderimi mevcut Rust runtime içinde çalışır. Handler, service, validation ve business logic
Java'da aynı şekilde kalır.

Stable `4.5.5` runtime; sınırlı `micro`, `jvm`, `sql`, `full` ve `diagnostic` profillerini destekler.
REST ABI `29` ve Glowroot ABI `3` gerekir. Bu Java sınıflarını ABI `28` DLL/SO ile kullanmayın.

Mevcut Glowroot Central/collector deployment'ı değişmez. İkinci bir collector veya framework'e özel
collector plugin'i kurmayın. Strict düşük-bellek production yolunda agent JAR gerekmez. Native
binary'sinde `glowroot` capability bulunan uyumlu Rust-Java REST artifact'ini kullanın. Özelliği
`-Dreactor.glowroot.*`, ortam değişkenleri veya `rust-spring.properties` ile açın. İsteğe bağlı
`-javaagent:/app/agent/java-rust-glowroot-agent.jar` yalnız konfigürasyon kolaylığı sağlar ve ayrı
ölçülür. Java controller, handler, service, validation ve iş mantığı değişmez.

Rastgele Java metodunu izlemek, otomatik JDBC weaving, geniş JMX discovery, sürekli profiler, log
capture veya canlı weaving istiyorsanız tam Glowroot Java agent'ı seçin. Sınırlı runtime artık açıkça
tanımlanan SQL sürelerini, sabit JVM/GC ölçümlerini, sınırlı hata stack bilgisini ve yetkili tanılama
komutlarını sağlayabilir. Yine de tam upstream agent değildir.

Agent'ın ağır işleri Rust tarafında kalır. İsteğe bağlı MXBean discovery ve polling, JNI global
referans sahipliği, aggregate işlemleri, kuyruklar, protobuf/h2 gönderimi, tanılama yönetimi, dosya
işlemleri ve profil kaynak iadesi Rust tarafından yürütülür. Java tarafında telemetri polling worker'ı
ve JVM bean cache'i yoktur. Java business kodu yalnız açık SQL süre/hata olayını ve JVM'de oluşan
`Throwable` referansını iletir.

Profili yalnız kimlik doğrulaması olan iç kontrol akışından değiştirin:

```java
GlowrootTelemetry.switchTo(TelemetryProfile.FULL, Duration.ofSeconds(5));
// Olay inceleme aralığını sınırlı tutun.
GlowrootTelemetry.restoreConfiguredProfile();
```

`restoreConfiguredProfile()`, `reactor.glowroot.profile` ile seçilen başlangıç değerine döner. Bu
değer `micro` ise profile ait SQL slotları, hata ve tanılama kuyrukları, devam eden export verisi ve
Rust'ın sahip olduğu JNI MXBean global referansları bırakılana kadar bekler. Eski SQL tanımları nesil
kontrollüdür. Profil yeniden açıldığında yeni slot kaydı yapar. Profili her request için değiştirmeyin.

Kaynak bırakma worker'ı Hyper'dan ayrıdır. Linux tarafındaki son `malloc_trim(0)` çağrısı yine de
process genelinde çalışan bir glibc işlemidir. Profil değişimini seyrek bir operasyon adımı olarak
kullanın. RSS etkisini telemetri kapalı/açık taze process A/B testiyle ölçün.

`@ReactorApplication(metrics = true)` açıksa lokal durumu şu endpoint'lerden okuyabilirsiniz:

```bash
curl -s http://localhost:8080/diagnostics/glowroot
curl -s http://localhost:8080/metrics | grep reactor_glowroot
```

JVM ölçümü veya tanılama içermeyen profile döndükten sonra `jvm_probe_registered=false` ve
`jvm_probe_owned_global_refs=0` değerlerini doğrulayın. Bu iki alan, Rust'ın sahip olduğu JNI bean
referanslarının bırakıldığını kanıtlar. Yalnız RSS değerinin düşmesi yeterli kanıt değildir.

Collector, HTTP request kritik yolunda beklenmez. Bağlantı kesilirse sınırlı reconnect backoff
çalışır. Süresi geçen rollup bellekte biriktirilmez; drop edilir ve sayaçta görünür. Uygulama servis
vermeye devam eder. Kaynak kodla uygulanan agent-owned üst sınır `1 MiB` değeridir. Önceki
`4.4.1` kanıtı shared-runtime yolunu ölçtü. Resident maksimum farkları `+1,742 MiB`, `+1,817 MiB`
ve `+1,754 MiB` oldu; telemetri thread'i eklenmedi. `4.5.5`, export ve profil kaynak bırakma işini
tek `256 KiB` Rust thread üzerinde izole eder. Hyper worker kullanmaz. Embedded REST başlangıç
collector bağlantısını kapatır ve yalnız sınırlı export penceresinde yeniden bağlanır. Koordineli
release gate'i, companion agent yayınlanmadan önce `+3 MiB`, RPS, p99 ve `503` sözleşmesini ölçer.

Uygulamada metrics özelliğini yalnız ihtiyaç varsa açın:

```java
@ReactorApplication(
        scanBasePackages = "com.example.catalog",
        metrics = true)
public final class CatalogApplication { }
```

| Endpoint veya metrik | Ne gösterir? |
| --- | --- |
| `GET /metrics` | Prometheus metrikleri |
| `GET /diagnostics/startup` | Startup aşamaları ve süreleri |
| `GET /diagnostics/routes` | Generated, direct, native veya fallback route durumu |
| JNI queue p95/p99 | Java handler kuyruğundaki bekleme |
| Route admission rejection | Endpoint kapasite limitine takılan istek |
| In-flight response bytes | Aynı anda taşınan response belleği |

Production gate sırasında `production_legacy`, `heavy_json_object_graph` ve fallback sayaçlarını
kontrol edin. Benchmark-only route değerlerini gerçek uygulama route'larıyla karıştırmayın.

## Startup ve OpenJ9

Build; component graph, route invoker, property metadata ve startup index dosyalarını üretir.
Production başlangıcında classpath taraması yerine bu dosyalar kullanılır. Generated dosyaları elle
değiştirmeyin.

OpenJ9/Semeru Java 21 düşük bellek için önerilen runtime'dır. `micro-rest` ile başlayın. JIT code
cache, compilation thread, native trim veya daha küçük stack ayarlarını ancak aynı endpoint setinde
RPS, p99, `503` ve container RSS karşılaştırması geçerse kullanın. Ayrıntılar için
[startup tuning](docs/startup-tuning.md) ve [production runtime](docs/production-runtime.md)
rehberlerine bakın.

## Konfigürasyon Önceliği

Değerler aşağıdaki sırayla uygulanır. Üstteki kaynak alttakini ezer:

1. JVM `-D...` değerleri ve desteklenen environment variable'lar.
2. `reactor.config.file` veya `REACTOR_CONFIG_FILE` ile verilen dış property dosyaları.
3. Classpath içindeki `rust-spring.properties`.
4. `RuntimeProfilePlan` ile yalnız eksik değerler için verilen uygulama varsayılanları.

Production secret'larını JAR içindeki property dosyasına yazmayın. Kubernetes Secret veya platformun
secret yönetimini kullanın. Framework property dosyasının tamamını her projeye kopyalamayın. Yalnız
uygulamanızın değiştirdiği değerleri tutun.

Property ve profil ayrıntıları:

- [Konfigürasyon rehberi](docs/configuration.tr.md)
- [Production runtime ve memory rehberi](docs/production-runtime.md)
- [OpenJ9 startup rehberi](docs/startup-tuning.md)

## Production Kontrol Listesi

- `mvn clean verify` başarılı olmalıdır.
- AOT component ve route index'leri JAR içinde bulunmalıdır.
- Runtime loglarında scan veya invoker fallback görülmemelidir.
- DLL/SO aynı Maven paketinden gelmelidir. ABI uyuşmazlığı startup'ı durdurmalıdır.
- Liveness yalnız process durumunu kontrol etmelidir.
- Readiness gerekli Redis, Dubbo veya DB bağımlılıklarını bounded timeout ile kontrol etmelidir.
- c64 ve c256 yükte RPS, p99, `503`, JNI queue wait ve container RSS birlikte ölçülmelidir.
- DB, Redis, Dubbo ve outbound HTTP için timeout ile bounded inflight limiti bulunmalıdır.
- Command retry yalnız idempotency anahtarı veya idempotent işlem varsa açılmalıdır.
- Büyük response için DTO listesi yerine producer, raw, file veya native handle yolu değerlendirilmelidir.
- Pod CPU ve memory limitleri gerçek staging yüküyle doğrulanmalıdır.

`503` her zaman framework hatası değildir. Çoğu zaman bounded overload davranışıdır. Limiti artırmadan
önce CPU, provider, DB pool, Redis ve RSS kapasitesini birlikte kontrol edin.

## Örnek Projeler

| İhtiyaç | Başlangıç projesi |
| --- | --- |
| En küçük REST endpoint | [`examples/minimal-rest`](examples/minimal-rest) |
| GET, POST, PUT, PATCH ve DELETE | [`examples/crud`](examples/crud) |
| Upload | [`examples/upload`](examples/upload) |
| Büyük JSON ve dosya response | [`examples/streaming`](examples/streaming) |
| WebSocket | [`examples/websocket`](examples/websocket) |
| Redis'ten hazır JSON okuyan REST API | `rest-sample-cache-reader` |
| PostgreSQL'den Redis snapshot üreten scheduler | `rest-sample-cache-writer` |
| Dubbo provider çağıran REST API | `rest-sample-dubbo-consumer` |
| Plain Java Dubbo provider | `rest-sample-dubbo-provider` |

`sample` modülü tam uyumluluk demosudur. Production template değildir. Yeni proje için
starter tabanlı yapı veya `scripts/new-reactor-project.ps1` kullanın.

```powershell
.\scripts\new-reactor-project.ps1 `
  -Mode rest `
  -Artifact customer-api `
  -Output C:\work\customer-api `
  -Group com.example
```

Desteklenen biçimler: `rest`, `cache-reader`, `cache-writer`, `dubbo-static` ve `dubbo-zookeeper`.
Generator dolu bir klasörün üzerine yazmaz.

## Sürüm ve Native ABI

Uyumlu dependency çizgisi `rust-java-rest:4.5.5`, `java-rust-dubbo:0.7.2` ve
`java-rust-cache:0.7.4` şeklindedir. Native artifact'ler REST ABI `29`, Dubbo ABI `7`, Redis ABI `6`
ve Glowroot ABI `3` taşır.

Native DLL/SO dosyasını başka bir sürümden kopyalamayın. Startup; ABI, platform, source revision ve
SHA-256 provenance bilgisini doğrular. Uyumsuz binary trafik başlamadan reddedilir.

Java tarafındaki handler, service, record ve annotation modeli korunur. Native ABI değişikliği Java
business logic kullanımını değiştirmez. Yalnız runtime ve native binary aynı paket çizgisinde olmalıdır.

## Ayrıntılı Rehberler

- [Deklaratif geliştirme](docs/declarative-development.tr.md)
- [Platform ve starter seçimi](platform/README.tr.md)
- [Konfigürasyon](docs/configuration.tr.md)
- [Operasyon](docs/operations.tr.md)
- [Sorun giderme](docs/troubleshooting.tr.md)
- [Compile edilmiş örnekler](examples/README.tr.md)
- [Production runtime ve performans kararları](docs/production-runtime.md)
- [4.5.5 sürüm notları](docs/release-notes/v4.5.5.tr.md)
- [4.4.1 sürüm notları](docs/release-notes/v4.4.1.tr.md)

## Kısa Sözlük

| Terim | Basit anlamı |
| --- | --- |
| RSS | Uygulamanın işletim sistemi tarafından görülen fiziksel bellek kullanımı |
| p99 | İsteklerin yüzde 99'unun tamamlandığı gecikme sınırı |
| Useful `200` RPS | Saniyede tamamlanan başarılı HTTP `200` response sayısı |
| `503` | Kapasite sınırı nedeniyle geçici olarak işlenemeyen istek |
| In-flight | Henüz tamamlanmamış ve kaynak tutan istek veya response |
| Route admission | Bir endpoint'in aynı anda kullanabileceği kapasite sınırı |
| Native response | Body'nin Java heap'e taşınmadan Rust belleğinden gönderilmesi |
| Generated path | Handler çağrısı veya serializer bilgisinin build sırasında üretilmesi |

Business logic Java'da kalır. Rust yalnız düşük seviyeli I/O ve seçilmiş serialization/transport
işlerini üstlenir. Bu sınırı korumak framework'ün temel tasarım kararıdır.
