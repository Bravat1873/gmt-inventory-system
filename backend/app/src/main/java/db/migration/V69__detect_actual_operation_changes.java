package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/** Retain every replay step, but distinguish actual business changes from timestamp/version touches. */
public class V69__detect_actual_operation_changes extends BaseJavaMigration {
    private static final Set<String> SECRET=Set.of("password_hash","password","token_hash","file_content");
    @Override public Integer getChecksum(){return 69001;}
    @Override public void migrate(Context context)throws Exception {
        Connection connection=context.getConnection();
        var tables=new ArrayList<String>();
        try(var statement=connection.createStatement();var rs=statement.executeQuery("SELECT table_name FROM operation_audit_table")) {
            while(rs.next())tables.add(rs.getString(1));
        }
        for(String table:tables) {
            var keys=new TreeMap<Integer,String>();var columns=new ArrayList<String>();var business=new ArrayList<String>();
            try(var rs=connection.getMetaData().getPrimaryKeys(connection.getCatalog(),null,table)) {
                while(rs.next())keys.put(rs.getInt("KEY_SEQ"),rs.getString("COLUMN_NAME"));
            }
            try(var rs=connection.getMetaData().getColumns(connection.getCatalog(),null,table,"%")) {
                while(rs.next()) {
                    String column=rs.getString("COLUMN_NAME");
                    if(!SECRET.contains(column))columns.add(column);
                    // Compare secret fields without ever persisting their values.
                    if(!Set.of("updated_at","version").contains(column))business.add(column);
                }
            }
            String changed=business.stream().map(c->"NOT (OLD.`"+c+"` <=> NEW.`"+c+"`)").collect(Collectors.joining(" OR "));
            String trigger="op_"+table+"_update";
            try(var statement=connection.createStatement()) {
                statement.execute("DROP TRIGGER IF EXISTS `"+trigger+"`");
                statement.execute("CREATE TRIGGER `"+trigger+"` AFTER UPDATE ON `"+table+"` FOR EACH ROW BEGIN "
                    +"INSERT INTO operation_change(operation_id,table_name,record_key,change_type,before_data,after_data,meaningful_change) "
                    +"SELECT operation_id,'"+table+"',CAST("+json("NEW",keys.values())+" AS CHAR),'UPDATE',"
                    +json("OLD",columns)+","+json("NEW",columns)+",("+(changed.isEmpty()?"FALSE":changed)+") "
                    +"FROM operation_context WHERE connection_id=CONNECTION_ID(); END");
            }
        }
    }
    private String json(String row,Collection<String> columns) {
        return "JSON_OBJECT("+columns.stream().map(c->"'"+c+"',"+row+".`"+c+"`").collect(Collectors.joining(","))+")";
    }
}
