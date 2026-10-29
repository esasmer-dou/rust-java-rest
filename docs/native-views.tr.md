# Ödünç Alınan Native Veri Görünümleri

[English](native-views.md) | [Turkish](native-views.tr.md)

Bu API güncel kaynak kodda bulunur. Yayımlanmış `4.8.0` paketinde bulunmaz.
Yayın öncesinde container RSS, RPS ve p99 kontrolleri tamamlanmalıdır.

Tahsisat testleri önce küçük ve bellekte tutulan nesnelerle sayacı kontrol eder.
Sayaç bu nesneleri gecikmeli toplu değerlerle gösteriyorsa ölçüm atlanır. Başarılı
tahsisat testi sayılmaz. Bu yüzden ölçüm, kalibre edilmiş Java 21 HotSpot üzerinde
de doğrulanır. Production için OpenJ9 seçimi değişmez. Bu kontrol RSS kabulü değildir.

Çalışan Rust request-buffer örneği için sample reader'ın ayrı
[`native-view` profilini](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/NATIVE_VIEW.tr.md)
inceleyin. Üretilmiş binary yazıcıyı, senkron okuma ömrünü ve kapasite tekrarını gösterir.
Normal Redis örneği ayrı kalır. Bu eklemeler henüz kaynak kod deposundadır.

## Hangi Modeli Seçmeliyim?

| İhtiyaç | Seçim |
| --- | --- |
| Normal request doğrulaması ve iş verisi | Record DTO |
| Java'nın incelemesi gerekmeyen hazır JSON | `RawResponse` veya native response yolu |
| Mevcut binary buffer içinden DTO oluşturmadan alan okumak | `@NativeView` |
| İş değerlerinden doğrudan JSON üretmek | Producer veya üretilen direct JSON writer |

View, belirli bir binary düzeni okur. Herhangi bir JSON veya Hessian verisini otomatik
çözmez. HTTP endpoint'lerini, Dubbo sözleşmelerini ve cache sonuçlarını değiştirmez.

`META-INF/reactor/contracts.tsv` dosyası, şema sürümünü ve her alanın index/ofset
değerini kaydeder. Varsayılan anotasyon değerleri de kayda girer. Getter imzası aynı
kalsa bile bu değerlerden biri değişirse sözleşme kaydı değişir. Yalnızca kaynak
koddaki metot sırasını değiştirmek ise kaydı değiştirmez. Enum alanları için sabitlerin
sırası da kayda girer. Başka bir sınıfın içindeki enum'lar da kapsanır. Enum sırası
değişirse binary değerin anlamı değişebileceği için sözleşme de değişir. Binary düzeni değiştirmeden
önce veriyi üreten ve okuyan uygulamaların manifest dosyalarını karşılaştırın. Bu
kontrol yapısal uyumu gösterir. Alanların iş anlamının aynı olduğunu tek başına kanıtlamaz.

## Bir View Tanımlayın

```java
package example;

import com.reactor.rust.view.NativeView;
import com.reactor.rust.view.Utf8Slice;
import com.reactor.rust.view.ViewField;

@NativeView
public interface AccountView {
    @ViewField(index = 0) long id();
    @ViewField(index = 1) Utf8Slice code();
    @ViewField(index = 2) long balanceCents();
    @ViewField(index = 3) boolean active();
}
```

Framework'ün normal annotation processor ayarlarını koruyun. Derleme, aynı pakette
`AccountView_NativeView` ve `AccountView_NativeWriter` sınıflarını üretir.

## Yazın, Okuyun ve Kapatın

```java
import com.reactor.rust.view.Utf8Slice;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

ByteBuffer storage = ByteBuffer.allocateDirect(256);
ByteBuffer json = ByteBuffer.allocateDirect(256);

// Buffer'ları her HTTP isteğinde değil, seçtiğiniz sahip için bir kez oluşturun.
try (var code = new Utf8Slice().reset(
        ByteBuffer.wrap("ACC-42".getBytes(StandardCharsets.UTF_8)), 0, 6)) {
    int size = AccountView_NativeWriter.write(storage, 0, 42L, code, 1299L, true);
    try (var account = AccountView_NativeView.threadLocalView(storage, 0, size)) {
        long id = account.id();
        boolean matches = account.code().contentEqualsAscii("ACC-42");
        json.clear();
        int bytesWritten = AccountView_NativeWriter.writeJson(account, json);
        // Çıktı buffer'ını yeniden kullanmadan önce json[0..bytesWritten) verisini tüketin.
    }
}
```

Binary writer; buffer konumunu, limitini ve byte sırasını değiştirmez. JSON writer,
çıktı konumunu ilerletir. JSON yazımı başarısız olursa kısmi çıktıyı kullanmayın.

## Verinin Ömrünü Koruyun

View, belleği ödünç alır. Native response'un sahibi değildir. Native verinin ömrünü
uzatmaz. View kapanana kadar native sahibi açık tutun ve veriyi değiştirmeyin.
Önce view'ı kapatın. Sonra native kaynağı bırakın. View, alt view veya slice değerini
bir sınıf alanında ya da daha sonra çalışacak async işlemde saklamayın.

Thread-local view aynı anda bir kullanıma izin verir. İç içe ikinci kullanım, ilk
işlemin verisini bozmak yerine hata verir. Kapanışta buffer referansları temizlenir.
Girdi ve çıktı aynı belleği paylaşmamalıdır. Buffer'ın `slice()` ve `duplicate()`
sonuçları da bu kurala dahildir. Framework bu paylaşımı her durumda algılayamaz.

Çok byte'lı alanlar big-endian düzenindedir. Göreli alan adresleri başlığın içine veya
kaydın dışına taşamaz. Aynı buffer'da birden çok kayıt varsa uzunluğu açıkça veren
üç parametreli metodu kullanın. Şema sürümü ve kayıt uzunluğu taşıma sözleşmesinin
parçasıdır. Binary writer bu bilgileri kayda otomatik eklemez.

## Hangi İşlem Nesne Oluşturur?

Tam sayı, boolean, enum ve UTF-8 alanları için aynı sarmalayıcılar yeniden kullanılabilir.
Tahsisat testi bu yolu ölçer. `materializeString()`, `toString()` ve alt dizi işlemleri
nesne oluşturur. Float/double biçimlendirmesi de nesne oluşturabilir. Bu yardımcılar,
her şema için sıfır tahsisat garantisi vermez.

View yalnız onu kullanan thread içinde kalmalıdır. Referans alanları null olamaz.
Generic şemalar, döngüler, derlemede erişilemeyen alt şemalar ve 256'dan fazla değeri
olan enum'lar derleme hatası verir.

## İsteğe Bağlı Toplu JSON Yazımı

Bu SWAR seçeneği yalnız kaynak kod deposunda bulunur; yeni bir Maven paketi olarak
henüz yayımlanmamıştır. Dar kapsamlı gerçek HTTP karşılaştırması 26 Ekim 2026'da
EmaTech'te geçti: dört yeni süreç çifti, 16/64/256 eşzamanlılık, kontrol ve literal
endpoint'leri, uygulama başına 1 CPU/128 MiB ve sıfır yanıt hatası.

Üretilen record writer'larında `-Areactor.json.swar=true`, önceden paketlenmiş sabit
öneklerin yazımını açar. Bu seçenek verilmezse mevcut byte tablosu kullanılır. JSON içeriği ve
buffer yetersiz olduğunda bildirilen gerekli uzunluk aynı kalır. Eski writer yoluna
runtime property kontrolü veya reflection eklenmez. Açık `false` değeri seçeneği
vermemekle aynıdır; diğer değerler derleme hatasıdır.

`rust-java-platform-parent` kullanan uygulamalarda A/B kolunu
`-Dreactor.json.swar=false` (varsayılan) veya `-Dreactor.json.swar=true` ile seçin.
**İşaretlenmiş DTO'nun bulunduğu artifact'i clean ile yeniden derleyin**; yalnız
onu kullanan uygulamayı derlemek yetmez. Uygulama başlarken verilen JVM `-D` değeri
önceden üretilmiş writer'ı değiştirmez. Bağımsız Maven projelerinde özelliği
annotation processor'a açıkça bağlayın:

```xml
<properties><reactor.json.swar>false</reactor.json.swar></properties>
<!-- maven-compiler-plugin/configuration icinde; diger argumanlari koruyun. -->
<compilerArgs><arg>-Areactor.json.swar=${reactor.json.swar}</arg></compilerArgs>
```

Bu processor yalnız `@GenerateDirectJsonWriter` ile işaretlenmiş record'larda çalışır.
Consumer örneğinin isteğe bağlı `direct-json-consumer` profilinde,
`/api/v1/catalog/literal` endpoint'inin döndürdüğü `SwarPayload` işaretlidir.
SWAR açıkken üretilen `SwarPayloadDirectJsonWriter` içinde `writePrebakedLiteral`
çağrılarını görebilirsiniz. Özellik, işaretlenmemiş DTO'ları veya hazır cache
JSON'unu kendiliğinden SWAR'a dönüştürmez.

Açık kolda seçim, alan adının karakter sayısına değil, `"field":` önekinin tamamının
UTF-8 byte uzunluğuna göre yapılır:

- **8..16 byte:** tek `writePrebakedLiteral(first, last, len)` çağrısı üretilir;
  bu önek için kalan byte'lar tabloda tutulmaz. Sabitler ilk ve son sekiz byte'ı,
  düşük byte önce olacak şekilde paketler. Örtüşen byte'lar aynı olmalıdır. Yeterli
  yer varsa önekin başına ve `start + len - 8` konumuna mutlak `ByteBuffer.putLong`
  yazımları yapılır; bu yazımlar örtüşebilir. Uzunluk 8 ise yalnız bir kez yazılır
  ve `last` kullanılmaz.
- **16 byte'tan uzun:** mevcut `writePrebakedLong` word yolu ve 0..7 byte'lık kalan
  parçalar için tablo yolu değişmez.
- **8 byte'tan kısa:** mevcut byte tablosu yolu değişmez.

UTF-8 dizisinin word sınırında bölünmesi byte'ları değiştirmez. Tam word yazımlarında
little-endian buffer için paketlenmiş word aynen, big-endian buffer için
`Long.reverseBytes(word)` sonucu kullanılır. VarHandle ByteBuffer view kullanılmaz;
istek başına word dizisi oluşturulmaz. Çağıranın byte order, position ve limit
değerleri korunur. Heap/direct buffer ve hizasız offset desteklenir.

Birleşik metot 8..16 dışındaki uzunlukları reddeder. Hem cursor hem gerekli-byte
sayacı için tam sayı toplaması, herhangi bir yazım veya sayaç güncellemesinden önce
denetlenir; taşmada `ArithmeticException` oluşur. Küçük buffer'da yalnız limitine
kadar byte byte yazan yol kullanılır, ancak önekin tam uzunluğu sayılır. Yer yetersizse
writer, değerler dahil tam gerekli uzunluğu negatif döndürür. Mevcut sınırlı
yanıt-yeniden-yazma sözleşmesini koruyun; kesilmiş JSON göndermeyin, serileştirmeyi
tekrarlamak için iş yan etkilerini yeniden çalıştırmayın.

Bu, varsayılanı kapalı bir derleme seçeneğidir; her endpoint için hızlanma vaadi değildir.
Sabitlerin açılması bytecode
ve JIT işi de ekler; DTO oluşturmayı, değer kaçışlamasını veya ondalıklı sayı
biçimlendirmesini kaldırmaz. Sıfır tahsisat veya her yerde hızlanma iddia edilmez.
Cache tarafındaki ayrı `Utf8JsonBuffer.writePrebakedLong(long)` byte-array VarHandle
API'si değişmez ve otomatik üreteç bağlantısı yoktur; birleşim örneği iki dilli
functional-operations rehberindedir.

### Dar Doğrulama

Eşleşen bağımlılıklar kurulduktan sonra workspace kökünden:

```sh
mvn -B -ntp -f rust-java-rest/pom.xml -Dtest=SwarJsonWriteTest,SwarJsonCodegenTest,JsonBufferWriterTest,ReactorCodegenProcessorsTest test
mvn -B -ntp -f java-rust-cache/pom.xml -Dtest=SwarJsonWriteTest,Utf8JsonWriterTest,Utf8JsonBufferResetTest test
```

Bu testler üretilmiş writer'ları, byte eşitliğini, iki derleme kolunu, hatalı seçenekleri,
birleşik yolun tüm uzunluk ve kesilme noktalarını, hizalamayı, byte sırasını,
salt okunur buffer'ları, tam sayı taşmasını, Unicode'u, iç içe/null değerleri,
sınırlı büyümeyi ve gerekli uzunluğu kapsar. Gerçek route RPS/p99/RSS kabulü değildir.
Bunlar yük testinden ayrı doğruluk kontrolleridir. EmaTech'teki gerçek HTTP
matrisinin altı hücresi de geçti: RPS -%2, p99 +%10 ve kararlı RSS/container
farkı +3 MiB sınırlarında kaldı. Literal endpoint'inde RPS değişimi -%0,48 ile
+%1,59 arasındaydı; kararlı RSS 0,54–1,38 MiB arttı. Bu sonuç bellek azalması
değil, sınırlı ek maliyet gösterir. Açmadan önce kendi DTO'larınızı ölçün.
Birleşik ürün ve platform dağıtım doğrulaması ayrı takip edilir.
