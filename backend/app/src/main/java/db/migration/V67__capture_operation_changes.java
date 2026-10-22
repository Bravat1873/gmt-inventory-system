package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/** Install row-level capture for every business table. Reinstall after future schema changes. */
public class V67__capture_operation_changes extends BaseJavaMigration {
    private static final Set<String> EXCLUDED = Set.of("flyway_schema_history", "operation_log", "user_session");
    private static final Set<String> SECRET = Set.of("password_hash", "password", "token_hash", "file_content");
    @Override public Integer getChecksum() { return 67001; }

    @Override public void migrate(Context context) throws Exception { install(context.getConnection()); }

    public static void install(Connection connection) throws Exception {
        var mapper = new ObjectMapper();
        var tables = new ArrayList<String>();
        try (var rs = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (!name.startsWith("operation_") && !EXCLUDED.contains(name)) tables.add(name);
            }
        }
        for (String table : tables) {
            var keys = new TreeMap<Integer,String>();
            var columns = new ArrayList<String>();
            try (var rs = connection.getMetaData().getPrimaryKeys(connection.getCatalog(), null, table)) {
                while (rs.next()) keys.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
            try (var rs = connection.getMetaData().getColumns(connection.getCatalog(), null, table, "%")) {
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME");
                    if (!SECRET.contains(name)) columns.add(name);
                }
            }
            if (keys.isEmpty()) throw new SQLException("操作记录要求业务表具有主键: " + table);
            try (var ps = connection.prepareStatement("INSERT INTO operation_audit_table(table_name,key_columns,data_columns) VALUES(?,?,?)")) {
                ps.setString(1, table); ps.setString(2, mapper.writeValueAsString(keys.values()));
                ps.setString(3, mapper.writeValueAsString(columns)); ps.executeUpdate();
            }
            for (String event : List.of("INSERT", "UPDATE", "DELETE")) {
                String before = event.equals("INSERT") ? "NULL" : json("OLD", columns);
                String after = event.equals("DELETE") ? "NULL" : json("NEW", columns);
                String key = json(event.equals("DELETE") ? "OLD" : "NEW", keys.values());
                String ddl = "CREATE TRIGGER `op_" + table + "_" + event.toLowerCase(Locale.ROOT) + "` AFTER " + event
                        + " ON `" + table + "` FOR EACH ROW BEGIN "
                        + "INSERT INTO operation_change(operation_id,table_name,record_key,change_type,before_data,after_data) "
                        + "SELECT operation_id,'" + table + "',CAST(" + key + " AS CHAR),'" + event + "'," + before + "," + after
                        + " FROM operation_context WHERE connection_id=CONNECTION_ID(); END";
                try (var statement = connection.createStatement()) { statement.execute(ddl); }
            }
        }
        // MySQL 8 does not fire row triggers for FK cascades. Explicit child deletion captures
        // images before their parent, and preserves the exact reverse replay order.
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER op_sku_delete_images BEFORE DELETE ON sku FOR EACH ROW DELETE FROM product_image WHERE product_id=OLD.id");
        }
    }
    private static String json(String row, Collection<String> columns) {
        return "JSON_OBJECT(" + columns.stream().map(c -> "'" + c + "'," + row + ".`" + c + "`").collect(Collectors.joining(",")) + ")";
    }
}
