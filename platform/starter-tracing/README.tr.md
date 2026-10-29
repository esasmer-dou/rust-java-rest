# Tracing Starter

[English](README.md) | [Turkish](README.tr.md)

Bir isteğin izleme kimliğini Java handler'dan sonraki HTTP servisine taşır.
Bu modül isteğe bağlıdır. Java iş mantığını ve native HTTP taşımasını değiştirmez.

## İçindekiler

- [Tek bir route ile başlayın](#tek-bir-route-ile-başlayın)
- [Retry sırasında başlıkları koruyun](#retry-sırasında-başlıkları-koruyun)
- [Hazır buffer'a yazın](#hazır-buffera-yazın)
- [Ayarlar ve maliyet](#ayarlar-ve-maliyet)
- [Çalışan örnek](#çalışan-örnek)

## Tek Bir Route İle Başlayın

Platform BOM sürümünüzle `com.reactor:rust-java-starter-tracing` bağımlılığını ekleyin.
Üretilmiş HTTP istemcisi için `rust-java-starter-http-client` da eklenir.

```properties
reactor.tracing.enabled=true
reactor.tracing.annotated-only=true
reactor.tracing.sample-ratio=0.01
```

```java
@GetMapping("/orders")
@Traced("orders.list")
public CompletionStage<RawResponse> list() {
    return inventory.list().thenApply(RawResponse::json);
}
```

Buradaki `inventory`, `@ReactorHttpClient` ile üretilen istemcidir.
Çağrıyı handler içinde başlatın. Başlık sağlayıcısı, handler stage'i dönmeden önce
aktif context'i okur. Elle trace başlığı yazmanız veya dinamik proxy kullanmanız gerekmez.

Geçerli bir `traceparent` gelirse trace ID korunur ve yeni bir yerel span ID üretilir.
Gelen span ID, `TraceSpan.parentSpanId()` alanına yazılır. Sonraki servise gönderilen
başlıkta **yerel** span ID bulunur. Gelen parent ID doğrudan tekrar gönderilmez.
Başlık yoksa veya geçersizse yeni bir kök iz başlar. Tamamı sıfır kimlikler,
büyük harfli hex değerleri ve `ff` sürümü reddedilir. `00` sürümü tam 55 karakter
olmalıdır. `01..fe` sürümlerinde geçerli önekten sonraki ek alanlar tolere edilir.

## Retry Sırasında Başlıkları Koruyun

HTTP istemcisi mantıksal isteği çağıran thread'de bir kez hazırlar.
Retry denemeleri aynı isteği ve yakalanan başlıkları kullanır.
`traceparent` ve uygulamanın verdiği idempotency key bu kapsamdadır.
Thread'in context'i sonradan temizlense veya değişse bile bekleyen retry değişmez.
Her **yeni** mantıksal istek kendi context'ini alır.

Tracing, retry'ı kendiliğinden açmaz. Yalnız güvenle tekrarlanabilen işlemlerde açın:

```java
@HttpExchange(path = "/inventory", timeoutMs = 2000, retries = 2, idempotent = true)
CompletionStage<byte[]> list();
```

İdempotency key, tek başına bir yazma işlemini güvenli yapmaz.
Karşı servis aynı anahtarla gelen yazmaları tekrar uygulamamalıdır.
Retry sınırı, timeout, backoff ve eşzamanlı istek sınırı geçerliliğini korur.
İşi başka bir executor'a taşıyorsanız context'i açıkça aktarın.
Örneğin `executor.execute(TraceContext.wrap(task))` kullanabilirsiniz.

## Hazır Buffer'a Yazın

```java
TraceContext context = TraceContext.currentOrNull();
if (context != null) {
    int written = W3cTraceparent.write(context, target, offset);
    // written == 55. target ve offset, uygulamanızın sınırlı buffer'ına aittir.
}
```

`write`, önce doğrulama yapar. Sonra geçici dizi veya String üretmeden 55 ASCII
byte yazar. Hedef aralığı yazmadan önce kontrol eder. HTTP'nin String başlık API'si
yine bir String üretir. Parse, span ve HTTP istek hazırlığı için sıfır tahsisat
iddiası yoktur. Bu garanti yalnız hazır context ve buffer ile çağrılan
`W3cTraceparent.write` içindir.

## Ayarlar Ve Maliyet

| Property | Varsayılan | Ne işe yarar? |
| --- | --- | --- |
| `reactor.tracing.enabled` | `false` | Açıldığında tracing guard'larını kurar. Kapalıysa exporter yüklenmez. |
| `reactor.tracing.annotated-only` | `false` | `true` olduğunda yalnız `@Traced` route'ları seçilir. |
| `reactor.tracing.sample-ratio` | `0.01` | Yeni kök izlerin örnekleme oranıdır. `0.0..1.0` aralığındadır. Geçerli gelen başlığın sampled biti önceliklidir. |

Yalnız birkaç route'u izlemek için annotated-only seçimini kullanın.
Gelen istek sampled ise yerel oran sıfır olsa da span dışa aktarılır.
Bu oran kesin bir trafik sınırlayıcı değildir. Örneklenmeyen isteklerde de
context sonraki servise taşınır.

Varsayılan exporter, tamamlanan sampled span'leri yerel framework metriklerine ekler.
Başka bir sisteme göndermek için `ServiceLoader` üzerinden tek bir `TraceExporter`
sağlayın. Exporter tamamlayan thread'de çalışır. Modül yeni bir thread pool açmaz.
Exporter'ı bloklamayan ve sınırlı bellek kullanan bir yapıda tutun.
Sınırsız span listesi biriktirmeyin. Bu modül Glowroot agent kurmaz.
İstek gövdelerini, gizli bilgileri veya uygulama loglarını otomatik toplamaz.

Bellek öncelikli profillerde Java metrik toplama kapalı olabilir.
Bu durumda varsayılan exporter sayaç biriktirmez.
Sayaçlar gerekiyorsa `reactor.metrics.collection-enabled=true` verin.
Alternatif olarak kendi sınırlı exporter'ınızı sağlayın.

`TraceSpan` sekizinci alan olarak `parentSpanId` içerir.
Yedi argümanlı eski constructor korunur; parent değeri null olur.
Native ABI değişmez.

## Çalışan Örnek

Cache-reader projesindeki ayrı `trace-propagation` Maven profili bu akışı gösterir.
Gelen başlık gerçek bir HTTP çağrısıyla sonraki servise taşınır.
Async yanıt ve kontrollü `503 -> 503 -> 200` retry örneği vardır.
Varsayılan Redis reader JAR'ına tracing sınıfları veya bağımlılıkları eklenmez.

[Örnek kullanım rehberine geçin](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/TRACE_PROPAGATION.tr.md).
