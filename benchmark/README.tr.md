# Benchmark Paketi

[English](README.md) | [Türkçe](README.tr.md) | [Framework Rehberi](../README.tr.md)

Bu dizin tekrarlanabilir performance kanıtlarını ve geçmiş release gate sonuçlarını tutar. Hızlı
başlangıç rehberi değildir. Her tarihsel bölüm, başlığında yazan source line için geçerlidir.

Güncel kaynak ağacı REST ABI `26`, Dubbo ABI `7` ve Redis ABI `6` kullanır. İki build'i
karşılaştırmadan önce native artefact'i aynı source revision'dan üretin. Eski DLL/SO ile yapılan test,
uygulama başlasa bile geçerli değildir.

## Sonucu Nasıl Okumalısınız?

| Metrik | Anlamı |
| --- | --- |
| Useful `200` RPS | Başarıyla tamamlanan business kapasitesi |
| p50/p95/p99 | Normal ve tail latency |
| `503` oranı | Bounded overload davranışı |
| Container `memory.current` | Kubernetes açısından container'a yazılan memory |
| Container `anon` | Heap, JVM/native runtime, thread ve allocator baskısı |
| Process RSS / `Private_Dirty` | Mapping ve process'e özel memory kanıtı |
| JNI queue wait ve in-flight byte | Framework backpressure baskısı |

Bir profili yalnız RPS ile değerlendirmeyin. Büyük queue `503` oranını düşürebilir. Ancak p99 ve
retained memory değerini artırabilir.

## Geçerli Karşılaştırma Kuralları

- En az üç tekrar kullanın.
- Çalıştırma sırasını karıştırın.
- İki build için aynı warmup ve süreyi kullanın.
- Aynı container CPU ve memory limitlerini kullanın.
- Aynı endpoint karışımını test edin.
- Useful `200` RPS, p99, `503` ve RSS değerlerini birlikte raporlayın.
- Benchmark-only route'ları production route raporuna katmayın.
- Full sample RSS değerini minimal service baseline olarak kullanmayın.

Memory öncelikli başlangıç için `micro-rest` kullanın. Ağır route için `micro-rest-plus` seçimini
yalnız ölçerek yapın. Geçmiş komutlar, ham tablolar ve ayrıntılı karar notları için
[İngilizce kanıt arşivine](README.md) bakın.

## Eşleştirilmiş Image Karşılaştırması

Bir framework değişikliğinin memory kazanırken RPS veya p99 kaybetmediğini görmek için
`paired_image_gate.ps1` kullanın. Araç her döngüde dış sıraları değiştirir:

- Tek numaralı döngü: baseline, candidate, candidate, baseline.
- Çift numaralı döngü: candidate, baseline, baseline, candidate.

Bu sıra, işletim sistemi cache'i, host sıcaklığı ve scheduler etkisinin sürekli aynı image'a avantaj
vermesini engeller. İki image'ın dış ve orta pozisyonlarda eşit sayıda çalışması için her zaman çift
tekrar sayısı kullanın. Yerel geliştirme kanıtı için `PairRepeats = 2` kullanın. Release kararı için
`PairRepeats >= 4` kullanın. `-FailOnGate`, tek tekrar sayısını reddeder. Beş saniyeden kısa koşuları
yalnız hızlı tanı amacıyla değerlendirin. Useful `200` RPS, p99, `503`, container memory ve RSS
değerlerini birlikte inceleyin.

Eşleştirilmiş test, yük üretici image'ını döngü başında bir kez oluşturur. Uygulamaları Docker test
ağı üzerinden kontrol eder. Normal kaynak build'lerinde temiz Maven çıktısı kullanır. Böylece host
portundaki başka bir uygulama veya eski bir shaded sample JAR sonucu kirletemez.

`annotated-generated-json` sınıfı, `GET /users/search?name=load&page=1` rotasını ölçer. Bu rota
deklaratif Java handler ve üretilmiş route çağırıcısını doğrular. Response tipi ayrıca
`@GenerateDirectJsonWriter` kullanmıyorsa uyumlu DSL-JSON yolu çalışır. Böylece yalnız native veya
elle yazılmış direct rotaları ölçerek yanlış sonuca varılmaz.

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\paired_image_gate.ps1 `
  -BaselineImage rust-java-rest:baseline `
  -CandidateImage rust-java-rest:candidate `
  -ConcurrencyLevels "64,256" `
  -EndpointClasses "small-json-direct,direct-json-writer,dynamic-producer-json,raw-json" `
  -Duration 10s `
  -Warmup 3s `
  -PairRepeats 4 `
  -CalibrationCycles 1 `
  -PlanPreWarm `
  -PlanPreWarmDuration 10s `
  -FailOnGate
```

`PlanPreWarmDuration`, ölçüm başlamadan önce seçilen her rotayı çalıştırır. OpenJ9 kullanan ve Java
iş yükü ağır olan rotalarda release kanıtı için en az `10s` kullanın. Böylece daha hızlı başlayan aday
image, baseline image'dan daha genç bir JIT derleme durumunda ölçülmez. Seçilen değer
`metadata.json` dosyasına yazılır.

`CalibrationCycles 1`, dengeli ölçümlerden önce kayda alınmayan bir baseline/candidate turu çalıştırır.
Docker Desktop'ın ilk turunda image yükleme veya page cache etkisi yüksekse release testinde bunu
kullanın. Calibration çıktıları `runs/cycle-00-*` altında kalır, karşılaştırmaya eklenmez.

### Resident crossover ve startup regresyon gate'leri

Generated invocation, echo parse veya native-static kontrol rotasını ayrı incelemek için
`resident_crossover_gate.ps1` kullanın. İki image bir phase boyunca açık kalır. İkinci phase'de CPU
slotları değiştirilir. Cooldown, host sıcaklığının sonucu tek yöne çekmesini azaltır. Process RSS ile
container memory ayrı ölçülür. Etki büyüklüğü, crossover-pooled candidate/baseline medyanıyla
hesaplanır. Aynı phase içindeki candidate/baseline farklarının dağılımı kararlılık kontrolüdür.
Normalize edilmemiş rapor tanı amacıyla `comparison/absolute` altında kalır. Phase'ler arasındaki host
kapasite değişimi release kararlılık metriğini şişirmez.

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\resident_crossover_gate.ps1 `
  -BaselineImage rust-java-rest:baseline `
  -CandidateImage rust-java-rest:candidate `
  -Concurrency 64 `
  -Duration 15s `
  -EndpointClasses "annotated-generated-json,echo-parse,small-json-direct" `
  -RepeatCountPerSlot 3 `
  -SlotACpuSet 2 `
  -SlotBCpuSet 3 `
  -RunnerCpuSet 4-7
```

Startup için `image_startup_gate.ps1` kullanın. Framework internal-ready ile host HTTP-ready ayrı
raporlanır. Başlatma sırası dengelenir. Karar aynı cycle içindeki eşleştirilmiş farklarla verilir.
Candidate CV değeri ayrıca gate olarak kalır.

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\image_startup_gate.ps1 `
  -BaselineImage rust-java-rest:baseline `
  -CandidateImage rust-java-rest:candidate `
  -RepeatCount 6 `
  -CpuSet 2
```

`small-json-direct` native-static kontrol rotasıdır. Java invocation sayısı sıfır kalmalıdır. İki
image aynı native binary'yi kullanırken bu rota kararsızsa sonucu host gürültüsü olarak işaretleyin ve
sakin bir Linux runner üzerinde tekrarlayın. Java'ya girmeyen rota için Java kodunu tune etmeyin.

### 10 Ağustos 2026 ROM-Only SCC ve İsteğe Bağlı State Gate'i

İkinci anon döngüsünde her ayar önce tek başına ölçüldü:

| Aday | Anon sonucu | Performance kararı |
| --- | ---: | --- |
| OpenJ9 idle GC | Anlamlı düşüş yok | Generated image varsayılanı olarak reddedildi |
| `MALLOC_ARENA_MAX=1` | Güncel arena-2 kontrolüne göre yaklaşık `-0,46 MiB` | Small/direct RPS gerilediği için varsayılan olmadı |
| `-Xms4m -Xmx32m` | Yaklaşık `-1,85 MiB` | Small-route p99 kararsız olduğu için yalnız servis bazlı deney olarak kaldı |
| ROM-only SCC `4m` | Yaklaşık `-3,37 MiB` | Cache `%100` doldu ve bazı route sınıfları gerilediği için reddedildi |
| Metrics route'u yokken Java metrics state'ini bırakmak | Yaklaşık `-0,92 MiB` | Request akışını değiştirmediği için kabul edildi |
| Metrics kapalıyken startup route detaylarını bırakmak | Yaklaşık ek `-0,20 MiB` | Kabul edildi; metrics açıkken route detayları korunur |
| ROM-only SCC `8m` birleşik low-anon image | Final cgroup anon `31,027 -> 28,207 MiB` | Endpoint gate'leri sonrasında isteğe bağlı image olarak kabul edildi |

Uzun ve dengeli small-route c64 gate'inde useful 200 RPS `%11,43` arttı, p99 `%35,18` düştü,
memory `2,11 MiB` azaldı ve hata oluşmadı. Small-route c256 satırı ile direct, producer ve raw
c64/c256 satırları da geçti. AOT içeren SCC, direct-writer p99 sınırını aştığı için kabul edilmedi.

`linux_smaps_breakdown.ps1` artık `-MallocArenaMax` ve `-MallocTrimThreshold` değerlerini rapora
yazar. Böylece allocator deneyi image içinde gizli kalan environment değerlerine bağlı olmaz.

### Generated response writer gate'i

Build sırasında hazırlanan route planı, önceden kayıtlı generated response writer'ı doğrudan bağlar.
Bu arama genel DSL-JSON serializer'ını başlatmaz. Route hazırlandıktan sonra kaydedilen custom writer,
yayınlanmış route descriptor'ını değiştirmez. Custom writer'ı route compilation'dan önce kaydedin.

10 Ağustos 2026 tarihli c64 paired gate; dört dengeli cycle, bir calibration cycle, 10 saniye route
prewarm ve 15 saniye ölçüm ile çalıştırıldı. Doğrudan AOT bağlama, lazy writer proxy'ye göre useful
`200` RPS değerini `%1,73` artırdı. p99 `%1,15` düştü. `503` oluşmadı. Ortalama örneklenen peak
container memory `1,29 MiB` azaldı. Ayrı bir 30 saniye yük ve 30 saniye idle A/B testinde idle sonrası
cgroup memory `1,24 MiB`, anon `1,19 MiB` ve process RSS `0,52 MiB` daha düşük ölçüldü. Önceki lazy
proxy daha sonra kaldırıldı. Explicit writer artık route compilation sırasında hazır olmalıdır.
