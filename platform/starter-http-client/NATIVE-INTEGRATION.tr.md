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
hyper-util legacy client veya ek TLS bağımlılığı gerektirmez.

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

Bu uygulama **yalnız HTTP/1** destekler. HTTPS, istemci bağlanırken ve istek
gönderilmeden reddedilir. Güvensiz TLS modu veya HTTPS'ten HTTP'ye düşürme yoktur.
Doğrulanmış TLS, HTTP/2, proxy, redirect, upgrade ve otomatik sıkıştırma açma
desteklenmez. **Tam production HTTPS kapsamı sağlanmış değildir.** Bu yetenekler
gerekiyorsa başlangıçta JDK seçin. Native I/O olası teslimden sonra gizli yeniden
bağlanma/tekrar gönderme yapmaz. Yeni denemeyi yalnız mevcut, açık ve idempotency
kurallarını gözeten uygulama retry politikası başlatabilir.

Ayarlar, varsayılanlar ve rezervasyon kuralları için
[kaynak sınırlarına](README.tr.md#native-kaynak-sınırları) bakın. Açıkça verilen
mevcut `reactor.http-client.*` ayarları native varsayılanlardan önce gelir. Kabul
beklemeden reddedilir; sınırsız kuyruk yoktur. Native metadata `try_read`/`try_lock`
kullanır, lock-free değildir. Java tamamlama ve uygulama kodu Tokio I/O dışında
çalışır.

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

Gerçek native doğruluk kanıtı şu anda entegre Windows x86-64 debug DLL'i kapsar;
Linux/macOS veya release profili kabulü anlamına gelmez. 1 MiB varsayılan
rezervasyon ve 3 MiB yapılandırma tavanı taşıma buffer sınırlarıdır, RSS garantisi
değildir. **Yük altında 3 MiB ek bellek, 128 MiB container ve throughput/release
kabul kapıları hâlâ açıktır.** Ortak DTO encoder'ın geçici tahsisatları ve uygulama
decoder tahsisatları bu rezervasyonların dışındadır.
