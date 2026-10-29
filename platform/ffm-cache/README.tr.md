# İsteğe Bağlı Shared Slab Redis Okuyucusu

[English](README.md) | [Türkçe](README.tr.md)

Bu modül, her Redis okumasında Java tarafında payload `byte[]` dizisi oluşturmayı
kaldırır. Handler ve iş kararları Java'da kalır. Rust, GET işlemini yapar ve yanıtı
HTTP yazımı bitene kadar sınırlı native bellekte tutar. Varsayılan cache API'si
değişmez. Bu ekleme henüz yayımlanmadı; kaynak koddan derleme gerekir.
Yayımlanmış 4.8.0 paketinde bu yeni API yoktur.

## Hangi Okumayı Seçmeliyim?

| İhtiyaç | Seçim |
| --- | --- |
| Sürümlü projection veya atomik snapshot | Mevcut `RustCache` projection API'si |
| Ham JSON anahtarı, en fazla 64 KiB değer, doğrudan HTTP çıktısı | Bu isteğe bağlı okuyucu |
| Daha büyük veri, Java'da veri işleme veya preview kullanmama | Mevcut JNI API'si |

Sürümlü okumayı ham GET ile değiştirmeyin. Yayının tutarlılık garantisini
kaybedersiniz. Bu yol ek bir cache kurmaz. Her okuma Redis'e gider. Mevcut native
istemcinin bağlantı havuzu, timeout ve topoloji yönlendirmesi kullanılır.
Bu modülün odak testi standalone Redis içindir. Cluster/Sentinel canlı failover
kabulünün yerine geçmez.

`maxResponseBytes`, mevcut RESP **wire byte** sınırıdır. Yalnız değer uzunluğu
değildir.65536-byte sınırda beş basamaklı uzunluk ve protokol çerçevesi10 byte
kullanır. Bu biçimde en büyük değer65526 byte olur;65527 byte reddedilir.
64KiB slab kapasitesi protokol maliyetini kaldırmaz. Daha büyük wire bütçesi
için normal JNI okuyucusunu kullanın. Slab seçimi istemci sınırını gevşetmez.

## Başlangıç

Platformu açık `ffm-fast` profiliyle derleyin. `rust-java-ffm-fast` ve
`rust-java-ffm-cache` ayrı paketlerdir. Varsayılan core/BOM bunları taşımaz.
Native dosya aynı kaynak koddan üretilmelidir. OpenJ9 JNI slab için
`--release --features shared-slab` seçilir. Normal abort motoru korunur.
HotSpot FFM için `--profile release-ffm --features ffm-fast` kullanılır.
Abort derlemesinde FFM yeteneği sıfırdır; foreign çağrı etkinleşmez.
REST29/Dubbo7/SpringDubbo3/Redis6/Glowroot6 sözleşmeleri değişmez.

```xml
<dependency>
    <groupId>com.reactor</groupId>
    <artifactId>rust-java-ffm-cache</artifactId>
    <version>4.8.0</version>
</dependency>
```

Yeni sürüm yayımlanana kadar bu tanım yerel kaynak derlemesini gerektirir.
Eski yayımlanmış native dosyayla birlikte kullanmayın.

```text
java --enable-preview --enable-native-access=ALL-UNNAMED \
  -Drust.lib.path=/app/native/librust_hyper.so -jar application.jar
```

OpenJ9 21 için slab'ı JNI üzerinden açın. Bu JVM'nin FFM linker'ı yoğun çağrıda
scope nesnelerini tutuyor. Bu nedenle FFM, preview sınıfı yüklenmeden reddedilir.
JDK iç yapılarına reflection veya ThreadLocal temizleme müdahalesi yoktur.
İstek tekrar edilmez. Okuyucuyu başlangıçta bir kez açın:

```java
RustCacheConfig config = RustCacheConfig.builder()
        .host("redis").port(6379).accessMode("read-only")
        .readConnections(1).writeConnections(1)
        .maxReadInflight(1).maxWriteInflight(1)
        .maxResponseBytes(65_536).build();
SharedSlabCacheReader reader = SharedSlabCacheReader.openNativeSlab(config, true, 4, 256);
// Native slab zorunlu, 4 slot, anahtar en fazla 256 UTF-8 byte. Preview gerekmez.
```

Ayrıca doğrulanmış HotSpot Java 21 ortamında `open(config, true, true, 4, 256)`
FFM seçer. Yukarıdaki preview/native-access bayrakları bu seçim içindir.
`open(config, false, false, 4, 256)` normal payload dizili JNI okumasını seçer.
OpenJ9'da isteğe bağlı FFM başlangıçta normal JNI'ya döner. Zorunlu FFM başlangıcı
durdurur. `openNativeSlab`, OpenJ9 için açık slab seçimidir; gizli geri dönüş değildir.

Framework handler'ında mevcut sahiplikli yanıtı döndürün:

```java
return CacheResponses.ownedProblem(reader.read("catalog:stock"));
```

Import paketleri: `com.reactor.rust.cache.config.RustCacheConfig`,
`com.reactor.rust.ffm.cache.SharedSlabCacheReader`,
`com.reactor.rust.cache.http.CacheResponses`.
Yanıt yazıcısı snapshot'ın sahibi olur. Yazım bitince veya hata oluşunca onu
kapatır. Çıktı buffer'ı büyütülürse Redis yeniden okunmaz. Çıktı buffer'ı direct
olmalıdır. Bu yolda DTO üretmek veya JSON ayrıştırmak gerekmez.

HTTP yazıcısı dışında kullanıyorsanız snapshot'ı kendiniz kapatın:

```java
try (CacheReadSnapshot snapshot = reader.read("catalog:stock")) {
    // Hit/miss durumunu inceleyin. Veriyi snapshot API'siyle kendi direct buffer'ınıza yazın.
}
```

`CacheReadSnapshot`, `com.reactor.rust.cache.api` paketindedir. Açık snapshot'ı
terk etmeyin. Her snapshot için Cleaner yoktur. Bırakılmayan lease, sınırlı slab'ı
doldurabilir ve süreç yeniden başlayana kadar kapasiteyi tüketebilir.
Sahipliği bir kez devredin veya try-with-resources kullanın.

## Ayarlar Ve Sınırlar

| Değer | Davranış |
| --- | --- |
| `requested=false` | JNI seçilir; linker veya slab açılmaz |
| `requested=true, required=false` | Başlangıçta FFM denenir; uygun değilse ilk GET'ten önce JNI seçilir |
| `requested=true, required=true` | Bayrak, dosya veya istemci sınırı uygun değilse başlangıç durur |
| `slots=1..32` | Süreç için sabit kapasite; her slot 64 KiB |
| `maxKeyBytes=1..4096` | UTF-8 anahtar sınırı; bozuk UTF-16 reddedilir |
| `maxResponseBytes=1..65536` | FFM gereksinimi; daha yüksek sınırda JNI seçilir veya zorunlu FFM başlangıcı durur |

`openNativeSlab(config, required, slots, maxKeyBytes)` aynı sınırları ve native
sahibi kullanır. FFM yerine ek JNI girişlerini çağırır. Eski native dosya
başlangıçta reddedilir. `required=true` ise uygulama başlamaz. `slabActive()`
gerçek slab kullanımını, `ffmActive()` ise FFM kullanımını gösterir.

Seçim class loader başına bir kez yapılır ve native client ID'ye bağlanır.
Süreç yaşam döngüsünde tek okuyucu kullanın. Aktif FFM sonradan JNI'ya dönmez.
GET başladıktan sonra BUSY, timeout, büyük veri veya native hata
`FfmReadException` olarak bildirilir. İşlem JNI ile tekrarlanmaz. Uygulama bunu
örneğin 503 yanıtına çevirebilir. Bulunamayan anahtar ile boş değer ayrıdır.
Mevcut istemcinin kendi bağlantı/topoloji toparlama kuralları korunur. Bu katman
yeni retry eklemez. Blocking GET sınırlı worker üzerinde çalışmalıdır.
Hyper/Tokio event loop üzerinde çalıştırmayın.

## Bellek Ve Kapatma

Dört slot 256 KiB payload ayırır. Kontrol tablosu 4096 byte'dır. JNI, lease sonucu
için yalnız `slots * 8` direct byte kullanır. Mevcut String doğrudan JNI'a verilir.
Rust, UTF-16 doğrulamasını ve sınırlı UTF-8 dönüşümünü ağ işlemi başlamadan stack
alanında yapar. JNI yolunda Java anahtar buffer'ı, anahtar sarmalayıcısı veya
karakter başına Java kodlama döngüsü yoktur. FFM yolunun girdi buffer'ları
`slots * (maxKeyBytes + 8)` direct byte kullanmayı sürdürür.

Tek sunuculu Redis'te küçük ve büyük yanıt doğrudan kendi slotuna okunur.
Geçici payload `Vec` ve `Vec → slab` kopyası oluşmaz. Küçük yanıtta mevcut gövde
ve CRLF ağ okuma biçimi korunur. Bu seçim yeni bir yanıt limiti değildir.
Java payload dizisi de oluşturmaz.
Sentinel/Cluster mevcut yönlendirme
ve toparlama yolunu kullanır; bu yolda geçici yanıt `Vec` kalır. Yol değiştirmek
için GET tekrarlanmaz. Anahtarın slot içinde tutulduğu eski C girişi de güvenli
kopyalama yolunu korur. Diğer işlemlerin native buffer'ları,
FFM/JVM sınıfları, HTTP buffer'ları ve Java nesneleri de bütçeye dahildir.
Snapshot ve segment nesneleri hâlâ oluşur. Bu, uçtan uca sıfır allocation veya
sıfır kopya değildir. Kaldırılan maliyet Java payload dizisidir.

Runtime, hazır UTF-8 JSON başlığını yeniden kullanır. En az 16 KiB olan gövdelerin
sahiplikli frame yolunda geçici başlık listesi oluşturmaz. Bu işlem yanıtınızı
cache'lemez. Her istekte handler
ve GET çalışmayı sürdürür. Özel başlıkların davranışı değişmez. Başlık hazırlığı
için JVM, heap veya profil ayarı değiştirmeniz gerekmez.

Uygulama kapanırken okuyucuyu kapatın. Yeni okumalar reddedilir. Başlamış okumalar
bitince native istemci kapanır. Daha önce dönen snapshot, kapatılana kadar geçerli
kalır. Girdi wrapper'ları bırakılır. Slab süreç ömürlüdür; `close()` slab belleğini
OS'a vermez. Zorla GC veya güvenli olmayan unload yapılmaz.

Çalışan ayrı örnek, cache reader projesinin `shared-slab` profilindedir.
Ortam değişkenleri ve başlangıç için [rehbere](../../../rest-sample-cache-reader/SHARED_SLAB.tr.md)
bakın. Varsayılan reader JNI kullanmayı sürdürür.
