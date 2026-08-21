# Derlenerek Doğrulanan REST Örnekleri

[English](README.md) | [Türkçe](README.tr.md)

Her modül tek bir framework özelliğini gösterir. Örnekler küçük tutulur. Böylece full compatibility
sample'ını yeni servisinize taşımadan yalnız ihtiyacınız olan kodu inceleyebilir ve kopyalayabilirsiniz.

Yeni production projesinde [ana README](../README.tr.md#beş-dakikada-başlangıç) içindeki platform
parent ve starter yolunu kullanın. Bu örnekler, her demoyu birkaç dosyada tutmak için küçük explicit
module kullanır.

## Gereksinimler

- Java 21 ve Maven 3.9+.
- Uyumlu `rust-java-rest:4.6.0` paketi ve onunla gelen DLL/SO.
- Seçtiğiniz modül için boş bir lokal port.

Her modül examples reactor tarafından derlenir. README örneği ile source ayrışırsa source modülü ve
testleri doğrudur; aynı değişiklik içinde README'yi de düzeltin.

## Örneği Seçin

| Modül | Port | Gösterdiği özellik | Ne zaman kopyalanır? |
| --- | ---: | --- | --- |
| `minimal-rest` | `8080` | Hazır health JSON | En küçük çalışan HTTP yapısı gerektiğinde |
| `crud` | `8081` | Record input, validation, GET, POST, PATCH, DELETE | Normal business API geliştirirken |
| `upload` | `8082` | Bounded request body ve JSON producer response | Dosya veya binary içerik alırken |
| `streaming` | `8083` | `FileResponse` ve object graph kurmayan JSON | Export veya büyük dinamik liste dönerken |
| `websocket` | `8084` | Bounded WebSocket session ve echo akışı | İki yönlü mesaj gerektiğinde |

## Tüm Örnekleri Derleyin

`rust-java-rest` dizininde çalıştırın:

```powershell
mvn -f examples/pom.xml clean package
```

Build, generated source ve startup index'lerini de doğrular. Generated dosyalar her modülün `target`
dizinindedir. Bu dosyaları elle değiştirmeyin.

## Tek Bir Örneği Çalıştırın

Minimal REST:

```powershell
mvn -f examples/pom.xml -pl minimal-rest exec:java `
  "-Dexec.mainClass=com.reactor.examples.minimal.MinimalApplication"
curl http://localhost:8080/app/health
```

CRUD:

```powershell
mvn -f examples/pom.xml -pl crud exec:java `
  "-Dexec.mainClass=com.reactor.examples.crud.CrudApplication"
```

```powershell
curl.exe -X POST http://localhost:8081/api/v1/products `
  -H "Content-Type: application/json" `
  -d '{"name":"Klavye","priceCents":259900}'

curl http://localhost:8081/api/v1/products/1
curl http://localhost:8081/api/v1/products

curl.exe -X PATCH http://localhost:8081/api/v1/products/1 `
  -H "Content-Type: application/json" `
  -d '{"name":"Mekanik Klavye","priceCents":319900}'

curl.exe -X DELETE http://localhost:8081/api/v1/products/1
```

Streaming ve dosya response:

```powershell
mvn -f examples/pom.xml -pl streaming exec:java `
  "-Dexec.mainClass=com.reactor.examples.streaming.StreamingApplication" `
  "-Dsample.export-file=README.md"

curl "http://localhost:8083/api/v1/orders/live?count=100"
curl.exe -OJ http://localhost:8083/api/v1/orders/export
```

## Neyi Kopyalamalısınız?

- Application, handler, record ve en küçük property setini kopyalayın.
- In-memory map yerine kendi service ve repository sınırınızı koyun.
- Normal uygulamayı `@ReactorApplication` ve constructor injection ile başlatın.
- Küçük business JSON için record kullanın.
- Büyük dinamik JSON için producer kullanın.
- Hazır body için `RawResponse` kullanın.
- Dosya için `FileResponse` kullanın.
- Yalnız gerçek process'in ihtiyaç duyduğu starter'ı ekleyin.

## Neyi Kopyalamamalısınız?

- Büyük dosyayı Java `byte[]` içine aldıktan sonra döndürmeyin.
- Yalnız serialize etmek için büyük DTO listesi kurmayın.
- Bounded admission yerine sınırsız executor veya queue kullanmayın.
- Full `sample` JAR'ını production dependency olarak eklemeyin.

Eski [`sample`](../sample/README.md) modülü tam uyumluluk uygulaması olarak korunur. Production
framework runtime'ından bilinçli olarak ayrıdır.
