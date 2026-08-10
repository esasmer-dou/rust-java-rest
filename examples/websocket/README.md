# WebSocket Example

[English](README.md) | [Türkçe](README.tr.md)

This module exposes `ws://localhost:8084/ws/echo`. Rust owns the socket transport and bounded
outbound queue. Java owns open, message, and close callbacks.

```powershell
mvn -f ../pom.xml -pl websocket exec:java `
  "-Dexec.mainClass=com.reactor.examples.websocket.WebSocketApplication"
```

Browser console test:

```javascript
const socket = new WebSocket("ws://localhost:8084/ws/echo");
socket.onmessage = event => console.log(event.data);
socket.onopen = () => socket.send("hello");
```

Keep per-session state bounded. Configure maximum frame size, outbound queue capacity, and slow
consumer policy. Do not block the callback while waiting for another network service.
