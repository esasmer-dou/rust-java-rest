# Rust-Java Platform

[English](README.md) | [Türkçe](README.tr.md)

Platform modülleri Rust-Java uygulamalarına tek bir Maven kullanım biçimi sağlar. Yeni projede
platform parent kullanın. Ardından yalnız process'in gerçekten ihtiyaç duyduğu starter'ları ekleyin.

Yeni servis için parent kullanın. Şirket parent POM'u değiştirilemiyorsa BOM kullanın. Doğrudan
dependency yolunu yalnız embedding veya framework geliştirme için seçin.

## İçindekiler

- [Kopyala-çalıştır parent kurulumu](#buradan-başlayın)
- [Process tipini seçin](#process-tipini-seçin)
- [Parent, BOM veya doğrudan dependency](#parent-bom-veya-doğrudan-dependency)
- [Build sırasında üretilen yüzey](#build-neleri-ekler)
- [Sık kullanılan starter birleşimleri](#sık-kullanılan-kombinasyonlar)
- [Production kuralları](#production-kuralları)
- [Modül haritası](#modül-haritası)

## Buradan Başlayın

```xml
<parent>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-platform-parent</artifactId>
  <version>4.4.1</version>
</parent>

<artifactId>customer-api</artifactId>

<dependencies>
  <dependency>
    <groupId>com.reactor</groupId>
    <artifactId>rust-java-starter-rest</artifactId>
  </dependency>
</dependencies>
```

Parent; Java 21 ayarını, dependency sürümlerini, build-only annotation processor'ları ve framework
Maven gate'lerini hazırlar. Paketlemeden önce şu komutu çalıştırın:

```powershell
mvn clean verify
```

Beklenen sonuç: generated component/route metadata oluşur, dependency sınırları geçer ve processor
artifact'i runtime dependency olarak paketlenmez.

## Process Tipini Seçin

| Process | Gerekli starter | Runtime'a eklenen yüzey |
| --- | --- | --- |
| REST API | `rust-java-starter-rest` | Rust HTTP runtime ve Java REST API |
| WebSocket API | `rust-java-starter-websocket` | REST ve bounded WebSocket transport |
| REST contract | `rust-java-starter-openapi` | REST ve build-time OpenAPI çıktısı |
| Güvenli REST API | `rust-java-starter-security` | REST ve startup'ta hazırlanan request guard |
| Trace üreten REST API | `rust-java-starter-tracing` | REST ve sampled tracing guard |
| Dış HTTP çağrısı yapan REST API | `rust-java-starter-http-client` | REST ve generated HTTP client |
| Scheduler kullanan REST API | `rust-java-starter-scheduler` | REST ve tek bounded scheduler lifecycle |
| Native Dubbo consumer | `rust-java-starter-dubbo` | REST ve native-static Dubbo client |
| Redis'ten okuyan REST API | `rust-java-starter-cache-reader` | REST ve native Redis read plane |
| Redis'e yazan scheduler | `rust-java-starter-cache-writer` | Native Redis write plane; REST runtime yok |
| Framework testi | Test scope ile `rust-java-starter-test` | Yalnız test yardımcıları |

Her starter'ı eklemeyin. Özellik kapalı olsa bile gereksiz starter classpath'i büyütebilir.

## Parent, BOM veya Doğrudan Dependency

| Seçim | Ne zaman kullanılır? | Sizin yöneteceğiniz alan |
| --- | --- | --- |
| `rust-java-platform-parent` | Yeni uygulama | Çok az; önerilen yol budur |
| `rust-java-platform-bom` | Şirket parent POM'u değiştirilemiyorsa | Compiler plugin, processor ve gate ayarları |
| Doğrudan `rust-java-rest` | Framework geliştirme veya özel embedding | Tüm sürüm ve build ayarları |

BOM yalnız dependency sürümlerini hizalar. Annotation processor ve Maven verification gate'lerini
kurmaz. Normal servislerde parent kullanın.

## Build Neleri Ekler?

Parent, codegen sınıflarını compiler yolunda tutar. Processor sınıfları runtime dependency olmaz.

| Build adımı | Görevi |
| --- | --- |
| `ReactorStartupProcessor` | Component factory, route, condition, config binding, client ve index üretir |
| `DirectJsonWriterProcessor` | Desteklenen response record'ları için direct writer üretir |
| `reactor:doctor` | Yaygın dependency ve config hatalarını bulur |
| `reactor:verify-aot` | Generated startup ve route yüzeyini doğrular |
| `reactor:verify-native-abi` | Paketlemeden önce Java/native sözleşmesini doğrular |

Generated dosyalar build çıktısıdır. Bu dosyaları commit etmeyin ve elle değiştirmeyin.

## Sık Kullanılan Kombinasyonlar

Küçük REST servisi:

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-starter-rest</artifactId>
</dependency>
```

OpenAPI ve JWT guard kullanan REST servisi:

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-starter-rest</artifactId>
</dependency>
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-starter-openapi</artifactId>
</dependency>
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-starter-security</artifactId>
</dependency>
```

HTTP server açmadan Redis'e yazan scheduler:

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-starter-cache-writer</artifactId>
</dependency>
```

## Production Kuralları

- Her artifact tek ve açık bir process sorumluluğu taşımalıdır.
- Fiziksel olarak farklı uygulama yüzeyleri için runtime branch yerine Maven profile kullanın.
- Aynı artifact küçük bir startup seçimi sunuyorsa `@RequiresProperty` veya `@Profile` kullanın.
  Koşul her request'te değil, startup sırasında değerlendirilir.
- Processor classifier'larını runtime classpath'e eklemeyin.
- `mvn clean verify` komutunu release gate olarak kabul edin.
- Yalnız aynı framework sürümünde paketlenen native DLL/SO dosyasını kullanın.
- Son container'ı gerçek starter setiyle ölçün. Full sample uygulamasının RSS değeri minimal
  production servisi için doğru baseline değildir.

## Modül Haritası

| Modül | Kullanan taraf |
| --- | --- |
| `parent` | Uygulama build'leri |
| `bom` | Başka parent POM kullanmak zorunda olan projeler |
| `starter-*` | Tek bir özellik seçen uygulamalar |
| `maven-plugin` | Build doğrulaması |
| `compat` | Yalnız bilinçli migration çalışması |
| `integration-smoke` | Platform geliştiricileri |

Uygulama kodu ve generated wiring örnekleri için
[Deklaratif Geliştirme](../docs/declarative-development.tr.md) rehberine geçin.
