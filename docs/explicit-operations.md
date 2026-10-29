# Explicit Operations

[English](explicit-operations.md) | [Turkish](explicit-operations.tr.md)

Available in the aligned 4.8.0 platform. Add only the modules your service uses.
Java owns business decisions. Native ABI versions and existing successful response bodies are unchanged.

## Choose The Tool

| Need | Use | Cost |
| --- | --- | --- |
| Same sync/async error | `ErrorResponses.from(error)` | Error path only |
| Several DB changes together | `JdbcTransactions` | One borrowed connection |
| Safe repeat after lost reply | `PostgresIdempotency` | Extra DB statements on opted-in writes |
| Notify a snapshot writer | `PostgresRefreshOutbox` | One coalesced row per registered projection |
| Large DB export | `JdbcSession.forEach` | Bounded fetch, no full result list |
| Explain active settings | `PropertiesLoader.effective` | On-demand report; no polling thread |
| Check a changed contract | `reactor:verify-contract` | Build only |
| Start two example processes | `ScenarioGenerator` | Generation only |

## Transaction

Add `com.reactor:rust-java-jdbc` from the matching platform build. It has no runtime third-party dependency.
Pass your existing `DataSource`. Do not create a pool per request.

```java
var transactions = new JdbcTransactions(dataSource);
transactions.execute(JdbcTransactions.Options.write(2), session -> {
    session.update("update orders set status=? where id=?", statement -> {
        statement.setString(1, "confirmed");
        statement.setLong(2, orderId);
    });
    session.update("insert into order_audit(order_id,event) values(?,?)", statement -> {
        statement.setLong(1, orderId);
        statement.setString(2, "confirmed");
    });
    return null;
});
```

Both statements use the same connection. A failure rolls back both. The timeout is per statement, not
a cancellation guarantee for external side effects. Pool acquisition still uses your pool timeout.
Pass the existing session to nested functions. Do not open a second transaction from inside this block.
Do not keep the session or send it to another thread. It is invalid after the callback.

## Idempotent Command

Install `PostgresIdempotency.SCHEMA` and `EXPIRY_INDEX` through your migration tool.
Create the helper once. Scope must include the trusted tenant and operation version.
The key can come from `Idempotency-Key` or your existing `requestId`.

```java
var commands = new PostgresIdempotency(transactions, 2, 86_400_000, 65_536);
byte[] response = commands.execute("tenant-42:confirm-order:v1", requestId, sha256OfCanonicalInput,
    session -> {
        session.update("update orders set status='confirmed' where id=?",
            statement -> statement.setLong(1, orderId));
        return responseJson;
    });
```

The stored response and business update commit together. A repeated key with the same digest returns
the stored bytes. Different input raises `KeyConflictException`; map it to HTTP 409. Waiting for an
in-progress command is bounded by the statement timeout. There is no hidden retry.

Do not call a remote payment API inside this transaction and claim exactly-once delivery.
An uncertain commit must be resolved with the same key, not a new key. After retention expires, a
key may execute again. Schedule bounded `deleteExpired(1000)` calls and monitor table size.
TTL alone is not cleanup, and this table is not a fixed-size in-memory cache.

## Refresh Notification

`PostgresRefreshOutbox` is optional. Install its schema and register a fixed set of projection names.
Call `enqueue(session, "customer.detail")` in the business transaction. An existing scheduler calls
`drain(16, projection -> refreshAndPublish(projection))`. Return true only after successful publication.

Redis work runs outside the DB transaction. A failed refresh keeps the notice. A newer notice cannot
be removed by an older acknowledgement. Multiple drainers can repeat a refresh; retain the existing
projection lock and fenced publish. This is at-least-once notification, not DB/Redis atomicity.
Snapshot projections remain independently consistent. Use a dedicated DB role with outbox read/delete
rights; a read-only writer connection cannot acknowledge notices.

## Bounded Export

```java
transactions.execute(JdbcTransactions.Options.read(3), session ->
    session.forEach("select id,name from customer order by id", JdbcSession.Binder.NONE,
        CustomerJdbcMapper::map, 128,
        customer -> { sink.write(customer); return !sink.cancelled(); }));
```

The cursor closes on cancellation or failure. Do not hold this transaction for an unbounded slow HTTP
client. Use route admission and a bounded stream lifecycle. A failed response after headers are sent
cannot become a second JSON error. The JDBC helper does not invent HTTP framing or a distributed snapshot.

## Effective Configuration

```java
var publicKeys = Set.of("reactor.runtime.profile", "reactor.rust.jni.workers");
var report = PropertiesLoader.effective(publicKeys, publicKeys);
```

The report identifies system property, environment, application configuration, runtime profile, or
default. Only explicitly public keys show values. Secret-looking keys and URLs remain masked.
Do not expose the report on a public endpoint. It does not increase pools or change configuration.

`ConfigurationChecks.unknownKeys` checks only an explicitly owned prefix against your declared keys.
`ConfigurationChecks.lessThan` checks positive budgets in the same unit, such as queue versus RPC timeout.
Call these checks at startup. They do not automatically discover every library setting.

## Contract Gate

Compilation emits `META-INF/reactor/contracts.tsv` for public records, enums and interfaces.
Extract this file from the previous artifact and retain it as your baseline.

```powershell
mvn clean verify reactor:verify-contract -Dreactor.contract.baseline=contracts/previous.tsv
```

Changed/removed existing signatures fail conservatively. A new type is allowed. JSON field tolerance,
Hessian references, cache schemas and business semantics still need old/new round-trip tests. A
signature match alone is not compatibility proof. Maven application version is passed to AOT generation;
do not maintain a separate sample version literal in `@ReactorApplication`.
Use a clean build so deleted sources cannot leave a stale manifest behind.

## Complete Example Pair

```powershell
java -cp rust-java-rest-4.8.0-codegen.jar com.reactor.rust.codegen.ScenarioGenerator cache my-cache
java -cp rust-java-rest-4.8.0-codegen.jar com.reactor.rust.codegen.ScenarioGenerator dubbo my-dubbo
```

Use the codegen JAR from the matching platform version. Each new directory contains
two Maven applications, Dockerfiles, Compose, bilingual instructions and a bounded GET smoke check.
The Dubbo pair also shares one interface module. Existing directories are never overwritten.
The cache fixture publishes a small ready snapshot; it is not a database performance example.

## Failure Tests

Use `Eventually.await` only for bounded readiness probes or idempotent reads. Each probe must have its
own network timeout. The helper does not interrupt an unbounded probe. Test command duplication with
the same key, not a retry loop that creates new commands. Test a real DB rollback and real Redis/provider
restart separately from HTTP mocks. A mock does not prove native reconnect or transaction behavior.

An HTTP 200 is not enough for a load test. Check that the body contains the expected business data.
Keep error bodies, rejected requests and network errors out of successful-request RPS.

Explicit `@Bean` methods can return a library's `HealthEndpoint`. Its public routes are included in
the generated route index: `/app/health`, `/app/liveness` and `/app/readiness`. There is no new runtime
classpath scan. Readiness checks dependencies on demand; it does not start a polling thread.

The native primitive callback fix requires a rebuilt DLL/SO. The ABI remains 29. The dedicated test
checks int, long, boolean, double and short values over real HTTP, then injects a callback exception
and invalid response lengths. Each failure must return 500; the same worker must serve the next valid
request. Run it in its own test JVM:

```powershell
mvn test -Dtest=NativePrimitiveHttpTest -Dreactor.test.native-http=true -Drust.lib.path=C:/native/rust_hyper.dll
```

Use the equivalent `.so` path on Linux. A development binary with dirty provenance is test evidence,
not a release artifact. Keep the clean-source packaging gate in place before publication.
