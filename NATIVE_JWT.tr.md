# Geçersiz HS256 Token'ını JNI'den Önce Reddetme

[English](NATIVE_JWT.md) | [Turkish](NATIVE_JWT.tr.md)

Security starter runtime classpath içinde kalmalıdır. JAR'ları birleştirirken
`META-INF/services` kayıtlarını silmeyin. Deployment sonrasında yalnız geçersiz
token'ın 401 aldığını değil, reader kullanıcısının admin rotasında 403 aldığını
da kontrol edin. Böylece Java rol kontrolünün de kurulduğunu doğrularsınız.

Bu özellik kaynak kod deposundadır. Yayımlanmış 4.8.0 native paketinde henüz yoktur.
Native kütüphaneyi `--features edge-jwt` ile derleyin. REST ABI 29 değişmez.

## Ne Zaman Kullanılır?

HS256 ile korunan API'nize çok sayıda geçersiz bearer isteği geliyorsa bu filtreyi
kullanın. Rust, geçersiz token'ı JNI kuyruğuna girmeden 401 ile reddeder.
Geçerli token Java'ya gider. Yerleşik HS256 guard, Rust'ın imza kanıtını kullanır.
Aynı HMAC Java'da ikinci kez hesaplanmaz. Kimlik, süre, rol ve iş yetkisi Java'da
kontrol edilir. Özel verifier uygulamaları kendi tam doğrulama yolunda kalır.
Bu özellik yetki kontrolünü atlamaz. Yanıtları cache'e de koymaz.

RSA, P-256, Ed25519, özel subject alanı, dinamik anahtar sağlayıcısı veya legacy
hata JSON'u kullanıyorsanız Java yolunda kalın. Native filtre HS256, `sub` ve
standart problem-details hata biçimini destekler. Ağdan anahtar veya JWKS indirmez.

## Korumalı Endpoint'i Tanımlayın

```java
package example;

import com.reactor.rust.annotations.GetMapping;
import com.reactor.rust.annotations.RestController;
import com.reactor.rust.annotations.RustEdgeJwt;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.security.Authenticated;
import java.nio.charset.StandardCharsets;

@RestController("/accounts")
public final class AccountApi {
    private static final RawResponse OK = RawResponse.json(
            "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8));

    @Authenticated(roles = "reader")
    @RustEdgeJwt
    @GetMapping("/me")
    public RawResponse me() { return OK; }
}
```

Security starter bağımlılığını koruyun. Derleyici `AccountApi_EdgeJwtPolicies`
sınıfını üretir. Uygulamanızın başlangıcında ayarları yükledikten sonra bu sınıfı
çağırın. Çağrı, **HTTP rotaları kaydedilmeden önce** yapılmalıdır:

```java
PropertiesLoader.load();
AccountApi_EdgeJwtPolicies.install();
RestApplication.run(Application.class, args);
```

Import'lar `com.reactor.rust.config.PropertiesLoader` ve
`com.reactor.rust.app.RestApplication` şeklindedir. `Application`, mevcut
`@ReactorApplication` sınıfınızdır. Yalnız anotasyon eklemek native yolu açmaz.
[Çalışan sample](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/OUTBOUND.tr.md),
bu çağrıyı `sample.edge.jwt.enabled` ayarına göre yapar.

Korumalı rotayı public edge cache veya `@PermitAll` ile birleştirmeyin. Derleyici
bu birleşimleri reddeder. Native static/file yolu da Java guard'ın yerine
geçemez. Installer'ı bir kez çağırın. Rotalar başladığında kurallar sabitlenir.

## Java ve Rust Aynı Ayarları Kullansın

```properties
reactor.security.enabled=true
reactor.security.jwt.issuer=sample-issuer
reactor.security.jwt.audience=stock-api
reactor.security.jwt.subject-claim=sub
reactor.security.jwt.require-expiration=true
reactor.security.jwt.clock-skew-seconds=0
reactor.security.jwt.max-token-chars=8192
reactor.rust.errors.format=problem-details
```

`REACTOR_SECURITY_JWT_HMAC_SECRET` değerini deployment secret üzerinden verin.
Repoya yazmayın. Ortak ayar grubu `reactor.security.jwt` olmalıdır. Farklı prefix
verirseniz derleme reddedilir. Java ve Rust sessizce farklı kurallarla başlamaz.

| Property | Ne işe yarar? / Sınır |
| --- | --- |
| `reactor.security.enabled` | `true` kalır. Java yetki kontrolü zorunludur. |
| `reactor.security.jwt.hmac-secret` | 32–1024 UTF-8 byte. Güçlü ve rastgele bir secret kullanın. |
| `reactor.security.jwt.issuer` | Doluysa issuer birebir eşleşir. En çok 256 UTF-8 byte. |
| `reactor.security.jwt.audience` | Doluysa audience eşleşir. Tek değer veya en çok 32 değer desteklenir. |
| `reactor.security.jwt.require-expiration` | Varsayılan `true`. `exp` yoksa token reddedilir. |
| `reactor.security.jwt.clock-skew-seconds` | 0–300 saniye. `exp` ve `nbf` için saat toleransıdır. |
| `reactor.security.jwt.max-token-chars` | 64–8192. Token kanonik base64url biçiminde olmalıdır. |

Geçersiz imza, farklı algoritma, desteklenmeyen JOSE başlığı, yinelenen kayıtlı
claim ve çift Authorization başlığı reddedilir. Süresi dolan token ve henüz
başlamamış `nbf` değeri de reddedilir. Çözülmüş header en çok 1 KiB olabilir.
Kaçışlı JSON metinleri token sınırı içinde desteklenir.

## Bellek ve Yaşam Döngüsü

Rota, küçük bir kural kimliği taşır. Kuralın kendisi süreç boyunca sabit tabloda
tutulur. Her istekte kural için `Arc` clone/drop yapılmaz. Geçersiz kimlik veya
henüz kurulmamış tablo, geçerli token gibi kabul edilmez.

Her kural sabit 32 slot içinde doğrulanmış token özeti ve zaman bilgisini tutar.
Cache araması, token'ın tamamından üretilen 256-bit BLAKE3 özetiyle yapılır.
Bu yalnız iç arama anahtarıdır. HS256 imzası değişmez; HMAC-SHA256 olarak kalır.
Ek C/assembler aracı gerekmeyen Rust SIMD yolu kullanılır. Bir rate-only edge
kuralı varsa aşırı yük, imza işinden önce reddedilebilir. Korumalı rota public
cache ile birleştirilemez.
Ham token veya kullanıcı payload'ı saklanmaz. Her cache isabetinde `exp` ve `nbf`
yeniden kontrol edilir. Java da guard çalıştığı andaki süreyi kontrol eder.
JNI kuyruğunda beklerken süresi dolan token reddedilir.

Üretilmiş installer, Java metodunu native kurala bağlar. Başlangıçta anahtar,
issuer, audience, boyut, saat toleransı ve expiration ayarları karşılaştırılır.
Ayarlar farklıysa veya güvenlik SPI kaydı yoksa uygulama başlamaz.
İstemcinin gönderdiği hiçbir HTTP başlığı doğrulama kanıtı sayılmaz.

Kanıt, kural kimliği ve token'ın tam özetidir. Native istek işiyle taşınır.
Yalnız o işin JNI çağrısı boyunca geçerlidir. Farklı token, rota, thread veya
sonraki callback bu kanıtı kullanamaz. Kontrol, sınırlı stack buffer'ları kullanır.
Yeni token byte dizisi oluşturmaz. Native kanıt olmadan guard'ı doğrudan çağırmak
reddedilir. Java yetki kontrolü zorunlu kalır.

Özel verifier değiştirilmez. İptal ve ek kuralları çalışmaya devam eder.
Alt seviye `registerFromProperties` çağrısı tek başına Java metodunu bağlamaz.
Bu çağrı tek başına kullanılırsa Java HMAC kontrolü kaldırılmaz.

Yenileme thread'i, ağdan anahtar okuma veya kullanıcı sayısıyla büyüyen map yoktur.
En çok 64 rota kaydedilebilir. Anahtarı, issuer'ı veya audience'ı değiştirince
uygulamayı yeniden başlatın. Dinamik ve farklı kurallı Java verifier ile bu sabit
anahtarlı filtreyi birleştirmeyin. Eksik native özellik başlangıçta açık hata verir.

Cargo özelliği kapalıysa native JWT yolu derlenmez. Özellikli binary kullanırken
yalnız açıkça kaydettiğiniz rotalar JWT kuralı alır. Bütün HTTP/JNI hattı sıfır
tahsisatlı olmaz. Her geçerli-token senaryosunda gecikme düşüşü de garanti edilmez.
Kendi trafiğinizle ölçün.
