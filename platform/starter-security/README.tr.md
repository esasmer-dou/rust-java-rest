# Açık JWT Doğrulama Seçimi

[English](README.md) | [Turkish](README.tr.md)

Aşağıdaki asimetrik doğrulama ve token önbelleği 4.8.0 ile yayımlanmıştır; isteğe bağlıdır.
Örnekteki sınırlı yapılandırma için EmaTech güvenlik,
HTTP ve eşlenmiş container RSS/RPS/p99 kontrolleri geçti. Bu sonuç her iş yükünün veya
yayımlanmış framework özelliklerinin tümünün kabul edildiği anlamına gelmez.

## Hangi Yolu Seçmeliyim?

| Durum | Seçim |
| --- | --- |
| Mevcut HS256 servisi | Property'leri koruyun; otomatik önbellek eklenmez |
| Aynı HS256 token'ı sık kullanılıyor | `VerifiedTokenCache.hmacFromProperties(...)` seçin |
| RSA, P-256 veya Ed25519 anahtarlarını siz yönetiyorsunuz | `AsymmetricJwtVerifier` kullanın |
| Asimetrik token'lar sık tekrarlanıyor | Doğrulayıcıyı `withCache(...)` ile sarın |

Ağdan anahtar arayan veya yenileyen thread başlatılmaz. Her anahtar kimliği tek bir
algoritmaya bağlıdır. Bilinmeyen anahtarlar, `none`, algoritma karışıklığı ve
desteklenmeyen JOSE uzantıları reddedilir. Token, yüklenecek dosya veya URL seçemez.

## HMAC Önbelleğini Açıkça Seçin

Mevcut sırrınızı deployment'ın secret kaynağında tutun. Uygulama property'leri:

```properties
reactor.security.enabled=true
reactor.security.jwt.issuer=https://issuer.internal.example
reactor.security.jwt.audience=orders-api
reactor.security.jwt.require-expiration=true
reactor.security.jwt.clock-skew-seconds=30
reactor.security.jwt.max-token-chars=8192
```

`REACTOR_SECURITY_JWT_HMAC_SECRET` değerini Kubernetes Secret veya eşdeğer bir secret
kaynağından verin. Sır en az 32 UTF-8 byte olmalıdır. Git'e koymayın.

```java
package example;

import com.reactor.rust.security.JwtVerifier;
import com.reactor.rust.security.JwtVerifierProvider;
import com.reactor.rust.security.VerifiedTokenCache;

public final class ApiJwtProvider implements JwtVerifierProvider {
    @Override public JwtVerifier create() {
        return VerifiedTokenCache.hmacFromProperties(
                new VerifiedTokenCache.Options(256, 4, 0, 256 * 1024));
    }
}
```

Bu başlangıç sağlayıcısını
`src/main/resources/META-INF/services/com.reactor.rust.security.JwtVerifierProvider`
dosyasına yazın:

```text
example.ApiJwtProvider
```

Mevcut `@Authenticated` ve `@PermitAll` endpoint'leriniz değişmez. En fazla bir
sağlayıcı seçin. Sağlayıcı yoksa mevcut önbelleksiz HS256 yolu kullanılır.

## Açılışta Güvenilir Public Key Verin

Bu kod, yukarıdaki `create()` metodunun gövdesi içindir. `trustedPublicKey`, güvenilir
deployment ayarınızdan aldığınız public key'dir. Token başlığından alınmaz.

```java
var keys = new com.reactor.rust.security.JwtKeySet(
        new com.reactor.rust.security.JwtKeySet.Key("orders-2026",
                com.reactor.rust.security.JwtAlgorithm.RS256, trustedPublicKey));
var policy = com.reactor.rust.security.JwtVerificationPolicy.forIssuerAndAudience(
        "https://issuer.internal.example", "orders-api");
var verifier = new com.reactor.rust.security.AsymmetricJwtVerifier(
        keys, policy, java.time.Clock.systemUTC(), 4);
return verifier.withCache(new VerifiedTokenCache.Options(256, 4, 0, 256 * 1024));
```

RS256, 2048–8192 bit RSA anahtarını kabul eder. ES256, P-256 ve 64 byte JOSE imzası
gerektirir. EdDSA burada Ed25519'dur; Ed448 değildir. Anahtar kümesi en fazla 64
anahtar tutar. Anahtar değişiminde doğrulayıcıyı ve önbelleğini birlikte yenileyin.
Kademeli deployment'ta geçiş süresince eski ve yeni public key'i birlikte kabul edin.
Sonra eski anahtarı kaldırın. `clear()`, dağıtık token iptal mekanizması oluşturmaz.

## İsteğe Bağlı Native HS256 Guard

Sabit anahtarlı HS256 rotalarında imza kontrolünü Rust'a vermek için
[`@RustEdgeJwt` rehberini](../../NATIVE_JWT.tr.md) kullanın. Üretilmiş installer ve
eşleşen native artifact gerekir. Yerleşik Java guard, aynı HMAC'ı tekrar hesaplamak
yerine o isteğe ait native kanıtı kullanır. Güncel süre, kimlik ve rol Java'da
kontrol edilir. JNI kuyruğunda süresi dolan token reddedilir. Güvenlik SPI kaydı
yoksa veya ayarlar farklıysa uygulama başlamaz. Özel verifier tam kontrol yolunda
kalır. Bu özellik aşağıdaki Java claim önbelleğinden ayrıdır. Native dosyanın
eşleşen `edge-jwt` yeteneği gerekir; yalnız REST ABI 29 yeterli değildir.

## Önbellek ve Kaynak Sınırları

| Örnekteki seçenek | Anlamı |
| --- | --- |
| `256` slot | Sabit kapasiteli, iki aday slotlu önbellek; kapasite ikinin kuvveti olmalıdır |
| `4` worker | Aynı anda yapılabilecek hash işi; doğrulayıcının ayrı kripto worker sınırı vardır |
| `0` eksik-exp TTL | Doğrulama izin verse bile `exp` bulunmayan token'ı önbelleğe alma |
| `256 * 1024` maxBytes | Başarılı girişlerin ve claim'lerin bellek bütçesi; toplam process RSS'i değildir |

Önbellek isabetinde token'ın **tamamının** hash'i alınır. Güncel zaman tekrar kontrol
edilir. İmza/MAC doğrulaması ve JSON ayrıştırma atlanır. Yalnız doğrulanmış sonuçlar
tutulur. Token metni saklanmaz; tam SHA-256 özeti saklanır. Hash çakışmasının
olmaması kriptografik bir varsayımdır. İlk eşzamanlı çağrılar birden fazla doğrulama yapabilir.

Byte bütçesi dolduğunda geçerli token, önbelleğe alınmadan doğrulanabilir. Worker
kapasitesi dolarsa beklemek yerine framework'ün `503` bağımlılık hatası döner. Retry
eklenmez. Geçersiz veya süresi dolan token `401` olarak kalır.

Byte bütçesi; sabit slot/hash yapısını, kripto worker'larını, anahtarları, JCA iç
belleğini, uygulamanın tuttuğu principal'ları ve JVM belleğini kapsamaz. Mantıksal
silme, RSS'in hemen düşeceği anlamına gelmez. İsabet, kaçırma, tutulan giriş/byte ve
kripto doğrulama sayaçlarını container RSS, başarılı RPS ve p99 ile birlikte izleyin.
Bearer token'ı loglamayın.

### Küçük ve Sabit Çalışma Alanı

Asimetrik imza girdisi ve cache hash işlemi, worker başına en fazla 256 byte'lık
yeniden kullanılan ASCII alanıyla çalışır. Daha büyük token'lar parçalar halinde
işlenir. Token boyut sınırı korunur. Başarılı ve başarısız çağrıdan sonra alan
silinir. SHA-256'nın 32 byte'ı da anahtarda tutulur; kısaltılmış hash kullanılmaz.

`maxBytes=0`, gerçek bir saklama bypass'ıdır. Kapasite sıfırdır. Hash worker ve
olumlu kayıt slotları oluşturulmaz. Her çağrı yine tam doğrulanır. Wrapper'ı da
istemiyorsanız doğrudan doğrulayıcıyı kullanın. Sabit alan tahmini JCA veya process
RSS ölçümü değildir.

Aynı güvenilir anahtar için hazırlanmış imza motoru yeniden kullanılır. Anahtar
değişirse veya işlem yarıda kalırsa yeniden hazırlanır. Bu davranış
[Java 21 Signature sözleşmesine](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/security/Signature.html#verify(byte%5B%5D))
uyar. Önbelleğe girmeyen istekte imza doğrulaması yine çalışır.

Boşta bekleyen cache'teki süresi dolmuş kayıtlar proaktif taranmaz. Eşleşen okuma,
yer değiştirme veya açık `clear()` çağrısı onları kaldırır. Bakım thread'i yoktur.
`clear()`, eski nesilde başlamış miss'in canlı tabloyu tekrar doldurmasını engeller.
Çalışmakta olan istekler için dağıtık iptal bariyeri değildir.

Worker sayısını gelen TCP bağlantısına göre değil, gerçekten paralel doğrulama
yapan çağrı sayısına göre seçin. Açık anahtar doğrulamasının CPU/JCA maliyeti vardır.
Cache, token tekrar kullanıldığında faydalıdır. Her token farklıysa imza işi bedelsiz
olmaz. Bellek bütçesini büyütmeden önce cache açık/kapalı ölçün.

Ayrı [JWT örneği](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/JWT_SECURITY.tr.md),
`@Authenticated`, güvenilir anahtar dosyası, cache property'leri ve Kubernetes
ortam değişkenleriyle birlikte JDK-only token üretimini ve hata kontrollerini gösterir.
Checkout'taki `jwt-security` profili Redis reader'ı değiştirmez.

Tahsisat ölçümü, küçük ve bellekte tutulan nesnelerle de doğrulanır. JVM sayacı
gecikmeli toplu değer veriyorsa bu ölçüm atlanır. Yanlış bir sıfır değeri başarı
sayılmaz. Kalibre edilmiş Java 21 HotSpot ölçümü ile OpenJ9 container kabulü ayrıdır.
