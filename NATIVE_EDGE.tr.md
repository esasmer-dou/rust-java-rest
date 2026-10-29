# Herkese Açık GET Rotaları İçin Edge Kuralları (Henüz Yayımlanmadı)

Korumalı HS256 rotaları için ayrı [native JWT filtresini](NATIVE_JWT.tr.md)
kullanın. Geçersiz token JNI'dan önce reddedilir. Geçerli isteğin yetki kararı
Java'da kalır. Bu amaçla herkese açık yanıt cache'i kullanmayın.

Bu yol Java iş mantığının yerine geçmez. Yalnız herkese açık katalog gibi hazır
cache yanıtlarını hızlandırır. Native kütüphane `--features edge-cache` ile
derlenmelidir. Normal native pakette edge kontrolü ve edge slab tahsisatı yoktur.

Cache isabetinde Java handler ve Java guard çalışmaz. Kullanıcıya veya tenant'a
özel yanıtlar, yetki kontrolü ve her istekte çalışması gereken işler bu yola uygun
değildir. `@Authenticated` taşıyan cache rotası derlemede reddedilir. Metot açıkça
`@PermitAll` ise kabul edilir. Özel guard'larınızı ayrıca değerlendirmeniz gerekir.

```java
import com.reactor.rust.annotations.*;
import com.reactor.rust.cache.core.RustCache;
import com.reactor.rust.cache.http.CacheResponses;
import com.reactor.rust.cache.versioned.CacheFreshness;
import com.reactor.rust.cache.versioned.VersionedJsonCacheReader;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.http.ResponseEntity;

@RestController("/catalog")
public final class CatalogController {
    private final VersionedJsonCacheReader reader;
    public CatalogController(RustCache cache) {
        reader = VersionedJsonCacheReader.create(cache, "catalog", 1_000,
            new CacheFreshness(60_000, false));
    }
    @GetMapping("/{id}")
    @RustEdgeCache(versionKey = "catalog:current",
        dataKeyPrefix = "catalog:v:{version}:id:", pathParam = "id",
        maxAgeMs = 60_000, serveStale = false)
    @RustEdgeRateLimit(permitsPerSecond = 25_000, burst = 1_000)
    public ResponseEntity<RawResponse> byId(@PathVariable("id") long id) {
        return CacheResponses.problem(reader.getById(id));
    }
}
```

Başlangıçta, HTTP rotaları kaydedilmeden önce üretilen sınıfı açıkça bağlayın:

```java
RestApplication.run(context -> {
    var cache = context.manage(RustCaches.create(context.properties()));
    cache.installPublicEdgePolicies(id -> CatalogController_EdgePolicies.install(id, true));
    context.handlers(new CatalogController(cache));
});
```

Başlangıç örneğinde `com.reactor.rust.app.RestApplication` ve
`com.reactor.rust.cache.core.RustCaches` sınıflarını import edin. Yönetilen cache,
HTTP sunucusundan sonra kapanır. Küçük okuma istemcisini şu ayarlarla oluşturun:

```properties
reactor.cache.redis.host=127.0.0.1
reactor.cache.redis.port=6379
reactor.cache.redis.access-mode=read-only
reactor.cache.redis.read-connections=2
reactor.cache.redis.max-read-inflight=8
reactor.cache.redis.max-response-bytes=16384
```

Java fallback yolunda da aynı tazelik kuralını kullanın. Örnekte iki yol da
60 saniyeden eski veriyi reddeder. Farklı kurallar seçerseniz native yolun
reddettiği eski veri Java handler tarafından yeniden döndürülebilir.

`true`, bu rotaların herkese açık olduğunu onaylar. Cache bağlantısını HTTP
sunucusu kapanıp devam eden yanıtlar bitene kadar açık tutun. Redis istemcisinde
okuma izni ve en fazla `16384` bayt `max-response-bytes` gerekir. Böylece büyük bir
Redis yanıtı önce tahsis edilip sonra reddedilmez. Normal büyük limitli istemci
sessizce kullanılmaz; bu rotalar için küçük bir okuma istemcisi açın.

| Kural | Sonuç |
| --- | --- |
| Sürüm | Mevcut writer'ın `fence\|timestamp-id` veya `timestamp-id` değeri |
| Anahtar | Bir `{version}` içeren şablon ve URI kodlamalı path parametresi |
| Sürüm kontrolü | Yeni sürümün görünmesi en fazla 1 saniye gecikebilir; ek thread yok |
| L1 veri süresi | En fazla 1 saniye; Redis silme veya TTL sonucu sonraki okumada görülür |
| Rota başına cache | 32 slot, slot başına en fazla 8192 bayt payload |
| Süreç başına cache | Anahtar ve kontrol alanları dahil en fazla 2 MiB bütçe |
| Redis okuması | Tüm edge rotalarında aynı anda en fazla 8 okuma |
| Yavaş istemci | Yanıt bitmeden kullanılan slotun üzerine yazılmaz |
| Query, kimlik bilgisi veya body | Cache atlanır; Java normal çalışır |
| Veri yok, sürüm bilgisi geçersiz, snapshot eski veya yanıt büyük | Java normal çalışır |
| İzin verilen eski veri | HTTP 200 ve `X-Cache-Stale: true` |
| Hız limiti | Her replica ve rota için ortaktır; kullanıcı bazlı değildir |
| Yalnız hız limiti | GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS desteklenir; command cache edilmez |
| Limit aşımı | HTTP 429; handler için JNI çağrısı yapılmaz |

Kurallar `META-INF/reactor/edge-policies.tsv` dosyasına derlemede yazılır.
Rotalar kaydedildikten sonra kurallar değişmez. REST ABI 29 ve mevcut rota
kurucuları korunur. Bu açıklama performans kabulü anlamına gelmez. Etkinleştirilen
reçete ayrıca RSS, RPS ve p99 kapısından geçmelidir.

Yayımlanan gövde zaten geçerli UTF-8 JSON olmalıdır. Bu yol JSON gövdesini çözmez
ve doğrulamaz. Bu sözleşmenin sahibi Java yayıncıdır. Public bir kuralı rastgele
veya özel Redis anahtarlarına bağlamayın.

## Deklaratif Cache Başlangıcı (Kaynak Kod Önizlemesi)

Birlikte derlenmiş cache kaynak kodunda manuel kurulum yerine şu tanım kullanılabilir:

```java
@EnableRustCache(publicEdgePolicies = PublicCatalogHandler.class,
    publicEdgeEnabledProperty = "sample.public.edge.enabled")
@ReactorApplication(scanBasePackages = "com.example.catalog")
public final class CatalogApplication {
    public static void main(String[] args) {
        RestApplication.run(CatalogApplication.class, args);
    }
}
```

Import'lar: `com.reactor.rust.cache.integration.EnableRustCache`,
`com.reactor.rust.annotations.ReactorApplication` ve
`com.reactor.rust.app.RestApplication`. Cache kod üreteci, policy sınıfını doğrudan
çağıran başlangıç kodunu hazırlar. Kurulum başarısızsa istemciyi kapatır.
Property yalnız başlangıçta okunur. Verilmezse `false` kabul edilir.
Policy sınıfı seçilmeyen normal `@EnableRustCache` çıktısı değişmez.

Yalnız açıkça herkese sunulan, kullanıcıdan bağımsız veriler Java'yı atlayabilir.
Bu seçenek native JWT doğrulaması eklemez. Özel endpoint'lerin mevcut Java
güvenlik kontrolleri korunur. `rest-sample-cache-reader` projesindeki isteğe bağlı
`public-edge` derlemesi, müşteri verilerini açmadan public cache ve hız sınırı
için ayrı adresler gösterir. Bu özellikler kaynak kod önizlemesidir; mevcut
yayımlanmış paketlerin parçası değildir.

Cache kullanan string path parametrelerinde native hit için Java parametre
String'leri oluşturulmaz. Parametre yalnız Java yoluna dönülürse hazırlanır.
Primitive parametre doğrulaması değişmez. Gövde kopyalanmadan paylaşılır.
Yanıt eski gövdeyi tutarken slotun üzerine yazılmaz. Yanıt bayt bütçesi, son
bayt referansı bırakılana kadar korunur.
