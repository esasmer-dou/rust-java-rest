package com.reactor.rust.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "REACTOR_TEST_JDBC_URL", matches = ".+")
class JdbcTransactionsTest {
    private PGSimpleDataSource source() {
        var source = new PGSimpleDataSource();
        source.setUrl(System.getenv("REACTOR_TEST_JDBC_URL"));
        source.setUser(System.getenv("REACTOR_TEST_JDBC_USER"));
        source.setPassword(System.getenv("REACTOR_TEST_JDBC_PASSWORD"));
        return source;
    }

    @Test void rollbackAndBorrowedSessionLifetime() throws Exception {
        var tx = new JdbcTransactions(source());
        var write = JdbcTransactions.Options.write(2);
        tx.execute(write, s -> { s.update("CREATE TABLE IF NOT EXISTS tx_test(id int PRIMARY KEY)", JdbcSession.Binder.NONE); return null; });
        JdbcSession[] escaped = new JdbcSession[1];
        assertThrows(Exception.class, () -> tx.execute(write, s -> {
            escaped[0] = s;
            s.update("INSERT INTO tx_test VALUES(1)", JdbcSession.Binder.NONE);
            s.update("INSERT INTO tx_test VALUES(1)", JdbcSession.Binder.NONE);
            return null;
        }));
        assertEquals(0, tx.execute(write, s -> s.queryOne("SELECT count(*) FROM tx_test", JdbcSession.Binder.NONE, r -> r.getInt(1), -1)).intValue());
        assertThrows(IllegalStateException.class, () -> escaped[0].update("SELECT 1", JdbcSession.Binder.NONE));
        try (Connection c = source().getConnection()) { assertTrue(c.getAutoCommit()); assertFalse(c.isReadOnly()); }
    }

    @Test void concurrentDeduplicationConflictAndFailureRollback() throws Exception {
        var tx = new JdbcTransactions(source());
        tx.execute(JdbcTransactions.Options.write(3), s -> {
            s.update("DROP TABLE IF EXISTS reactor_command_result", JdbcSession.Binder.NONE);
            s.update(PostgresIdempotency.SCHEMA, JdbcSession.Binder.NONE);
            s.update(PostgresIdempotency.EXPIRY_INDEX, JdbcSession.Binder.NONE);
            s.update("CREATE TABLE IF NOT EXISTS command_test(id int PRIMARY KEY, value int)", JdbcSession.Binder.NONE);
            s.update("INSERT INTO command_test VALUES(1,0) ON CONFLICT(id) DO UPDATE SET value=0", JdbcSession.Binder.NONE);
            return null;
        });
        var first = new PostgresIdempotency(tx, 3, 60_000, 100);
        var second = new PostgresIdempotency(new JdbcTransactions(source()), 3, 60_000, 100);
        var calls = new AtomicInteger();
        PostgresIdempotency.Command command = s -> {
            calls.incrementAndGet();
            s.update("UPDATE command_test SET value=value+1 WHERE id=1", JdbcSession.Binder.NONE);
            return new byte[]{42};
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> first.execute("tenant:command:v1", "same", new byte[32], command));
            var b = executor.submit(() -> second.execute("tenant:command:v1", "same", new byte[32], command));
            assertArrayEquals(a.get(), b.get());
        }
        assertEquals(1, calls.get());
        assertArrayEquals(new byte[]{42}, first.execute("tenant:command:v1", "same", new byte[32], command));
        byte[] changed = new byte[32]; changed[0] = 1;
        assertThrows(PostgresIdempotency.KeyConflictException.class, () -> first.execute("tenant:command:v1", "same", changed, command));
        assertThrows(Exception.class, () -> first.execute("tenant:command:v1", "failed", new byte[32], s -> {
            s.update("UPDATE command_test SET value=100 WHERE id=1", JdbcSession.Binder.NONE);
            throw new IllegalArgumentException("business failed");
        }));
        assertEquals(1, tx.execute(JdbcTransactions.Options.read(2), s -> s.queryOne("SELECT value FROM command_test WHERE id=1", JdbcSession.Binder.NONE, r -> r.getInt(1), -1)).intValue());
        assertArrayEquals(new byte[]{42}, first.execute("tenant:command:v1", "failed", new byte[32], command));
    }

    @Test void cursorCancellationClosesAndTimeoutIsBounded() throws Exception {
        var tx = new JdbcTransactions(source());
        assertEquals(2L, tx.execute(JdbcTransactions.Options.read(1), s -> {
            var count = new AtomicInteger();
            return s.forEach("SELECT generate_series(1,1000)", JdbcSession.Binder.NONE, r -> r.getInt(1), 8,
                    row -> count.incrementAndGet() <= 2);
        }).longValue());
        long start = System.nanoTime();
        assertThrows(Exception.class, () -> tx.execute(JdbcTransactions.Options.read(1), s ->
                s.queryOne("SELECT pg_sleep(10)", JdbcSession.Binder.NONE, r -> 1, 0)));
        assertTrue(System.nanoTime() - start < 5_000_000_000L);
    }
}
