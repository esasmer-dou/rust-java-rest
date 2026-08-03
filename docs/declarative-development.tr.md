# Deklaratif Geliştirme

[English](declarative-development.md) | [Türkçe](declarative-development.tr.md)

## Neler Üretilir?

Yalnız derlemede kullanılan codegen artifact'i route ve component index, application descriptor,
property metadata, direct JSON writer ve JDBC record mapper üretir. Generated sınıflar doğrudan
method çağrısı kullanır. Request sırasında classpath taraması veya yeni reflection eklemez.

## Uygulama Bağlantıları

```java
@ReactorApplication(scanBasePackages = "com.example.customer")
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

Constructor factory ve route invoker sınıfları derleme sırasında üretilir. Üçüncü taraf nesneler
için `@Configuration` ve `@Bean` kullanın. Generated factory, `@Bean` metodunun parametrelerini
constructor injection ile çözebilir. Normal CRUD bağlantıları için module yazmayın.

Aynı interface'i iki bean sağlıyorsa yalnız bir implementasyona `@Primary` ekleyin veya injection
parametresinde `@Qualifier("beanName")` kullanın. Generated container ilk bean'i sessizce seçmez.
Belirsizlik varsa uygulamayı açık bir hata ile durdurur. Generated constructor veya `@Bean` metodu
checked exception üretirse hata bean adı ve asıl neden korunarak `BeanCreationException` olarak
raporlanır. Bu işlemler yalnız başlangıçta çalışır. Request yoluna ek maliyet getirmez.

## Direct JSON Writer

```java
@GenerateDirectJsonWriter
public record OrderSummary(long id, String status, Boolean priority) {}
```

Scalar record için kullanın. Primitive, boxed scalar, String, enum, UUID ve yaygın time tipleri
desteklenir. Nested object graph ve collection için açık bir `JsonBodyProducer` veya business writer
yazın. Processor uygun olmayan tipi sessizce yavaş yola düşürmez. Derlemeyi hata ile durdurur.

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
Her kontrol bounded virtual thread kullanır. Arka planda sürekli polling thread'i tutulmaz.
Dependency exception mesajı HTTP yanıtına yazılmaz.

## Proje Generator

`scripts/new-reactor-project.ps1` veya `scripts/new-reactor-project.sh` kullanın. Generator seçilen
proje biçimi için minimum POM, kaynak ve property dosyalarını üretir. Dolu klasörün üzerine yazmaz.
Üretilen REST, cache-reader ve Dubbo projeleri deklaratif application sınıfı ve constructor injection
kullanır. Processor sınıfları build-only classifier içinden otomatik bulunur.
