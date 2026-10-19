package com.reactor.rust.jdbc;

import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JdbcResetTest {
    @Test void preparationIsDeferredAndFailureDoesNotBorrowOrRunBusinessWork() {
        var events = new ArrayList<String>();
        DataSource source = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{DataSource.class},
                (p,m,a) -> { events.add("borrow"); throw new AssertionError("Must not borrow after failed preparation"); });
        var transactions = new JdbcTransactions(source, () -> {
            events.add("prepare");
            throw new IllegalStateException("schema unavailable");
        });
        assertTrue(events.isEmpty(), "Construction must not perform I/O");
        assertThrows(IllegalStateException.class, () -> transactions.execute(JdbcTransactions.Options.write(1), s -> {
            events.add("business");
            return 1;
        }));
        assertEquals(List.of("prepare"), events);
    }

    // Reflection is limited to this test double; the production transaction path uses JDBC directly.
    private JdbcTransactions transactions(List<String> events, boolean failRollback, boolean failReset) {
        Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class}, (p,m,a) -> {
            String name = m.getName();
            if (name.equals("getAutoCommit")) return true;
            if (name.equals("isReadOnly")) return false;
            if (name.equals("getTransactionIsolation")) return Connection.TRANSACTION_READ_COMMITTED;
            events.add(name + (a == null ? "" : ":" + a[0]));
            if (name.equals("rollback") && failRollback) throw new SQLException("rollback");
            if (name.equals("setAutoCommit") && Boolean.TRUE.equals(a[0]) && failReset) throw new SQLException("reset");
            return null;
        });
        DataSource source = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{DataSource.class},
                (p,m,a) -> m.getName().equals("getConnection") ? connection : null);
        return new JdbcTransactions(source);
    }
    @Test void successRestoresBorrowedConnection() throws Exception {
        var events = new ArrayList<String>();
        transactions(events,false,false).execute(JdbcTransactions.Options.read(1), session -> 1);
        assertEquals(List.of("setReadOnly:true", "setTransactionIsolation:2", "setAutoCommit:false", "commit",
                "setAutoCommit:true", "setTransactionIsolation:2", "setReadOnly:false", "close"), events);
    }
    @Test void failedRollbackNeverAccidentallyCommitsAndAbortsConnection() {
        var events = new ArrayList<String>();
        var error = assertThrows(IllegalStateException.class, () -> transactions(events,true,false)
                .execute(JdbcTransactions.Options.write(1), session -> { throw new IllegalStateException("business"); }));
        assertEquals(1,error.getSuppressed().length);
        assertFalse(events.contains("setAutoCommit:true"));
        assertFalse(events.contains("commit"));
        assertTrue(events.stream().anyMatch(e -> e.startsWith("abort:")));
        assertEquals("close",events.getLast());
    }
    @Test void resetFailureIsVisibleAndConnectionIsAborted() {
        var events = new ArrayList<String>();
        assertThrows(SQLException.class, () -> transactions(events,false,true)
                .execute(JdbcTransactions.Options.write(1), session -> 1));
        assertTrue(events.stream().anyMatch(e -> e.startsWith("abort:")));
        assertEquals("close",events.getLast());
    }
}
