package com.efxstudios.bans.common;

import java.nio.file.Path;

public record DatabaseSettings(String type, Path sqlite, String host, int port, String database,
                               String username, String password, boolean tls, int poolSize) {
    public DatabaseSettings {
        if (!type.equalsIgnoreCase("sqlite") && !type.equalsIgnoreCase("mysql"))
            throw new IllegalArgumentException("database.type: sqlite veya mysql olmalı");
        // Both values end up in the JDBC URL, so only plain names are accepted.
        if (!database.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("Geçersiz veritabanı adı");
        if (!host.matches("[A-Za-z0-9.:-]+")) throw new IllegalArgumentException("Geçersiz veritabanı sunucusu");
        if (port < 1 || port > 65535 || poolSize < 1 || poolSize > 64) throw new IllegalArgumentException("Geçersiz port/havuz boyutu");
    }

    public boolean mysql() {
        return type.equalsIgnoreCase("mysql");
    }

    /** Never print the password, e.g. when a settings object ends up in a log line. */
    @Override
    public String toString() {
        return "DatabaseSettings[type=" + type + ", host=" + host + ", port=" + port + ", database=" + database + ", tls=" + tls + "]";
    }
}
