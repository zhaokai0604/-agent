package com.award.log.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import java.sql.*;
import java.util.*;
import java.util.zip.CRC32;

/** V3 module schema adoption: metadata checks replace exception-driven startup DDL. */
public final class ModuleMigrationSupport {
    private ModuleMigrationSupport() {}
    public static int checksum(Class<?> migration, String resource) {
        var crc=new CRC32();
        try {
            for(Class<?> type:List.of(migration,ModuleMigrationSupport.class)) {
                try(var in=type.getResourceAsStream(type.getSimpleName()+".class")) {
                    if(in==null) throw new IllegalStateException("Missing migration bytecode");
                    crc.update(in.readAllBytes());
                }
            }
            try(var in=new ClassPathResource(resource).getInputStream()){crc.update(in.readAllBytes());}
            return (int)crc.getValue();
        } catch(java.io.IOException e){throw new IllegalStateException("Cannot validate module migration",e);}
    }

    public static void migrate(Connection c,String resource) throws Exception {
        JsonNode doc;
        try(var in=new ClassPathResource(resource).getInputStream()){doc=new ObjectMapper().readTree(in);}
        for(var table:doc.path("tables")) {
            execute(c,table.path("createSql").asText());
            String name=table.path("name").asText();
            for(var column:table.path("columns")) {
                String col=column.path("name").asText(),definition=column.path("definition").asText();
                if(!hasColumn(c,name,col)) execute(c,"ALTER TABLE "+name+" ADD COLUMN "+col+" "+definition);
                validateColumn(c,name,col,definition);
            }
            for(var index:table.path("indexes")) ensureIndex(c,name,index);
            for(var constraint:table.path("constraints")) ensureForeignKey(c,name,constraint.asText());
        }
        for(var index:doc.path("extraIndexes")) ensureIndex(c,index.path("table").asText(),index);
        for(var repair:doc.path("repairs")) execute(c,repair.asText());
    }

    private static String actualTable(Connection c,String table) throws SQLException {
        try(var rs=c.getMetaData().getTables(c.getCatalog(),c.getSchema(),null,new String[]{"TABLE"})) {
            while(rs.next())if(table.equalsIgnoreCase(rs.getString("TABLE_NAME")))return rs.getString("TABLE_NAME");
        }
        return table;
    }
    private static boolean hasColumn(Connection c,String table,String column) throws SQLException {
        try(var rs=c.getMetaData().getColumns(c.getCatalog(),c.getSchema(),actualTable(c,table),null)) {
            while(rs.next())if(column.equalsIgnoreCase(rs.getString("COLUMN_NAME")))return true;
        }
        return false;
    }
    private static void validateColumn(Connection c,String table,String column,String definition) throws SQLException {
        String sqlType=definition.split("[ (]",2)[0].toUpperCase(Locale.ROOT);
        try(var rs=c.getMetaData().getColumns(c.getCatalog(),c.getSchema(),actualTable(c,table),null)) {
            while(rs.next())if(column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) {
                int type=rs.getInt("DATA_TYPE");
                boolean valid=switch(sqlType) {
                    case "VARCHAR","TEXT","LONGTEXT" -> Set.of(Types.VARCHAR,Types.LONGVARCHAR,Types.CLOB,Types.NVARCHAR,Types.NCLOB).contains(type);
                    case "INT","INTEGER","BIGINT","TINYINT" -> Set.of(Types.BIGINT,Types.INTEGER,Types.SMALLINT,Types.TINYINT,Types.BIT,Types.BOOLEAN).contains(type);
                    case "DOUBLE" -> Set.of(Types.DOUBLE,Types.FLOAT,Types.REAL,Types.DECIMAL,Types.NUMERIC).contains(type);
                    case "TIMESTAMP","DATETIME" -> type==Types.TIMESTAMP || type==Types.TIMESTAMP_WITH_TIMEZONE;
                    default -> throw new SQLException("Unsupported versioned column type: "+sqlType);
                };
                var width=java.util.regex.Pattern.compile("(?i)VARCHAR\\((\\d+)\\)").matcher(definition);
                if(width.find() && valid && rs.getInt("COLUMN_SIZE")<Integer.parseInt(width.group(1))) {
                    widenVarchar(c,table,column,Integer.parseInt(width.group(1)),rs);
                }
                if(!valid)throw new SQLException("Incompatible existing column: "+table+"."+column+"; review schema before upgrade");
                return;
            }
        }
        throw new SQLException("Missing required column: "+table+"."+column);
    }

    /** Widen only; retain null/default and MySQL column collation/comment. Never narrow data. */
    private static void widenVarchar(Connection c,String table,String column,int width,ResultSet metadata) throws SQLException {
        String type="VARCHAR("+width+")";
        if(c.getMetaData().getDatabaseProductName().equalsIgnoreCase("H2")) {
            execute(c,"ALTER TABLE "+table+" ALTER COLUMN "+column+" SET DATA TYPE "+type);
            return;
        }
        String defaultValue=metadata.getString("COLUMN_DEF");
        String attributes=metadata.getInt("NULLABLE")==DatabaseMetaData.columnNoNulls?" NOT NULL":" NULL";
        if(defaultValue!=null)attributes+=" DEFAULT '"+defaultValue.replace("'","''")+"'";
        String charset="",comment="";
        try(var ps=c.prepareStatement("SELECT CHARACTER_SET_NAME,COLLATION_NAME,COLUMN_COMMENT,EXTRA FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?")) {
            ps.setString(1,table);ps.setString(2,column);
            try(var rs=ps.executeQuery()) {
                if(!rs.next())throw new SQLException("Cannot inspect column before widening: "+table+"."+column);
                String cs=rs.getString(1),co=rs.getString(2),extra=rs.getString(4);
                if(cs!=null&&co!=null&&cs.matches("[A-Za-z0-9_]+")&&co.matches("[A-Za-z0-9_]+")) charset=" CHARACTER SET "+cs+" COLLATE "+co;
                if(extra!=null&&!extra.isBlank())throw new SQLException("Review generated/invisible column before widening: "+table+"."+column);
                comment=" COMMENT '"+rs.getString(3).replace("'","''")+"'";
            }
        }
        execute(c,"ALTER TABLE "+table+" MODIFY COLUMN "+column+" "+type+charset+attributes+comment);
    }

    private static void ensureIndex(Connection c,String table,JsonNode spec) throws SQLException {
        List<String> wanted=new ArrayList<>();spec.path("columns").forEach(x->wanted.add(x.asText().toLowerCase(Locale.ROOT)));
        Map<String,SortedMap<Integer,String>> indexes=new LinkedHashMap<>();Map<String,Boolean> unique=new HashMap<>();
        try(var rs=c.getMetaData().getIndexInfo(c.getCatalog(),c.getSchema(),actualTable(c,table),false,false)) {
            while(rs.next()) {
                String name=rs.getString("INDEX_NAME"),col=rs.getString("COLUMN_NAME");
                if(name==null||col==null)continue;
                indexes.computeIfAbsent(name,x->new TreeMap<>()).put(rs.getInt("ORDINAL_POSITION"),col.toLowerCase(Locale.ROOT));
                unique.put(name,!rs.getBoolean("NON_UNIQUE"));
            }
        }
        boolean mustUnique=spec.path("unique").asBoolean();
        for(var e:indexes.entrySet())if(new ArrayList<>(e.getValue().values()).equals(wanted)&&(!mustUnique||unique.get(e.getKey())))return;
        String name=spec.path("name").asText();
        if(indexes.keySet().stream().anyMatch(x->x.equalsIgnoreCase(name)))throw new SQLException("Incompatible index: "+table+"."+name);
        execute(c,"CREATE "+(mustUnique?"UNIQUE ":"")+"INDEX "+name+" ON "+table+"("+String.join(",",wanted)+")");
    }

    private static void ensureForeignKey(Connection c,String table,String definition) throws SQLException {
        var pattern=java.util.regex.Pattern.compile("(?i)CONSTRAINT (\\w+) FOREIGN KEY \\((\\w+)\\) REFERENCES (\\w+)\\((\\w+)\\) ON DELETE CASCADE");
        var m=pattern.matcher(definition);
        if(!m.matches())throw new SQLException("Unsupported versioned foreign key: "+table);
        try(var rs=c.getMetaData().getImportedKeys(c.getCatalog(),c.getSchema(),actualTable(c,table))) {
            while(rs.next())if(m.group(2).equalsIgnoreCase(rs.getString("FKCOLUMN_NAME"))) {
                if(!m.group(3).equalsIgnoreCase(rs.getString("PKTABLE_NAME"))||!m.group(4).equalsIgnoreCase(rs.getString("PKCOLUMN_NAME"))
                        ||rs.getInt("DELETE_RULE")!=DatabaseMetaData.importedKeyCascade)throw new SQLException("Incompatible foreign key on "+table+"."+m.group(2));
                return;
            }
        }
        execute(c,"ALTER TABLE "+table+" ADD "+definition);
    }
    private static void execute(Connection c,String sql) throws SQLException {
        try(var statement=c.createStatement()){statement.execute(sql);}
    }
}
