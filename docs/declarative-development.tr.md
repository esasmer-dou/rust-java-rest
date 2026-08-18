# Deklaratif Geliştirme

[English](declarative-development.md) | [Türkçe](declarative-development.tr.md)

## Starter ile Kurulum

Platform parent kullanın. Uygulamanın gerçekten ihtiyaç duyduğu starter'ları ekleyin. Parent; framework,
Dubbo, cache, DSL-JSON, annotation processor ve Maven plugin sürümlerini birlikte yönetir. Annotation
processor sınıfları yalnız derleyici yolunda kalır. Production runtime bağımlılığı olmaz.

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

| İhtiyaç | Starter | Runtime etkisi |
| --- | --- | --- |
| REST API | `rust-java-starter-rest` | Rust HTTP runtime ve Java REST API |
| Sabit adresli native Dubbo consumer | `rust-java-starter-dubbo` | Native-static Dubbo client; resmi Dubbo, Netty ve ZooKeeper stack'i yoktur |
| Redis reader | `rust-java-starter-cache-reader` | REST ve native Redis cache |
| Redis writer process | `rust-java-starter-cache-writer` | Yalnız native Redis cache; REST bağımlılığı eklenmez |
| OpenAPI | `rust-java-starter-openapi` | Derleme zamanı sözleşmesi ve isteğe bağlı `/openapi.json` ile `/docs` route'ları |
| JWT security | `rust-java-starter-security` | Başlangıçta bir kez oluşturulan route guard'ları |
| W3C tracing | `rust-java-starter-tracing` | İsteğe bağlı örnekleme ve outbound trace aktarımı |
| Scheduler | `rust-java-starter-scheduler` | Uygulamanın yönettiği tek bounded scheduler |
| HTTP client | `rust-java-starter-http-client` | Generated client'lar ve tek bounded JDK HTTP runtime |
| Test | `rust-java-starter-test` | Yalnız test kapsamı; production runtime'a paketlenmez |

Her gerçek özellik için ilgili starter'ı ekleyin. İleride gerekebilir düşüncesiyle bütün starter'ları
eklemeyin. Kapalı özellik request yoluna işlem eklemez. Yine de kullanılmayan sınıfları ve metadata'yı
classpath'e taşımamak daha düşük RSS ve daha net bir production artefact'i sağlar.

## Neler Üretilir?

Yalnız derlemede kullanılan codegen artefact'i route ve component index, application descriptor,
property metadata, direct JSON writer ve JDBC record mapper üretir. Generated sınıflar doğrudan
metot çağrısı kullanır. Request sırasında classpath taraması veya yeni reflection çalıştırmaz.

## Uygulama Bağlantıları

```java
@ReactorApplication(
        name = "Customer API",
        version = "1.0.0",
        description = "Müşteri sorgu ve komut endpoint'leri",
        scanBasePackages = "com.example.customer",
        metrics = true)
public final class CustomerApplication {
    public static void main(String[] args) {
        RestApplication.run(CustomerApplication.class, args);
    }
}

@RestController("/api/v1/customers")
final class CustomerHandler {
    private final CustomerService customers;

    CustomerHandler(CustomerService customers) {
        this.customers = customers;
    }

    @GetMapping("/{id}")
    CustomerResponse customer(@PathVariable("id") long id) {
        return customers.find(id);
    }
}
```

`scanBasePackages` yazılmazsa application sınıfının paketi tarama kökü olur. Açık bir değer
verildiğinde bu varsayılanın yerini alır. Kardeş `app`, `handler` ve `service` paketlerinin ortak
kökünü yazın.
`metrics` varsayılan olarak `false` olur. Built-in metrics ve diagnostics endpoint'leri bu uygulamada
gerekiyorsa `true` yapın.

Constructor factory ve route invoker sınıfları derleme sırasında üretilir. Üçüncü taraf nesneler
için `@Configuration` ve `@Bean` kullanın. Generated factory, `@Bean` metodunun parametrelerini
çözer. Normal CRUD bağlantıları için module yazmayın. `RestApplication.Module` yalnız özel artefact
yüzeyleri veya lifecycle'ı uygulamanın kendisinin yönetmesi gereken ileri seviye entegrasyonlar içindir.

Aynı interface'i iki bean sağlıyorsa yalnız bir implementasyona `@Primary` ekleyin veya injection
parametresinde `@Qualifier("beanName")` kullanın. Generated container ilk bean'i sessizce seçmez.
Belirsizlik varsa uygulamayı açık bir hata ile durdurur. Generated constructor veya `@Bean` metodu
checked exception üretirse bean adı ve asıl hata `BeanCreationException` içinde korunur. Bu işlemler
yalnız başlangıçta çalışır. Request yoluna ek maliyet getirmez.

## Koşullu Component ve İsteğe Bağlı Özellik

`main` içinde `if` yazmak veya farklı module nesneleri kurmak yerine başlangıç koşulu kullanın.
Koşul generated factory içine yazılır. Kapalı component oluşturulmaz.

```java
@Component
@RequiresProperty(name = "customer.commands.enabled", value = "true")
final class CustomerCommandService {}

@Configuration
final class ClientConfiguration {
    @Bean
    @RequiresProperty(name = "customer.audit.enabled", value = "true")
    AuditClient auditClient() {
        return new AuditClient();
    }
}

@RestController("/api/v1/customers")
final class CustomerHandler {
    CustomerHandler(Optional<AuditClient> audit) {}
}
```

Component veya `@Bean` metodu üzerinde `@Profile({"micro-rest", "micro-dubbo"})` da kullanılabilir.
Koşullar başlangıçta bir kez değerlendirilir. Her request'e yeni branch eklenmez. Kapalı bir
controller'ın route'u strict route-index kontrolünde hata sayılmaz. AOT ve açıkça seçilmiş
compatibility mode aynı koşul kurallarını kullanır.

## Tip Güvenli Konfigürasyon

```java
@ConfigurationProperties("clients.customer")
record CustomerClientProperties(
        @ConfigDefault("2") int threads,
        @ConfigDefault("750ms") Duration timeout,
        Optional<String> proxy) {}
```

Generated factory desteklenen scalar değerleri doğrudan bağlar. Eksik zorunlu değer veya hatalı sayı
ve duration uygulamayı başlangıçta durdurur. Kubernetes değerlerini environment variable veya harici
property dosyasında tutun. Operasyon varsayılanlarını Java kodunda tekrar etmeyin.

## Generated Outbound HTTP Client

```java
@ReactorHttpClient(baseUrlProperty = "clients.customer.base-url")
interface CustomerClient {
    @HttpExchange(path = "/customers/{id}")
    CompletionStage<Customer> get(
            @PathVariable("id") long id,
            @HeaderParam("x-tenant") String tenant);

    @HttpExchange(method = HttpMethod.POST, path = "/customers", retries = 0)
    CompletionStage<Customer> create(@RequestBody CreateCustomer request);
}
```

```properties
clients.customer.base-url=http://customer-service:8080
reactor.http-client.threads=2
reactor.http-client.queue-capacity=256
reactor.http-client.max-inflight=128
reactor.http-client.connect-timeout-ms=1000
reactor.http-client.request-timeout-ms=2000
reactor.http-client.retries=1
reactor.http-client.max-response-bytes=8388608
```

Implementasyon derleme sırasında üretilir. Dynamic proxy kullanılmaz. Retry yalnız idempotent
işlemlerde veya açıkça `idempotent=true` yazılan metotlarda çalışır. Command metotlarında business
işlemi idempotency key kullanmıyorsa `retries=0` seçin. Queue dolduğunda request'in reddedilmesi
bilinçli backpressure davranışıdır. Bounded executor yerine sınırsız queue kullanmayın.

## Response ve Generated Exception Handling

Normal `200` sonucu için response record'unu doğrudan döndürün. Farklı status veya header gerekiyorsa
`HttpResponse<T>` kullanın. Tek ve kararlı hata şeması için `ProblemDetail` kullanın.

```java
@Component
final class ApiErrors {
    @ExceptionHandler(NotFoundException.class)
    HttpResponse<ProblemDetail> notFound(NotFoundException error) {
        return HttpResponse.notFound(
                ProblemDetail.of(HttpStatus.NOT_FOUND, error.getMessage())
                        .withCode("customer_not_found"));
    }
}
```

Processor handler imzasını doğrular ve doğrudan exception dispatch kodu üretir. Handler, tanımlanan
exception tipini alabilir ve DTO, `ResponseEntity<T>` veya `HttpResponse<T>` dönebilir. Dışarı açılan
hata kodlarını burada merkezileştirin. Database, Redis veya RPC exception ayrıntısını client'a açmayın.

## Security, Tracing ve OpenAPI

```java
@RestController("/api/v1/orders")
@Authenticated(roles = "order-read")
final class OrderHandler {
    @PermitAll
    @GetMapping("/public-status")
    Status publicStatus() { return new Status("UP"); }

    @Traced("orders.get")
    @GetMapping("/{id}")
    Order get(@PathVariable("id") long id) { return load(id); }
}
```

```properties
reactor.security.enabled=true
reactor.security.jwt.hmac-secret=${JWT_SECRET_AT_LEAST_32_BYTES}
reactor.security.jwt.issuer=orders
reactor.security.jwt.audience=internal-api
reactor.security.jwt.require-expiration=true
reactor.tracing.enabled=true
reactor.tracing.annotated-only=true
reactor.tracing.sample-ratio=0.01
reactor.openapi.enabled=true
reactor.openapi.ui.enabled=false
```

Built-in verifier sıkı bir HS256 seçeneğidir. Production sistemi farklı token veya key kaynağı
kullanıyorsa bir `JwtVerifierProvider` ekleyin. Secret değerini paketlenen property dosyasına
yazmayın. Tracing örneklenebilir ve yalnız annotation bulunan route'larla sınırlandırılabilir.
`TraceExporter` bloklamamalıdır. OpenAPI sözleşmesi derleme sırasında üretilir. Route'u açmak runtime
controller taraması başlatmaz. Sözleşmeyi `@Operation` ve tekrar kullanılabilen `@ApiResponse` ile
zenginleştirin.

## Generated Scheduler

```java
@Component
final class CatalogRefresh {
    @Scheduled(
            name = "catalog-refresh",
            intervalProperty = "catalog.refresh-ms",
            initialDelayProperty = "catalog.initial-delay-ms",
            lockName = "catalog-refresh",
            lockAtMostProperty = "catalog.lock-at-most-ms")
    void refresh() {
        // Database'i batch halinde okuyun ve bounded cache projection yayınlayın.
    }
}
```

```properties
reactor.scheduler.enabled=true
reactor.scheduler.threads=1
reactor.scheduler.max-tasks=16
catalog.refresh-ms=60000
catalog.initial-delay-ms=5000
catalog.lock-at-most-ms=55000
```

`lockName` kullanan task için tam olarak bir `ScheduledLockProvider` gerekir. Bu lock, iki replica'nın
aynı projection'ı birlikte yazmasını önler. Bağımsız task'ların gerçekten çakıştığı ve DB ile Redis
kapasitesinin bunu taşıdığı ölçülmeden thread sayısını birden büyük yapmayın.

## Extension ve Artefact Sınırları

Security ve tracing `RequestGuardFactory` kullanır. Guard'lar route'lar oluşturulurken seçilir. Sync
ve async tamamlanma davranışları aynıdır. Eski genel `Middleware` API'si deprecated durumdadır. AOT
codegen kullanıcı tarafından yazılmış `Middleware` implementasyonunu reddeder. Bu eski API native
request yoluna bağlı değildi ve request başına map ile chain allocation riskini artırıyordu.

Normal REST, Dubbo, cache, scheduler, security, tracing ve HTTP-client uygulamalarında starter ve
generated wiring kullanın. `RestApplication.Module` yalnız embedded kullanım veya bilinçli olarak
elle yönetilen sıra dışı lifecycle içindir.

Paketlemeden önce `mvn clean verify` çalıştırın. Platform parent otomatik olarak `reactor:doctor`,
`reactor:verify-aot` ve `reactor:verify-native-abi` gate'lerini çalıştırır. Production runtime JAR'ları
framework processor sınıflarını, DSL-JSON processor sınıflarını ve annotation-processor ServiceLoader
metadata'sını içermez. `codegen` classifier yalnız derleyici içindir. Normal dependency olarak eklemeyin.

## Direct JSON Writer

```java
@Response
@GenerateDirectJsonWriter
public record OrderSummary(
        long id,
        String status,
        java.util.List<OrderLine> lines,
        java.util.Optional<String> note) {}

public record OrderLine(long productId, int quantity) {}
```

`@Response`, tipi HTTP response DTO olarak işaretler. Tek başına direct writer üretmez. Bu DTO ölçülmüş
bir serialization hot path üzerindeyse `@GenerateDirectJsonWriter` ekleyin. Processor; primitive ve
boxed scalar tipler, `String`, enum, UUID, zaman tipleri, nested record, array, `Iterable`, `Optional`,
`BigDecimal` ve `BigInteger` için tipe özel writer üretir.

Generated writer startup sırasında kaydedilir ve request kabul edilmeden önce route'a bir kez
bağlanır. Runtime sırasında lazy writer araması yapılmaz. Explicit annotation varken map, byte/char
array, wildcard veya type-variable collection ve recursive graph build hatası verir. Yalnız
`@Response` kullanan record, uyumlu DSL-JSON yolunda kalır. Manuel writer da route compilation'dan
önce kaydedilmelidir. Büyük dinamik object graph için `List<Record>` kurmak yerine
`JsonBodyProducer` kullanın.

## JDBC Record Mapper

```java
@GenerateJdbcMapper
public record CustomerRow(
        long id,
        @JdbcColumn("customer_no") String customerNo,
        Instant createdAt) {}

CustomerRow row = CustomerRowJdbcMapper.map(resultSet);
```

Mapper kolonları doğrudan okur. Runtime sırasında record alanlarını incelemez.

## Runtime Profile Plan

```java
RuntimeProfilePlan plan = RuntimeProfilePlan.named("customer-api")
        .positiveInt("reactor.rust.jni.workers", 2)
        .routeBudget("customer-heavy", 96, 125)
        .build();

RestApplication.run(context -> context.profile(plan).scan("com.example.customer"));
```

`-D`, environment ve property dosyası değerleri önceliğini korur. Plan yalnız doğrulanmış uygulama
varsayılanlarını verir. Operasyon ayarlarını gizlemez.

## Health ve Readiness

```java
HealthEndpoint health = HealthStarter.application("customer-api")
        .required("postgres", 250, repository::ping)
        .optional("telemetry", 100, telemetry::available)
        .build();
```

Liveness dependency çağırmaz. Readiness kontrolü yalnız `/app/readiness` çağrıldığında çalışır.
Her kontrol bir bounded virtual thread kullanır. Arka planda sürekli polling executor tutulmaz.
Dependency exception mesajı HTTP yanıtına yazılmaz.

## Proje Generator

`scripts/new-reactor-project.ps1` veya `scripts/new-reactor-project.sh` kullanın. Generator seçilen
proje biçimi için deklaratif application sınıfı, constructor-injected handler, minimum property dosyası,
Maven repository ve codegen yollarını üretir. Processor sınıfları build-only classifier içinden bulunur.
Generator dolu bir klasörün üzerine yazmaz.
