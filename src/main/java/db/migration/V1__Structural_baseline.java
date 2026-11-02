package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import com.award.log.migration.MigrationSupport;

/** Structural bootstrap only: never imports accounts, sample rules, or destructive SQL. */
public class V1__Structural_baseline extends BaseJavaMigration {
    @Override public Integer getChecksum() {
        return MigrationSupport.checksum(getClass(), "db/bootstrap/mysql.sql", "db/bootstrap/h2.sql");
    }
    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        MigrationSupport.script(connection, "db/bootstrap/" + (MigrationSupport.h2(connection) ? "h2" : "mysql") + ".sql");
    }
}
