# Native Paket Uyumluluğu

[English](native-availability.md) | [Turkish](native-availability.tr.md)

## Hangi Dosyayı Kullanmalıyım?

REST 4.8.0 ve cache 0.9.0, aynı temiz kaynak sürümünden derlenmiş native dosyalar içerir.
Kaynak: `3418671b5010afb5dc049897f2a7287dc6a58daf`; derleme: `40834723168`.

| Platform | Paket içindeki dosya | Doğrulama sınırı |
| --- | --- | --- |
| Linux x64 | SO; GLIBC 2.17 ve sonrası | Çalışma testleri EmaTech'te yapılır |
| Windows x64 | DLL; Visual Studio gerektirmeyen çapraz derleme | Bu yayında yeni Windows çalışma testi yok |
| Linux ARM64 | SO; GLIBC 2.17 ve sonrası | Temiz derleme; ARM çalışma testi yok |
| macOS ARM64 | dylib; macOS 11 ve sonrası | Temiz derleme; ARM çalışma testi yok |

Yayımlanmış 4.8.0 bu ARM64 binary'lerini içerir; ancak eski platform sidecar'ları
nedeniyle provenance kontrolü dosyaları reddeder. Yeni
4.8.1 yaması yalnız metadata'yı düzeltir; binary'leri yeniden derlemez ve ARM çalışma
kanıtı eklemez (`runtime.verified=false`). [4.8.1 notlarına](release-notes/v4.8.1.tr.md) bakın.


Bağımsız agent paketi yalnız Linux x64 ve Windows x64 içerir. REST/cache ile kullanıldığında eşleşen runtime içindeki exporter kullanılır; ikinci bir motor açılmaz.

Normal runtime; WebSocket, Dubbo, Redis, Glowroot, edge cache, JWT, dış HTTP ve shared slab desteğiyle derlenir.
Özelliğin dosyada bulunması onu etkinleştirmez. Java iş kuralları değişmez.
Sözleşmeler REST 29, Dubbo 7, Redis 6 ve Glowroot 6 olarak kalır. Ayrı Spring Dubbo sözleşmesi 3'tür.

OpenJ9 için varsayılan yol JNI'dır. FFM ayrı, isteğe bağlı HotSpot Java 21 modülüdür.
FFM dosyasını varsayılan native dosyanın yerine koymayın.

Yükleyici önce seçilen dosyanın kendi JAR'ındaki platform kaydını okur.
Bu kayıt yoksa aynı JAR'ın kök kaydına bakar. Geçersiz kayıt, hash veya ABI eşleşmezse başlangıç durur.
REST kaynak sürümü de doğrulanır. Kontroller başlangıçta yapılır; her istekte tekrarlanmaz.
Eski DLL/SO ile yeni JAR'ı karıştırmayın. Derleme kanıtı tüm platformlarda performans garantisi değildir.

## ARM64 Çalışmasını EmaTech'te Doğrulayın

Gerçek ARM64 donanımı kullanın. Çapraz derleme veya QEMU duman testi, üretim çalışma kanıtı değildir.
`rust-spring` içindeki `arm64-runtime-qualification.yml` iş akışı, EmaTech üzerinde
`ARM64` ve `native-hardware` etiketlerini taşıyan bir doğrulama makinesi gerektirir.

Operatör önce incelenmiş `plan.json` dosyasını `ARM64_QUALIFICATION_PLAN_ROOT` altına yerleştirir.
Plan; gerçek binary dosyalarını, hash değerlerini, mevcut doğrulayıcı komutlarını ve karşılaştırmalı ölçümleri tanımlar.
Bu makinedeki `rust-spring` dizininden çalıştırın:

```sh
python3 scripts/qualify-arm64-runtime.py --platform linux-aarch64 \
  --plan-root /opt/ematech/arm64 --plan plan.json --output /tmp/arm64-proof
```

Gerçek macOS ARM64 makinesinde `--platform osx-aarch64` kullanın. Rapor, kullanılan JVM'i ayrıca kaydeder.
Bu sonuç OpenJ9 üretim kanıtı sayılmaz. Doğrulama; paket kaynağını, beş ABI sözleşmesini,
edge özelliklerini, HTTPS'i, slab güvenliğini ve kaynak tüketimindeki değişimi kapsar. Eksik kanıt varsa işlem geçmez.
Betik platform kayıtlarını değiştirmez ve `runtime.verified=true` yazmaz. Bu değişiklik için tam rapor ayrıca incelenir.
