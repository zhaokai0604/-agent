package db.migration;

import com.award.log.migration.MigrationSupport;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V7__Upload_idempotency extends BaseJavaMigration {
    @Override public Integer getChecksum() {
        return MigrationSupport.checksum(getClass(), "db/changes/upload-idempotency.sql");
    }
    @Override public void migrate(Context context) {
        MigrationSupport.script(context.getConnection(), "db/changes/upload-idempotency.sql");
    }
}
