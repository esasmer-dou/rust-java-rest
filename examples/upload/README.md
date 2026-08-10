# Bounded Upload Example

[English](README.md) | [Türkçe](README.tr.md)

This module accepts one buffered multipart request and returns file metadata as producer JSON.
The route has an explicit `8 MiB` body limit.

```powershell
mvn -f ../pom.xml -pl upload exec:java `
  "-Dexec.mainClass=com.reactor.examples.upload.UploadApplication"

curl.exe -X POST http://localhost:8082/api/v1/files `
  -F "file=@README.md"
```

Use this pattern for bounded small uploads. The request body is available to Java as `byte[]` before
multipart parsing. For large media or multi-gigabyte files, design a streaming/native upload path or
upload directly to object storage. Do not increase the limit without an in-flight byte budget and a
concurrency gate.
