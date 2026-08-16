package com.internalops.importing;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImportRowViewTest {

    @Test
    void preservesBlankOptionalProductFieldsAsNull() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productCode", "BR_D51");
        data.put("supplierName", null);

        ImportRowView row = new ImportRowView(
                1L, "GMT库存产品清单", 9, ImportRowStatus.VALID, data, null, false);

        assertThat(row.data())
                .containsEntry("productCode", "BR_D51")
                .containsEntry("supplierName", null);
    }
}
