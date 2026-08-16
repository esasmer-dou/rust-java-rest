# Konfigürasyon

[English](configuration.md) | [Türkçe](configuration.tr.md)

Öncelik sırası açıktır. JVM `-D` ve environment değerleri dış property dosyasını ezer. Dış dosya
classpath içindeki `rust-spring.properties` değerini ezer. `RuntimeProfilePlan` yalnız eksik uygulama
varsayılanlarını tamamlar.

| Hedef | Başlangıç | Yalnız ölçerek değiştirin |
| --- | --- | --- |
| En düşük pratik REST RSS | `reactor.runtime.profile=micro-rest` | JNI worker, response pool, max connection |
| Ağır producer JSON | `micro-rest-plus` ve named route budget | `max-concurrent`, `queue-timeout-ms` |
| En yüksek RPS | `balanced` veya `throughput` | Pod CPU/RSS limitine göre worker ve queue |
| Büyük dosya | `FileResponse` | `reactor.rust.static-file.max-concurrent-streams` |
| WebSocket | Yalnız gereken artifact'te açın | Frame boyutu ve outbound queue kapasitesi |

Framework'ün tüm property dosyasını her servise kopyalamayın. Local varsayılanları küçük tutun.
Production gate ve ileri tuning değerlerini ayrı overlay dosyalarında yönetin.

`META-INF/reactor/properties.idx`, build-time `@RustProperty` envanteridir. Secret saklamaz ve runtime
config kaynağı değildir.

| Production gate | Varsayılan | Ne zaman açılır? |
| --- | --- | --- |
| `reactor.optimizer.fail-on-reflection-route-metadata` | `false` | `/diagnostics/routes` tüm production route'larda generated metadata gösterdiğinde |
| `reactor.optimizer.fail-on-fallback` | `false` | Gerekli route'lar hedeflenen compiled, direct veya native yolu kullandığında |
| `reactor.optimizer.fail-on-heavy-json-object-graph` | `false` | Ağır route'lar producer, direct, raw, file veya native response yoluna taşındığında |

Bu gate'leri migration tamamlandıktan sonra açın. Kontroller startup sırasında çalışır. Request hot
path'ine ek yük getirmez.

## İsteğe Bağlı Runtime Retention Ayarları

Bu ayarlar yalnız kullanılmayan gözlem verisini bellekten çıkarır.

| Property | Varsayılan | Ne yapar? | Ne zaman değiştirilir? |
| --- | --- | --- | --- |
| `reactor.metrics.collection-enabled` | `false` | Built-in metrics route'ları kayıtlı değilken de Java counter, gauge ve histogram verisini tutar | Uygulama kodu `Metrics` sınıfını doğrudan okuyorsa ve `@ReactorApplication(metrics = true)` veya `RestApplication.Builder.metrics()` kullanılmıyorsa `true` yapın |
| `reactor.optimizer.retain-route-plans` | `auto` | `auto`, metrics veya runtime route metrics açıksa detaylı planları tutar; aksi durumda startup kontrolü bitince plan nesnelerini bırakır | Built-in metrics olmadan özel route diagnostics kullanıyorsanız `true` yapın; startup sonrasında route detayı bilinçli olarak gerekmiyorsa `false` kullanın |

`@ReactorApplication(metrics = true)` ve `RestApplication.Builder.metrics()` Java metrics toplamayı
her zaman açar. Metrics'i kapatmak Rust tarafındaki native HTTP sayaçlarını kaldırmaz. Yalnız hiçbir
endpoint'in okumadığı Java registry verisinin bellekte tutulmasını engeller.

## Glowroot Telemetrisi

Stable `4.5.4` runtime REST ABI `29` ve Glowroot ABI `3` kullanır. Sınırlı profiller çalışma sırasında
değiştirilebilir. Bu Java sınıflarını REST `4.4.x` ABI `28` DLL/SO dosyasıyla karıştırmayın.

Glowroot Central ve Cassandra değişmez. Rust-Java REST için agent JAR gerekmez. Protobuf encode,
collector bağlantısı, profil state'i ve izole `256 KiB` exporter thread'i native runtime'a aittir.
Hyper worker'ları kullanılmaz. İsteğe bağlı `java-rust-glowroot-agent.jar` yalnız erken
`-javaagent` argümanlarını property'lere çevirir.

| Property | Varsayılan | Kabul edilen değer | Ne işe yarar? |
| --- | ---: | --- | --- |
| `reactor.glowroot.enabled` | `false` | boolean | Sınırlı telemetri state'ini ve exporter'ı açar |
| `reactor.glowroot.profile` | `micro` | `micro`, `jvm`, `sql`, `full`, `diagnostic` | Başlangıç profilini seçer; API daha sonra değiştirebilir |
| `reactor.glowroot.profile.release-timeout-ms` | `5000` | 100-60000 | Eski profile ait state'in bırakılması için en uzun senkron bekleme |
| `reactor.glowroot.collector.address` | `http://127.0.0.1:8181` | plaintext `host:port` veya `http://host:port` | Glowroot Central gRPC over HTTP/2 adresi |
| `reactor.glowroot.agent.id` | boş | 1-256 byte | Zorunlu pod veya rollup kimliği |
| `reactor.glowroot.application.name` | uygulama adı | 1-128 byte | Glowroot ekranında görünen ad |
| `reactor.glowroot.hostname` | `HOSTNAME` | en fazla 255 byte | Pod veya host kimliği |
| `reactor.glowroot.export.interval-ms` | `60000` | 60000-3600000 ve 60000'in katı | Aggregate ve gauge gönderim aralığı |
| `reactor.glowroot.connect-timeout-ms` | `1000` | 100-30000 | TCP/HTTP2 bağlantı zaman sınırı |
| `reactor.glowroot.request-timeout-ms` | `2000` | 100-30000 | Tüm unary gRPC çağrısının zaman sınırı |
| `reactor.glowroot.trace.slow-threshold-ms` | `500` | 1-3600000 | Startup kuyruğu varsa HTTP yavaş trace eşiği |
| `reactor.glowroot.http.sample-rate` | `256` | 1-1024 arasında ikinin kuvveti | Başarılı HTTP aggregate örneklemesi; `5xx` tam sayılır |
| `reactor.glowroot.trace.capacity` | `0` | 0-32 | Startup'ta ayrılan HTTP trace kuyruğu; `0` iken ayrılmaz |
| `reactor.glowroot.sql.capacity` | `16` | 0-32 | Yalnız `sql`, `full` veya `diagnostic` açıkken ayrılan SQL slotu |
| `reactor.glowroot.error.trace.capacity` | `8` | 0-16 | Yalnız hata profilleri açıkken tutulan hata ayrıntısı |
| `reactor.glowroot.error.max-frames` | `24` | 0-32 | Bir hata için kopyalanacak en fazla stack frame sayısı |
| `reactor.glowroot.error.max-bytes` | `4096` | 256-8192 | Bir hata ayrıntısının en fazla UTF-8 byte boyutu |
| `reactor.glowroot.max-routes` | `64` | 1-64 | En fazla HTTP route slotu |
| `reactor.glowroot.max-export-bytes` | `65536` | 16384-65536 | Encode edilen request için kesin üst sınır |

`reactor.native.capabilities` değerini açıkça veriyorsanız `glowroot` ekleyin:

```properties
reactor.native.capabilities=http,dubbo,redis,glowroot
reactor.glowroot.enabled=true
reactor.glowroot.profile=micro
```

Kontrol API'sini kimlik doğrulaması olan iç operasyon akışından çağırın. Public endpoint veya request
hot path üzerinden çağırmayın:

```java
GlowrootTelemetry.switchTo(TelemetryProfile.FULL, Duration.ofSeconds(5));
// Olay inceleme aralığını sınırlı tutun.
GlowrootTelemetry.restoreConfiguredProfile();
```

Her request'te profil değişimini otomatikleştirmeyin. SQL slotları, 25-bit nesil içeren ayrı ve
pozitif bir 32-bit kimlik kullanır. Eski slotlar normal process ömründe yeni SQL tanımıyla eşleşmez.
Runtime yalnız `33 milyon` üzerindeki state-shape geçişinde sessiz wrap yerine hata verir.

Çağrı sıraya alınır ve senkrondur. `restoreConfiguredProfile()`, `reactor.glowroot.profile` ile seçilen
başlangıç değerine döner; `micro` değerini kod içinde sabitlemez. Temel profil `micro` ise dönüş
tamamlandığında SQL slotları, hata ve tanılama kuyrukları, profile ait export verisi ve Rust'ın sahip
olduğu JNI MXBean global referansları bırakılmıştır. Linux, son referans bırakıldıktan sonra izole agent
thread'inden `malloc_trim(0)` ister. Windows sahipliği bırakır, ancak bütün process için zorunlu
working-set boşaltma yapmaz.

İzole thread, Hyper worker'larını veya server Tokio runtime'ını kullanmaz. Buna rağmen glibc trim
çağrısı process genelindedir. Profil değişimini request akışında değil, seyrek bir kontrol düzlemi
işlemi olarak kullanın. RSS etkisini tek bir geçiş sonrası değerle değil, telemetri kapalı/açık taze
process A/B testiyle ölçün.

Her normalize SQL için tek ve tekrar kullanılan descriptor oluşturun. Bind value eklemeyin. Her
request için descriptor üretmeyin:

```java
private static final GlowrootTelemetry.SqlStatement FIND_CUSTOMER =
        GlowrootTelemetry.sql("customer.find", "select id, name from customer where id = ?");
```

`http.sample-rate` ve `trace.capacity` startup ayarıdır. Profil geçişi bu kapasiteleri değiştirmez.
En sıkı `micro` kaynak bırakma davranışı için `trace.capacity=0` kullanın. Her key normal uppercase
environment karşılığına sahiptir. Örneğin `reactor.glowroot.profile.release-timeout-ms`,
`REACTOR_GLOWROOT_PROFILE_RELEASE_TIMEOUT_MS` olur.
