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

Yük testine başlamadan önce üç response yolunu doğrulayın:

```powershell
Invoke-RestMethod http://localhost:8085/bench/native-static
Invoke-RestMethod http://localhost:8085/bench/direct-record
Invoke-RestMethod "http://localhost:8085/bench/producer?items=3"
```

İlk iki çağrı bir JSON nesnesi döndürür. Producer çağrısı, istenen sayıda eleman içeren bir dizi
döndürür. Bu kontroller beklenen veri biçimiyle `200` dönmeden yük testi sonucu geçerli sayılmaz.

Useful `200` RPS, p99, `503` ve container memory değerlerini birlikte karşılaştırın. İki testte aynı
warmup, CPU limiti, endpoint karışımı ve tekrar sayısını kullanın. Native static sonucu Java business
logic çalıştıran endpoint sonucu olarak sunmayın.

Gerçek kullanım örneği seçmek için [örnekler dizinine](../README.tr.md) dönün.
