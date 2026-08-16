# Rust-Java REST Uyumluluk Sample'ı

[English](README.md) | [Türkçe](README.tr.md)

Bu modül `rust-java-rest` için tam uyumluluk, diagnostics ve benchmark fixture uygulamasıdır. Yeni
production servisi için önerilen template değildir.

İçinde çok sayıda handler, response stratejisi, WebSocket örneği, diagnostics ve yalnız benchmark
için kullanılan route bulunur. Bu sınıflar yayınlanan production framework JAR'ından ayrıdır.

## Doğru Başlangıç Noktasını Seçin

| Hedef | Başlangıç |
| --- | --- |
| Yeni servis geliştirmek | Platform parent ve en küçük starter |
| Tek endpoint biçimini öğrenmek | [`../examples`](../examples/README.tr.md) |
| Response yollarını karşılaştırmak | Bu compatibility sample |
| Minimal production RSS ölçmek | Bu sample değil, `benchmark/minimal-production` |

Bu sample JAR'ını uygulama dependency'si olarak eklemeyin.

## Derleme ve Çalıştırma

`rust-java-rest` dizininde çalıştırın:

```powershell
mvn clean install
mvn -f sample/pom.xml clean package
java -jar sample/target/rust-java-rest-4.5.3-sample.jar
```

Benchmark çalıştırmadan önce process'i doğrulayın:

```powershell
curl http://localhost:8080/app/health
curl http://localhost:8080/diagnostics/routes
```

İlk endpoint sağlıklı olmalıdır. Route diagnostics, production ve benchmark-only route'ları açıkça
ayırmalıdır. Binary veya profile değiştirmeden önce process'i durdurun.

Uygulama `server.port` ve diğer runtime limitlerini
`sample/src/main/resources/rust-spring.properties` ile dış overlay dosyalarından okur.

## Artefact'ler

| İhtiyaç | Artefact |
| --- | --- |
| Production dependency | `com.reactor:rust-java-rest:4.5.3` |
| Full compatibility uygulaması | `sample/target/rust-java-rest-4.5.3-sample.jar` |
| Tek parça minimal benchmark classpath | `target/rust-java-rest-4.5.3-core-runtime.jar` |
| Build-only processor'lar | `rust-java-rest-4.5.3-codegen.jar` |

Codegen JAR annotation-processor path üzerinde bulunur. Runtime dependency değildir.

## Benchmark Güvenliği

- Production route ile benchmark-only route'u ayırın.
- Useful `200` RPS, p99, `503` oranı ve container RSS değerlerini birlikte raporlayın.
- İki build'i karşılaştırmadan önce aynı route setini warm edin.
- Bu full sample'ın RSS değerini framework baseline olarak kullanmayın. Class ve route yüzeyi normal
  bir servisten bilinçli olarak daha büyüktür.
- Aynı build içinde paketlenen native binary'leri kullanın. Güncel kaynak ağacı REST ABI `29` bekler.

Normal uygulama geliştirmek için
[beş dakikada başlangıç](../README.tr.md#beş-dakikada-başlangıç) bölümüne dönün.
