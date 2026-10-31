# Sorun Giderme

[English](troubleshooting.md) | [Türkçe](troubleshooting.tr.md)

## Olay Kontrol Listesi

1. JAR sürümünü, native dosyanın SHA-256 değerini, ABI numarasını ve etkin profili kaydedin. ABI numarasının aynı olması yeni bir JNI fonksiyonunun dosyada bulunduğunu kanıtlamaz. Rapora parola veya token eklemeyin.
2. Uygulama sağlığı ile bağımlılık hazırlığını ayırın. HTTP sunucusunun çalışması Redis, Dubbo veya veritabanına ulaşılabildiğini göstermez.
3. Sorunlu rota için başarılı istek hızını, p99 değerini, ret sayısını ve bağımlılık beklemesini birlikte inceleyin. Hızlı dönen `503` başarılı istek değildir. Her denemede yalnız bir sınırlı kapasite ayarını değiştirin.
4. Native HTTPS başlangıç hatasında önce JAR ve SO sürüm eşleşmesine bakın. TLS el sıkışması hatasında sunucu adını, sertifika süresini ve güvenilen CA dosyasını kontrol edin. Sertifika doğrulamasını kapatmayın.
5. Trafik bittikten sonra bellek yüksek kalırsa boşta RSS, native işlemdeki byte sayısı ve canlı response handle sayısını karşılaştırın. Ayarlanan byte limiti RSS ölçümü değildir. Ödünç buffer'ı callback tamamlanmadan serbest bırakmayın.

Sonraki adımlar için [operasyon rehberini](operations.tr.md), DLL veya SO değişimi için
[native uyumluluk rehberini](native-availability.tr.md) kullanın.

| Belirti | Önce neye bakılır? | Doğru çözüm |
| --- | --- | --- |
| Startup scan fallback yazıyor | `components.idx` JAR içinde mi? | `ReactorStartupProcessor` ekleyin. Index'i elle yazmayın. |
| Duplicate route build hatası | Aynı HTTP method ve normalize path | Duplicate route'u kaldırın veya pasif profil yüzeyini exclude edin. |
| Generated writer build hatası | İşaretli record içinde nested/list alan | `JsonBodyProducer` veya açık business writer kullanın. |
| `503` artıyor | Route admission ve dependency pool wait | Yalnız ilgili route'u tune edin. Önce global queue büyütmeyin. |
| Burst sonrası RSS inmiyor | In-flight byte, pool ve allocator retention | Idle/soak ölçün. p99 geçerse konservatif idle trim açın. |
| Eski DLL/SO hatası | Startup ABI ve provenance mesajı | Aynı release içindeki native artifact'leri kullanın. |
| Türkçe karakter bozuk | Request ve response content type | UTF-8 gönderin. `charset=utf-8` kullanın. Platform encoding kullanmayın. |
| Yayımlanmamış `@KeyAdmission` rotası başlangıçta duruyor | Üretilmiş invoker ve adı verilen `LongKeyAdmission` bean'i | APT'yi çalıştırıp üretilmiş startup kaydını paketleyin; bean adı/tipini düzeltin. Genel invoker zorunluluğunu kapatmak bu kontrolü atlatmaz. |
| Yayımlanmamış admission codegen anahtarı reddediyor | Tek bir doğrudan path/query `long` veya `String` parametresi | Java ya da binding adını kullanın; DTO/header/body anahtarları ve ödünç çıktı buffer'ı rotaları desteklenmez. |
| Yayımlanmamış native adaptörde temizleme sembolü eksik | Java/native provenance ve `discardNativeResponse` export'u | Koordineli yeni native derleme gerekir; ABI numarasının aynı olması ek sembolün varlığını kanıtlamaz. |

Generated kaynaklar `target/generated-sources/annotations` altında bulunur. Runtime fallback olduğunu
düşünmeden önce bu dizini kontrol edin. Route metadata dosyaları `target/classes/META-INF/reactor`
altındadır.
