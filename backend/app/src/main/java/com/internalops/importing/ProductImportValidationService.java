package com.internalops.importing;

import com.internalops.productcode.ProductUniqueId;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Adds generated code and batch-local duplicate decisions to parsed product rows. */
@Service
public class ProductImportValidationService {
    private final ProductImportCodeResolver codeResolver;

    public ProductImportValidationService(ProductImportCodeResolver codeResolver) {
        this.codeResolver = codeResolver;
    }

    public ParsedImportRow validate(int ignoredIndex, String sheetName, int rowNumber, Map<String, Object> source) {
        Map<String, Object> data = new LinkedHashMap<>(source);
        try {
            validateInventoryFields(data);
            ProductImportCodeResolver.ResolvedProductCode resolved = codeResolver.resolve(data);
            data.put("productCode", resolved.productCode());
            data.put("_businessUniqueId", ProductUniqueId.from(
                    resolved.productCode(), text(data, "customerPartNumber")));
            data.put("_ruleFingerprint", resolved.ruleFingerprint());
            return new ParsedImportRow(sheetName, rowNumber, ImportRowStatus.VALID, data, null);
        } catch (IllegalArgumentException exception) {
            return new ParsedImportRow(sheetName, rowNumber, ImportRowStatus.ERROR, data, exception.getMessage());
        }
    }

    public List<ParsedImportRow> validateAll(List<ParsedImportRow> rows) {
        List<ParsedImportRow> validated = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            ParsedImportRow row = rows.get(index);
            if (row.status() == ImportRowStatus.VALID) {
                validated.add(validate(index, row.sheetName(), row.rowNumber(), row.data()));
            } else {
                validated.add(row);
            }
        }

        Map<String, List<Integer>> duplicateRows = new LinkedHashMap<>();
        for (int index = 0; index < validated.size(); index++) {
            ParsedImportRow row = validated.get(index);
            if (row.status() == ImportRowStatus.VALID) {
                duplicateRows.computeIfAbsent(text(row.data(), "_businessUniqueId"), ignored -> new ArrayList<>())
                        .add(index);
            }
        }

        List<ParsedImportRow> result = new ArrayList<>();
        for (int index = 0; index < validated.size(); index++) {
            ParsedImportRow row = validated.get(index);
            Map<String, Object> data = new LinkedHashMap<>(row.data());
            if (row.status() != ImportRowStatus.VALID) {
                data.remove("_conflict");
                data.remove("_conflictGroup");
                data.remove("_conflictAction");
            } else {
                String businessUniqueId = text(data, "_businessUniqueId");
                boolean conflict = duplicateRows.get(businessUniqueId).size() > 1;
                data.put("_conflict", conflict);
                if (conflict) {
                    data.put("_conflictGroup", businessUniqueId);
                    data.put("_conflictAction", "UNRESOLVED");
                } else {
                    data.remove("_conflictGroup");
                    data.remove("_conflictAction");
                }
            }
            result.add(new ParsedImportRow(row.sheetName(), row.rowNumber(), row.status(), data, row.errorMessage()));
        }
        return result;
    }

    private String text(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private void validateInventoryFields(Map<String, Object> data) {
        int actual = integer(data, "actualQuantity");
        int locked = integer(data, "lockedQuantity");
        int transit = integer(data, "inTransitQuantity");
        if (actual < 0 || locked < 0 || transit < 0 || locked > actual + transit) {
            throw new IllegalArgumentException("库存数量无效或锁定库存大于实际与在途库存之和");
        }
        data.put("actualQuantity", actual);
        data.put("lockedQuantity", locked);
        data.put("inTransitQuantity", transit);
    }

    private int integer(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value == null || value.toString().isBlank()) return 0;
        return new java.math.BigDecimal(value.toString()).intValueExact();
    }
}
