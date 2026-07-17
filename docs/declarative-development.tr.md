# Deklaratif Geliştirme

[English](declarative-development.md) | [Türkçe](declarative-development.tr.md)

## Neler Üretilir?

Yalnız derlemede kullanılan codegen artifact'i route ve component index, application descriptor,
property metadata, direct JSON writer ve JDBC record mapper üretir. Generated sınıflar doğrudan
method çağrısı kullanır. Request sırasında classpath taraması veya yeni reflection eklemez.

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

RestApplication.run(context -> context.profile(plan).handlers(handler));
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
