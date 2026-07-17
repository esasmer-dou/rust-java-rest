# Sorun Giderme

[English](troubleshooting.md) | [Türkçe](troubleshooting.tr.md)

| Belirti | Önce neye bakılır? | Doğru çözüm |
| --- | --- | --- |
| Startup scan fallback yazıyor | `components.idx` JAR içinde mi? | `ReactorStartupProcessor` ekleyin. Index'i elle yazmayın. |
| Duplicate route build hatası | Aynı HTTP method ve normalize path | Duplicate route'u kaldırın veya pasif profil yüzeyini exclude edin. |
| Generated writer build hatası | İşaretli record içinde nested/list alan | `JsonBodyProducer` veya açık business writer kullanın. |
| `503` artıyor | Route admission ve dependency pool wait | Yalnız ilgili route'u tune edin. Önce global queue büyütmeyin. |
| Burst sonrası RSS inmiyor | In-flight byte, pool ve allocator retention | Idle/soak ölçün. p99 geçerse konservatif idle trim açın. |
| Eski DLL/SO hatası | Startup ABI ve provenance mesajı | Aynı release içindeki native artifact'leri kullanın. |
| Türkçe karakter bozuk | Request ve response content type | UTF-8 gönderin. `charset=utf-8` kullanın. Platform encoding kullanmayın. |

Generated kaynaklar `target/generated-sources/annotations` altında bulunur. Runtime fallback olduğunu
düşünmeden önce bu dizini kontrol edin. Route metadata dosyaları `target/classes/META-INF/reactor`
altındadır.
