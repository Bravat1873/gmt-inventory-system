package com.internalops.importing;

import com.internalops.productcode.ProductCodeGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProductImportValidationServiceTest {
    private ProductImportValidationService service;

    @BeforeEach
    void setUp() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:product-import;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        ScriptUtils.executeSqlScript(jdbc.getDataSource().getConnection(), new ClassPathResource("product-import-schema.sql"));
        insertRule(jdbc, 1, "BRAND", "G", "GMT");
        insertRule(jdbc, 2, "SERIES", "T7", "T7");
        insertRule(jdbc, 3, "BODY_COLOR", "PBY", "瀑布银");
        insertRule(jdbc, 4, "LOCK_TYPE", "10", "10");
        insertRule(jdbc, 5, "CONNECTIVITY", "W", "Wifi");
        insertRule(jdbc, 6, "SALES_CHANNEL", "P", "平面");
        insertRule(jdbc, 7, "OPERATING_ENTITY", "S", "深圳");
        insertRule(jdbc, 8, "LANGUAGE", "C", "中文版");
        service = new ProductImportValidationService(new ProductImportCodeResolver(jdbc, new ProductCodeGenerator(jdbc)));
    }

    @Test
    void ignoresSourceCodeAndGeneratesFromCurrentRules() {
        ParsedImportRow result = service.validate(3, "GMT库存产品清单", 9,
                row("错误的原表编号", "GMT", "T7", "瀑布银", "10", "W_Wifi", "平面", "深圳", "中文版"));

        assertThat(result.status()).isEqualTo(ImportRowStatus.VALID);
        assertThat(result.data()).containsEntry("sourceProductCode", "错误的原表编号")
                .containsEntry("productCode", "G_T7PBY10WPSC");
        assertThat(String.valueOf(result.data().get("_ruleFingerprint"))).isNotBlank();
    }

    @Test
    void marksGeneratedDuplicatesAsUnresolvedConflicts() {
        List<ParsedImportRow> rows = service.validateAll(List.of(
                parsed(9, row("参考编号1", "G", "T7", "PBY", "10", "Wifi", "P", "S", "C")),
                parsed(10, row("参考编号2", "GMT", "T7", "瀑布银", "10", "W", "平面", "深圳", "中文版"))));

        assertThat(rows).allSatisfy(row -> assertThat(row.data())
                .containsEntry("_conflict", true)
                .containsEntry("_conflictAction", "UNRESOLVED")
                .containsEntry("_conflictGroup", "G_T7PBY10WPSC"));
    }

    @Test
    void reportsUnknownRuleValuesAsRowErrors() {
        ParsedImportRow result = service.validate(3, "GMT库存产品清单", 9,
                row("参考编号", "不存在品牌", "T7", "瀑布银", "10", "Wifi", "平面", "深圳", "中文版"));

        assertThat(result.status()).isEqualTo(ImportRowStatus.ERROR);
        assertThat(result.errorMessage()).contains("品牌").contains("不存在品牌");
    }

    private ParsedImportRow parsed(int rowNumber, Map<String, Object> data) {
        return new ParsedImportRow("GMT库存产品清单", rowNumber, ImportRowStatus.VALID, data, null);
    }

    private Map<String, Object> row(String sourceProductCode, String brand, String series, String bodyColor,
                                    String lockType, String connectivity, String salesChannel,
                                    String operatingEntity, String language) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sourceProductCode", sourceProductCode);
        row.put("brand", brand);
        row.put("series", series);
        row.put("bodyColor", bodyColor);
        row.put("lockType", lockType);
        row.put("connectivity", connectivity);
        row.put("salesChannel", salesChannel);
        row.put("operatingEntity", operatingEntity);
        row.put("language", language);
        return row;
    }

    private void insertRule(JdbcTemplate jdbc, long id, String category, String code, String displayName) {
        jdbc.update("INSERT INTO product_code_rule(id,category,code,display_name,enabled) VALUES(?,?,?,?,TRUE)",
                id, category, code, displayName);
    }
}
