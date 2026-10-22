package com.internalops.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

/** Fail closed on incomplete capture after a schema change; never silently miss a module. */
@Component
@ConditionalOnProperty(name="app.operation-audit.enabled",havingValue="true",matchIfMissing=true)
public class OperationAuditSchemaVerifier implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public OperationAuditSchemaVerifier(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    @Override public void run(ApplicationArguments ignored) {
        var registered=new HashMap<String,Set<String>>();
        for(var row:jdbc.queryForList("SELECT table_name,data_columns FROM operation_audit_table")) {
            try { registered.put(row.get("table_name").toString(),json.readValue(row.get("data_columns").toString(),new TypeReference<Set<String>>(){})); }
            catch(Exception e){throw new IllegalStateException("操作记录表结构配置无法读取",e);}
        }
        var actual=new HashMap<String,Set<String>>();
        for(var row:jdbc.queryForList("SELECT TABLE_NAME,COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()")) {
            String table=row.get("TABLE_NAME").toString(),column=row.get("COLUMN_NAME").toString();
            if(table.startsWith("operation_")||Set.of("operation_log","user_session","flyway_schema_history").contains(table))continue;
            if(!Set.of("password_hash","password","token_hash","file_content").contains(column))actual.computeIfAbsent(table,k->new HashSet<>()).add(column);
        }
        if(!actual.equals(registered))throw new IllegalStateException("操作记录未覆盖当前业务表字段，请通过新数据库迁移同步审计触发器");
        Set<String> triggers=new HashSet<>(jdbc.queryForList("SELECT TRIGGER_NAME FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()",String.class));
        for(String table:actual.keySet())for(String action:List.of("insert","update","delete")) {
            if(!triggers.contains("op_"+table+"_"+action))throw new IllegalStateException("缺少操作记录触发器: "+table+" "+action);
        }
        if(!triggers.contains("op_sku_delete_images"))throw new IllegalStateException("缺少产品图片级联删除记录保护");
    }
}
