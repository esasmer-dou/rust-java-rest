package com.reactor.rust.jdbc;

import java.security.MessageDigest;
import java.util.Objects;

/** Durable local-DB command deduplication. Install the schema through a migration. */
public final class PostgresIdempotency {
    public static final String SCHEMA = """
            CREATE TABLE reactor_command_result (
              scope varchar(160) NOT NULL, request_key varchar(160) NOT NULL,
              digest bytea NOT NULL CHECK (octet_length(digest) = 32),
              result bytea, expires_at timestamptz NOT NULL,
              PRIMARY KEY(scope, request_key))
            """;
    public static final String EXPIRY_INDEX =
            "CREATE INDEX reactor_command_result_expiry ON reactor_command_result(expires_at)";
    private final JdbcTransactions transactions;
    private final JdbcTransactions.Options options;
    private final long retentionMillis;
    private final int maxResultBytes;

    public PostgresIdempotency(JdbcTransactions transactions, int timeoutSeconds,
                               long retentionMillis, int maxResultBytes) {
        this.transactions = Objects.requireNonNull(transactions);
        this.options = JdbcTransactions.Options.write(timeoutSeconds);
        if (retentionMillis < 1 || maxResultBytes < 1 || maxResultBytes > 1_048_576)
            throw new IllegalArgumentException("Positive retention and maxResultBytes <= 1048576 required");
        this.retentionMillis = retentionMillis;
        this.maxResultBytes = maxResultBytes;
    }

    /** digest is SHA-256 of canonical business input; scope includes tenant and operation version. */
    public byte[] execute(String scope, String key, byte[] digest, Command command) throws Exception {
        requireKey(scope, "scope");
        requireKey(key, "key");
        if (digest == null || digest.length != 32) throw new IllegalArgumentException("SHA-256 digest required");
        Objects.requireNonNull(command);
        byte[] stableDigest = digest.clone();
        return transactions.execute(options, session -> {
            session.update("DELETE FROM reactor_command_result WHERE scope=? AND request_key=? AND expires_at <= clock_timestamp()",
                    s -> { s.setString(1, scope); s.setString(2, key); });
            int claimed = session.update("INSERT INTO reactor_command_result(scope,request_key,digest,expires_at) "
                    + "VALUES(?,?,?,clock_timestamp() + (? * interval '1 millisecond')) ON CONFLICT DO NOTHING",
                    s -> { s.setString(1, scope); s.setString(2, key); s.setBytes(3, stableDigest); s.setLong(4, retentionMillis); });
            if (claimed == 0) {
                Stored stored = session.queryOne("SELECT digest,result FROM reactor_command_result WHERE scope=? AND request_key=? FOR UPDATE",
                        s -> { s.setString(1, scope); s.setString(2, key); },
                        r -> new Stored(r.getBytes(1), r.getBytes(2)), null);
                if (stored == null || stored.result == null)
                    throw new IllegalStateException("Command result is unavailable; retry using the same key");
                if (!MessageDigest.isEqual(stored.digest, stableDigest)) throw new KeyConflictException();
                return stored.result;
            }
            byte[] result = Objects.requireNonNull(command.execute(session), "command result");
            if (result.length > maxResultBytes) throw new IllegalArgumentException("Command result exceeds maxResultBytes");
            session.update("UPDATE reactor_command_result SET result=?, expires_at=clock_timestamp() + (? * interval '1 millisecond') WHERE scope=? AND request_key=?",
                    s -> { s.setBytes(1, result); s.setLong(2, retentionMillis); s.setString(3, scope); s.setString(4, key); });
            return result;
        });
    }

    /** Run from an existing maintenance scheduler; no thread is created by this library. */
    public int deleteExpired(int batchSize) throws Exception {
        if (batchSize < 1 || batchSize > 10_000) throw new IllegalArgumentException("batchSize must be 1..10000");
        return transactions.execute(options, session -> session.update("DELETE FROM reactor_command_result WHERE (scope,request_key) IN "
                + "(SELECT scope,request_key FROM reactor_command_result WHERE expires_at <= clock_timestamp() "
                + "ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED)", s -> s.setInt(1, batchSize)));
    }

    private static void requireKey(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 160)
            throw new IllegalArgumentException(name + " must contain 1..160 characters");
    }
    private record Stored(byte[] digest, byte[] result) {}
    @FunctionalInterface public interface Command { byte[] execute(JdbcSession session) throws Exception; }
    public static final class KeyConflictException extends RuntimeException {
        public KeyConflictException() { super("Idempotency key was already used with different input"); }
    }
}
