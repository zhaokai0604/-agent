package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import com.award.log.migration.ModuleMigrationSupport;

public class V3__Versioned_module_schema extends BaseJavaMigration {
    private static final String RESOURCE="db/changes/module-structure.json";
    @Override public Integer getChecksum(){return ModuleMigrationSupport.checksum(getClass(),RESOURCE);}
    @Override public void migrate(Context context)throws Exception {ModuleMigrationSupport.migrate(context.getConnection(),RESOURCE);}
}
