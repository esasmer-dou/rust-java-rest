package com.reactor.rust.jdbc;

import java.util.Set;
import java.util.Objects;

/** Coalescing refresh notifications committed with business data, not a distributed transaction. */
public final class PostgresRefreshOutbox {
    public static final String SCHEMA = """
            CREATE TABLE reactor_refresh_outbox (
              projection varchar(160) PRIMARY KEY,
              revision bigserial NOT NULL)
            """;
    private final Set<String> projections;
    private final JdbcTransactions transactions;
    private final JdbcTransactions.Options options;
    private final java.util.List<String> orderedProjections;
    private final String select;

    public PostgresRefreshOutbox(JdbcTransactions transactions, Set<String> projections, int timeoutSeconds) {
        this.transactions = Objects.requireNonNull(transactions);
        if (projections.isEmpty() || projections.size() > 64) throw new IllegalArgumentException("Register 1..64 fixed projections");
        for (String name : projections)
            if (name == null || name.isBlank() || name.length() > 160) throw new IllegalArgumentException("Invalid projection name");
        this.projections = Set.copyOf(projections);
        orderedProjections = this.projections.stream().sorted().toList();
        select = "SELECT projection,revision FROM reactor_refresh_outbox WHERE projection IN ("
                + String.join(",", java.util.Collections.nCopies(projections.size(), "?"))
                + ") ORDER BY revision LIMIT ?";
        options = JdbcTransactions.Options.write(timeoutSeconds);
    }

    public void enqueue(JdbcSession businessTransaction, String projection) throws java.sql.SQLException {
        if (!projections.contains(projection)) throw new IllegalArgumentException("Unregistered projection");
        businessTransaction.update("INSERT INTO reactor_refresh_outbox(projection) VALUES(?) "
                + "ON CONFLICT(projection) DO UPDATE SET revision=excluded.revision",
                s -> s.setString(1, projection));
    }

    /** Call from your existing scheduler. Redis refresh is outside a DB transaction. */
    public int drain(int batchSize, Refresh refresh) throws Exception {
        if (batchSize < 1 || batchSize > 64) throw new IllegalArgumentException("batchSize must be 1..64");
        var notices = transactions.execute(options, s -> s.query(
                select, st -> {
                    int index=1;
                    for(String projection:orderedProjections) st.setString(index++,projection);
                    st.setInt(index,batchSize);
                }, r -> new Notice(r.getString(1), r.getLong(2)), batchSize));
        int acknowledged = 0;
        for (Notice notice : notices) {
            if (!projections.contains(notice.projection)) continue;
            if (refresh.publish(notice.projection)) {
                acknowledged += transactions.execute(options, s -> s.update(
                        "DELETE FROM reactor_refresh_outbox WHERE projection=? AND revision=?",
                        st -> { st.setString(1, notice.projection); st.setLong(2, notice.revision); }));
            }
        }
        return acknowledged;
    }
    private record Notice(String projection, long revision) {}
    @FunctionalInterface public interface Refresh { boolean publish(String projection) throws Exception; }
}
