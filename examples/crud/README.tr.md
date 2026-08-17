# CRUD Örneği

[English](README.md) | [Türkçe](README.tr.md)

Record input, validation, generated JSON write ve temel REST verb'lerini gösterir. Veri yalnız
memory içinde tutulur. Map yerine kendi service ve repository katmanınızı koyun.

## Çalıştırma

```powershell
mvn -f ../pom.xml -pl crud exec:java `
  "-Dexec.mainClass=com.reactor.examples.crud.CrudApplication"
```

## Endpoint'ler

| Metot | Path | Sonuç |
| --- | --- | --- |
| `POST` | `/api/v1/products` | Ürün oluşturur |
| `GET` | `/api/v1/products/{id}` | Tek ürün getirir |
| `GET` | `/api/v1/products` | Ürünleri listeler |
| `PATCH` | `/api/v1/products/{id}` | Örnek alanları değiştirir |
| `DELETE` | `/api/v1/products/{id}` | Ürünü siler |

```powershell
curl.exe -X POST http://localhost:8081/api/v1/products `
  -H "Content-Type: application/json" `
  -d '{"name":"Klavye","priceCents":259900}'

curl http://localhost:8081/api/v1/products/1
curl http://localhost:8081/api/v1/products

curl.exe -X PATCH http://localhost:8081/api/v1/products/1 `
  -H "Content-Type: application/json" `
  -d '{"name":"Mekanik Klavye","priceCents":319900}'

curl.exe -i -X DELETE http://localhost:8081/api/v1/products/1
```

Beklenen status sırası `201`, `200`, `200`, `200`, `204` şeklindedir. Silinen id yeniden çağrılırsa
`404` döner. Geçersiz ad veya negatif fiyat service logic'e girmeden validation hatası döndürür.

Validation request record üzerinde kalmalıdır. Business kararı service içinde olmalıdır. Production
ortamında bounded DB pool ve repository kullanın. In-memory map'i shared mutable domain model olarak
kullanmayın.

Upload, streaming veya WebSocket yolunu seçmek için [örnekler dizinine](../README.tr.md) dönün.
