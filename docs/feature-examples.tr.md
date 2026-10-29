# 4.8.0 Özellik Ve Çalışan Tüketici Haritası

[English](feature-examples.md) | [Türkçe](feature-examples.tr.md)

`v4.8.0`, REST, platform, plugin ve isteğe bağlı Java modüllerini birlikte yayımlar.
Yayım iş yükü kabulü değildir. [Sürüm sınırlamaları](release-notes/v4.8.0.tr.md#kabul-kapsamı)
korunur: evrensel RSS düşüşü, sabit writer p99, sıfır consumer ısınma reti veya tüm
platformlarda işlev/performans PASS sonucu çıkarılmaz. Normal JNI panic-abort'tur;
ayrı HotSpot FFM derlemesi açık feature/profile seçimiyle unwind kullanır.

## Hangi Uygulama?

Beş küçük [core örneği](../examples/README.tr.md), explicit module ile HTTP kullanımını
öğretir. Core [uyumluluk sample'ı](../sample/README.tr.md) geniş bir diagnostics
uygulamasıdır. Bunlar bağımsız derlenen aşağıdaki dört kardeş uygulama değildir:

| Uygulama | Gerçek rol / gereksinimler |
| --- | --- |
| [Cache writer](https://github.com/esasmer-dou/rest-sample-cache-writer/blob/master/README.tr.md) | PostgreSQL'den sürümlü Redis projection üretir; DB/Redis ayarları ve schema gerekir. |
| [Cache reader](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/README.tr.md) | Generated projection reader; normal rotalarda writer'ın yayımladığı Redis snapshot gerekir. Aşağıdaki isteğe bağlı profiller farklı uygulamalardır. |
| [Dubbo provider](https://github.com/esasmer-dou/rest-sample-dubbo-provider/blob/master/README.tr.md) | Java iş servisi; DB modu PostgreSQL ister, static catalog modları istemez. |
| [Dubbo consumer](https://github.com/esasmer-dou/rest-sample-dubbo-consumer/blob/master/README.tr.md) | HTTP'den Dubbo'ya gider; eşleşen provider, ortak 0.5.0 kontratlar ve uyumlu Java/native dosyalar gerekir. |

Depolar arası bağlantılar GitHub'a gider; checkout komutları depoların yan yana
olduğunu varsayar. Kendi geliştirme/deployment ortamınızda JDK 21, Maven 3.9+ ve
GitHub Packages Maven erişimi gerekir; özel bir cluster zorunluluğu yoktur.
Değişen ortak kütüphaneleri tüketicilerden
önce derleyin; checkout Maven install değildir. Ayrı reader profillerini birlikte
açmayın; iki örneği 8080 portunda aynı anda bırakmayın.

İsteğe bağlı sample profil/flag'leri bu checkout'u anlatır; yeni kaynakların sample
uygulama 0.8.0 ile zaten yayımlandığı iddia edilmez. Seçilen checkout profilini
derleyin; POM sürümünün değişmemesi tek başına yayım kanıtı değildir.

## Özellik Seçimi Ve Okuma Maliyeti

| Özellik | Somut kaynak / çalıştırma tarifi | Sınır ve maliyet |
| --- | --- | --- |
| Generated app/modules | [Reader application](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/src/main/java/com/reactor/sample/cache/reader/app/RestSampleCacheReaderApplication.java), [platform kurulumu](../platform/README.tr.md) | APT startup/dispatch üretir; runtime classpath taraması değildir. Core örnekleri explicit module kullanır, deklaratif uygulama kapsamı sayılmaz. |
| Generated repository / JSON | [Platform üretim rehberi](../platform/README.tr.md), [ortak model](https://github.com/esasmer-dou/rust-sample-model/blob/master/README.tr.md), [provider repository](https://github.com/esasmer-dou/rest-sample-dubbo-provider/blob/master/src/main/java/com/reactor/sample/dubbo/provider/db/PostgresCustomerRepository.java) | Dört sample generated codec/mapper kullanır; gösterilen repository elle yazılmış JDBC'dir, generated repository interface kanıtı değildir. Burada bağımsız generated-repository uygulaması verilmez. |
| İsteğe bağlı L1 / owned cache JSON | [Generated reader tanımı](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/src/main/java/com/reactor/sample/cache/reader/service/CustomerCacheService.java), [reader kurulumu](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/README.tr.md) | `allowL1=true` yapılandırmaya izin verir; L1'in açıldığını kanıtlamaz. Hit sınırlı native bellek tutar; sürüm/tazelik kontrolü ve miss Redis işi gerektirir. Owned çıktı Java payload dizisini kaldırır, bütün kopya/allocation'ları değil. |
| HS256 / RSA / EC / Ed25519 / sınırlı JWT cache | [Starter](../platform/starter-security/README.tr.md), [çalışan issuer ve HTTP/hata adımları](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/JWT_SECURITY.tr.md), [native HS256](../NATIVE_JWT.tr.md) | Reader `jwt-security` seçilir; Redis/DB/RPC yoktur. Miss'te JCA imza kontrolü; cache varsayılan kapalı, entry/worker bütçeleri açıktır. Native edge HS256 ayrı seçimdir; geçerli trafik Java yetki kontrolüne girer. |
| W3C / retry başlık koruması | [Tracing starter](../platform/starter-tracing/README.tr.md), [reader trace profili](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/TRACE_PROPAGATION.tr.md) | `trace-propagation` ayrıdır; caller-buffer yazımı response cache değildir. Retry korunan başlıkları saklar; yan etkili yazmayı idempotency olmadan tekrar göndermeyin. |
| HTTP client / circuit breaker | [Starter](../platform/starter-http-client/README.tr.md), [reader outbound profili](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/OUTBOUND.tr.md) | `outbound` ayrıdır; varsayılan JDK taşıması TLS destekler. Native yalnız HTTP/1 kullanır, HTTPS reddedilir. Deadline, bağlantı, admission ve completion payını sınırlayın. |
| FFM / shared slab / OpenJ9 JNI | [Java katmanı](../platform/ffm-cache/README.tr.md), [çalışan reader](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/SHARED_SLAB.tr.md) | `shared-slab` ham Redis JSON okur: **her istekte bir GET**, L1 yoktur. OpenJ9'da JNI slab; FFM yalnız uygun HotSpot, preview/native-access bayrakları ve ayrı unwind binary ile. Dört 64-KiB slot 256 KiB ayırır; RESP çerçevesi wire bütçesinden düşer. |
| Ödünç binary/native view | [Çalışan producer ve reader](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/NATIVE_VIEW.tr.md) | `native-view` Redis/DB istemez. `/view` ödünç byte'ları doğrular ve JSON yazar; `/dto` bilinçli record/String üretir. Borrow senkron handler dönmeden biter. Rastgele JSON/Hessian çözülmez. |
| Generated direct Dubbo JSON / owned response | [Consumer handler](https://github.com/esasmer-dou/rest-sample-dubbo-consumer/blob/master/src/direct-json/java/com/reactor/sample/dubbo/consumer/directjson/DirectCatalogHandler.java), [çalıştırma rehberi](https://github.com/esasmer-dou/rest-sample-dubbo-consumer/blob/master/docs/DIRECT_JSON.tr.md) | `direct-json-consumer` catalog provider ister; **her istekte bir RPC**, response cache değildir. Consumer satır DTO'ları kurulmaz; sınırlı native Hessian sonucu tutulur ve JSON byte'ları yazılır. Consumer DTO constructor/normalization kuralları çalışmaz. |
| SWAR/hazır literal | [Literal handler](https://github.com/esasmer-dou/rest-sample-dubbo-consumer/blob/master/src/direct-json/java/com/reactor/sample/dubbo/consumer/directjson/SwarHandler.java), [build seçimi](https://github.com/esasmer-dou/rest-sample-dubbo-consumer/blob/master/docs/DIRECT_JSON.tr.md) | Derlemede `-Dreactor.json.swar=true`, temiz rebuild gerekir; runtime bayrağı değildir. Normal generated writer varsayılandır. Buffer büyümesi RPC tekrarı değildir. |
| AppCDS / jlink / OpenJ9 SCC | [Gerçek minimal profil, eğitim ve başlangıç](../platform/maven-plugin/README-appcds.tr.md#çalıştırılabilir-minimal-örnek) | Yalnız core örneği; dört sample'da açık değildir. HotSpot 21 `.jsa` ile OpenJ9 SCC farklıdır. `runtime-image` tarif üretir, kabul edilmiş image üretmez; thin JAR self-contained değildir. |
| Lifecycle / async deadline / backpressure | [Runtime rehberi](../README.tr.md), [consumer rota bütçeleri](https://github.com/esasmer-dou/rest-sample-dubbo-consumer/blob/master/src/main/java/com/reactor/sample/dubbo/consumer/handler/CustomerHandler.java) | Core native pending istekleri ve geç sonuç temizliğini yönetir. HTTP timeout işi iptal etmez; DB/RPC deadline'larını ayrıca sınırlayın. Sample bütçeleri kullanım örneğidir, yeni cancellation/slow-client kabul paketi değildir. |

Profillerin kaynak/resource/çıktı dizinleri ayrıdır. Birini seçip clean-package
edin; belgelenen main'i eşleşen runtime bağımlılıklarıyla başlatın, başarı **ve hata**
isteklerini deneyin. POM'a isteğe bağlı paket eklemek uygulamayı, native yetenekleri,
anahtarları veya dış servisleri kendiliğinden seçmez.

## Ortak Kontrattan Gerçek Process'e

| Kontrat | Üreten/provider | Tüketici ve çalıştırma adımları |
| --- | --- | --- |
| [StreamCatalogRow](https://github.com/esasmer-dou/rust-sample-model/blob/master/src/main/java/com/reactor/rust/dubbo/sample/dto/StreamCatalogRow.java) / [StreamCatalogService](https://github.com/esasmer-dou/rest-sample-utility/blob/master/src/main/java/com/reactor/rust/dubbo/sample/StreamCatalogService.java) | [Java implementation](https://github.com/esasmer-dou/rest-sample-dubbo-provider/blob/master/src/main/java/com/reactor/sample/dubbo/provider/service/StreamCatalogServiceImpl.java), [catalog main](https://github.com/esasmer-dou/rest-sample-dubbo-provider/blob/master/src/main/java/com/reactor/sample/dubbo/provider/streamcatalog/StreamCatalogProviderApplication.java); provider'ın `stream-catalog-provider` profilini kullanın. | [DirectCatalogClient](https://github.com/esasmer-dou/rest-sample-dubbo-consumer/blob/master/src/direct-json/java/com/reactor/sample/dubbo/consumer/directjson/DirectCatalogClient.java), [direct-json adımları](https://github.com/esasmer-dou/rest-sample-dubbo-consumer/blob/master/docs/DIRECT_JSON.tr.md); static adresi başlayan provider'a yöneltin. |
| [CustomerSegmentQuery](https://github.com/esasmer-dou/rust-sample-model/blob/master/src/main/java/com/reactor/rust/dubbo/sample/dto/CustomerSegmentQuery.java) / [CustomerQueryService](https://github.com/esasmer-dou/rest-sample-utility/blob/master/src/main/java/com/reactor/rust/dubbo/sample/CustomerQueryService.java) | [CustomerQueryServiceImpl](https://github.com/esasmer-dou/rest-sample-dubbo-provider/blob/master/src/main/java/com/reactor/sample/dubbo/provider/service/CustomerQueryServiceImpl.java); static catalog-only değil, provider DB kurulumu gerekir. | [CustomerHandler](https://github.com/esasmer-dou/rest-sample-dubbo-consumer/blob/master/src/main/java/com/reactor/sample/dubbo/consumer/handler/CustomerHandler.java); normal provider/consumer README DB adımlarını izleyin. Tipli sonuç nesne üretir; native JSON ayrı seçimdir. |
| [AccountSnapshot](https://github.com/esasmer-dou/rust-sample-model/blob/master/src/main/java/com/reactor/sample/model/account/AccountSnapshot.java) | Reader [native-view tarifindeki](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/NATIVE_VIEW.tr.md) generated binary producer. | Aynı profilin `/dto` karşılaştırma rotası; sade record kayıtlı serializer veya bağımsız servis değildir. |

Model/utility kontrat kütüphanesidir; uygulama olarak başlamaz. İki tarafta da
0.5.0 kullanın. Runtime Dubbo 0.9.0, bağımsız yayımlanan Spring annotation 0.8.0
paketinden ayrıdır; [utility bağımlılık gerekçesi](https://github.com/esasmer-dou/rest-sample-utility/blob/master/README.tr.md).
