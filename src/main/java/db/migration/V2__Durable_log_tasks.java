package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import com.award.log.migration.MigrationSupport;

public class V2__Durable_log_tasks extends BaseJavaMigration {
    @Override public Integer getChecksum() {
        return MigrationSupport.checksum(getClass(), "db/changes/durable-log-tasks.sql");
    }
    @Override public void migrate(Context context) throws Exception {
        var c = context.getConnection();
        if (!MigrationSupport.hasColumn(c, "log_analysis_task", "user_id")) {
            try (var statement = c.createStatement()) {
                statement.execute("ALTER TABLE log_analysis_task ADD COLUMN user_id INTEGER NULL");
                statement.execute("CREATE INDEX idx_task_owner ON log_analysis_task(user_id)");
                statement.execute("ALTER TABLE log_analysis_task ADD CONSTRAINT fk_task_owner "
                        + "FOREIGN KEY(user_id) REFERENCES sys_user(user_id) ON DELETE SET NULL");
            }
        }
        MigrationSupport.script(c, "db/changes/durable-log-tasks.sql");
    }
}
