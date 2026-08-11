# WebSocket Örneği

[English](README.md) | [Türkçe](README.tr.md)

`ws://localhost:8084/ws/echo` adresini açar. Socket transport ve bounded outbound queue Rust
tarafında çalışır. Open, message ve close callback'leri Java'da kalır.

```powershell
mvn -f ../pom.xml -pl websocket exec:java `
  "-Dexec.mainClass=com.reactor.examples.websocket.WebSocketApplication"
```

Tarayıcı console testi:

```javascript
const socket = new WebSocket("ws://localhost:8084/ws/echo");
socket.onmessage = event => console.log(event.data);
socket.onopen = () => socket.send("merhaba");
socket.onerror = error => console.error(error);
// Echo geldikten sonra socket.close(1000, "test tamamlandı") çalıştırın.
```

Beklenen mesaj: `echo:merhaba`.

Session başına state sınırlı olmalıdır. Maksimum frame boyutu, outbound queue kapasitesi ve slow
consumer policy ayarlanmalıdır. Başka bir network servisini beklerken callback thread'ini bloklamayın.

WebSocket'i ilgisiz bir REST servisine eklemeden önce [örnekler dizinine](../README.tr.md) dönün.
