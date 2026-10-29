# Deneysel Dağıtım Kanıtı

[English](README.md) | [Türkçe](README.tr.md)

## 26 Ekim Güncellemesi

Güncel Linux dosyaları ayrı üretildi. JNI slab/normal abort SHA256:
`0c68e2e417e3be7949904f783e9db4948f120ab06c6851e132ee5c7c1120f8b5`.
Ayrı FFM/unwind SHA256:
`9d6f7e5a648fb7519c22d74ea6e527c35357cc4dda8526eb9ea09f54f9abff56`.
En yüksek GLIBC2.14;17 C sembolü+4 JNI slab girişi ve ABI29/7/6/6 geçti.
EmaTech'te184 Rust,8 OpenJ9 başlangıç/cache ve18 HotSpot testi geçti.
OpenJ9 Java21 FFM scope birikimi yükte tekrarlandı; PHD/DTFJ ve gerçek JVM
bytecode çıktısıyla doğrulandı. Bu JVM'de FFM artık linker açılmadan engellenir.
Açık JNI slab ve HotSpot FFM yollarında150binlik kalıcı okuma testleri geçti.
OpenJ9 HTTP kabulü JNI ile JNI slab'ı karşılaştırır; reddedilen FFM yolu değildir.
Windows ve birleşik
varsayılan kaynak yayını yapılmadı. Aşağıdaki 21 Ekim kaydı tarihsel kanıttır;
bugünkü Linux derlemesinin yerine geçmez.

Doğrulama tarihi 2026-10-21. Bu belge sınırlı derleme ve çalışma kanıtıdır.
Sürüm onayı değildir. Varsayılan paketlere native dosya kopyalanmadı. Bu kontroller
Rust kaynağını, Cargo yapılandırmasını, core HTTP kodunu veya varsayılan Maven
bağımlılıklarını değiştirmedi. Tam özetler [verification.json](verification.json)
dosyasındadır.

## Güncel Tüm Özellikler Denemesi

Sonraki güncel kaynak denemesi **TAMAMLANMADI**. Yeni PASS değildir. 51 dosyalık
kaynak özeti `ff98fa6c354684e40f06ea79c9ef337fc23ac2579df189970a1e936a73cfdcbd`.
Varsayılanlara ek `ffm-fast,edge-cache,outbound-http`, profil `release-ffm` kullanıldı.
Windows native link aşamasında Cargo 101 ile durdu. Önbellekteki SDK arama yolunda
bcrypt, advapi32, kernel32, ntdll, userenv, ws2_32 ve dbghelp import kütüphaneleri
bulunamadı. Açılmış önbellekte DbgHelp.Lib var; araç zinciri veya kaynak değiştirilmedi.
Linux derlemesi on dakikalık görev bütçesinde açıkça durduruldu. Çıkış 137, gözlenmiş
OOM veya ürün assertion hatası değildir. Güncel dosya özeti, ABI/sembol/GLIBC,
Java/strict testleri, Redis veya birleşik Rust unit testleri için PASS verilmedi.
Önbellekten kopyalanmış eski dosyalar güncel çıktı sayılmaz. Manifestin ana girdileri
bu denemeye aittir; `priorSnapshot*` alanları ve aşağıdaki sonuçlar yalnız önceki
başarılı kaynak kopyasına aittir.

Çalışma dizini `.tmp/ffm-current-boole-20261021-v2`. Git dışı kanıtlar dağıtım
dizininin `current-all-features` altındadır. Native kaynak veya varsayılan paket
değişmedi. Yük testi yapılmadı; bu görevin derleme işleri durduruldu.

## Sabitlenmiş Girdiler

İki dosya aynı 49 dosyalık kirli kaynak kopyasından derlendi. Git kaydı
`ccf11143cea8b7513706c439c67939b7803469d0-dirty`. Ayrı kaynak ağacı SHA256 değeri:
`54984470ca47f1ee3e18b575b0f33eb7f71098af211281d0a4e6244cc5be84d2`.
Bu kopya edge-cache eklemelerinden ve JNI worker daemon-attach düzeltmesinden
ÖNCEDİR. Daha sonraki kaynak ağacının doğrulandığı anlamına gelmez.

Varsayılan websocket, dubbo, redis, glowroot özellikleri korundu; `ffm-fast`
eklendi. Gerçek özel Redis/Glowroot yardımcıları derlendi. Bağımsız sahte backend
kullanılmadı. İki komut da offline/locked çalıştı. `CARGO_BUILD_JOBS=2` ve yukarıdaki
kirli Git kaydı için `REACTOR_NATIVE_SOURCE_REVISION` kullanıldı.

```text
# Linux: önbellekteki manylinux2014 imajı, Rust 1.91.0
cargo build --offline --locked --profile release-ffm --features ffm-fast
# Windows: Rust 1.91.0, cargo-xwin 0.23.0, önbellekteki LLD 21.1.2
cargo-xwin xwin build --offline --locked --profile release-ffm --features ffm-fast --target x86_64-pc-windows-msvc --target-dir <private-target>
```

Linux için `java-rust-dubbo-glibc217-builder:local` kullanıldı. Kaynak salt okunur,
çıktı dizini ayrıdır. Sınırlar: 2 CPU, 2 GiB, 128 PID, ağ erişimi yok.
Windows için `C:/Users/generic/AppData/Local/cargo-xwin` kullanıldı. Visual Studio
veya yeni bağımlılık kurulmadı. Derleme işinin toplam bellek sınırı 2 GiB, CPU
kotası ikidir. Derlemeler sırasıyla 8 dk 21 sn ve 12 dk 11 sn sürdü. Bunlar hız
karşılaştırması değildir.

## Sonuçlar

- Linux ELF x86-64 dosyasının en yüksek GLIBC gereksinimi **2.14**. Bağımlılıklar
  GLIBC 2.17 derleyici imajında çözüldü. `verify-linux.py` eksik sembolleri,
  bağımlılıkları, yanlış mimariyi ve 2.17 üzeri gereksinimleri reddeder. Negatif
  kontrolde eski GLIBC 2.34 dosyası beklendiği gibi reddedildi.
- İki gerçek dosyada ABI sabitleri **29/7/6/6** ve **16** FFM C sembolü doğrulandı.
  İki platformda da **14/14** canlı Java testi geçti. Atlanan veya başarısız test yok.
- Sekiz küçük allocation kontrolünün her biri **4800 B** ölçtü. Büyük pozitif
  kontrol **144000 B** ölçtü. Isınmış **100000** slab/copy çevrimi toplam **384 B**
  ölçtü. Bu, sıfır allocation veya Redis allocation ölçümü değildir.
- Gerçek Redis 7.4 standalone üzerinde mevcut kayıtlı istemciyle Türkçe içerik,
  boş hit ve miss doğrulandı. Lease bırakıldı ve istemci kapatıldı. İki JVM de
  doğal olarak sonlandı. Geçici Redis ve ağı kaldırıldı.
- Linux için önbellekteki Temurin HotSpot 21.0.12+8 JRE, Windows ve javac için
  GraalVM HotSpot 21.0.6+8.1 JDK kullanıldı. JVM sınırları:
  `-Xms32m -Xmx384m -XX:ActiveProcessorCount=2`.
  `--enable-preview --enable-native-access=ALL-UNNAMED` açıktı. `System.gc` yok.

Aynı iki dosyada ayrı `FfmStrictZeroLeafTest` de geçti. Her platformda bir odaklı
test çalıştı; atlama veya hata yok. Native metadata, tutulmuş lease ile slab okuma
ve native devre dışı kayıt yollarının her biri 200000 ısınma turu kullandı.
Ardından art arda üç adet 100000 turluk ölçüm yapıldı. Her yol `[0, 0, 0]` B ölçtü.
Her çalışmanın önceki ve sonraki sekiz küçük kontrolü 4800 B ölçtü. Yeniden deneme
veya iyi pencere seçimi yapılmadı. Metadata turunda üç getter çağrısı vardır.

Kayıt yolunda GLOWROOT yeteneği ve geçerli slot sıfır kullanıldı. Yeni süreçte
telemetry yapılandırılmadı. Java kestirmesi yerine native devre dışı engine yolu
ölçüldü. Yalnız metadata getter'ları `isTrivial` kullandı. Okuma ve kayıt normal
downcall kullandı. Bu sonuç Java thread heap içindir; native heap veya RSS kanıtı
değildir. Tam lease/copy sonucu **384 B olarak kalır, sıfır değildir**.

Kayıtlar kanıt dizininin `strict-zero` alt dizininde, komutlar ayrıca
`.tmp/ffm-strict-zero-boole-20261021` içindedir. Native derleme veya üretim kodu
değişikliği yapılmadı. Önceki 14 testin kayıtları ve AppCDS dosyaları korundu.

## Gerçek Linux AppCDS

Önbellekteki HotSpot JRE arşiv oluşturmayı destekledi. Linux üzerinde ayrıca
javac/jar kurulmadı. Test jar'ı mevcut Windows JDK ile
`--enable-preview --release 21 -proc:none` kullanılarak derlendi.

```text
java <bounded-preview-flags> -XX:ArchiveClassesAtExit=/proof/appcds/app.jsa -cp /proof/ffm-probes.jar com.reactor.rust.ffm.FfmAppCdsProbe
java <bounded-preview-flags> -Xshare:on -XX:SharedArchiveFile=/proof/appcds/app.jsa -Xlog:cds=info,class+load=info -cp /proof/ffm-probes.jar com.reactor.rust.ffm.FfmAppCdsProbe
```

Eğitim ve yükleme ayrı Linux süreçlerinde, aynı jar/native yollarıyla yapıldı.
2699264 byte arşiv oluştu. 439 sınıfın altısı uygulama sınıfıdır. Yeni süreçte
`FfmAppCdsProbe` ve `FfmFastBridge` kaynağı `shared objects file (top)` olarak
göründü. İki süreç de gerçek slab copy/read/release yaptı ve
`APP_CDS_FFM_PROBE=OK` yazdı. Eksik arşivle `-Xshare:on` beklendiği gibi başarısız oldu.
Bu kanıt Maven yaşam döngüsü entegrasyonu, OpenJ9 SCC veya RSS/hızlanma kabulü
değildir. Arşiv başka JDK, classpath veya mimariye taşınabilir kabul edilmez.

## Kayıtlar Ve Açık Kapılar

Çalışma dizini `.tmp/ffm-dist-boole-20261021-v1`. Git dışı kanıt dizini:
`C:/Users/generic/AppData/Local/Temp/reactor-zero-materialization-testagent-20261021/phase4-ffm-distribution`.
Komutlar, kaynak manifesti, dosya özetleri, başarılı kayıtlar ve altyapı sorunları
burada saklanır. Temel kayıtlar İngilizce belgedeki adlarla aynıdır.

İlk Linux denemelerinde offline registry eksikleri ve Windows bind-mount açma
maliyeti görüldü. Ayrı Linux registry volume ile çözüldü. İlk Redis ağında host
portu oluşmadı. Yalnız geçici ağ normal bridge yapıldı ve Windows testi geçti.
Bu hatalar ürün assertion hatası değildir.

Güncel kaynakla yeniden derleme ve kabul hâlâ gerekir. Tüm süreç için <=+3 MiB
RSS, eşlenik JNI/FFM A/B, canlı OpenJ9 FFM, Cluster/Sentinel failover ve sürüm
paketlemesi açık kapılardır. Gürültülü mikro süreler kabul veya RPS kanıtı değildir.
