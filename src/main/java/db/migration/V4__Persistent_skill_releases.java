package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import com.award.log.migration.MigrationSupport;

public class V4__Persistent_skill_releases extends BaseJavaMigration {
    @Override public Integer getChecksum(){return MigrationSupport.checksum(getClass(),"db/changes/skill-releases.sql");}
    @Override public void migrate(Context c){MigrationSupport.script(c.getConnection(),"db/changes/skill-releases.sql");}
}
