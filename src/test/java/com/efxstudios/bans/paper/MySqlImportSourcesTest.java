package com.efxstudios.bans.paper;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Objects;

@EnabledIfEnvironmentVariable(named = "EFXBANS_MYSQL_HOST", matches = ".+")
class MySqlImportSourcesTest extends ImportSourcesContract {
    private static String env(String name, String fallback) {
        return Objects.requireNonNullElse(System.getenv(name), fallback);
    }

    @Override
    Connection open() throws Exception {
        String url = "jdbc:mysql://" + env("EFXBANS_MYSQL_HOST", "localhost") + ":" + env("EFXBANS_MYSQL_PORT", "3306") + "/"
                + env("EFXBANS_MYSQL_DATABASE", "efxbans_test") + "?sslMode=DISABLED&allowPublicKeyRetrieval=true";
        return DriverManager.getConnection(url, env("EFXBANS_MYSQL_USER", "root"), env("EFXBANS_MYSQL_PASSWORD", ""));
    }

    @Override
    List<String> schema() {
        String advancedBan = " (`id` int NOT NULL AUTO_INCREMENT,`name` VARCHAR(16) NULL DEFAULT NULL,`uuid` VARCHAR(35) NULL DEFAULT NULL,"
                + "`reason` VARCHAR(255) NULL DEFAULT NULL,`operator` VARCHAR(16) NULL DEFAULT NULL,`punishmentType` VARCHAR(16) NULL DEFAULT NULL,"
                + "`start` LONG DEFAULT NULL,`end` LONG DEFAULT NULL,`calculation` VARCHAR(50) NULL DEFAULT NULL,PRIMARY KEY (`id`))";
        String liteBans = " (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, uuid VARCHAR(36), ip VARCHAR(45), reason VARCHAR(2048),"
                + " banned_by_uuid VARCHAR(36) NOT NULL, banned_by_name VARCHAR(128), removed_by_uuid VARCHAR(36), removed_by_name VARCHAR(128),"
                + " removed_by_date TIMESTAMP NULL DEFAULT NULL, removed_by_reason VARCHAR(2048), time BIGINT NOT NULL, until BIGINT NOT NULL,"
                + " template TINYINT UNSIGNED NOT NULL DEFAULT 255, server_scope VARCHAR(32), server_origin VARCHAR(32), silent BIT(1) NOT NULL,"
                + " ipban BIT(1) NOT NULL, ipban_wildcard BIT(1) NOT NULL DEFAULT 0, active BIT(1) NOT NULL";
        return List.of(
                "CREATE TABLE `Punishments`" + advancedBan,
                "CREATE TABLE `PunishmentHistory`" + advancedBan,
                "CREATE TABLE litebans_bans" + liteBans + ")",
                "CREATE TABLE litebans_mutes" + liteBans + ")",
                "CREATE TABLE litebans_warnings" + liteBans + ", warned BIT(1) NOT NULL DEFAULT 0)",
                "CREATE TABLE litebans_kicks (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, uuid VARCHAR(36), ip VARCHAR(45), reason VARCHAR(2048),"
                        + " banned_by_uuid VARCHAR(36) NOT NULL, banned_by_name VARCHAR(128), time BIGINT NOT NULL, until BIGINT NOT NULL,"
                        + " template TINYINT UNSIGNED NOT NULL DEFAULT 255, server_scope VARCHAR(32), server_origin VARCHAR(32), silent BIT(1) NOT NULL,"
                        + " ipban BIT(1) NOT NULL, ipban_wildcard BIT(1) NOT NULL DEFAULT 0, active BIT(1) NOT NULL)",
                "CREATE TABLE litebans_history (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + " name VARCHAR(16), uuid VARCHAR(36), ip VARCHAR(45))");
    }
}
