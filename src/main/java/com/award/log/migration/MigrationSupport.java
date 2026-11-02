package com.award.log.migration;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.*;
import java.io.IOException;
import java.util.zip.CRC32;

/** Helpers shared by immutable, checksummed Flyway migrations. */
public final class MigrationSupport {
    private MigrationSupport() {}

    public static int checksum(Class<?> type, String... resources) {
        CRC32 crc = new CRC32();
        try {
            try (var in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
                if (in == null) throw new IOException("Missing migration class");
                crc.update(in.readAllBytes());
            }
            try (var in = MigrationSupport.class.getResourceAsStream("MigrationSupport.class")) {
                if (in == null) throw new IOException("Missing migration helper");
                crc.update(in.readAllBytes());
            }
            for (String resource : resources) {
                try (var in = new ClassPathResource(resource).getInputStream()) { crc.update(in.readAllBytes()); }
            }
            return (int) crc.getValue();
        } catch (IOException e) { throw new IllegalStateException("Cannot checksum migration", e); }
    }

    public static boolean h2(Connection c) throws SQLException {
        return c.getMetaData().getDatabaseProductName().equalsIgnoreCase("H2");
    }

    public static void script(Connection c, String resource) {
        ScriptUtils.executeSqlScript(c, new ClassPathResource(resource));
    }

    public static boolean hasColumn(Connection c, String table, String column) throws SQLException {
        // Some JDBC drivers report identifiers in upper case.
        for (String name : new String[]{table, table.toUpperCase(java.util.Locale.ROOT)}) {
            try (var rs = c.getMetaData().getColumns(c.getCatalog(), c.getSchema(), name, null)) {
                while (rs.next()) if (column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) return true;
            }
        }
        return false;
    }
}
