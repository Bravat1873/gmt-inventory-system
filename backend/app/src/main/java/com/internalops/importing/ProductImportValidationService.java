package com.internalops.importing;

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
            ProductImportCodeResolver.ResolvedProductCode resolved = codeResolver.resolve(data);
            data.put("productCode", resolved.productCode());
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
                duplicateRows.computeIfAbsent(text(row.data(), "productCode").toUpperCase(Locale.ROOT), ignored -> new ArrayList<>())
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
                String productCode = text(data, "productCode").toUpperCase(Locale.ROOT);
                boolean conflict = duplicateRows.get(productCode).size() > 1;
                data.put("_conflict", conflict);
                if (conflict) {
                    data.put("_conflictGroup", productCode);
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
}
