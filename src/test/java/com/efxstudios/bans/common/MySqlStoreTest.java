package com.efxstudios.bans.common;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Objects;

@EnabledIfEnvironmentVariable(named = "EFXBANS_MYSQL_HOST", matches = ".+")
class MySqlStoreTest extends StoreContract {
    private static final String[] TABLES = {"efxb_meta", "efxb_profiles", "efxb_names", "efxb_ips", "efxb_punishments", "efxb_changes",
            "efxb_warning_deliveries", "efxb_imports"};

    private static String env(String name, String fallback) {
        return Objects.requireNonNullElse(System.getenv(name), fallback);
    }

    @Override
    DatabaseSettings settings() {
        return new DatabaseSettings("mysql", Path.of("unused.db"), env("EFXBANS_MYSQL_HOST", "localhost"),
                Integer.parseInt(env("EFXBANS_MYSQL_PORT", "3306")), env("EFXBANS_MYSQL_DATABASE", "efxbans_test"),
                env("EFXBANS_MYSQL_USER", "root"), env("EFXBANS_MYSQL_PASSWORD", ""), false, 4);
    }

    @Override
    void reset() throws Exception {
        DatabaseSettings s = settings();
        String url = "jdbc:mysql://" + s.host() + ":" + s.port() + "/" + s.database() + "?sslMode=DISABLED&allowPublicKeyRetrieval=true";
        try (Connection c = DriverManager.getConnection(url, s.username(), s.password()); Statement statement = c.createStatement()) {
            for (String table : TABLES) statement.execute("DROP TABLE IF EXISTS " + table);
        }
    }
}
