package com.internalops.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.internalops.auth.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OperationRecordService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private volatile Map<String,Table> catalog;
    private static final Set<String> PRESERVED = Set.of("document_number_sequence");
    private static final int PAGE_SIZE = 20;
    public OperationRecordService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc=jdbc; this.json=json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    long begin(String method, String path, CurrentUser actor, String ip, boolean mutation) {
        if (mutation) jdbc.queryForObject("SELECT id FROM operation_write_lock WHERE id=1 FOR UPDATE", Integer.class);
        var keys=new GeneratedKeyHolder();
        jdbc.update(c -> {
            var ps=c.prepareStatement("""
                INSERT INTO operation_record(action,module_name,http_method,request_path,actor_id,actor_name,actor_username,actor_role,status,client_ip)
                VALUES(?,?,?,?,?,?,?,?,'RUNNING',?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1,OperationCatalog.action(method,path)); ps.setString(2,OperationCatalog.requestModule(path));
            ps.setString(3,method); ps.setString(4,truncate(path,1000));
            ps.setObject(5,actor==null?null:actor.id()); ps.setString(6,actor==null?"未登录人员":actor.displayName());
            ps.setString(7,actor==null?"":actor.username()); ps.setString(8,actor==null?"":actor.role().name());
            ps.setString(9,truncate(ip,80)); return ps;
        },keys);
        long id=Objects.requireNonNull(keys.getKey()).longValue();
        if (mutation) jdbc.update("INSERT INTO operation_context(connection_id,operation_id) VALUES("+connectionId()+",?)",id);
        return id;
    }

    void finish(long id, int status, CurrentUser actor, boolean mutation) {
        List<Change> changes=changes(id);
        var modules=new LinkedHashSet<String>(); var references=new LinkedHashSet<String>();
        var entities=new LinkedHashSet<String>(); String blocked=null;
        for (Change change:changes) {
            modules.add(OperationCatalog.module(change.table()));
            Map<String,Object> row=change.after()==null?change.before():change.after();
            references.add(OperationCatalog.label(change.table(),row));
            if (change.table().equals("sys_user")) blocked="用户安全信息不支持撤回，请通过用户管理重新设置；密码不会保存在日志中";
            if (change.table().equals("import_workbook_file") && !change.type().equals("INSERT")) blocked="原始导入文件内容未复制到日志，无法自动还原";
            if (!PRESERVED.contains(change.table())) {
                entities.add(entity(change.table(),map(change.key())));
                addReferences(change.before(),change.table(),entities,new HashSet<>(),0);
                addReferences(change.after(),change.table(),entities,new HashSet<>(),0);
            }
        }
        for (String key:entities) {
            jdbc.update("INSERT INTO operation_entity(operation_id,entity_key) VALUES(?,?)",id,key);
            int separator=key.indexOf(':');String table=key.substring(0,separator);
            var related=current(table(table),map(key.substring(separator+1)),false);
            if(related!=null)references.add(OperationCatalog.module(table)+"："+OperationCatalog.label(table,related));
        }
        if (changes.isEmpty()) blocked="此次操作没有可撤回的数据变更";
        jdbc.update("""
            UPDATE operation_record SET status='SUCCESS',completed_at=CURRENT_TIMESTAMP,http_status=?,change_count=?,
            affected_modules=?,related_records=?,non_reversible_reason=? WHERE id=?
            """,status,changes.size(),String.join("、",modules),truncate(String.join("；",references),16000),blocked,id);
        if (actor!=null) jdbc.update("UPDATE operation_record SET actor_id=?,actor_name=?,actor_username=?,actor_role=? WHERE id=?",
                actor.id(),actor.displayName(),actor.username(),actor.role().name(),id);
        if (mutation) jdbc.update("DELETE FROM operation_context WHERE connection_id="+connectionId());
    }

    @Transactional
    public void failed(String method,String path,CurrentUser actor,String ip,int status,String message) {
        long id=begin(method,path,actor,ip,false);
        jdbc.update("UPDATE operation_record SET status='FAILED',http_status=?,completed_at=CURRENT_TIMESTAMP,result_message=?,non_reversible_reason=? WHERE id=?",
                status,truncate(message,1000),"操作未成功，业务变更已回滚",id);
    }

    public Map<String,Object> list(String keyword,String actor,String module,String status,String from,String to,int requestedPage) {
        CurrentUser.required();
        int page=Math.max(1,requestedPage); var args=new ArrayList<Object>(); var where=new StringBuilder(" WHERE status<>'RUNNING'");
        if (!blank(keyword)) {
            where.append(" AND (action LIKE ? OR related_records LIKE ? OR request_path LIKE ? OR CAST(id AS CHAR)=?)");
            for(int i=0;i<3;i++)args.add("%"+keyword.trim()+"%"); args.add(keyword.trim());
        }
        if (!blank(actor)) { where.append(" AND (actor_name LIKE ? OR actor_username LIKE ?)"); args.add("%"+actor.trim()+"%"); args.add("%"+actor.trim()+"%"); }
        if (!blank(module)) { where.append(" AND (module_name=? OR affected_modules LIKE ?)");args.add(module);args.add("%"+module+"%"); }
        if (!blank(status)) { where.append(" AND status=?");args.add(status); }
        if (!blank(from)) { where.append(" AND operated_at>=?");args.add(LocalDate.parse(from).atStartOfDay()); }
        if (!blank(to)) { where.append(" AND operated_at<?");args.add(LocalDate.parse(to).plusDays(1).atStartOfDay()); }
        long total=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM operation_record"+where,Long.class,args.toArray()));
        args.add(PAGE_SIZE);args.add((long)(page-1)*PAGE_SIZE);
        var items=jdbc.queryForList("SELECT * FROM operation_record"+where+" ORDER BY id DESC LIMIT ? OFFSET ?",args.toArray()).stream().map(this::view).toList();
        return Map.of("items",items,"total",total,"page",page,"pageSize",PAGE_SIZE);
    }

    public Map<String,Object> detail(long id,int requestedPage) {
        CurrentUser.required(); Map<String,Object> record=record(id); int page=Math.max(1,requestedPage);
        var changes=jdbc.query("SELECT * FROM operation_change WHERE operation_id=? ORDER BY id LIMIT 100 OFFSET ?",
                (rs,n)->readChange(rs),id,(long)(page-1)*100);
        var result=new LinkedHashMap<String,Object>();result.put("operation",view(record));
        result.put("changes",changes.stream().map(c->Map.of("id",c.id(),"module",OperationCatalog.module(c.table()),"table",c.table(),
                "recordKey",c.key(),"label",OperationCatalog.label(c.table(),c.after()==null?c.before():c.after()),"type",c.type(),
                "fields",diff(c),"preserved",PRESERVED.contains(c.table()))).toList());
        result.put("changePage",page);result.put("changePageSize",100);
        var blockers=blockers(id);String reason=unavailable(record,blockers);
        if(reason==null) reason=staleReason(id);
        result.put("canUndo",reason==null); result.put("undoReason",reason==null?"":reason);result.put("blockers",blockers);
        return result;
    }
    private String staleReason(long id) {
        var latest=new LinkedHashMap<String,Change>();
        for(Change change:changes(id))if(!PRESERVED.contains(change.table()))latest.put(change.table()+":"+change.key(),change);
        for(Change change:latest.values()) {
            if(!matchesSnapshot(current(table(change.table()),map(change.key()),false),change.after()))
                return "关联数据已发生变化，无法撤回："+OperationCatalog.module(change.table())+" "+change.key();
        }
        return null;
    }

    @Transactional
    public Map<String,Object> undo(long id,String reason) {
        CurrentUser.required();
        if (blank(reason) || reason.trim().length()>1000) throw new IllegalArgumentException("请填写 1～1000 字的撤回原因");
        Long undoId=OperationScope.current();
        if (undoId==null) throw new IllegalStateException("撤回必须通过操作记录接口执行");
        jdbc.queryForObject("SELECT id FROM operation_write_lock WHERE id=1 FOR UPDATE",Integer.class);
        Map<String,Object> record=record(id);
        if(!authorized(record))throw new SecurityException("只能撤回自己的操作，管理员可以撤回全部操作");
        String blocked=unavailable(record,blockers(id));
        if (blocked!=null) throw new UndoConflict(blocked);
        List<Change> changes=changes(id); Collections.reverse(changes);
        var versions=new LinkedHashMap<String,Long>();
        var touched=new LinkedHashMap<String,Change>();
        for(Change change:changes) {
            if(PRESERVED.contains(change.table()))continue;
            String identity=change.table()+":"+change.key();
            if(touched.putIfAbsent(identity,change)==null) {
                var current=current(table(change.table()),map(change.key()),true);
                if(current!=null && current.get("version") instanceof Number version)versions.put(identity,version.longValue());
            }
            if(change.before()!=null && change.before().get("version") instanceof Number version)versions.merge(identity,version.longValue(),Math::max);
        }
        for (Change change:changes) {
            if (PRESERVED.contains(change.table())) continue; // Numbers must never be reused.
            Map<String,Object> key=map(change.key()); Table table=table(change.table());
            var current=current(table,key,true);
            if (!matchesSnapshot(current,change.after())) throw new UndoConflict("关联数据已发生变化，无法撤回："+OperationCatalog.module(change.table())+" "+change.key());
            if (change.before()==null) {
                jdbc.update("DELETE FROM "+quote(table.name())+" WHERE "+predicate(key),key.values().toArray());
            } else if (change.after()==null) {
                var before=change.before(); checkColumns(table,before);
                jdbc.update("INSERT INTO "+quote(table.name())+" ("+before.keySet().stream().map(this::quote).collect(Collectors.joining(","))+") VALUES ("+
                        String.join(",",Collections.nCopies(before.size(),"?"))+")",values(before).toArray());
            } else {
                var before=change.before();checkColumns(table,before);var values=values(before);values.addAll(key.values());
                jdbc.update("UPDATE "+quote(table.name())+" SET "+before.keySet().stream().map(c->quote(c)+"=?").collect(Collectors.joining(","))+" WHERE "+predicate(key),values.toArray());
            }
        }
        // Keep optimistic-lock versions increasing so a form opened before undo stays stale.
        for(var entry:versions.entrySet()) {
            Change change=touched.get(entry.getKey());Table table=table(change.table());var key=map(change.key());
            var values=new ArrayList<Object>();values.add(entry.getValue()+1);values.addAll(key.values());
            jdbc.update("UPDATE "+quote(table.name())+" SET version=?"+(table.columns().contains("updated_at")?",updated_at=CURRENT_TIMESTAMP":"")+" WHERE "+predicate(key),values.toArray());
        }
        jdbc.update("UPDATE operation_record SET status='REVERSED',reversed_by_operation_id=? WHERE id=?",undoId,id);
        jdbc.update("UPDATE operation_record SET reverses_id=?,reversal_reason=?,action=? WHERE id=?",id,reason.trim(),"撤回操作 #"+id,undoId);
        return Map.of("id",id,"reversalOperationId",undoId);
    }

    private String unavailable(Map<String,Object> record,List<Map<String,Object>> blockers) {
        if (!authorized(record)) return "只能撤回自己的操作，管理员可以撤回全部操作";
        if (!"SUCCESS".equals(record.get("status"))) return "REVERSED".equals(record.get("status"))?"此操作已撤回":"此记录不支持撤回";
        if (record.get("reverses_id")!=null) return "撤回记录不可再次撤回；如需重新执行，请回到业务模块操作";
        if (record.get("non_reversible_reason")!=null) return record.get("non_reversible_reason").toString();
        if (!blockers.isEmpty()) return "关联数据已被后续操作使用，请先处理操作 #"+blockers.get(0).get("id");
        return null;
    }
    private boolean authorized(Map<String,Object> record) {
        CurrentUser user=CurrentUser.required();
        return user.role()==UserRole.ADMIN || (record.get("actor_id") instanceof Number id && id.longValue()==user.id());
    }
    private List<Map<String,Object>> blockers(long id) {
        return jdbc.queryForList("""
            SELECT DISTINCT r.id,r.action,r.actor_name AS actorName,r.operated_at AS operatedAt
            FROM operation_record r JOIN operation_entity later ON later.operation_id=r.id
            JOIN operation_entity earlier ON earlier.entity_key=later.entity_key AND earlier.operation_id=?
            WHERE r.id>? AND r.status='SUCCESS' AND r.reverses_id IS NULL
            ORDER BY r.id DESC LIMIT 20
            """,id,id);
    }

    private void addReferences(Map<String,Object> row,String name,Set<String> entities,Set<String> seen,int depth) {
        if(row==null||depth>8)return;
        Table table=table(name);
        for (var ref:table.references().entrySet()) {
            Object value=row.get(ref.getKey()); String target=ref.getValue();
            // Operator and common warehouse ids do not define business dependencies.
            if(value==null||target.equals("sys_user")||target.equals("warehouse"))continue;
            addParent(target,value,entities,seen,depth);
        }
        String source=OperationCatalog.sourceTable(row.get("source_type"));
        if(source!=null&&row.get("source_id")!=null) addParent(source,row.get("source_id"),entities,seen,depth);
        source=OperationCatalog.sourceTable(row.get("business_type"));
        if(source!=null&&row.get("business_id")!=null) addParent(source,row.get("business_id"),entities,seen,depth);
    }
    private void addParent(String name,Object value,Set<String> entities,Set<String> seen,int depth) {
        if(!tables().containsKey(name))return;
        Table parent=table(name); if(parent.keys().size()!=1)return;
        Map<String,Object> key=Map.of(parent.keys().get(0),value);String entity=entity(name,key);entities.add(entity);
        if(seen.add(entity)) addReferences(current(parent,key,false),name,entities,seen,depth+1);
    }

    private List<Map<String,Object>> diff(Change change) {
        var columns=new LinkedHashSet<String>();if(change.before()!=null)columns.addAll(change.before().keySet());if(change.after()!=null)columns.addAll(change.after().keySet());
        var result=new ArrayList<Map<String,Object>>();
        for(String column:columns) {
            Object before=change.before()==null?null:change.before().get(column),after=change.after()==null?null:change.after().get(column);
            if(change.type().equals("UPDATE")&&equivalent(before,after))continue;
            var field=new LinkedHashMap<String,Object>();field.put("field",column);field.put("before",displayValue(before));field.put("after",displayValue(after));result.add(field);
        }
        return result;
    }
    private Map<String,Object> view(Map<String,Object> raw) {
        var result=new LinkedHashMap<String,Object>();
        raw.forEach((k,v)->{ var parts=k.split("_");var key=new StringBuilder(parts[0]);for(int i=1;i<parts.length;i++)key.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));result.put(key.toString(),v instanceof Timestamp time?time.toLocalDateTime().toString():v); });
        return result;
    }
    private Map<String,Object> record(long id) {
        var rows=jdbc.queryForList("SELECT * FROM operation_record WHERE id=?",id);
        if(rows.isEmpty())throw new IllegalArgumentException("操作记录不存在");return rows.get(0);
    }
    private List<Change> changes(long id) { return jdbc.query("SELECT * FROM operation_change WHERE operation_id=? ORDER BY id",(rs,n)->readChange(rs),id); }
    private Change readChange(ResultSet rs)throws SQLException { return new Change(rs.getLong("id"),rs.getString("table_name"),rs.getString("record_key"),rs.getString("change_type"),map(rs.getString("before_data")),map(rs.getString("after_data"))); }
    private Map<String,Object> current(Table table,Map<String,Object> key,boolean lock) {
        if(!new HashSet<>(table.keys()).equals(key.keySet()))throw new UndoConflict("记录主键结构已变化，请人工核查");
        var rows=jdbc.queryForList("SELECT "+table.columns().stream().map(this::quote).collect(Collectors.joining(","))+" FROM "+quote(table.name())+" WHERE "+predicate(key)+(lock?" FOR UPDATE":""),key.values().toArray());
        return rows.isEmpty()?null:rows.get(0);
    }
    private void checkColumns(Table table,Map<String,Object> data) { if(!new HashSet<>(table.columns()).equals(data.keySet()))throw new UndoConflict("记录字段结构已变化，请人工核查"); }
    private String predicate(Map<String,Object> key) { return key.keySet().stream().map(c->quote(c)+"=?").collect(Collectors.joining(" AND ")); }
    private String quote(String name) { if(!name.matches("[a-zA-Z_][a-zA-Z0-9_]*"))throw new IllegalArgumentException("无效字段");return "`"+name+"`"; }
    private List<Object> values(Map<String,Object> map) { return map.values().stream().map(v->v instanceof Map||v instanceof List?encode(v):v).collect(Collectors.toCollection(ArrayList::new)); }
    private String entity(String name,Map<String,Object> key) { return name+":"+encode(new TreeMap<>(key)); }
    private String encode(Object value) { try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("操作记录序列化失败",e);} }
    private Map<String,Object> map(String value) {
        if(value==null)return null;
        try { var node=json.readTree(value); if(node.isTextual())node=json.readTree(node.textValue());return json.convertValue(node,new TypeReference<LinkedHashMap<String,Object>>(){}); }
        catch(Exception e){throw new IllegalStateException("操作记录快照无法读取",e);}
    }
    private boolean equivalent(Object a,Object b) {
        if(a==null||b==null)return a==b;
        if(a instanceof Map<?,?> am && b instanceof Map<?,?> bm) return am.keySet().equals(bm.keySet())&&am.keySet().stream().allMatch(k->equivalent(am.get(k),bm.get(k)));
        if(a instanceof Boolean) a=Boolean.TRUE.equals(a)?1:0;
        if(b instanceof Boolean) b=Boolean.TRUE.equals(b)?1:0;
        if(a instanceof Number && b instanceof Number)return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()))==0;
        if(b instanceof Map||b instanceof List) { try{return json.readTree(a instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):a.toString()).equals(json.valueToTree(b));}catch(Exception e){return false;} }
        return normalized(a).equals(normalized(b));
    }
    private Object displayValue(Object value) {return value instanceof BigDecimal number?number.toPlainString():value instanceof Number?value.toString():value;}
    private boolean matchesSnapshot(Map<String,Object> actual,Map<String,Object> expected) {
        if(actual==null||expected==null)return actual==expected;
        return actual.keySet().equals(expected.keySet()) && actual.keySet().stream()
                .filter(k->!k.equals("version")&&!k.equals("updated_at")).allMatch(k->equivalent(actual.get(k),expected.get(k)));
    }
    private String normalized(Object value) {
        String text=value.toString().replace('T',' ');
        if(text.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d+"))text=text.replaceFirst("0+$","").replaceFirst("\\.$","");return text;
    }
    private Table table(String name) { Table table=tables().get(name);if(table==null)throw new UndoConflict("关联表结构已变化："+name);return table; }
    private Map<String,Table> tables() {
        if(catalog!=null)return catalog;
        synchronized(this) {
            if(catalog!=null)return catalog;
            var result=new LinkedHashMap<String,Table>();
            for(var row:jdbc.queryForList("SELECT * FROM operation_audit_table")) {
                String name=row.get("table_name").toString();var refs=new LinkedHashMap<String,String>();
                jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>)c->{try(var rs=c.getMetaData().getImportedKeys(c.getCatalog(),null,name)){while(rs.next())refs.put(rs.getString("FKCOLUMN_NAME"),rs.getString("PKTABLE_NAME"));}return null;});
                result.put(name,new Table(name,strings(row.get("key_columns")),strings(row.get("data_columns")),refs));
            }
            catalog=result;return result;
        }
    }
    private List<String> strings(Object raw) { try {var node=json.readTree(raw instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):raw.toString());if(node.isTextual())node=json.readTree(node.textValue());return json.convertValue(node,new TypeReference<List<String>>(){});}catch(Exception e){throw new IllegalStateException(e);} }
    private String connectionId() {return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)c->c.getMetaData().getDatabaseProductName().equals("H2")?"SESSION_ID()":"CONNECTION_ID()");}
    private static boolean blank(String text) {return text==null||text.isBlank();}
    private static String truncate(String text,int length) {return text==null?null:text.substring(0,Math.min(text.length(),length));}
    private record Change(long id,String table,String key,String type,Map<String,Object> before,Map<String,Object> after) { }
    private record Table(String name,List<String> keys,List<String> columns,Map<String,String> references) { }
    public static class UndoConflict extends RuntimeException {public UndoConflict(String message){super(message);} }
}
