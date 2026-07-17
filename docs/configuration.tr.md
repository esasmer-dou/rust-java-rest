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
