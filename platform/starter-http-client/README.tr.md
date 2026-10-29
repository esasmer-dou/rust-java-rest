# Sınırlı HTTP İstemcisi ve Devre Kesici

[English](README.md) | [Turkish](README.tr.md)

Devre kesici ve isteğe bağlı native HTTP taşıması henüz yayımlanmayan kaynak kodda
bulunur. Varsayılan taşıma `jdk` olarak kalır. Native artifact **yalnız HTTP/1
destekler; HTTPS desteklenmez ve açıkça reddedilir**, sessizce HTTP'ye düşürülmez.

## Yavaşlayan Bağımlılığı Sınırlayın

```java
package example;

import com.reactor.rust.annotations.PathVariable;
import com.reactor.rust.http.client.HttpCircuitBreaker;
import com.reactor.rust.http.client.HttpExchange;
import com.reactor.rust.http.client.ReactorHttpClient;
import java.util.concurrent.CompletionStage;

@ReactorHttpClient(baseUrlProperty = "app.stock.base-url")
public interface StockClient {
    @HttpExchange(path = "/stock/{id}", timeoutMs = 500, retries = 0, idempotent = true)
    @HttpCircuitBreaker(windowSize = 64, minimumCalls = 16,
            failureRateThreshold = 50, openDurationMs = 10_000, halfOpenMaxCalls = 2)
    CompletionStage<String> stock(@PathVariable("id") long id);
}
```

```properties
app.stock.base-url=http://stock-service:8080
```

Normal framework derlemesi istemciyi üretir. Dynamic proxy oluşturulmaz. Buradaki
`String`, yanıtın ham metnidir. İş kodunuz tipli JSON değerleri okuyacaksa normal
üretilen DTO eşlemesini kullanın.

| Ayar | Etkisi |
| --- | --- |
| `windowSize=64` | Tamamlanan son 64 mantıksal çağrının sonucunu tutar |
| `minimumCalls=16` | 16 sonuç oluşmadan devreyi açmaz |
| `failureRateThreshold=50` | Penceredeki sonuçların en az yarısı hatalıysa devreyi açar |
| `openDurationMs=10000` | Bu sürede soket açmadan çağrıyı reddeder |
| `halfOpenMaxCalls=2` | En fazla iki iyileşme denemesi kabul eder; ikisi de başarılı olmalıdır |
| `record5xxAsFailure=true` | Son HTTP 5xx yanıtlarını bağımlılık hatası sayar |

Ağ ve decode hataları hata sayılır. Normal bir HTTP 4xx yanıtı ağ hatası değildir.
`HttpClientResponse<T>` dönüşüyle durum kodunu okuyabilirsiniz. Ancak ayar açıksa
son 5xx yanıtı yine hata sayılır. Metot annotation'ı, arayüz ayarının tamamını
değiştirir. Alanları tek tek devralmaz.

Decoder `RuntimeException` veya `Error` fırlatırsa çağrı hatayla tamamlanır.
Devre kesici başarısız sonucu kaydeder. Çağıran taraf sonsuza kadar beklemez;
yarı açık devredeki toparlanma izni elde tutulmaz.

Native çağrı, devre kesici sonucunu mevcut tamamlama sınırında kaydeder.
İkinci bir gözlemci future veya continuation eklemez. İptal ile tamamlanma tek
sonuç için yarışır; geç gelen yanıt iptali geri alamaz. Annotation olmayan çağrı,
korumalı çağrının devre kesici kayıt alanlarını oluşturmaz.
Native çağrıda iptal, kaynak tamamlama ve devre sonucu tek atomik durum alanında
tutulur. Bu işaretler için ayrı nesneler oluşturulmaz.

Korumalı native metot `CompletionStage<T>` veya `CompletionStage<List<T>>`
döndürüyorsa sonuç doğrudan çözümlenen body ile tamamlanır. Arada
`HttpClientResponse` ve yalnız body ayırmak için ek future oluşturulmaz.
Durum kodu, header limitleri, retry ve hata kuralları yine kontrol edilir.
İş kodunuzda durum kodu veya header gerekiyorsa
`CompletionStage<HttpClientResponse<T>>` kullanın. Java metot imzası ve
annotation kullanımı değişmez.

Yalnız body döndüren korumalı çağrıda kullanılmayan Java header metinleri,
map ve listeleri de oluşturulmaz. Rust, HTTP header'larını ve limitlerini
doğrulamaya devam eder. Java da her alanın uzunluğunu ve tamamlama frame'ini
kontrol eder. Tam response API'si bütün header'ları korur. Body byte'ları hâlâ
JNI üzerinden taşınır; uçtan uca zero-copy iddiası yoktur.

## Sabit Çağrıyı Her İstekte Yeniden Hazırlamayın

Parametresiz üretilmiş bir metotta native istemci URI'yi ve sabit başlıkları
başlangıçta bir kez hazırlar. Örneğin aşağıdaki metot için ek kod veya ayar gerekmez:

```java
@HttpExchange(path = "/stock/summary", timeoutMs = 500, retries = 0)
@HttpCircuitBreaker
CompletionStage<byte[]> summary();
```

Bu, yanıt cache'i değildir. Her çağrı stok servisine gider. Request, future,
devre izni ve yanıt gövdesi çağrıya özeldir. Aynı hazırlık, devre kesici olmayan
parametresiz native metotlarda da kullanılır. Saklanan tanım sayısı üretilmiş
metot sayısıyla sınırlıdır; istek verisiyle büyüyen bir cache yoktur.

Path, query, header veya body parametresi olan metotlar mevcut dinamik yoldadır.
`OutboundHeaderProvider` varsa başlıklar her çağrıda çağıran thread'de hazırlanır.
Kullanıcı kimliği veya `traceparent` başlangıçta sabitlenmez. Hazırlanmış Request
değiştirilirse de dinamik hazırlık kullanılır. Geçersiz sabit URI veya başlık,
native istemci kurulurken reddedilir. JDK taşımasının mevcut gönderim yolu korunur.

## Hata ve Retry Kuralları

Devre açıkken tek bir ortak, stackless `HttpCircuitOpenException` kullanılır. Ortak
REST hata eşlemesi `503` döner. Bağımlılık adresi dışarıya verilmez. Reddedilen çağrı
soket açmaz. Devre kesici için kuyruk, scheduler veya bakım thread'i eklenmez.

Mevcut retry kuralı tamamlandıktan sonra **tek nihai sonuç** kaydedilir. Devre kesici
retry eklemez. Bir yazma işlemini idempotent yapmaz. Örnekte bu yüzden `retries=0`
kullanılır. Gerçek idempotency sözleşmesi olmadan ödeme veya sipariş yazımına retry
açmayın. JDK iptalinde mevcut timeout/drain davranışı korunur. Native iptal, üretilen
devre kesici sarmalayıcılarından da I/O'ya aktarılır. Native onay ve tamamlama işi
gerçekten bitmeden kapasite yeniden açılmaz.

Annotation olmayan metotlar mevcut üretilen yolu kullanır. Devre kesici alanı veya
kontrolü eklenmez. Durum, sonuç penceresi ve deneme sayısı sınırlıdır. Kısa tahsisat
ve süre testleri, container RPS/RSS garantisi değildir.

Tahsisat testleri küçük ve bellekte tutulan nesnelerle sayacı da doğrular. Sayaç
gecikmeli toplu değer gösteriyorsa ölçüm atlanır. Yanlış bir sıfır değeri başarı
sayılmaz. Kalibre edilmiş Java 21 HotSpot ölçümü, production JVM seçimini değiştirmez.

## Trace Aktarımı

Giden başlıklar çağıran thread'de bir kez hazırlanır. Retry aynı request'i ve
traceparent'ı kullanır. Başka thread'in bağlamı alınmaz. Tracing starter,
`W3cTraceparent.write(context, target, offset)` ile size ait 55 byte'lık buffer'a
yazabilir. Geçerli kimliklerde bu yazım nesne oluşturmaz. Ayrıştırma ise iki kimliği
oluşturur. `TraceSpan.parentSpanId()`, gelen uzak parent bilgisini korur. Bu değişiklik,
JDK HTTP başlıklarını native zero-copy transport'a dönüştürmez.

## Taşımayı Başlangıçta Seçin

```properties
# Varsayılan; native runtime, kuyruk, worker veya adapter oluşturulmaz.
reactor.http-client.transport=jdk

# DLL/SO, outbound-http Cargo özelliğiyle üretilmiş olmalıdır.
# reactor.http-client.transport=rust-native
```

Özel artifact için mutlak `-Drust.lib.path=C:/native/rust_hyper.dll` veya SO yolunu
kullanın. Mevcut `NativeLibraryLoader` kullanılır. Eksik JNI sembolü uygulama
başlangıcını açık bir özellik mesajıyla durdurur; JDK'ya sessiz dönüş yoktur. REST
ABI 29 korunur, ek outbound API sürümü 1'dir. Kaynak kod özelliği ve artifact
gereksinimleri [native derleme kılavuzundadır](NATIVE-INTEGRATION.tr.md).

Üretilen istemci, oluşturulurken taban adresini bağlar. Native seçiminde HTTPS,
userinfo ve fazla origin, gönderimden önce reddedilir. İstemciyi her istekte değil,
başlangıçta bir kez bağlayın. Doğrulanmış TLS, HTTP/2, proxy, redirect, otomatik
cookie/authenticator, upgrade ve uygulamanın verdiği framing/hop-by-hop başlıkları
desteklenmez. Bunlar gerekiyorsa başlangıçta JDK seçin. **Tam production HTTPS
kapsamı sağlanmış değildir.**

Üretilmiş istemci factory'leri `@RequiresProperty` ve profil koşullarına
başlangıçta uyar. Kapalı istemci oluşturulmaz. Her istekte property aranmaz.
[Çalışan outbound örneği](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/OUTBOUND.tr.md)
normal ve devre kesicili istemciyi birlikte gösterir.

## Native Kaynak Sınırları

Aşağıdaki değerler native varsayılanlardır. Thread, eşzamanlı çağrı, gövde ve başlık
sayısı için açıkça verilen mevcut `reactor.http-client.*` sistem, ortam veya uygulama
ayarları önceliklidir. Framework dosyasındaki varsayılanlar, açık kullanıcı ayarı
sayılmaz.

| `reactor.http-client.` sonrasındaki ayar | Native varsayılan |
| --- | --- |
| `threads` | Sunucudan ayrı 1 Tokio worker |
| `native.completion-threads` | 1 Java worker; 256 KiB istenen stack; thread-local devralmaz |
| `max-inflight` | Generation kimliği taşıyan 8 sabit mantıksal slot |
| `max-request-bytes` / `max-response-bytes` | 16 KiB / 64 KiB |
| `max-headers` / `native.max-header-bytes` | 16 alan / istek veya yanıt başına 8 KiB |
| `native.byte-budget-bytes` | 1 MiB; runtime başına en fazla 3 MiB yapılandırılabilir |
| `native.max-connections` | Bağlanan, aktif ve saklanan toplam 4 bağlantı |
| `native.max-origins` / `native.max-idle-per-origin` | 8 origin / origin başına 1 boş bağlantı |
| `connect-timeout-ms` / `request-timeout-ms` | Deneme başına 1000 / 2000 ms |
| `native.idle-timeout-ms` / `shutdown-timeout-ms` | 30000 / 5000 ms |

Süreç genelinde en fazla **8 canlı native runtime** bulunur. Her mantıksal çağrı,
retry beklemesi ve Java tamamlaması boyunca
`4 * (maxRequest + maxResponse + 4 * maxHeaderBytes + 8192)` bayt ayırır. Her native
bağlantı ayrıca `2 * maxHeaderBytes + 16384` bayt ayırır. Varsayılan çağrı payı
491520 bayttır. Bu nedenle 1 MiB bütçe sekiz değil, en fazla iki böyle çağrı kabul
eder. Artırılan gövde sınırı tek çağrı payına sığmıyorsa başlangıç reddedilir.
Bağlantı payları kabulü daha erken de durdurabilir. Sınırsız bekleme kuyruğu yoktur.

Her runtime'da en fazla bir kuyrukta/çalışan OS DNS işi ve en fazla sekiz dönen adres
kabul edilir. İstek iptal olsa veya timeout alsa da DNS işi gerçekten bitene kadar
resolver izni ve `maxHeaderBytes + 8192` bayt payı tutulur. IP adresi DNS'e gitmez.
Meşgul resolver çağrıyı reddeder; Tokio'nun bloklayan kuyruğunda iptal edilmiş işler
birikmez.

Bu bütçe taşıma buffer'larını sınırlar; toplam RSS, kernel socket buffer'ları,
çağırana ait nesneler, mevcut ortak DTO encoder'ın geçici tahsisatları veya uygulama
decoder/continuation tahsisatları için toplam bellek sınırı değildir. String ham
UTF-8, byte dizisi ham byte olarak kalır; ayrıca JSON'a çevrilmez. 3 MiB taşıma
bütçesi, toplam RSS artışının en fazla 3 MiB olacağını garanti etmez. Üretim
bütçesini seçmeden önce kendi payload, decoder ve callback'lerinizi seçtiğiniz
JVM ve pod sınırlarıyla ölçün.

## Çekişme, İptal ve Kapanış

Origin bilgisi ve sabit boş bağlantı kuyruğu da başlangıçta byte bütçesine yazılır.
Rota limitiyle istemci limitini birlikte seçerken tamamlama payı bırakın. Örneğin
sekiz rota işlemi ve bir tamamlama worker'ı için dokuz mantıksal slot, sekiz socket
bağlantısı kullanılabilir. Future tamamlanınca sonraki rota başlayabilir; önceki
continuation henüz dönmemiş olabilir. Slotu erken bırakmak, hâlâ çalışan uygulama
işini bellek hesabından gizler.

Java kabul ve tamamlama yolunda CAS sayaçları ve sabit `AtomicReferenceArray`
kullanılır. JDK gövde yoluna istek başına transport seçimi, pending map veya Java
kilidi eklenmez. Native çağrı slotları ve iptal generation bilgisi atomiktir. Native
registry sabit sekiz `ArcSwapOption` slotundan oluşur. Her slot generation ile
denetlenir. Başlangıçta bağlanan her origin, sınırlı bir `ArrayQueue` içinde boş
bağlantılarını tutar. İstek, bağlantıyı alma/geri verme, iptal ve tamamlama yolu
registry veya havuz mutex'i almaz. Eski handle, aynı slotta yeniden açılmış
runtime'ı etkileyemez. Başlangıç kaydı ve kapatma kilitleri ayrı kalır. Bu ifade,
Tokio, Hyper, JVM ve allocator içindeki her işlemin lock-free olduğu anlamına gelmez.

Tamamlama başlıkları ile gövdesi JNI üzerinden ayrı geçer. Gövde için tek Java
`byte[]` oluşturulur. Gövdeyi içeren ikinci bir frame dizisi hazırlanmaz ve
Java'da yeniden gövde kopyası alınmaz. Her çağrının gövdesi kendisine aittir;
başka çağrıda tekrar kullanılmaz. Worker'ın tek slotlu metadata taşıyıcısı
yeniden kullanılır. Metadata başvurusu aynı turda silinir. Native rezervasyon,
JNI kopyaları bitmeden bırakılmaz. JNI tahsisatı hata verirse de native slot boşaltılır.
Bu yol tamamen zero-copy değildir; Rust gövdesi Java dizisine bir kez kopyalanır.
Eski `poll` sembolü ve frame biçimi korunur. Yeni kaynak seti, split completion
sembolü olan eşleşen native binary'yi ister. Eksik sembol başlangıçta hata verir;
sessiz JDK fallback yapılmaz. REST ABI 29 değişmez.

Tokio Java çağırmaz. Sınırlı Java tamamlama worker'ları sonucu çekip decoder ve
continuation çalıştırır. İptal, generation ile denetlenen sınırlı slotları kullanır.
Devrede hata yoksa başarılı sonuç, pencereyi tek atomik güncellemeyle ilerletir.
Sıfır olan halka slotları tekrar yazılmaz. Hata sonrası pencere ve generation
kuralları aynen uygulanır. Ek ayar, bellek havuzu veya zamanlayıcı gerekmez.
Tamamlama, "iş bitti" ve devre sonucu kaydı bitlerini aynı CAS ile alır.
İptal daha önce sonucu almışsa iptal kazanır. Aynı sonuç iki kez kaydedilmez.
Worker başlangıçta park edilir. Aktif akıştan sonra tek bir sınırlı native bekleme,
kısa boşlukları karşılar. Yanıt gelirse hemen uyanır; çağrıya sabit gecikme eklenmez.
İş gelmezse worker süresiz park edilir. Böylece idle durumda 50 ms'de bir JNI
sorgusu yapılmaz. Yeni çağrı ve kapatma için kayıp uyanma bariyeri korunur.
Spin döngüsü, timer veya yeni thread eklenmez. Callback dönmeden çağrı ve byte
rezervasyonu bırakılmaz.
Tamamlama onayı beklenirken I/O'nun kendi zaman aşımı korunur. Yalnız tamamen
okunmuş yanıtın bağlantısı havuza döner.
Socket izni, yalnız abort istendiğinde değil sürücü gerçekten bitince bırakılır.

Header provider çağıran thread'de bir kez çalışır; retry aynı request ve başlıkları
kullanır. GET/PUT/DELETE için mevcut idempotent sınıflaması korunur. POST varsayılan
olarak tekrar gönderilmez. Gerçek idempotency sözleşmesi olmayan yazmaya retry
açmayın. Native kütüphane olası teslimden sonra gizli reconnect/replay yapmaz.

Close yeni kabulü kapatır, aktif/bekleyen native I/O'yu iptal eder ve worker'ları
tanımlı süreye kadar boşaltır. Tamamlama worker'ının içinden close güvenlidir.
Bloklanmış uygulama kodu zorla öldürülemez; worker gerçekten bitene kadar runtime
payı tutulur. İptal edilemeyen işletim sistemi DNS işi de sınırlı shutdown worker'ında
tutulur ve süreç runtime payını bırakmaz.

## Gerçek Artifact'i Doğrulayın

`rust-java-rest` kaynak dizininde eşleşen gerçek native kütüphane yolunu verin:

```sh
mvn -f platform/starter-http-client/pom.xml -Dreactor.http-client.native-test-library=/absolute/path/librust_hyper.so test
```

Windows'ta mutlak DLL yolu kullanın. `reactor.http-client.native-test-library` verilmezse native
socket testleri atlanır; bu native başarı kanıtı değildir. Testler gerçek JNI,
loopback HTTP ve temiz alt JVM'nin gerçek sınıf yükleme loglarını kullanır. JDK
kontrolü gerçek istemcisini başlatmalıdır. Native süreçte JDK HTTP thread sayısı tam
sıfır olmalı; HttpClientImpl, HttpClientFacade ve SelectorManager yüklenmemelidir.
Bytecode kontrolü yerel Maven cache'indeki gerçek önceki 4.8.0 starter'ını gerektirir;
sahte transport kullanılmaz.
