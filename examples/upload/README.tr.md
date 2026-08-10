# Bounded Upload Örneği

[English](README.md) | [Türkçe](README.tr.md)

Tek bir buffered multipart request alır. Dosya metadata bilgisini producer JSON olarak döner. Route
üzerinde açık `8 MiB` body limiti vardır.

```powershell
mvn -f ../pom.xml -pl upload exec:java `
  "-Dexec.mainClass=com.reactor.examples.upload.UploadApplication"

curl.exe -X POST http://localhost:8082/api/v1/files `
  -F "file=@README.md"
```

Bu biçimi sınırlı ve küçük upload için kullanın. Multipart parse işleminden önce request body Java
tarafında `byte[]` olur. Büyük medya veya çok büyük dosya için streaming/native upload yolu tasarlayın
ya da doğrudan object storage kullanın. In-flight byte bütçesi ve concurrency gate olmadan limiti
artırmayın.
