package com.reactor.rust.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Borrowed, thread-confined connection. Valid only inside the transaction callback. */
public final class JdbcSession {
    private final Connection connection;
    private final int timeoutSeconds;
    private final Thread owner = Thread.currentThread();
    private boolean active = true;

    JdbcSession(Connection connection, int timeoutSeconds) {
        this.connection = connection;
        this.timeoutSeconds = timeoutSeconds;
    }

    public int update(String sql, Binder binder) throws SQLException {
        try (PreparedStatement statement = prepare(sql, binder)) {
            return statement.executeUpdate();
        }
    }

    public <T> T queryOne(String sql, Binder binder, Mapper<T> mapper, T missing) throws SQLException {
        try (PreparedStatement statement = prepare(sql, binder); ResultSet rows = statement.executeQuery()) {
            return rows.next() ? mapper.map(rows) : missing;
        }
    }

    public <T> List<T> query(String sql, Binder binder, Mapper<T> mapper, int maxRows) throws SQLException {
        if (maxRows < 1) throw new IllegalArgumentException("maxRows must be positive");
        try (PreparedStatement statement = prepare(sql, binder)) {
            statement.setMaxRows(maxRows);
            try (ResultSet rows = statement.executeQuery()) {
                List<T> result = new ArrayList<>();
                while (rows.next()) result.add(mapper.map(rows));
                return result;
            }
        }
    }

    /** Cursor is closed even when the sink fails or cancels. No intermediate List. */
    public <T> long forEach(String sql, Binder binder, Mapper<T> mapper, int fetchSize,
                           RowSink<T> sink) throws Exception {
        if (fetchSize < 1) throw new IllegalArgumentException("fetchSize must be positive");
        try (PreparedStatement statement = prepare(sql, binder)) {
            statement.setFetchSize(fetchSize);
            try (ResultSet rows = statement.executeQuery()) {
                long count = 0;
                while (rows.next()) {
                    if (!sink.accept(mapper.map(rows))) break;
                    count++;
                }
                return count;
            }
        }
    }

    void invalidate() { active = false; }

    private PreparedStatement prepare(String sql, Binder binder) throws SQLException {
        if (!active || owner != Thread.currentThread())
            throw new IllegalStateException("JdbcSession is valid only on its transaction callback thread");
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setQueryTimeout(timeoutSeconds);
            binder.bind(statement);
            return statement;
        } catch (Throwable error) {
            try { statement.close(); } catch (Throwable close) { error.addSuppressed(close); }
            throw error;
        }
    }

    @FunctionalInterface public interface Binder {
        Binder NONE = statement -> {};
        void bind(PreparedStatement statement) throws SQLException;
    }
    @FunctionalInterface public interface Mapper<T> { T map(ResultSet row) throws SQLException; }
    @FunctionalInterface public interface RowSink<T> { boolean accept(T row) throws Exception; }
}
