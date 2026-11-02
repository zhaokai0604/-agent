package db.migration;

import com.award.log.migration.MigrationSupport;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V5__Tracked_log_diagnosis extends BaseJavaMigration {
    @Override public Integer getChecksum() {
        return MigrationSupport.checksum(getClass(), "db/changes/log-diagnosis-jobs.sql");
    }
    @Override public void migrate(Context context) {
        MigrationSupport.script(context.getConnection(), "db/changes/log-diagnosis-jobs.sql");
    }
}
