# Native HTTP Taşımasını Derleyin

[English](NATIVE-INTEGRATION.md) | [Turkish](NATIVE-INTEGRATION.tr.md)

Native outbound taşıması isteğe bağlı 4.8.0 özelliğidir. Yayımlanmış Java starter'ı
ve `outbound-http` yeteneği olan eşleşen native dosyayı kullanın; yalnız ABI numarası
bu yeteneği kanıtlamaz. Yoksa ilgili feature'ı eşleşen kaynaklardan derleyin. Varsayılan taşıma
`jdk` olarak kalır; native seçilmedikçe native outbound kaynakları oluşturulmaz.

## Native Kütüphaneyi Derleyin

`outbound-http` Cargo özelliği ve koşullu modül zaten entegredir. Cargo veya
`lib.rs` değişikliği gerekmez. Özellik varsayılan olarak açık değildir. Çalışan bir
yerel Rust toolchain ile `rust-spring` dizininde:

```sh
cargo build --release --locked --features outbound-http
```

Linux çıktısı `target/release/librust_hyper.so`, macOS çıktısı
`target/release/librust_hyper.dylib` olur. Uygulamanın JVM işletim sistemi ve
mimarisi için derleyin. Özellik mevcut Hyper/Tokio bağımlılıklarını kullanır;
hyper-util legacy client gerektirmez. Outbound özelliği `tokio-rustls`, ring
sağlayıcılı `rustls`, WebPKI kökleri ve PEM okuyucusunu içerir. OpenSSL bağlanmaz.
Varsayılan REST özellik seçimi değişmez.

Windows'ta hazırlanmış cargo-xwin/LLVM SDK önbelleğiyle Visual Studio olmadan
derlenebilir. Aşağıdaki konumda `lld-link.exe` içeren önbellek için:

```powershell
$env:XWIN_CACHE_DIR=Join-Path $env:LOCALAPPDATA 'cargo-xwin'
$env:CARGO_TARGET_X86_64_PC_WINDOWS_MSVC_LINKER=Join-Path $env:XWIN_CACHE_DIR 'lld-link.exe'
cargo xwin build --release --locked --target x86_64-pc-windows-msvc --features outbound-http
```

Windows çıktısı `target/x86_64-pc-windows-msvc/release/rust_hyper.dll` olur. Çapraz
derlenen artifact hedef işletim sisteminde de test edilmelidir. Derleme komutu,
bütün platformların release çıktılarının doğrulandığı anlamına gelmez.

## Uygulamayı Yapılandırın

Mevcut `NativeLibraryLoader` için açık bir mutlak kütüphane yolu verin:

```text
-Drust.lib.path=/absolute/path/librust_hyper.so
```

Windows'ta `-Drust.lib.path=C:/native/rust_hyper.dll` gibi mutlak DLL yolu kullanın.
Taşımayı runtime oluşturulmadan seçin; üretilen istemcileri uygulama başlangıcında
bir kez bağlayın:

```properties
reactor.http-client.transport=rust-native
app.stock.base-url=http://stock-service:8080
```

REST ABI 29 korunur. Ek outbound JNI API'sinin bağımsız sürüm kontrolü 1'dir;
sembolleri taşıma başlangıcında kontrol edilir. Eksik özellik, eksik sembol veya
uyumsuz API, açık bir hata mesajıyla başlangıcı durdurur. JDK'ya sessiz dönüş
yoktur. Başlangıçta açıkça `jdk` seçilirse mevcut taşıma kullanılır.

Sonraki eşleşen Java/native derlemesi, HTTP ve doğrulanmış HTTPS üzerinden
HTTP/1.1 destekler. Eski yayımlanmış kitaplıkta ek TLS ayar export'u yoktur.
Sertifika zinciri, süre ve SAN/sunucu adı doğrulaması zorunludur. Kurum CA dosyası
için `reactor.http-client.native.tls.ca-pem-path` ayarına mutlak PEM yolu verin.
Bu ayar doğrulamayı kapatmaz. Runtime bir TLS yapılandırması tutar. Hedef ve
bağlantı sayısı sınırlıdır. TLS oturumu devam ettirme kapalıdır. Yalnız kayıt
sayısını sınırlamak, sunucunun gönderdiği sertifika ve biletlerin boyutunu
sınırlamaz. HTTP keep-alive mevcut TLS bağlantısını kullanmaya devam eder.
DNS, TCP ve TLS aynı bağlantı zaman
bütçesini kullanır. TLS belleği JNI kopyası, driver sonlanması ve iptal temizliği
boyunca rezervasyonda kalır.

Başlangıçta `reactor.http-client.native.tls.enabled=false` verirseniz tüm istemci
runtime'ı JDK taşımasına geçer. Güvensiz native moda geçilmez. HTTP/2, proxy,
yönlendirme, upgrade ve otomatik sıkıştırma açma native kapsamda değildir.
Bunlar için JDK seçin. Native I/O olası teslimden sonra gizli yeniden
bağlanma/tekrar gönderme yapmaz. Yeni denemeyi yalnız mevcut, açık ve idempotency
kurallarını gözeten uygulama retry politikası başlatabilir.

Ayarlar, varsayılanlar ve rezervasyon kuralları için
[kaynak sınırlarına](README.tr.md#native-kaynak-sınırları) bakın. Açıkça verilen
mevcut `reactor.http-client.*` ayarları native varsayılanlardan önce gelir. Kabul
beklemeden reddedilir; sınırsız kuyruk yoktur. Native metadata `try_read`/`try_lock`
kullanır, lock-free değildir. Java tamamlama ve uygulama kodu Tokio I/O dışında
çalışır.

Byte bütçesine her etkin TLS çağrısı için 64 KiB, her TLS bağlantısı için 128 KiB
eklenir. Her istemcinin TLS ayarları için 16 KiB ve güven kökü vektörünün
kapasitesi ayrılır. Yerleşik sertifika baytları salt okunur olarak paylaşılır.
Özel PEM dosyası en fazla 256 KiB ve 32 güven kökü içerebilir. Ayrıştırma belleği
ve tutulan sertifikalar, ayrılmadan önce bütçeye yazılır. En fazla sekiz istemci
runtime'ı vardır. Bunlar rezervasyon değerleridir; aynı miktarda RSS artışı
olacağı anlamına gelmez. Oturum devam ettirme kapalı olduğu için yeni TLS
bağlantısının CPU maliyeti artabilir. Etkiyi keep-alive ve iş yükünüz belirler.

## Gerçek Artifact'i Test Edin

Normal bağımlılıkları derledikten sonra `rust-java-rest` kaynak dizininde:

```sh
mvn -f platform/starter-http-client/pom.xml -Dreactor.http-client.native-test-library=/absolute/path/librust_hyper.so test
```

Windows'ta mutlak DLL yolunu verin. `reactor.http-client.native-test-library`
özelliği gerçek JNI/loopback native testlerini açar. Verilmezse native socket
testleri atlanır; bu native başarı kanıtı değildir. Bytecode karşılaştırması,
değişiklik öncesi 4.8.0 kaynaklarından alınmış sabit bir komut çıktısını kullanır.
Değişebilen yerel Maven önbelleğine güvenmez. Gerçek eski kaynaklardan üretilmiş
JAR dosyasını ayrıca `-Dreactor.http-client.baseline-jar=/tam/yol/baseline.jar`
ile kontrol edebilirsiniz. Yeni JAR'ın kendisiyle karşılaştırılması reddedilir.

Entegre testler HTTP isteklerini, ham UTF-8 ve byte verisini, sınırlı başlık ve
gövdeyi, açık retry kurallarını, timeout, iptal, close, bağlantı yeniden kullanımı
ve kaynak kabulünü kapsar. Temiz alt JVM'ler gerçek sınıf yükleme logu üretir.
Native süreç REST ABI 29'u, outbound özelliğini, tam sıfır JDK HTTP thread'ini ve
`HttpClientImpl`, `HttpClientFacade`, `SelectorManager` yüklenmediğini doğrular.
JDK pozitif kontrolü gerçek JDK istemcisini başlatmalıdır. Özelliksiz kütüphane
fallback olmadan hata vermelidir. Sahte native transport kullanılmaz.

Önceki Windows x86-64 debug doğrulaması, diğer platformların kabulü anlamına
gelmez. 31 Ekim 2026'da güncel kaynaklardan oluşturulan Linux x86-64 adayı,
EmaTech üzerinde OpenJ9 21 ile 38 Java TLS/transport testinin ve iki JNI yanıt
sahipliği testinin tamamını geçti. Bu, native kitaplık yolu açıkça belirtilerek
elde edilen işlevsel doğrulamadır; paketlenmiş JAR doğrulaması, yayımlanmış sürüm
teyidi veya tam performans kabulü değildir. ARM64 ve macOS donanımında çalışma
zamanı doğrulaması henüz tamamlanmadı;
[native platform durumuna](../../docs/native-availability.tr.md) bakın.

1 MiB varsayılan rezervasyon ve 3 MiB yapılandırma tavanı taşıma buffer
sınırlarıdır, RSS garantisi değildir. **Yük altında 3 MiB ek bellek, 128 MiB
container ve throughput/release kabul kapıları hâlâ açıktır.** Ortak DTO encoder'ın
geçici tahsisatları ve uygulama decoder tahsisatları bu rezervasyonların dışındadır.
