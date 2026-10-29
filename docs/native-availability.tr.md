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
