# Compile-Verified REST Examples

Each module is a separate artifact. Choose one shape instead of copying the full compatibility demo.

| Module | Use it for |
| --- | --- |
| `minimal-rest` | One health/read endpoint with the smallest application surface. |
| `crud` | Record request parsing, validation, GET, POST, PATCH, DELETE, and generated JSON writing. |
| `upload` | Bounded multipart upload. |
| `streaming` | Rust-native file response and object-graph-free dynamic JSON. |
| `websocket` | Bounded Rust WebSocket transport with Java callbacks. |
| `benchmark` | Static, generated record, and producer response path comparison. |

Build every module:

```bash
mvn -f examples/pom.xml clean package
```

The original `sample` module remains a full compatibility and feature demo. It is not part of the
published framework runtime JAR and should not be used as the baseline for production RSS.
