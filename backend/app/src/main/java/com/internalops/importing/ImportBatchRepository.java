package com.internalops.importing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ImportBatchRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ImportBatchRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<Long> findId(ImportType type, String hash) {
        List<Long> ids = jdbc.query("SELECT id FROM import_batch WHERE import_type=? AND file_hash=?",
                (rs, index) -> rs.getLong(1), type.name(), hash);
        return ids.stream().findFirst();
    }

    public long create(ImportType type, String filename, String hash, List<ParsedImportRow> rows) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO import_batch(import_type,original_filename,file_hash,status) VALUES(?,?,?,'PREVIEW')",
                    new String[]{"id"});
            statement.setString(1, type.name());
            statement.setString(2, filename);
            statement.setString(3, hash);
            return statement;
        }, keys);
        long batchId = keys.getKey().longValue();
        for (ParsedImportRow row : rows) insertRow(batchId, row, false);
        recalculate(batchId);
        return batchId;
    }

    public long createAppendOnly(ImportType type, String filename, RawWorkbookSnapshot snapshot) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO import_batch(import_type,original_filename,file_hash,status,total_rows,valid_rows) VALUES(?,?,?,'PREVIEW',?,?)",
                    new String[]{"id"});
            int rawRows = snapshot.sheets().stream().mapToInt(sheet -> sheet.rows().size()).sum();
            statement.setString(1, type.name());
            statement.setString(2, filename);
            statement.setString(3, UUID.randomUUID().toString().replace("-", ""));
            statement.setInt(4, rawRows);
            statement.setInt(5, rawRows);
            return statement;
        }, keys);
        long batchId = keys.getKey().longValue();
        jdbc.update("INSERT INTO import_workbook_file(batch_id,file_content) VALUES(?,?)", batchId, snapshot.fileContent());
        for (RawWorkbookSheet sheet : snapshot.sheets()) {
            long sheetId = insertSheet(batchId, sheet);
            for (RawWorkbookRow row : sheet.rows()) {
                jdbc.update("INSERT INTO import_workbook_row(batch_id,sheet_id,source_row,cell_values) VALUES(?,?,?,?)",
                        batchId, sheetId, row.sourceRow(), json(row.cells()));
            }
        }
        return batchId;
    }

    public boolean isAppendOnly(long batchId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM import_workbook_file WHERE batch_id=?", Integer.class, batchId);
        return count != null && count > 0;
    }

    private long insertSheet(long batchId, RawWorkbookSheet sheet) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO import_workbook_sheet(batch_id,sheet_index,sheet_name,column_count) VALUES(?,?,?,?)",
                    new String[]{"id"});
            statement.setLong(1, batchId);
            statement.setInt(2, sheet.sheetIndex());
            statement.setString(3, sheet.sheetName());
            statement.setInt(4, sheet.columnCount());
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    public void replaceForReimport(long batchId, List<ParsedImportRow> rows) {
        jdbc.update("DELETE FROM import_row WHERE batch_id=?", batchId);
        jdbc.update("UPDATE import_batch SET status='PREVIEW', total_rows=0, valid_rows=0, error_rows=0, "
                + "ignored_rows=0, committed_rows=0, result_detail=NULL, committed_at=NULL WHERE id=?", batchId);
        for (ParsedImportRow row : rows) insertRow(batchId, row, false);
        recalculate(batchId);
    }

    public ImportRowView insertManual(long batchId, ParsedImportRow row) {
        long id = insertRow(batchId, row, true);
        recalculate(batchId);
        return findRow(batchId, id);
    }

    private long insertRow(long batchId, ParsedImportRow row, boolean manual) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO import_row(batch_id,source_sheet,source_row,row_status,normalized_data,error_message,manual_entry) VALUES(?,?,?,?,?,?,?)",
                    new String[]{"id"});
            statement.setLong(1, batchId);
            statement.setString(2, row.sheetName());
            statement.setInt(3, row.rowNumber());
            statement.setString(4, row.status().name());
            statement.setString(5, json(row.data()));
            statement.setString(6, row.errorMessage());
            statement.setBoolean(7, manual);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    public ImportRowView updateRow(long batchId, long rowId, ParsedImportRow row) {
        int changed = jdbc.update("UPDATE import_row SET row_status=?,normalized_data=?,error_message=? WHERE id=? AND batch_id=?",
                row.status().name(), json(row.data()), row.errorMessage(), rowId, batchId);
        if (changed != 1) throw new IllegalArgumentException("导入行不存在");
        recalculate(batchId);
        return findRow(batchId, rowId);
    }

    public ImportBatchView findBatchForUpdate(long batchId) {
        var batches = jdbc.query("SELECT * FROM import_batch WHERE id=? FOR UPDATE", (rs, index) -> new ImportBatchView(
                rs.getLong("id"), ImportType.valueOf(rs.getString("import_type")), rs.getString("original_filename"),
                rs.getString("status"), rs.getInt("total_rows"), rs.getInt("valid_rows"), rs.getInt("error_rows"),
                rs.getInt("ignored_rows"), rs.getInt("committed_rows"), readJsonObject(rs.getString("result_detail")),
                findRows(batchId)), batchId);
        if (batches.isEmpty()) throw new IllegalArgumentException("\u5BFC\u5165\u6279\u6B21\u4E0D\u5B58\u5728");
        return batches.get(0);
    }
    public ImportBatchView findBatch(long batchId) {
        var batches = jdbc.query("SELECT * FROM import_batch WHERE id=?", (rs, index) -> new ImportBatchView(
                rs.getLong("id"), ImportType.valueOf(rs.getString("import_type")), rs.getString("original_filename"),
                rs.getString("status"), rs.getInt("total_rows"), rs.getInt("valid_rows"), rs.getInt("error_rows"),
                rs.getInt("ignored_rows"), rs.getInt("committed_rows"), readJsonObject(rs.getString("result_detail")),
                findRows(batchId)), batchId);
        if (batches.isEmpty()) throw new IllegalArgumentException("导入批次不存在");
        return batches.get(0);
    }

    public ImportRowView findRow(long batchId, long rowId) {
        var rows = jdbc.query("SELECT * FROM import_row WHERE batch_id=? AND id=?", (rs, index) -> new ImportRowView(
                rs.getLong("id"), rs.getString("source_sheet"), rs.getInt("source_row"),
                ImportRowStatus.valueOf(rs.getString("row_status")), readMap(rs.getString("normalized_data")),
                rs.getString("error_message"), rs.getBoolean("manual_entry")), batchId, rowId);
        if (rows.isEmpty()) throw new IllegalArgumentException("导入行不存在");
        return rows.get(0);
    }

    public List<ImportRowView> findRows(long batchId) {
        return jdbc.query("SELECT * FROM import_row WHERE batch_id=? ORDER BY source_row,id", (rs, index) -> new ImportRowView(
                rs.getLong("id"), rs.getString("source_sheet"), rs.getInt("source_row"),
                ImportRowStatus.valueOf(rs.getString("row_status")), readMap(rs.getString("normalized_data")),
                rs.getString("error_message"), rs.getBoolean("manual_entry")), batchId);
    }

    public ImportType type(long batchId) {
        return ImportType.valueOf(jdbc.queryForObject("SELECT import_type FROM import_batch WHERE id=?", String.class, batchId));
    }

    public String status(long batchId) {
        return jdbc.queryForObject("SELECT status FROM import_batch WHERE id=?", String.class, batchId);
    }

    public int nextManualRow(long batchId) {
        Integer value = jdbc.queryForObject("SELECT COALESCE(MAX(source_row),0)+1 FROM import_row WHERE batch_id=?", Integer.class, batchId);
        return value == null ? 1 : value;
    }

    public void recalculate(long batchId) {
        jdbc.update("UPDATE import_batch SET total_rows=(SELECT COUNT(*) FROM import_row WHERE batch_id=?)," +
                        "valid_rows=(SELECT COUNT(*) FROM import_row WHERE batch_id=? AND row_status='VALID')," +
                        "error_rows=(SELECT COUNT(*) FROM import_row WHERE batch_id=? AND row_status='ERROR')," +
                        "ignored_rows=(SELECT COUNT(*) FROM import_row WHERE batch_id=? AND row_status='IGNORED') WHERE id=?",
                batchId, batchId, batchId, batchId, batchId);
    }

    public void markCommitted(long batchId, int committedRows, Map<String, Object> result) {
        jdbc.update("UPDATE import_batch SET status='COMMITTED',committed_rows=?,result_detail=?,committed_at=CURRENT_TIMESTAMP WHERE id=?",
                committedRows, json(result), batchId);
    }

    public void markCommitting(long batchId) {
        jdbc.update("UPDATE import_batch SET status='COMMITTING' WHERE id=?", batchId);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("导入数据无法序列化", exception);
        }
    }

    private Map<String, Object> readMap(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("导入行数据损坏", exception);
        }
    }

    private Object readJsonObject(String value) {
        if (value == null || value.isBlank()) return null;
        return readMap(value);
    }
}
