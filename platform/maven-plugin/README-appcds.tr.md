# İsteğe Bağlı HotSpot AppCDS Derlemesi

[English](README-appcds.md) | [Turkish](README-appcds.tr.md)

Bu özellik güncel kaynak kodda bulunur. Henüz yayımlanan Maven plugin paketinde yoktur.
Gerçek hedef imajda arşiv üretimi ve yüklenmesi ayrıca doğrulanmalıdır.

`reactor:generate-appcds`, paketleme sırasında sınıf arşivi oluşturur. Varsayılan
olarak kapalıdır. `reactor.appcds.enabled=false` iken hedef; JVM, proje, dosya ve
process bilgilerini okumadan döner. Maven, hedefi çağırmadan önce bağımlılıkları
çözebilir. Bu davranış Maven'e aittir.

## OpenJ9 Kullanıyorsanız

Bu hedef yalnız Maven'i çalıştıran **Java 21 HotSpot/OpenJDK Server VM** içindir.
OpenJ9/Semeru ile açılırsa anlaşılır bir hata verir. OpenJ9 sınıf önbelleği, HotSpot
`.jsa` dosyası değildir. OpenJ9 için mevcut `runtime-image` hedefini ve
`reactor.runtime.openj9.scc.enabled=true` ayarını kullanın.

[Başlangıç ayarları](../../docs/startup-tuning.md#openj9-shared-classes-cache--aot)
ve [production SCC rehberi](../../docs/production-runtime.md#rom-only-openj9-shared-cache)
bu yolu anlatır. Yeni hedef OpenJ9 davranışını değiştirmez.

## Arşiv Stratejisi

Hedef varsayılan olarak **kapalı kalır**. Açıkça etkinleştirildiğinde yeni varsayılan
`reactor.appcds.strategy=dynamic` olur. `ArchiveClassesAtExit`, JDK'nın mevcut CDS
tabanının üstüne ek arşiv üretir; hazır JDK sınıf kapsamını değiştirmez.
Taban `java.home/lib/server/classes.jsa`, Windows'ta `bin/server/classes.jsa`
olmalıdır. Eksik/geçersiz tabanda hata verilir; otomatik taban üretimi, static'e
dönüş veya eğitim tekrarı yoktur. Eski iki aşamalı davranış yalnız açık
`reactor.appcds.strategy=static` seçimiyle korunur.

## Sonlu Bir Hazırlık Programı Tanımlayın

Paketlenen JAR içinde yalnız yerel test verisiyle çalışan kısa bir hazırlık programı
oluşturun. Main sınıfını ve 1–64 parametreyi açıkça verin. Hedef, manifest içinden main
seçmez. Servisinizi veya iş akışınızı kendiliğinden başlatmaz. Shell kullanmaz.
Parametrelerdeki boşluklar korunur; ayrıca tırnak karakteri eklemeyin.

```xml
<plugin>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-maven-plugin</artifactId>
  <version>4.8.0</version>
  <executions>
    <execution>
      <id>optional-appcds</id>
      <goals><goal>generate-appcds</goal></goals>
    </execution>
  </executions>
  <configuration>
    <strategy>dynamic</strategy>
    <trainingMainClass>example.OfflineArchiveTraining</trainingMainClass>
    <trainingArgs>
      <arg>--offline</arg>
      <arg>--fixtures</arg>
      <arg>fixtures with spaces/input.json</arg>
    </trainingArgs>
    <timeoutSeconds>60</timeoutSeconds>
    <maxHeapMb>256</maxHeapMb>
  </configuration>
</plugin>
```

HotSpot arşiv derlemesinde `"-Dreactor.appcds.enabled=true"` verin. Proje JAR'ı önceden
paketlenmiş olmalıdır. `target/classes` ile hazırlık yapılmaz. Proje JAR'ı classpath'te
ilk sıradadır. Ardından Maven'in çözdüğü runtime JAR'ları gelir. Yinelenen yollar
çıkarılır. Dizin, eksik JAR ve wildcard kabul edilmez. İç içe executable JAR loader'ları
ve module-path kullanımı bu hedefin kapsamında değildir.

Hazırlık programı dış servise bağlanmamalı ve iş verisini değiştirmemelidir. Worker veya
alt process başlatmamalıdır. Normal şekilde sonlanmalıdır. `--offline` uygulamanızın
uyguladığı bir kuraldır; ağ güvenlik duvarı değildir. Canlı erişim bilgisi vermeyin.
Göreli dosya yolları, oluşturulan çıktı dizinine göre çözülür.

## Sınırlar ve Dosyalar

### Gerçek Endpoint Sınıflarını Hazırlığa Dahil Edin

Küçük bir hazırlık programı geçerli arşiv oluşturabilir. Ancak bu arşiv servisin
kullandığı sınıfları kapsamıyor olabilir. Arşivin oluşması hız kazancı anlamına gelmez.
Seçtiğiniz endpoint'lerin generated dispatch sınıflarını, JSON writer'larını ve
DTO'larını listeye alın. Sınıf yüklemek için HTTP sunucusu, JMX sorgusu, DB havuzu
veya Redis istemcisi başlatmayın. Bunlar gereksiz hazırlık yükü oluşturur.

`src/main/resources/appcds-hot-classes.txt` dosyasına her satırda bir sınıf yazın:

```text
com.reactor.generated.ReactorApplicationDescriptor
com.reactor.generated.ReactorApplicationDescriptor__Dispatch
com.example.customer.CustomerResponse
com.example.customer.CustomerResponseJsonWriter
```

Son iki satır örnektir. Kendi projenizde bulunan sınıflarla değiştirin. Listedeki
her sınıf paketlenen JAR classpath'inde bulunmalıdır. JVM logundaki geçici lambda
sınıf adlarını kopyalamayın.

[Tam Java örneği](README-appcds.md#train-the-classes-your-routes-actually-use)
kopyalanabilir hazırlık kodunu içerir. Program yalnız `--offline` kabul eder.
Listeyi 256 KiB ve 4096 sınıfla sınırlar. Sınıf adını doğrular. Eksik veya geçersiz
sınıfta hata verir. `Class.forName(name, false, loader)` yalnız metadata yükler;
sınıfın statik başlangıç kodunu veya iş metotlarını çalıştırmaz.

Bu örnek için plugin'e şu iki ayarı verin:

```xml
<trainingMainClass>example.OfflineArchiveTraining</trainingMainClass>
<trainingArgs><arg>--offline</arg></trainingArgs>
```

Bu işlem yalnız seçtiğiniz derleme hazırlığında çalışır. İstek başına sınıf arama
veya reflection eklemez. Sınıf yüklemek serializer doğruluk testinin yerine geçmez.
Deployment'ta sınıfların gerçekten arşivden yüklendiğini kontrol edin. Ardından
kendi payload'larınızla RPS, p99 ve RSS ölçün. OpenJ9 için SCC yolu değişmez.

Dynamic hedef sırasıyla iki JVM başlatır. İlki `-Xshare:on`, açık JDK tabanı ve
`ArchiveClassesAtExit` ile ek katmanı üretir; sınıf listesi tanı için tutulur.
İkincisi `-XX:+VerifySharedSpaces -XX:+PrintSharedArchiveAndExit` ile taban+ek
arşivi doğrular ve main'i tekrar çalıştırmadan çıkar. Başarı için doğru dynamic
arşiv ve eğitim main'inin app-loader kaydı zorunludur; yalnız tabanın yüklenmesi
yeterli değildir. Static modda eski classlist/`-Xshare:dump` akışı kullanılır.

Taban SHA-256'sı öncesinde ve sonrasında denetlenir. Metadata; strateji, tabanın
yolu/hash'i/boyutu, doğrulama durumu ve tam `runtime.sharedArchiveFile` taban+ek
değerini kaydeder. Çalıştırma bayrağı Linux'ta
`-XX:SharedArchiveFile=<jdk-base>:<top>`, Windows'ta arada `;` kullanır.
Static arşiv JDK tabanının yerine geçer ve hazır sınıf kapsamını azaltabilir.
`deploymentVerified=false` canlı uygulamanın kabul edildiği anlamına gelmez.

Her JVM `-Xms32m`, sınırlı `-Xmx`, iki işlemci ve birer GC thread'i ile başlar. Heap
sınırı 32–1024 MiB'dir. Her process için timeout 1–300 saniyedir; varsayılan 60 saniye
olur. Kapatma için en fazla iki ek ikişer saniyelik bekleme yapılır. Hazırlık otomatik
tekrarlanmaz. Ortamdan gelen JVM agent/option ve classpath değişkenleri alt process'e
aktarılmaz. Standart giriş kapatılır.

Çıktılar `${project.build.directory}/reactor-appcds/` altında oluşur:

| Dosya | İçerik |
| --- | --- |
| `app-cds.classlist` | Hazırlık sırasında yüklenen sınıflar |
| `app-cds.jsa` | HotSpot sınıf arşivi |
| `training.log`, `validation.log` | Dynamic eğitim/doğrulama logları; static modda `dump.log` kullanılır |
| `compatible-jvm.properties` | JVM, işletim sistemi, mimari, JVM seçenekleri ve JAR/arşiv SHA-256 bilgileri |

Loglar plugin heap'inde biriktirilmez. Doğrudan diske yazılır. Log dosyalarının byte
limiti yoktur; disk alanını planlayın. Parametre değerleri metadata'ya yazılmaz.
Hata halinde log ve sınıf listesi kalır. Eksik arşiv ve başarı metadata'sı kaldırılır.
Kesinti veya stdin hatasında da kilit kaldırılmadan önce doğrudan alt process'in
sonlandığı beklenir. Zorla sonlandırma doğrulanamazsa başarı metadata'sı üretilmez,
üretim kilidi korunur. Hâlâ yazan process'in eksik arşivi kalabilir. Kilidi kaldırmadan
önce yalnız bu çalıştırmanın process'ini ve çıktılarını inceleyin.

Aynı dizine eşzamanlı üretim kilitle engellenir. Maven zorla kapatılırsa kalan kilidi
silmeden önce dizini inceleyin. Hedef yeniden çalıştırıldığında kendi arşivini yeniler.
Windows'ta HotSpot'un arşive eklediği salt okunur niteliği de bu dosya için kaldırılır.
Girdi JAR'larının izinleri değiştirilmez. Arşivi başka bir JVM kullanıyorsa işletim
sistemi yenilemeyi engelleyebilir. Plugin bu JVM'yi durdurmaz veya belleğinden arşivi
zorla çıkarmaz. Timeout veya kesinti yalnız hedefin başlattığı doğrudan
alt process'i kapatır. Başka process'lere dokunmaz. Alt process başlatan hazırlık
programları desteklenmez.

## Container'a Almadan Önce

Arşiv taşınabilir bir JAR değildir. JVM build'i, işletim sistemi/mimari, JVM seçenekleri
ve sıralı JAR classpath'i uyumlu olmalıdır. Hazırlık öncesinde ve sonrasında JAR
hash'leri kontrol edilir. Metadata bir kanıttır; deployment uyumluluk denetleyicisi değildir.

JDK, bağımlılık veya paketleme değişirse arşivi yeniden oluşturun. Hedef container
imajındaki dizinlerle hazırlayın. Dynamic modda
`-Xshare:on -XX:SharedArchiveFile=<jdk-base>:<top>` kullanın; static modda tek
`<archive>` verilir. Windows'ta arşivler arasında `;` kullanılır. Hedef imajdaki
sınıf yükleme loglarıyla gerçekten kullanıldığını doğrulayın. Bu hedef deployment
başlangıcını değiştirmez. Otomatik release asset üretmez. Ölçülmüş RSS, başlangıç süresi
veya RPS kazancı iddiasında bulunmaz.

HotSpot 21, JAR boyutlarını ve dosya değişiklik zamanlarını da denetler. Aynı
SHA-256 değerine sahip bir JAR yeni zaman damgasıyla kopyalanırsa üst arşiv
geçersiz olabilir; classpath'in sonundaki offline fixture JAR'ı da buna dahildir.
Zaman damgalarını koruyun veya son dizin yapısında yeniden arşiv üretin.
Kopyalanmış arşivi yüklemek için doğrulamayı devre dışı bırakmayın.

Bellek karşılaştırmasında allocator ortam ayarlarını kaydedin ve default-CDS ile
layered-CDS kollarında aynı tutun. Değiştirilen allocator tarifinin RPS/p99 kaybına
yol açmadığını ayrıca ölçün. Bu hedef allocator ortam değişkeni atamaz ve runtime
imajının varsayılanlarını değiştirmez.

## Yalnız EmaTech'te Kabul

Mock birim testleri dahil bütün testler EmaTech'te çalışır. Yerelde
`mvn -f platform/maven-plugin/pom.xml -DskipTests test-compile` yalnız derler.
Yeni test sınıfları plugin veya uygulama dağıtımına eklenmez.
`AppCdsArchiveIntegrationTest` için `REACTOR_TEST_CLUSTER=EmaTech` ve imaja uygun
`-Dreactor.appcds.integration.vm=hotspot|openj9` gerekir. Atlanan test PASS değildir.

`AppCdsEmaTechHarness`, gerçek JAR-only `MavenProject` ile gerçek hedefi çağırır.
Maven executable gerekmez. Derlenmiş plugin/descriptor, ayrı test sınıfları,
Maven `lib/*.jar` ve JUnit hazırlanmalıdır. Ana çalışmanın doğruladığı EmaTech pod'unda:

```sh
export REACTOR_TEST_CLUSTER=EmaTech
CP='/work/test-lib/junit-platform-console-standalone-1.11.4.jar:/payload/main:/payload/tests:/payload/maven-lib/*'
# İsteğe bağlı framework kanıtı: gerçek uygulama JAR'ı ilk sırada olmalı.
# Tüm runtime JAR yollarını açıkça yazın; wildcard/dizin kullanmayın.
export APPCDS_FRAMEWORK_JARS='/payload/reader-switch/lib/application.jar:/payload/reader-switch/lib/rust-java-rest.jar:...'
export APPCDS_FRAMEWORK_DESCRIPTOR=com.reactor.generated.ReactorApplicationDescriptor
java -Xms32m -Xmx128m -cp "$CP" com.reactor.rust.maven.AppCdsEmaTechHarness hotspot /evidence/hotspot-functional
java -Xms32m -Xmx128m -cp "$CP" com.reactor.rust.maven.AppCdsEmaTechHarness compare /evidence/hotspot-functional /evidence/http-comparison 3
# Ayrı ve gerçek OpenJ9 21 imajında:
java -Xms32m -Xmx128m -cp "$CP" com.reactor.rust.maven.AppCdsEmaTechHarness openj9 /evidence/openj9-functional
```

Örnek yolları ve `...` bölümünü gerçek JAR'larla değiştirin. Yalnız mekanik kabul
için iki framework değişkenini de kaldırın. Framework modu gerçek `JsonBufferWriter`
ile JSON üretir, verilen generated `ApplicationDescriptor` sınıfını oluşturur ve
yalnız paket metadata'sını sorgular. Component/handler/extension kaydetmez; Redis'e
bağlanmaz ve framework servisini başlatmaz. Verilen descriptor'ın offline davranışı
önceden incelenmelidir. Hazırlık fixture JAR'ı classpath'in sonuna eklenir; arşivi
kullanırken aynı sıralı classpath korunmalıdır. Bütün JAR hash'leri kaydedilir.
Test reflection'ı framework hot path'ine eklenmez.

Çıktı dizinleri yeni olmalıdır. Son arşiv:
`/evidence/hotspot-functional/target/reactor-appcds/app-cds.jsa`.
`archive-load.log`, `-Xshare:on` ile fixture sınıfı için
`source: shared objects file` göstermelidir. Framework modunda ayrıca gerçek
`JsonBufferWriter` ve generated descriptor için aynı kanıt zorunludur. Yalnız JDK
sınıflarının paylaşılması yeterli değildir. Bozuk arşiv ve değişmiş classpath
başarısız olmalıdır. Sonsuz hazırlık timeout sonrası başarı metadata'sı, arşiv veya
kilit bırakmamalıdır. OpenJ9 gerçek imajda reddedilmeli; kapalı hedef iş yapmamalıdır.

Dynamic modda üç yeni process çifti değişen sırayla JDK `default` ve `layered`
modlarını ölçer. Eski static seri ayrıca `off` kontrolünü tutar. Yeni çiftlerde
HTTP JSON yanıtlarını gerçek framework writer'ı üretir. Aynı JAR/JVM ayarları,
512 doğru HTTP yanıtı ve aynı bellek ölçüm noktası
kullanılır. CSV, ham `smaps_rollup` ve `status`; hazır yanıt süresini, RSS/PSS/özel
belleği ve heap kullanımını saklar. Sınıf loglaması süre ölçümünde kapalıdır.
Dosya önbelleği temizlenmez. Framework sınıfları kullanılsa bile bu JDK HTTP
fixture'ıdır; Rust/native HTTP sunucusunu başlatmaz. Gerçek framework HTTP, soğuk
container, RPS/p99 veya production bellek kabulü değildir. RPS/p99, ana çalışmanın
all-on kabulüne aittir. Varsayılan CDS kazanımı AppCDS'ye yazılmaz.

Dynamic class-load kanıtı, JDK sınıfları için tabandan `shared objects file`,
writer/descriptor için `shared objects file (top)` göstermelidir.
CSV ayrıca cgroup `memory.current` öncesi/steady değerlerini ve ham `memory.stat`
kaydını tutar. Bunlar harness ve dosya cache'ini de içerir. Zorla GC veya uygulama
bütçesi büyütme yapılmaz. Asıl Faz7 isteğe bağlı kabul sınırı steady RSS/cgroup için
+3 MiB'dir; net fiziksel bellek azalması zorunlu değildir. PSS/private yeni bir
sert kabul koşulu değildir. Startup yüzdesi request-p99 gecikme sınırı değildir.

Ortam değişkeni cluster kimlik kanıtı değildir. Ana çalışma EmaTech context,
pod UID/imaj ID/node/kotaları ve kaynak hash'lerini kaydeder; kaynakları kendisi
başlatır ve kanıtları toplar. Süre, disk, CPU/bellek ve ağ sınırları uygulanmalıdır.
Heap sınırı RSS sınırı değildir. Normal başlangıca eğitim, benchmark, doğrulayıcı,
arşiv bayrağı veya otomatik tekrar maliyeti eklenmez.

Kaynaklar: [JDK 21 AppCDS](https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html#application-class-data-sharing),
[OpenJ9 sınıf önbelleği](https://eclipse.dev/openj9/docs/shrc/).
