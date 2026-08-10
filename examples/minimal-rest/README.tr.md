# Minimal REST Örneği

[English](README.md) | [Türkçe](README.tr.md)

En küçük çalışan HTTP örneğidir. `8080` portunda tek bir hazır JSON health response açar.

```powershell
mvn -f ../pom.xml -pl minimal-rest exec:java `
  "-Dexec.mainClass=com.reactor.examples.minimal.MinimalApplication"
curl http://localhost:8080/app/health
```

Beklenen body:

```json
{"status":"UP","service":"minimal-rest"}
```

Örneği iki Java dosyasında tutmak için küçük explicit module kullanılır. Normal production servisi;
platform parent, `rust-java-starter-rest`, `@ReactorApplication` ve constructor injection kullanmalıdır.

Hazır JSON biçimini yalnız gerçekten sabit veya daha önce hazırlanmış içerik için alın. Normal
dinamik business response için record kullanın.
