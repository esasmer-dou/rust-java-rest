# Streaming ve Büyük Response Örneği

[English](README.md) | [Türkçe](README.tr.md)

Bu modül, büyük body'yi Java DTO graph üzerinden taşımayan iki response yolunu gösterir.

| Endpoint | Response tipi | Veri akışı |
| --- | --- | --- |
| `GET /api/v1/orders/export` | `FileResponse` | Rust dosyayı bounded concurrency ile stream eder |
| `GET /api/v1/orders/live?count=100` | `JsonProducerResponse` | Java JSON'u doğrudan native response buffer'a yazar |

## Çalıştırma

`sample.export-file` değerini okunabilir bir dosya olarak verin:

```powershell
mvn -f ../pom.xml -pl streaming exec:java `
  "-Dexec.mainClass=com.reactor.examples.streaming.StreamingApplication" `
  "-Dsample.export-file=README.md"
```

```powershell
curl "http://localhost:8083/api/v1/orders/live?count=100"
curl.exe -OJ http://localhost:8083/api/v1/orders/export
```

## Doğru Yolu Seçin

- Diskte hazır duran dosya ve export için `FileResponse` kullanın.
- Büyük JSON dizisi `List<DTO>` oluşturmadan sırayla yazılabiliyorsa `JsonProducerResponse` kullanın.
- Küçük business payload için normal record response kullanın. Producer her endpoint için otomatik
  olarak daha iyi değildir.
- Kullanıcıdan gelen adet değerine açık üst sınır koyun. Bu örnek `count` değerini `10_000` ile sınırlar.
- File stream concurrency değerini yalnız disk throughput, p99, `503` ve container RSS ölçümünden
  sonra değiştirin.

Dosyanın tamamını Java heap'e almayın. Yalnız bir kez serialize etmek için büyük object graph
oluşturmayın. İki yaklaşım da business modele katkı sağlamadan allocation ve retained memory artırır.
