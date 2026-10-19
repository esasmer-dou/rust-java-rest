package com.reactor.rust.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="REACTOR_TEST_JDBC_URL", matches=".+")
class RefreshOutboxTest {
    @Test void failedAndSupersededRefreshCannotLoseNotification() throws Exception {
        var ds = new PGSimpleDataSource(); ds.setUrl(System.getenv("REACTOR_TEST_JDBC_URL"));
        ds.setUser(System.getenv("REACTOR_TEST_JDBC_USER")); ds.setPassword(System.getenv("REACTOR_TEST_JDBC_PASSWORD"));
        var tx = new JdbcTransactions(ds); var options=JdbcTransactions.Options.write(2);
        tx.execute(options,s->{s.update("DROP TABLE IF EXISTS reactor_refresh_outbox",JdbcSession.Binder.NONE);s.update(PostgresRefreshOutbox.SCHEMA,JdbcSession.Binder.NONE);return null;});
        var outbox = new PostgresRefreshOutbox(tx, Set.of("customer.detail"),2);
        assertThrows(Exception.class,()->tx.execute(options,s->{outbox.enqueue(s,"customer.detail");throw new IllegalStateException();}));
        assertEquals(0,outbox.drain(8,p->fail("rolled-back notice must not exist")));
        tx.execute(options,s->{outbox.enqueue(s,"customer.detail");return null;});
        assertEquals(0,outbox.drain(8,p->false));
        assertEquals(0,outbox.drain(8,p->{tx.execute(options,s->{outbox.enqueue(s,p);return null;});return true;}));
        assertEquals(1,outbox.drain(8,p->true));
        assertEquals(0,outbox.drain(8,p->fail("acknowledged notice must be gone")));
        tx.execute(options,s->{outbox.enqueue(s,"customer.detail");return null;});
        assertEquals(0,outbox.drain(8,p->{
            assertEquals(1,outbox.drain(8,other->true));
            tx.execute(options,s->{outbox.enqueue(s,p);return null;});
            return true;
        }));
        assertEquals(1,outbox.drain(8,p->true));
    }
}
