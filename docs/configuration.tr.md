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
