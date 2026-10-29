# Üretilmiş Dispatch

[English](generated-dispatch.md) | [Türkçe](generated-dispatch.tr.md)

İlk kez REST 4.8.0 ile yayımlanmıştır. Uygun annotation'lı handler'ların çağrılma
biçimini değiştirir; Java API'sini, istek sözleşmesini veya native ABI'yi değiştirmez.

## Derleme Zamanında Seçim

| Processor seçeneği | Davranış |
| --- | --- |
| Seçenek yok veya `-Areactor.codegen.dispatch=auto` | Varsayılan. Uygun metotlarda switch kullanır; diğerlerinde uyarı verip mevcut üretilmiş doğrudan çağrı adapter'ını korur. |
| `-Areactor.codegen.dispatch=switch` | Katı seçim. Desteklenmeyen metot veya tablo taşması derleme hatasıdır. |
| `-Areactor.codegen.dispatch=legacy` | Önceki üretilmiş route adapter'larını ve exception-handler lambda'larını korur. |

Diğer değerler derleme hatasıdır. Seçim her istekte değil, annotation processing
sırasında yapılır. Başlangıçta JVM property'si vermek derlenmiş sınıfları
değiştirmez. Mod değiştirirken temiz derleme gerekir.

## Uyumluluk Ve Sınırlar

Switch için kayıtlı component veya `@Bean` sahibi gerekir: public, somut ve
generic olmayan sınıf; public, static olmayan, generic olmayan metotlar ve dış
tipleri/generic argümanları dahil public erişilebilir parametre tipleri.
Normal parametrelerde desteklenen istek binding'lerinden tam biri bulunmalıdır.

`auto` modunda package-private sahipler, metotlar, parametre tipleri ve hata
handler'ları mevcut paket-yerel generated factory'lerini korur. Package-private
`@Bean` dönüş tipleri de buna dahildir. Kayıt, koşullar ve yaşam döngüsü taşınmaz;
aynı uygulamadaki uygun public metotlar yine switch kullanabilir. Kayıtsız
sahiplere veya önceden geçersiz imzalara yeni destek eklenmez. Uyarıları hata
sayan derlemelerde gerektiğinde açıkça `legacy` seçin.

Rotalarda parametre sayısı 0..8, her parametre sayısı için en fazla 256 switch
kaydı ve ayrı olarak en fazla 256 exception-handler metodu desteklenir. ID'ler ve
sıralama deterministiktir. `auto`, taşan kayıtları üretilmiş legacy çağrıda tutar;
katı `switch` reddeder. `legacy`, mevcut sekiz parametre sınırını kaldırmaz.
Bu sınırlar bağlantı veya eşzamanlılık sınırı değil, üretilen tablo sınırlarıdır.

Üretilen her `RouteN` adapter'ında switch ve doğrudan handler çağrısı `invokeN`
metodunun içindedir; başka dispatcher'a yönlendiren bir ara metot yoktur.
Özelleşmiş `invokeInt`, `invokeLong`, `invokeBoolean`, `invokeDouble` ve
`invokeShort` metotları da kendi switch'lerini içerir. Mevcut binding ve
direct-buffer/scalar yolları değişmez; `@DirectQueryInt` / `@DirectPathInt`
runtime-direct MethodHandle çağrıları da korunur. Adapter üretilmesi bu çağrıları
başka yola taşımaz. Framework buffer'ını ödünç alan async handler'lar desteklenmez.

## Maven Kullanımı

Platform parent'ı `reactor.codegen.dispatch` değerini varsayılan olarak `auto`
seçer ve annotation processor'a iletir. Uygulama dizininde bir mod seçin:

```sh
mvn -B -ntp -DskipTests -Dreactor.codegen.dispatch=auto clean package
mvn -B -ntp -DskipTests -Dreactor.codegen.dispatch=switch clean package
mvn -B -ntp -DskipTests -Dreactor.codegen.dispatch=legacy clean package
```

Uygulama veya profil compiler argümanı ekliyorsa parent seçeneklerini değiştirmek
yerine listeye ekleyin. Örneğin `maven-compiler-plugin` içinde:

```xml
<configuration>
  <compilerArgs combine.children="append">
    <arg>-parameters</arg>
  </compilerArgs>
</configuration>
```

`compilerArgs` tanımlayan her profilde aynı birleştirme kuralını uygulayın.
Platform parent'ı kullanılmıyorsa `-Areactor.codegen.dispatch=...` seçeneğini
mevcut compiler ayarınızdan iletin; tek başına Maven property'si processor
seçeneği değildir. Derleyicinin `Generated dispatch mode=...` notu switch/legacy
rota ve hata handler sayılarını gösterir. Gerçek çağrılar üretilen kaynakta görülür.

## Runtime Ve Doğrulama Kapsamı

OpenJ9 aynı önceden derlenmiş Java adapter'larını çalıştırır. Üretilmiş istek
yoluna reflection ile çağrı, dynamic proxy veya runtime yeniden derleme eklenmez.
Mevcut başlangıç incelemesi ve runtime-direct MethodHandle yolları kaldırılmaz.
Guard, async tamamlanma, status/header, yanıt sahipliği ve yaşam döngüsü mevcut
davranışını korur. Java API'leri ve REST29 / Dubbo7 / SpringDubbo3 / Redis6 /
Glowroot6 değişmez.

### Ölçülen Reader Kapsamı

2026-10-27 tarihinde `phase79-dc6e245883e69cefe9e6` kaynağı, tanımlanan
EmaTech/OpenJ9 reader matrisini geçti: beş taze süreç çifti, c64/c256 altında
small/raw/heavy altı hücre, gövdesi birebir doğrulanan 19.816.245 ısınma/ölçüm
yanıtı ve ayrıca 100 HTTP sözleşme kontrolü; hata sıfır. Gerçek cache-reader
detay, segment ve generated health rotaları ölçüldü; müşteri numarası binding'i
ve eksik müşteri için beklenen 404 de doğrulandı. HTTP, public ingress yerine
pod loopback kullandı. Eksik karşıt ölçüm sırası eklendikten sonra ilk başarısız
hücreler dahil beş çiftin tamamı korundu ve değerlendirmeye alındı.

İki kol da 1 CPU / 128 MiB, `0,3` affinity, kapalı L1, veritabanı olmadan
önceden doldurulmuş Redis ve her kol/hücre için 15 saniye ısınma / 8 saniye ölçüm
kullandı. **Yalnız benchmark reçetesi:** `cache-read` ve `cache-read-point`
admission kuyruk süresi iki kolda da 125 ms; eşzamanlılık sınırları aynı 64/128.
Üretim varsayılanları değiştirilmedi; sonuç, varsayılan 25 ms reçetesinin kabulü
değildir. Kabul, beş çiftin hücre bazlı eşlenmiş medyanlarıyla hesaplandı:
RPS >= 0,98, p99 <= 1,10, RSS/container farkı <= +3 MiB ve sıfır hata.

| Hücre | RPS oranı | p99 oranı | RSS farkı MiB | Container farkı MiB |
| --- | ---: | ---: | ---: | ---: |
| small c64 | 1,026040 | 1,027248 | -0,795 | -0,369 |
| small c256 | 1,036327 | 1,029094 | -0,801 | -0,428 |
| raw c64 | 0,998793 | 0,992910 | -0,598 | -0,223 |
| raw c256 | 0,991837 | 1,003699 | -0,703 | -0,277 |
| heavy c64 | 1,025231 | 0,991344 | -2,230 | -2,086 |
| heavy c256 | 0,989412 | 1,021405 | -0,488 | -0,910 |

Ayrı HotSpot kontrolünde aynı JVM'de önce 532.480 byte pozitif sayaç kalibrasyonu
yapıldı; ardından primitive invoker için iki modda da 100.000 çağrıda 0 byte
ölçüldü. OpenJ9 tahsisat metodunun abort sonucu PASS sayılmadı; bu kanıt, tam
HTTP isteğinin sıfır tahsisat yaptığı anlamına gelmez. Önceki FAIL kayıtları
korunur. Bu sınırlı sonuçlar evrensel hızlanma, consumer performansı, public ağ,
uzun süreli kararlılık, diğer platformlar veya tam üretim/yayım kabulü değildir.
Bu ölçüm kaydı kaynak-önizleme dönemine aittir. Özellik artık 4.8.0 ile
yayımlanmıştır; yayım yukarıdaki sınırlı ölçümü tam üretim veya kullanıcı kabulüne çevirmez.
