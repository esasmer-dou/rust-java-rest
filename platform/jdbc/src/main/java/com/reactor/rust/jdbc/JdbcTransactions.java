package com.reactor.rust.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Explicit local transactions; no ThreadLocal, proxy, implicit nesting or retries. */
public final class JdbcTransactions {
    private final DataSource dataSource;
    private final Runnable beforeBorrow;

    public JdbcTransactions(DataSource dataSource) { this(dataSource, null); }

    /** Optional repository preparation runs on explicit execution, never during construction. */
    public JdbcTransactions(DataSource dataSource, Runnable beforeBorrow) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.beforeBorrow = beforeBorrow;
    }

    public <T> T execute(Options options, Work<T> work) throws Exception {
        Objects.requireNonNull(options);
        Objects.requireNonNull(work);
        if (beforeBorrow != null) beforeBorrow.run();
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            if (!autoCommit) throw new SQLException("Expected an idle auto-commit connection; pass the existing JdbcSession to nested work");
            boolean readOnly = connection.isReadOnly();
            int isolation = connection.getTransactionIsolation();
            JdbcSession session = new JdbcSession(connection, options.queryTimeoutSeconds());
            Throwable failure = null;
            boolean transactionStarted = false;
            boolean rollbackFailed = false;
            try {
                connection.setReadOnly(options.readOnly());
                connection.setTransactionIsolation(options.isolation());
                connection.setAutoCommit(false);
                transactionStarted = true;
                T result = work.run(session);
                connection.commit();
                return result;
            } catch (Throwable error) {
                failure = error;
                if (transactionStarted) {
                    try { connection.rollback(); }
                    catch (Throwable rollback) { error.addSuppressed(rollback); rollbackFailed = true; }
                }
                throw error;
            } finally {
                session.invalidate();
                // Never set autoCommit=true after a failed rollback: that could commit partial work.
                if (rollbackFailed) {
                    try { connection.abort(Runnable::run); }
                    catch (Throwable abort) { failure.addSuppressed(abort); }
                } else {
                    try {
                        connection.setAutoCommit(autoCommit);
                        connection.setTransactionIsolation(isolation);
                        connection.setReadOnly(readOnly);
                    } catch (Throwable reset) {
                        try { connection.abort(Runnable::run); }
                        catch (Throwable abort) { reset.addSuppressed(abort); }
                        if (failure != null) failure.addSuppressed(reset);
                        else throw reset;
                    }
                }
            }
        }
    }

    public record Options(boolean readOnly, int isolation, int queryTimeoutSeconds) {
        public Options {
            if (queryTimeoutSeconds < 1) throw new IllegalArgumentException("queryTimeoutSeconds must be positive");
            if (isolation != Connection.TRANSACTION_READ_COMMITTED
                    && isolation != Connection.TRANSACTION_REPEATABLE_READ
                    && isolation != Connection.TRANSACTION_SERIALIZABLE)
                throw new IllegalArgumentException("Unsupported transaction isolation");
        }
        public static Options write(int timeoutSeconds) {
            return new Options(false, Connection.TRANSACTION_READ_COMMITTED, timeoutSeconds);
        }
        public static Options read(int timeoutSeconds) {
            return new Options(true, Connection.TRANSACTION_READ_COMMITTED, timeoutSeconds);
        }
    }
    @FunctionalInterface public interface Work<T> { T run(JdbcSession session) throws Exception; }
}
