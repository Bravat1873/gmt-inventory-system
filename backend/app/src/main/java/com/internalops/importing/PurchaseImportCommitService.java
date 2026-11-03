package com.internalops.importing;

import com.internalops.workbench.ManualPurchaseRequest;
import com.internalops.workbench.ProcurementWorkflowService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.*;
import static com.internalops.importing.PurchaseImportValidationService.text;

@Service
public class PurchaseImportCommitService {
    private final ImportBatchRepository repository;
    private final JdbcTemplate jdbc;
    private final ProcurementWorkflowService procurement;

    public PurchaseImportCommitService(ImportBatchRepository repository, JdbcTemplate jdbc, ProcurementWorkflowService procurement) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.procurement = procurement;
    }

    @Transactional
    public ImportBatchView commit(long batchId) {
        ImportBatchView batch = repository.findBatchForUpdate(batchId);
        if (batch.importType() != ImportType.PURCHASE) throw new IllegalArgumentException("不是采购导入批次");
        if ("COMMITTED".equals(batch.status())) return batch;
        if (!"PREVIEW".equals(batch.status())) throw new IllegalArgumentException("仅预览中的批次可以提交");
        // Lock quote rows before revalidation, so the preview price cannot change during creation.
        var quoteIds = batch.rows().stream().map(row -> row.data().get("_purchaseInfoId"))
                .filter(Objects::nonNull).map(id -> ((Number) id).longValue()).distinct().sorted().toList();
        for (Long id : quoteIds) {
            jdbc.queryForList("SELECT id FROM sku_supplier_purchase_info WHERE id=? FOR UPDATE", id);
        }
        var rows = new PurchaseImportValidationService(jdbc).validateAll(batch.rows().stream()
                .map(row -> new ParsedImportRow(row.sheetName(), row.rowNumber(), row.status(), row.data(), row.errorMessage())).toList());
        if (rows.isEmpty() || rows.stream().anyMatch(row -> row.status() != ImportRowStatus.VALID))
            throw new IllegalArgumentException("采购导入存在错误或报价已变化，请修正文件并重新预览");
        Map<String, List<ParsedImportRow>> groups = new LinkedHashMap<>();
        for (ParsedImportRow row : rows) groups.computeIfAbsent(PurchaseImportValidationService.groupKey(row), key -> new ArrayList<>()).add(row);
        List<Map<String, Object>> created = new ArrayList<>();
        for (var group : groups.values()) {
            var header = group.get(0).data();
            var items = group.stream().map(row -> new ManualPurchaseRequest.Item(
                    ((Number) row.data().get("_skuId")).longValue(),
                    ((Number) row.data().get("_purchaseInfoId")).longValue(),
                    ((Number) row.data().get("quantity")).intValue())).toList();
            String date = text(header, "expectedArrivalDate");
            created.add(procurement.manual(new ManualPurchaseRequest(((Number) header.get("_supplierId")).longValue(),
                    0, 0, 0, date.isBlank() ? null : LocalDate.parse(date), text(header, "deliveryAddress"),
                    text(header, "remark"), items, null)));
        }
        repository.markCommitted(batchId, rows.size(), Map.of("createdPurchases", created.size(), "committed", rows.size(), "purchases", created));
        return repository.findBatch(batchId);
    }
}
