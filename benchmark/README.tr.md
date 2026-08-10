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
