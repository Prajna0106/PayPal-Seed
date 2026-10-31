package com.paypalseed.platform.testing;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * One real PostgreSQL 16 server per test JVM, started from bundled binaries, with a fresh database
 * per test class. Integration tests get real constraint triggers, row locks and {@code SKIP LOCKED}
 * without needing Docker, and test classes never see each other's loans or close-of-business runs.
 */
public final class EmbeddedPostgresSupport {

    private static EmbeddedPostgres server;

    private EmbeddedPostgresSupport() {
    }

    /** Creates (or reuses) a database named after {@code name} and returns its JDBC URL. */
    public static synchronized String database(String name) {
        EmbeddedPostgres pg = server();
        String db = "t_" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        try (Connection c = pg.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + db + " WITH (FORCE)");
            s.execute("CREATE DATABASE " + db);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create test database " + db, e);
        }
        return pg.getJdbcUrl("postgres", db);
    }

    private static EmbeddedPostgres server() {
        if (server == null) {
            try {
                server = EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new UncheckedIOException("Could not start embedded Postgres", e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(EmbeddedPostgresSupport::stop));
        }
        return server;
    }

    private static synchronized void stop() {
        try {
            server.close();
        } catch (IOException ignored) {
            // The JVM is exiting; there is nothing useful left to do.
        }
    }
}
