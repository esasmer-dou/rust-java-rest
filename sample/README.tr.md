# Rust-Java REST Uyumluluk Sample'ı

[English](README.md) | [Türkçe](README.tr.md)

Bu modül `rust-java-rest` için tam uyumluluk ve diagnostics uygulamasıdır. Yeni production servisi
için önerilen template değildir.

İçinde çok sayıda handler, response stratejisi, WebSocket örneği ve diagnostics bulunur. Bu sınıflar
yayınlanan production framework JAR'ından ayrıdır.

## Doğru Başlangıç Noktasını Seçin

| Hedef | Başlangıç |
| --- | --- |
| Yeni servis geliştirmek | Platform parent ve en küçük starter |
| Tek endpoint biçimini öğrenmek | [`../examples`](../examples/README.tr.md) |
| Birden fazla özelliği birlikte incelemek | Bu compatibility sample |

Bu sample JAR'ını uygulama dependency'si olarak eklemeyin.

## Derleme ve Çalıştırma

`rust-java-rest` dizininde çalıştırın:

```powershell
mvn clean install
mvn -f sample/pom.xml clean package
java -jar sample/target/rust-java-rest-4.5.6-sample.jar
```

Uygulama başladıktan sonra process'i doğrulayın:

```powershell
curl http://localhost:8080/app/health
curl http://localhost:8080/diagnostics/routes
```

İlk endpoint sağlıklı olmalıdır. Binary veya profile değiştirmeden önce process'i durdurun.

Uygulama `server.port` ve diğer runtime limitlerini
`sample/src/main/resources/rust-spring.properties` ile dış overlay dosyalarından okur.

## Artefact'ler

| İhtiyaç | Artefact |
| --- | --- |
| Production dependency | `com.reactor:rust-java-rest:4.5.6` |
| Full compatibility uygulaması | `sample/target/rust-java-rest-4.5.6-sample.jar` |
| Tek parça yalın runtime classpath | `target/rust-java-rest-4.5.6-core-runtime.jar` |
| Build-only processor'lar | `rust-java-rest-4.5.6-codegen.jar` |

Codegen JAR annotation-processor path üzerinde bulunur. Runtime dependency değildir.

## Runtime Güvenliği

- Bu full sample'ın RSS değerini framework baseline olarak kullanmayın. Class ve route yüzeyi normal
  bir servisten bilinçli olarak daha büyüktür.
- Aynı build içinde paketlenen native binary'leri kullanın. Güncel kaynak ağacı REST ABI `29` bekler.

Normal uygulama geliştirmek için
[beş dakikada başlangıç](../README.tr.md#beş-dakikada-başlangıç) bölümüne dönün.
