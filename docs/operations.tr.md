# Operasyon Rehberi

[English](operations.md) | [Türkçe](operations.tr.md)

Canlıya çıkmadan önce container RSS, p50/p95/p99, `503` oranı, JNI queue bekleme süresi, in-flight
response byte, aktif dosya stream ve dependency pool bekleme değerlerini kaydedin. Aynı endpoint
karışımını c64 ve c256 yükte karşılaştırın.

Liveness için `/app/health` kullanın. Gerekli dependency kontrolleri için `/app/readiness` kullanın.
Liveness Redis, Dubbo veya veri tabanı çağırmamalıdır. Readiness başarısızsa sağlıklı process yeniden
başlatılmadan pod trafikten çıkarılmalıdır.

`503`, kontrollü overload sonucu olabilir. Route budget yalnız provider, DB pool, CPU ve RSS
kapasitesi varsa artırılmalıdır. Global queue büyütmek başarılı istek sayısını artırırken p99 ve
tutulan memory değerini kötüleştirebilir.

Idle native trim yalnız düşük trafikli ve memory-first podlarda açılmalıdır. Request handler içinde
trim çağırmayın.

Release öncesi `mvn clean verify` çalıştırın. Codegen sınıfı runtime JAR içine girerse build fail olur.
Windows DLL ve Linux SO aynı kaynak revision'dan üretilmeli, ABI manifesti doğrulanmalıdır.
