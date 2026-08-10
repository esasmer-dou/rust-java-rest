# Response Yolu Karşılaştırma Örneği

[English](README.md) | [Türkçe](README.tr.md)

Kontrollü response yolu karşılaştırması için üç route açar. Business API değildir.

| Route | Yol |
| --- | --- |
| `/bench/native-static` | Değişmeyen JSON native static yoldan döner |
| `/bench/direct-record` | Küçük record generated direct writer ile yazılır |
| `/bench/producer?items=100` | Dinamik dizi DTO listesi kurulmadan yazılır |

```powershell
mvn -f ../pom.xml -pl benchmark exec:java `
  "-Dexec.mainClass=com.reactor.examples.benchmark.BenchmarkApplication"
```

Useful `200` RPS, p99, `503` ve container memory değerlerini birlikte karşılaştırın. İki testte aynı
warmup, CPU limiti, endpoint karışımı ve tekrar sayısını kullanın. Native static sonucu Java business
logic çalıştıran endpoint sonucu olarak sunmayın.
