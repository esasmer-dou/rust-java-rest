# Açık İş Sınırları

[English](explicit-operations.md) | [Turkish](explicit-operations.tr.md)

Bu API'ler 4.7.0 platformundadır. Yalnız uygulamanızın kullandığı modülleri ekleyin.
İş kararları Java'da kalır. Native ABI sürümleri ve mevcut başarılı yanıtların biçimi değişmez.

## Hangi Araç?

| İhtiyaç | Araç | Maliyet |
| --- | --- | --- |
| Ortak hata yanıtı | `ErrorResponses.from(error)` | Yalnız hata oluştuğunda |
| Birlikte tamamlanacak DB işlemleri | `JdbcTransactions` | Havuzdan tek bağlantı |
| Yanıt kaybolunca güvenli tekrar | `PostgresIdempotency` | Seçilen yazma işlemlerinde ek SQL |
| Cache yenileme bildirimi | `PostgresRefreshOutbox` | Tanımlı projection başına bir satır |
| Büyük DB çıktısı | `JdbcSession.forEach` | Sınırlı okuma; tüm sonuç belleğe alınmaz |
| Kullanılan ayarı görmek | `PropertiesLoader.effective` | İstendiğinde rapor; arka plan thread'i yok |
| Sözleşme değişikliğini bulmak | `reactor:verify-contract` | Yalnız build sırasında |
| İki uygulamalı başlangıç | `ScenarioGenerator` | Yalnız proje üretirken |

## Transaction

Platform build'iyle eşleşen `com.reactor:rust-java-jdbc` bağımlılığını ekleyin.
Bu modülün ek runtime kütüphanesi yoktur. Mevcut `DataSource` nesnenizi verin.
Her istekte yeni bağlantı havuzu açmayın.

```java
var transactions = new JdbcTransactions(dataSource);
transactions.execute(JdbcTransactions.Options.write(2), session -> {
    session.update("update orders set status=? where id=?", statement -> {
        statement.setString(1, "confirmed");
        statement.setLong(2, orderId);
    });
    session.update("insert into order_audit(order_id,event) values(?,?)", statement -> {
        statement.setLong(1, orderId);
        statement.setString(2, "confirmed");
    });
    return null;
});
```

İki SQL aynı bağlantıda çalışır. İkinci işlem hata verirse ilk değişiklik de geri alınır.
Buradaki 2 saniye her SQL'in süresini sınırlar. Havuzdan bağlantı bekleme süresini Hikari ayarı belirler.
Alt metoda aynı `session` nesnesini verin. İçeride ikinci transaction açmayın.
Session'ı saklamayın ve başka thread'e taşımayın. Blok bitince kullanılamaz.

## Aynı Komutun Tekrarı

Önce migration aracınızla `PostgresIdempotency.SCHEMA` ve `EXPIRY_INDEX` tanımlarını uygulayın.
Yardımcı nesneyi bir kez oluşturun. Scope, doğrulanmış müşteri/tenant kimliğini ve işlem sürümünü içersin.
Anahtar `Idempotency-Key` başlığından veya mevcut `requestId` alanından gelebilir.

```java
var commands = new PostgresIdempotency(transactions, 2, 86_400_000, 65_536);
byte[] response = commands.execute("tenant-42:confirm-order:v1", requestId, sha256OfCanonicalInput,
    session -> {
        session.update("update orders set status='confirmed' where id=?",
            statement -> statement.setLong(1, orderId));
        return responseJson;
    });
```

İş değişikliği ve yanıt aynı transaction'da kaydedilir. Aynı anahtar ve aynı içerik tekrar gelirse
kayıtlı yanıt döner. İçerik farklıysa `KeyConflictException` oluşur. HTTP tarafında 409 döndürün.
Devam eden komutu bekleme süresi SQL timeout'u ile sınırlıdır. Gizli tekrar yapılmaz.

Bu bloktan ödeme servisi çağırıp dağıtık sistemde “tam bir kez” garantisi vermeyin.
Commit sonucu belirsizse aynı anahtarı kullanın. Yeni anahtar yeni işlem demektir.
Saklama süresi dolunca aynı anahtar yeniden çalışabilir. Mevcut bakım scheduler'ınızdan
`deleteExpired(1000)` çağırın. TTL kaydı kendiliğinden silmez. DB boyutunu izleyin.

## Cache Yenileme Bildirimi

`PostgresRefreshOutbox` isteğe bağlıdır. Şemasını oluşturun ve sabit projection adlarını tanımlayın.
İş transaction'ında `enqueue(session, "customer.detail")` çağırın.
Mevcut scheduler `drain(16, projection -> refreshAndPublish(projection))` çalıştırsın.
Yalnız snapshot başarıyla yayımlandığında true döndürün.

Redis işlemi DB transaction'ının dışında yapılır. Yenileme başarısızsa bildirim kalır.
Yeni bir bildirim gelmişse eski işlemin onayı onu silemez. İki replica aynı yenilemeyi deneyebilir.
Mevcut projection lock ve fenced publish mekanizmasını koruyun. Bu yapı DB ile Redis'i tek
transaction yapmaz. Her projection bağımsızdır. Bildirimleri silmek için yetkili bir DB bağlantısı
kullanın; read-only bağlantı ile onay verilemez.

## Büyük Sonuç

```java
transactions.execute(JdbcTransactions.Options.read(3), session ->
    session.forEach("select id,name from customer order by id", JdbcSession.Binder.NONE,
        CustomerJdbcMapper::map, 128,
        customer -> { sink.write(customer); return !sink.cancelled(); }));
```

Tüm kayıtlar bir listeye toplanmaz. İptalde veya hatada cursor kapanır.
Yavaş HTTP istemcisi için DB bağlantısını sınırsız süre tutmayın. Route limiti ve sınırlı bir
stream akışı kullanın. HTTP başlıkları gönderildikten sonra hata olursa ikinci bir JSON yanıtı
gönderilemez. JDBC yardımcısı HTTP akışını sizin yerinize tasarlamaz.

## Etkin Ayarlar

```java
var publicKeys = Set.of("reactor.runtime.profile", "reactor.rust.jni.workers");
var report = PropertiesLoader.effective(publicKeys, publicKeys);
```

Raporda değerin `-D`, ortam değişkeni, uygulama dosyası, runtime profili veya varsayılan kaynaktan
geldiği görünür. Yalnız izin verdiğiniz anahtarların değeri gösterilir. Parola benzeri alanlar ve
URL'ler yine gizlenir. Raporu internete açık endpoint'e koymayın. Rapor ayarları değiştirmez.

`ConfigurationChecks.unknownKeys`, yalnız belirttiğiniz uygulama önekini kontrol eder.
`ConfigurationChecks.lessThan`, aynı birimde iki pozitif bütçeyi karşılaştırır.
Örneğin kuyruk süresini RPC timeout ile karşılaştırabilirsiniz.
Bu kontrolleri başlangıçta çağırın. Tüm kütüphane ayarları otomatik keşfedilmez.

## Sözleşme Kontrolü

Derleme, public record, enum ve interface'ler için `META-INF/reactor/contracts.tsv` üretir.
Önceki artifact'tan bu dosyayı çıkarıp referans olarak saklayın.

```powershell
mvn clean verify reactor:verify-contract -Dreactor.contract.baseline=contracts/previous.tsv
```

Mevcut tanım değişirse veya silinirse kontrol durur. Yeni tip eklenebilir.
JSON, Hessian ve cache uyumu aynı şey değildir. Eski/yeni consumer-provider testlerini ayrıca yapın.
İmzaların eşleşmesi tek başına iş davranışının aynı olduğunu kanıtlamaz.
Silinen kaynaklardan eski manifest kalmaması için temiz derleme yapın.
Uygulama sürümü Maven'den üretilir; annotation içinde ayrı sample sürümü tutmayın.

## İki Uygulamalı Örnek

```powershell
java -cp rust-java-rest-4.7.0-codegen.jar com.reactor.rust.codegen.ScenarioGenerator cache my-cache
java -cp rust-java-rest-4.7.0-codegen.jar com.reactor.rust.codegen.ScenarioGenerator dubbo my-dubbo
```

Platformla aynı sürümdeki codegen JAR'ını kullanın.
Yeni dizinde iki Maven uygulaması, Dockerfile'lar, Compose, iki dilde açıklama ve GET kontrol betiği bulunur.
Dubbo örneğinde ortak interface modülü de oluşur. Mevcut dizinin üzerine yazılmaz.
Cache örneği küçük bir hazır snapshot üretir. Bu örnek DB performans testi değildir.

## Hata Testleri

`Eventually.await` yalnız hazırlık kontrolü veya güvenli GET tekrarları içindir.
Her kontrolün kendi bağlantı timeout'u olmalıdır. Yardımcı sınıf sınırsız bloklanan kodu kesmez.
Komut tekrarını aynı anahtarla test edin. Her denemede yeni komut üretmeyin.
DB rollback ve Redis/provider restart testlerini gerçek yerel servislerle ayrıca yapın.
HTTP mock testi native reconnect veya DB transaction davranışını kanıtlamaz.

Yük testinde HTTP 200 yeterli değildir. Yanıtın beklenen iş verisini içerdiğini de kontrol edin.
Hata gövdelerini, reddedilen istekleri ve bağlantı hatalarını başarılı RPS hesabına katmayın.

Bir `@Bean` metodu kütüphanedeki `HealthEndpoint` sınıfını dönebilir. Bu sınıfın `/app/health`,
`/app/liveness` ve `/app/readiness` yolları derleme sırasında route listesine eklenir.
Çalışma zamanında yeni bir sınıf taraması yapılmaz. Readiness, bağımlılıkları istek geldiğinde kontrol
eder. Arka planda sürekli kontrol yapan bir thread başlatmaz.

Native primitive çağrı düzeltmesi için DLL/SO yeniden üretilmelidir. ABI 29 olarak kalır.
Ayrı entegrasyon testi int, long, boolean, double ve short değerlerini gerçek HTTP üzerinden kontrol
eder. Ardından callback hatası ve geçersiz yanıt uzunluğu üretir. Hatalı çağrı 500 dönmelidir.
Aynı worker sonraki doğru isteği işleyebilmelidir. Testi ayrı bir JVM'de çalıştırın:

```powershell
mvn test -Dtest=NativePrimitiveHttpTest -Dreactor.test.native-http=true -Drust.lib.path=C:/native/rust_hyper.dll
```

Linux'ta ilgili `.so` dosyasının yolunu kullanın. Commit edilmemiş kaynaklardan üretilen geliştirme
binary'si test içindir. Release artifact'i değildir. Yayından önce temiz kaynak ve provenance
kontrollerini koruyun.
