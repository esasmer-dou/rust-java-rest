# Operasyon Rehberi

[English](operations.md) | [Türkçe](operations.tr.md)

Canlıya çıkmadan önce container RSS, p50/p95/p99, `503` oranı, JNI queue bekleme süresi, in-flight
response byte, aktif dosya stream ve dependency pool bekleme değerlerini kaydedin. Aynı endpoint
karışımını c64 ve c256 yükte karşılaştırın.

Liveness için `/app/health` kullanın. Gerekli dependency kontrolleri için `/app/readiness` kullanın.
Liveness Redis, Dubbo veya veri tabanı çağırmamalıdır. Readiness başarısızsa sağlıklı process yeniden
başlatılmadan pod trafikten çıkarılmalıdır.

`503`, kontrollü overload sonucu olabilir. Route budget yalnız provider, DB pool, CPU ve RSS
kapasitesi varsa artırılmalıdır. Global queue büyütmek başarılı istek sayısını artırırken p99 ve
tutulan memory değerini kötüleştirebilir.

Idle native trim yalnız düşük trafikli ve memory-first podlarda açılmalıdır. Request handler içinde
trim çağırmayın.

## Henüz Yayımlanmadı: Üretilmiş Admission ve Yanıt Sahipliği

`@KeyAdmission` kullanmadan önce üretilmiş startup descriptor/invoker'ı paketleyin ve aktif bir
configuration içinde adı verilen `LongKeyAdmission` bean'ini tanımlayın. Bean başlangıçta bir kez
çözülür. `reactor.codegen.route-invoker.required=false` olsa da üretilmiş kayıt yoksa başlangıç durur.
Doğrudan `long`/`String` path/query anahtarları, sınırlı stripe kapasitesi ve dependency deadline'ları
kullanın. Stripe çakışmasında kapasite paylaşılır; bu bir dağıtık kilit, idempotency deposu veya bekleme
kuyruğu değildir.

APT, native JSON `NativeResponseHandle` dönüşlerini async ve `ResponseEntity` biçimleriyle birlikte
uyarlar; istek yolunda reflection kullanmaz. `DubboResponses.replyAsync(source, DubboResponses::reply)`,
HTTP iptalinden sonra kaynağı izlemeyi sürdürür ve geç gelen owner'ı bırakır. RPC deadline'ını sınırlı
tutun. Bu sahiplik adaptörünü iptal edilebilir bir `thenApply` zinciriyle değiştirmeyin.
Buffer yeniden yazımı yalnız mevcut yanıtı yazar; yanıt byte'ları otomatik cache'lenmez.

Bu değişiklikler henüz yayımlanmadı. K-001 gereği JNI ve mevcut ABI imzaları korunur. Ek temizleme
export'ları yalnız yeni Java sınıfları değil, koordineli yeni native derleme ve eşleşen provenance ister.

Release öncesi `mvn clean verify` çalıştırın. Codegen sınıfı runtime JAR içine girerse build fail olur.
Windows DLL ve Linux SO aynı kaynak revision'dan üretilmeli, ABI manifesti doğrulanmalıdır.

## Sorun Anında Kontrol Listesi

1. Çalışan podun Java sürümünü, native platformunu, dosya SHA-256 değerini ve başlangıç ABI
   sonucunu kaydedin. Yalnız image etiketine bakmayın. Rapora parola veya token eklemeyin.
2. Liveness ve uygulamanın bağımlılık readiness sonucunu ayrı kontrol edin. JVM'in çalışması,
   gerekli Redis snapshot'ının, Dubbo servisinin veya veri tabanının hazır olduğunu göstermez.
3. `503` için hangi rotanın reddettiğini bulun. Aktif iş, kuyruk bekleme ve bağımlılık havuzunun
   bekleme değerlerini karşılaştırın. Sınırlı kapasite değerlerini tek tek değiştirin.
   Tekrarlanması güvenli olmayan yazma işlemlerini yeniden denemek aynı işi iki kez çalıştırabilir.
4. Native yanıt hatasında önce Java/native paket eşleşmesine ve üretilmiş invoker'a bakın.
   İptalden sonra aktif işlerin kendi zaman sınırında bitmesini kontrol edin.
   Hâlâ yazılan gövdeyi zorla kapatmayın. Hatayı susturmak için sahiplik adaptörünü kaldırmayın.
5. Native HTTPS için sunucu adını, sertifika zincirini, geçerlilik tarihini ve CA PEM yolunu kontrol edin.
   Sertifika doğrulamasını kapatmayın. [Native HTTP(S) ayarlarına](../platform/starter-http-client/NATIVE-INTEGRATION.tr.md) bakın.
   Byte bütçesi nedeniyle ret almak kapasite korumasıdır; sınırları kaldırmak çözüm değildir.
6. Aynı iş yükü ve kaynak sınırlarıyla başlangıç, yük sonrası ve boşta RSS değerlerini karşılaştırın.
   Ayrılan bellek bütçesi, ölçülen RSS değildir. ARM64 doğrulama iş akışı gerçek donanımda geçene
   kadar runtime doğrulaması kapalı kalmalıdır. Çapraz derleme tek başına yeterli değildir.
