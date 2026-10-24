package com.efxstudios.bans.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** The store contract on SQLite, plus SQLite-only schema migration. */
class StoreTest extends StoreContract {
    @TempDir
    Path dir;

    @Override
    DatabaseSettings settings() {
        return new DatabaseSettings("sqlite", dir.resolve("test.db"), "localhost", 3306, "efxbans", "", "", false, 2);
    }

    @Override
    void reset() {
        // A fresh @TempDir per test is already empty.
    }

    @Test
    void oldDatabaseWithoutSilentColumnIsUpgraded() throws Exception {
        var old = issue(Kind.BAN, a, null, 0);
        store.close();
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + settings().sqlite()); var s = c.createStatement()) {
            s.execute("ALTER TABLE efxb_punishments DROP COLUMN silent");
        }
        store = new Store(settings());
        assertFalse(store.get(old.id()).orElseThrow().silent());
    }
}
