package com.internalops.importing;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;

@Service
public class ImportPreviewService {
    private final ImportBatchRepository repository;
    private final ImportValidationService validation;

    public ImportPreviewService(ImportBatchRepository repository, ImportValidationService validation) {
        this.repository = repository;
        this.validation = validation;
    }

    @Transactional
    public ImportBatchView preview(ImportType type, MultipartFile file) {
        try {
            String filename = file.getOriginalFilename();
            if (file.isEmpty() || filename == null
                    || !(filename.toLowerCase().endsWith(".xls") || filename.toLowerCase().endsWith(".xlsx"))) {
                throw new IllegalArgumentException("请选择有效的 .xls 或 .xlsx 文件");
            }
            byte[] bytes = file.getBytes();
            List<ParsedImportRow> parsedRows = new ExcelImportParser().parse(type, new ByteArrayInputStream(bytes));
            List<ParsedImportRow> rows = validation.validateAll(type, parsedRows);
            long id = repository.create(type, filename, UUID.randomUUID().toString().replace("-", ""), rows);
            return repository.findBatch(id);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("文件上传失败：" + exception.getMessage(), exception);
        }
    }

    public ImportBatchView get(long batchId) {
        return repository.findBatch(batchId);
    }

    @Transactional
    public ImportRowView add(long batchId, ImportRowRequest request) {
        ImportBatchView batch = editableBatch(batchId);
        ParsedImportRow row = validation.withConflict(batch.importType(),
                validation.validate(batch.importType(), "手工录入", repository.nextManualRow(batchId), request.data()));
        return repository.insertManual(batchId, row);
    }

    @Transactional
    public ImportRowView update(long batchId, long rowId, ImportRowRequest request) {
        ImportBatchView batch = editableBatch(batchId);
        ImportRowView original = batch.rows().stream()
                .filter(row -> row.id() == rowId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("导入行不存在"));
        ParsedImportRow row = validation.withConflict(batch.importType(),
                validation.validate(batch.importType(), original.sheetName(), original.rowNumber(), request.data()));
        return repository.updateRow(batchId, rowId, row);
    }

    private ImportBatchView editableBatch(long batchId) {
        ImportBatchView batch = repository.findBatchForUpdate(batchId);
        if ("COMMITTED".equals(batch.status())) throw new IllegalArgumentException("已提交批次不能修改");
        return batch;
    }
}
