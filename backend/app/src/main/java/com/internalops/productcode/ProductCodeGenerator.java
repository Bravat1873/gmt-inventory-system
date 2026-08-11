package com.internalops.productcode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ProductCodeGenerator {
    private final JdbcTemplate jdbc;
    public ProductCodeGenerator(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public String generate(ProductCodeSelection selection) {
        if (selection == null || !selection.complete()) throw new IllegalArgumentException("请补齐产品编码信息");
        String brand = code(selection.brandRuleId(), ProductCodeCategory.BRAND);
        String series = code(selection.seriesRuleId(), ProductCodeCategory.SERIES);
        String color = code(selection.bodyColorRuleId(), ProductCodeCategory.BODY_COLOR);
        String lock = code(selection.lockTypeRuleId(), ProductCodeCategory.LOCK_TYPE);
        String connectivity = code(selection.connectivityRuleId(), ProductCodeCategory.CONNECTIVITY);
        String channel = code(selection.salesChannelRuleId(), ProductCodeCategory.SALES_CHANNEL);
        String entity = code(selection.operatingEntityRuleId(), ProductCodeCategory.OPERATING_ENTITY);
        String language = code(selection.languageRuleId(), ProductCodeCategory.LANGUAGE);
        return brand + "_" + series + color + lock + connectivity + channel + entity + language;
    }

    public String generateEntryDoor(EntryDoorProductCodeSelection selection) {
        if (selection == null || !selection.complete()) throw new IllegalArgumentException("请补齐入户门产品编号信息");
        String brand = code(selection.brandRuleId(), ProductCodeCategory.BRAND);
        String model = code(selection.doorModelRuleId(), ProductCodeCategory.DOOR_MODEL);
        String security = code(selection.securityGradeRuleId(), ProductCodeCategory.SECURITY_GRADE);
        String material = code(selection.baseMaterialRuleId(), ProductCodeCategory.BASE_MATERIAL);
        String thickness = code(selection.thicknessRuleId(), ProductCodeCategory.THICKNESS);
        String finish = code(selection.finishColorRuleId(), ProductCodeCategory.FINISH_COLOR);
        return brand + "_" + model + security + material + thickness + finish;
    }
    private String code(long id, ProductCodeCategory expected) {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT category,code,enabled FROM product_code_rule WHERE id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("产品编码映射不存在");
        Map<String,Object> row = rows.get(0);
        if (!expected.name().equals(String.valueOf(row.get("CATEGORY"))) || !Boolean.TRUE.equals(row.get("ENABLED")))
            throw new IllegalArgumentException("产品编码映射无效或已停用：" + expected.name());
        return String.valueOf(row.get("CODE")).trim().toUpperCase(Locale.ROOT);
    }
}
