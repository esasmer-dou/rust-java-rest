# İsteğe Bağlı Java 21 Preview FFM Köprüsü

[English](README.md) | [Türkçe](README.tr.md)

Bu modül deneysel bir prototiptir. Hızlı yol olarak kabul edilmiş değildir.
Normal JNI akışı ve REST29/Dubbo7/Redis6/Glowroot6 ABI sabitleri değişmez.
Varsayılan core bağımlılığına preview sınıfı veya bayrağı eklenmez. Devre dışıyken
core yoluna kontrol, native yükleme, thread veya allocation eklenmez.

## Güncel Kaynak Entegrasyonu

Ayrı [shared slab cache katmanı](../ffm-cache/README.tr.md), mevcut sahiplikli HTTP
yanıtına bağlandı. Cache-reader `shared-slab` profili, Java payload dizisi
oluşturmadan ham Redis GET örneği sunar. Varsayılan core/cache yolu değişmez.
Bu yol sürümlü atomik snapshot okuması değildir.

26 Ekim yük testinde OpenJ9 Java21 FFM downcall scope tablosunda birikim bulundu.
PHD histogramı ve kullanılan JVM sınıfının bytecode çıktısı, bırakılmayan
HeapArgInfo kayıtlarını doğruladı. Bu JVM artık PanamaAccess veya linker
yüklenmeden reddedilir. JDK iç yapılarına müdahale etmeyin; heap'i büyüterek
sorunu gizlemeyin. OpenJ9 için `SharedSlabCacheReader.openNativeSlab` kullanın.
Bu açık JNI seçimi aynı Rust slab protokolünü kullanır. Uygun HotSpot için FFM
ayrı seçenek olarak kalır.

Düzeltilmiş Linux derlemesi fmt/clippy ve184 Rust testini geçti. EmaTech OpenJ9
üzerinde sekiz başlangıç/cache testi geçti. JNI slab,48MiB heap ile150bin okumayı
tamamladı. HotSpot18 test geçti; gerçek FFM ve JNI slab ayrı ayrı150bin okuma
yaptı. Kalibre edilmiş leaf testleri tam sıfır ölçtü. Snapshot, segment ve Redis
akışının bütünü sıfır allocation değildir. ABI29/7/6/6 aynı.17 C sembolü ve dört
JNI slab girişi doğrulandı; eski112 JNI sembolü korundu. GLIBC üst gereksinimi
2.14'tür. OpenJ9 HTTP A/B, JNI ile JNI slab'ı karşılaştırır; FFM ölçümü değildir.

26 Ekim 2026 tarihli odak testinin Linux JNI slab (normal abort) SHA256 değeri:
`0c68e2e417e3be7949904f783e9db4948f120ab06c6851e132ee5c7c1120f8b5`.
O tarihli ayrı Linux FFM (unwind) dosyası:
`9d6f7e5a648fb7519c22d74ea6e527c35357cc4dda8526eb9ea09f54f9abff56`.
O aşamada varsayılan native kaynak değiştirilmemiş, paket yayımlanmamış ve Windows
dağıtımı doğrulanmamıştı. Bu hash/sonuçlar tarihsel test dosyalarıdır; 29 Ekim
koordineli yayım manifesti değildir. Java modülleri artık 4.8.0 ile yayımlanmıştır;
bu, Windows HotSpot FFM kabulü veya ayrı unwind binary'nin yerini alma anlamına
gelmez. Eski hash yerine yayım provenance'ını ve hedef platform kabulünü kullanın.

## Açık Etkinleştirme

Cargo `ffm-fast = ["redis", "glowroot"]` özelliği varsayılan değildir.
`release-ffm` profili release profilini devralır ve `panic = "unwind"` kullanır.
Normal release profili `panic = "abort"` olarak kalır. `catch_unwind`, abort
profilinde koruma sağlamaz. Özel alt modül `redis_cache.rs` içinde unwind FFM
veya açık `shared-slab` JNI koşuluyla derlenir.
Platform Maven `-Pffm-fast` profili yalnızca ayrı `ffm-fast` ve `ffm-cache` modüllerini ekler.

Ayrı `shared-slab = ["redis"]` Cargo özelliği, normal abort motorda JNI slab
sahipliğini açar. Modül koşulu artık
`any(feature="shared-slab", all(feature="ffm-fast", panic="unwind"))` olur.
Abort derlemesinde FFM yeteneği sıfır kalır. Foreign seçim slab ayırmadan veya
I/O yapmadan reddedilir. JNI mevcut motorun abort panic kuralını korur.
Aktif FFM yalnız ayrı unwind dosyasında sunulur. Süreç başına tek native dosya
seçin. Taşıyıcı değiştirmek için iki dosyayı aynı JVM'ye yüklemeyin.

Java 21 için açık `--enable-preview --enable-native-access=ALL-UNNAMED` gerekir.
Adlandırılmış modülde gerçek modül adı kullanılır. Java 21'in yalnızca uyarı
vererek native erişime izin vermesi yeterli değildir. Yerel kütüphane mevcut
uygulama yaşam döngüsünde önceden yüklenir. Bu modül kütüphane yüklemez.
OpenJ9 üzerinde FFM desteği yoksa seçim ilk işlemden önce reddedilir.
Giriş sınıfının class-file minor sürümü 0'dır. JVM preview yüklemesini denetler.
Preview factory sıradan `Calls` arayüzünü döndürür. Linker oluşturulmadan önce
`Module.isNativeAccessEnabled()` denetlenir. Köprü uyarı vererek örtük native
erişim istemez. ManagementFactory, JMX başlatma veya argüman ayrıştırma yoktur.
Modül denetimi mevcut izni ölçer; önceden başka bir restricted çağrıyla açılmış
izin ile launch flag kaynağını ayırmaz. FFM/linker başlangıç sınıfları RSS
ölçümüne dahildir.

Redis için iş yolu seçilmeden önce `tryActivateForRedisClient` çağrılır.
`reactor_redis_client_status` mevcut clientId, okuma yetkisi ve
`max_response_bytes` değerinin 1..65536 aralığında olduğunu denetler.
Bu sorgu slab ayırmaz, bağlantı açmaz ve GET çalıştırmaz. Varsayılan 1 MiB yanıt
sınırı uyumsuzdur. Bu durumda JNI başlangıçta seçilebilir; GET sonrasında
yeniden oynatma yapılmaz. Genel `tryActivate`, REDIS isteğini reddeder.
Native özellik biti tek başına client uygunluk kanıtı değildir.

Seçici null döndürürse mevcut JNI yolu class loader ömrü için seçilir.
Başarılı FFM aktivasyonundan sonra JNI'ya dönüş, yeniden yapılandırma veya işlem
tekrarı yoktur. Seçim hazırlanmış clientId ile sınırlıdır. Başka bir ID için
UNSUPPORTED döner. Client yaşam döngüsü çağıranın sorumluluğundadır; kapatılan
client sonraki GET'i geçersiz kılabilir. Bu, saydam bir RustCache veya
NativeTelemetry entegrasyonu değildir.

## Bellek Ve Sahiplik

Mantıksal yerleşim 64 x 64 KiB'dir. Fiziksel payload varsayılan olarak 16 slot,
yani 1 MiB'dir. Üst sınır 32 slot, yani 2 MiB'dir. Kontrol tablosu 4096 byte'dır.
İlk yapılandırma kapasiteyi atomik olarak sabitler. Bellek ilk gereksinimde
heap üzerinden ayrılır. 4 MiB stack dizisi yoktur. Slab process ömürlüdür.
Unload veya free API'si yoktur.

Acquire en fazla 32 durumu tarar ve primitive epoch lease döndürür.
Her byte, copy ve GET işlemi native BUSY sahipliği gerektirir. Eski epoch,
çift release ve BUSY release reddedilir. Epoch taşmasında slot emekliye ayrılır.
Acquire 64 KiB temizlemez. BUSY altında initialized-prefix sıfırlanır.
Okuma ve copy-out, bu prefix dışını mantıksal sıfır olarak döndürür.
Write yalnızca prefix ile yeni veri arasındaki boşluğu temizler. Uzak offset'e
write hâlâ yaklaşık 64 KiB temizleyebilir. Copy-out maliyeti istenen uzunluğa
bağlıdır. Bu tasarım A/B ölçümünün yerine geçmez.

Raw C base pointer güvenli bir veri görünümü değildir. Lease API'sini atlayan
erişim eski fiziksel byte'ları açığa çıkarabilir ve veri yarışına neden olabilir.
Bu kullanım desteklenmez. Java API slab segmenti veya adresi döndürmez.

Caller direct buffer bir kez sarılır ve tekrar kullanılır. Harici native sahip
için `wrapBuffer(bytes, nativeOwner)` kullanılır. Reachability fence erken GC
temizliğini engeller; açık close/free çağrısını engellemez. Tüm erişim ve copy
işlemleri bitene kadar owner kapatılmamalı ve input değiştirilmemelidir.
Wrapper close referansları bırakır. Caller belleğini serbest bırakmaz ve başlamış
copy'yi iptal etmez. Wrapper başlangıçta allocation yapar; ek payload ayırmaz.

Redis mevcut havuzları, admission, timeout, routing, handle_result ve command_get
yolunu kullanır. Yanıt için mevcut sınırlı Vec allocation'ı devam eder.
16 eşzamanlı yanıt ek 1 MiB, 32 yanıt ek 2 MiB payload tutabilir.
Caller, bağlantı ve topoloji buffer'ları da bellek bütçesine dahildir.
Bu sınırlar tüm process için <=+3 MiB RSS kanıtı değildir.

## İşlem Sınırları

GET tam, boş olmayan UTF-8 anahtar kullanır. Namespace hazırlığı çağıranda kalır.
Hit verisi slot offset sıfıra yazılır. Sonucun üst 32 biti status, alt 32 biti
length değeridir. MISS ile boş hit ayrıdır. Kabul edilmiş GET hata veya panic
ile sonuçlanırsa mantıksal sonuç boş kalır. BUSY/STALE reddi başka işlemin slotuna
dokunmaz. Sonuç lease tutulurken tüketilir ve lease finally içinde bırakılır.
Bu API senkron raw GET'tir; async veya versioned-cache atomik okuma API'si değildir.
Blocking GET event-loop üzerinde çalıştırılmamalıdır. Yeni retry eklenmez.

Glowroot mevcut register_operation/record_operation davranışını ve yavaş/hata
trace kuyruğunu kullanır. Metric slot ID, slab lease değildir. Java Throwable
detay yakalaması mevcut API'de kalır. Yalnızca küçük metadata getter'ları JDK21
`Linker.Option.isTrivial()` kullanır. Redis client sorgusu, blocking Redis,
Glowroot ve slot işlemleri normal downcall kullanır. Runtime reflection,
upcall veya code generation yoktur.

## Kanıt Sınırı

21 Ekim'deki tüm özellikler derlemesi tamamlanmadı. Windows SDK import-library link
hatası verdi. Linux on dakikalık görev bütçesinde durduruldu.
[Güncel deneme](packaging/README.tr.md) ayrıntıları kaydeder. Aşağıdaki başarılı
sonuçlar önceki kaynak kopyasına aittir; güncel kaynak kabulü değildir.
26 Ekim Linux/OpenJ9 kanıtı yukarıdaki güncel kaynak bölümündedir.

Önceki kaynak kodla gerçek Rust crate'i için 148 test geçti. Bunların 14'ü slab
ve backend işlemlerini kapsar. Linux HotSpot 21 üzerinde 14 canlı Java/native
testi de atlanmadan geçti. Gerçek standalone Redis üzerinde mevcut kayıtlı
istemciyle Türkçe içerik, boş değer ve bulunamayan anahtar kontrol edildi.
OpenJ9 üzerinde başlangıçtaki güvenli geri dönüşü ve seçili JMX sınıflarının
yüklenmediğini kapsayan altı kontrol geçti. Bunlar OpenJ9 hızlı yolunun veya
JNI karşısında performans üstünlüğünün kabul edildiği anlamına gelmez.

[Dağıtım kanıtı](packaging/README.tr.md), sabitlenmiş deneysel kaynak kopyasından
Linux x86-64 ve Windows x86-64 derlemelerini kaydeder. Linux dosyasının en yüksek
GLIBC gereksinimi 2.14'tür; <=2.17 kapısını geçti. Önceki GLIBC 2.34 test dosyası
aynı kapıda reddedildi. İki yeni dosya da 14 canlı Java testini atlamadan geçti.
Dört ABI sabiti, 16 C sembolü ve gerçek standalone Redis işlemleri doğrulandı.
Windows için önbellekteki cargo-xwin/lld kullanıldı; Visual Studio gerekmedi.
Linux AppCDS arşivi gerçekten oluşturuldu ve ayrı süreçte yüklendi. Kaynak kopyası
sonraki native değişikliklerden eskidir. Bu dosyalar güncel sürüm adayı değildir;
paketlerdeki varsayılan native dosyaların yerine konmadı.

Gerçek Cargo özelliği ve özel Redis/Glowroot yardımcılarıyla derleme gerekir.
Bağımsız slab derlemesi yeterli değildir. Java testleri
`-Dreactor.ffm.test.library=<absolute-path>` ile gerçek SymbolLookup kullanır.
Eksik özellik nedeniyle atlanan native test, native PASS değildir.
Loopback RESP fixture gerçek client ve transport yolunu sınar. Gerçek Redis
single/sentinel/cluster servis kabulünün yerine geçmez.

Allocation kapısı önce büyük pozitif kontrolü, ardından önceden ayrılmış global
dizide tutulan 100 x byte[32] için sekiz küçük kontrolü doğrular. Her küçük
kontrol 4000..6400 byte ölçmelidir. Kaba veya geçersiz sayaçta kapı SKIP olur.
Etkilenen OpenJ9 sayaçlarıyla sıfır allocation kabulü verilmez. Kalibre edilmiş
HotSpot 21 çalıştırması gerekir. Yalnızca büyük pozitif kontrol yeterli değildir.
`System.gc` çağrılmaz.

Standalone Redis kontrolü, Cluster/Sentinel failover kabulü değildir.
JNI/FFM eşlenik A/B, tüm process RSS ve topology kabul kapıları ayrıdır.
Mikro örnek yeni RPS veya üretim hızlanması kanıtı değildir. Deneysel doğrulama
varsayılan artifact'i kopyalamaz, değiştirmez veya yayımlamaz.

## Tam Sıfır Leaf Kontrolü

Ayrı `FfmStrictZeroLeafTest`, iki sabitlenmiş dağıtım dosyasında da tam sıfır
ölçütünü geçti. Native metadata, slab okuma ve native devre dışı `recordOperation`
için sonuçların her biri `[0, 0, 0]` B oldu. Her yol tam 200000 ısınma turundan
sonra art arda üç adet 100000 turluk ölçüm yaptı. Önceki ve sonraki sekiz küçük
kontrolün her biri 4800 B ölçtü. Yeniden deneme veya iyi pencere seçimi yapılmadı.

Kayıt yolu yeni süreçte GLOWROOT yeteneği ve geçerli slot kullandı. Telemetry
yapılandırılmadı. Böylece Java kestirmesi değil native devre dışı engine yolu
ölçüldü. Metadata turu sürüm, yetenek ve payload boyutu getter'larını çağırdı.
Yalnız bu küçük getter'lar `isTrivial` kullandı. Okuma ve kayıt normal downcall
kullandı. Bu kanıt Java thread heap ölçümüdür; native heap veya RSS kabulü değildir.
Tam lease/copy ölçümü ayrı kalır: **100000 çevrimde 384 B, tam sıfır değil**.
