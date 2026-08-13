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

## Glowroot Mikro Ajan (4.4.1)

Yayınlanmış `4.4.1` runtime REST ABI `28` ve Glowroot ABI `1` kullanır. Yalnız koordineli `4.4.1`
artifact'iyle gelen DLL/SO dosyasını kullanın.

Bu entegrasyon yalnız application tarafındadır. Mevcut Glowroot Central/collector değişmez. Strict
düşük-bellek yolunda agent JAR gerekmez. Uyumlu native capability'yi JVM property, ortam değişkeni
veya `rust-spring.properties` ile açın. İsteğe bağlı `java-rust-glowroot-agent.jar` yalnız
`-javaagent` argümanlarını çevirir ve ayrı ölçülür. Benchmark mock collector'ı veya özel bir
collector plugin'ini production ortamına kurmayın.

| Property | Varsayılan | Kabul edilen değer | Ne işe yarar? |
| --- | ---: | --- | --- |
| `reactor.glowroot.enabled` | `false` | boolean | Sınırlı telemetri state'ini ve exporter'ı açar |
| `reactor.glowroot.profile` | `micro` | `micro` | Sınırsız bir profilin yanlışlıkla açılmasını engeller |
| `reactor.glowroot.collector.address` | `http://127.0.0.1:8181` | plaintext `host:port` veya `http://host:port` | Glowroot Central h2 adresi |
| `reactor.glowroot.agent.id` | boş | 1-256 byte | Zorunlu pod veya rollup kimliği |
| `reactor.glowroot.application.name` | uygulama adı | 1-128 byte | Glowroot ekranında görünen ad |
| `reactor.glowroot.hostname` | `HOSTNAME` | en fazla 255 byte | Pod veya host kimliği |
| `reactor.glowroot.export.interval-ms` | `60000` | 60000-3600000 ve 60000'in katı | Aggregate ve gauge gönderim aralığı |
| `reactor.glowroot.connect-timeout-ms` | `1000` | 100-30000 | TCP/h2 bağlantı zaman sınırı |
| `reactor.glowroot.request-timeout-ms` | `2000` | 100-30000 | Tüm unary gRPC çağrısının zaman sınırı |
| `reactor.glowroot.trace.slow-threshold-ms` | `500` | 1-3600000 | Yavaş trace eşiği |
| `reactor.glowroot.http.sample-rate` | `256` | 1-1024 arasında ikinin kuvveti | Başarılı HTTP aggregate örneklemesi; `5xx` tam sayılır |
| `reactor.glowroot.trace.capacity` | `0` | 0-32 | Sınırlı yavaş/hatalı trace kuyruğu; `0` iken trace state'i ayrılmaz |
| `reactor.glowroot.max-routes` | `64` | 1-64 | 1 MiB profilindeki en fazla HTTP route slotu |
| `reactor.glowroot.max-export-bytes` | `65536` | 16384-65536 | 1 MiB profilindeki kesin encode request sınırı |

`reactor.native.capabilities` değerini açıkça veriyorsanız ajan açıkken `glowroot` ekleyin:

```properties
reactor.native.capabilities=http,dubbo,redis,glowroot
reactor.glowroot.enabled=true
```

Varsayılan profil aggregate önceliklidir: sample rate `256`, trace capacity `0`. Staging ortamında
başarılı isteklerin gecikme dağılımını daha sık örneklemek gerekiyorsa `64` veya `128` değerini test
edin. Sınırlı trace kapasitesini, örneğin `16`, yalnız açık bir teşhis ihtiyacı için kullanın. Ajan
kapalı/açık p99, başarılı RPS, `503`, process RSS ve cgroup memory karşılaştırması yapmadan bu
değerleri production'a taşımayın.

Her key normal uppercase environment karşılığına sahiptir. Örneğin
`reactor.glowroot.http.sample-rate`, `REACTOR_GLOWROOT_HTTP_SAMPLE_RATE` olur.
