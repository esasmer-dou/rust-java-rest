# Minimal REST Example

[English](README.md) | [Türkçe](README.tr.md)

The smallest runnable HTTP example. It exposes one prebuilt JSON health response on port `8080`.

```powershell
mvn -f ../pom.xml -pl minimal-rest exec:java `
  "-Dexec.mainClass=com.reactor.examples.minimal.MinimalApplication"
curl http://localhost:8080/app/health
```

Expected body:

```json
{"status":"UP","service":"minimal-rest"}
```

This example uses an explicit tiny module to keep the demo in two Java files. A normal production
service should use the platform parent, `rust-java-starter-rest`, `@ReactorApplication`, and
constructor injection.

Copy the ready JSON pattern only for content that is truly static or already prepared. Use records
for normal dynamic business responses.
