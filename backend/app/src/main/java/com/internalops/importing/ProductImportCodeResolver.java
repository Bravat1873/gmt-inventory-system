package com.internalops.importing;

import com.internalops.productcode.ProductCodeCategory;
import com.internalops.productcode.ProductCodeGenerator;
import com.internalops.productcode.ProductCodeSelection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves workbook labels against the currently enabled product-code rules. */
@Service
public class ProductImportCodeResolver {
    private static final Pattern CODE_IN_PARENS = Pattern.compile("^(.*)\\(([^()]+)\\)$");

    private final JdbcTemplate jdbc;
    private final ProductCodeGenerator generator;

    public ProductImportCodeResolver(JdbcTemplate jdbc, ProductCodeGenerator generator) {
        this.jdbc = jdbc;
        this.generator = generator;
    }

    public ResolvedProductCode resolve(Map<String, Object> data) {
        ProductCodeSelection selection = new ProductCodeSelection(
                ruleId(ProductCodeCategory.BRAND, "品牌", text(data, "brand")),
                ruleId(ProductCodeCategory.SERIES, "系列", text(data, "series")),
                ruleId(ProductCodeCategory.BODY_COLOR, "物料颜色", text(data, "bodyColor")),
                ruleId(ProductCodeCategory.LOCK_TYPE, "锁体类型", text(data, "lockType")),
                ruleId(ProductCodeCategory.CONNECTIVITY, "联网方式", text(data, "connectivity")),
                ruleId(ProductCodeCategory.SALES_CHANNEL, "销售渠道", text(data, "salesChannel")),
                ruleId(ProductCodeCategory.OPERATING_ENTITY, "运营主体", text(data, "operatingEntity")),
                ruleId(ProductCodeCategory.LANGUAGE, "语言", text(data, "language")));
        String productCode = generator.appendSuffix(generator.generate(selection), text(data, "codeSuffix"));
        return new ResolvedProductCode(productCode, selection, fingerprint(selection));
    }

    private long ruleId(ProductCodeCategory category, String label, String value) {
        if (value.isBlank()) throw new IllegalArgumentException(label + "不能为空，无法生成产品编号");
        List<Map<String, Object>> matches = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT id,code,display_name AS displayName FROM product_code_rule WHERE category=? AND enabled=TRUE",
                category.name())) {
            if (matches(value, text(row, "code"), text(row, "displayName"))) matches.add(row);
        }
        if (matches.isEmpty()) throw new IllegalArgumentException(label + "“" + value + "”未匹配当前启用的编码规则");
        if (matches.size() > 1) throw new IllegalArgumentException(label + "“" + value + "”匹配到多个当前启用的编码规则");
        return ((Number) value(matches.get(0), "id")).longValue();
    }

    private boolean matches(String source, String code, String displayName) {
        String normalizedSource = normalize(source);
        String normalizedCode = normalize(code);
        String normalizedDisplayName = normalize(displayName);
        if (normalizedSource.equals(normalizedCode) || normalizedSource.equals(normalizedDisplayName)
                || normalizedSource.equals(normalize(code + "_" + displayName))) return true;
        Matcher matcher = CODE_IN_PARENS.matcher(source.replace('（', '(').replace('）', ')').trim());
        return matcher.matches() && ((normalize(matcher.group(1)).equals(normalizedDisplayName)
                && normalize(matcher.group(2)).equals(normalizedCode))
                || (normalize(matcher.group(1)).equals(normalizedCode)
                && normalize(matcher.group(2)).equals(normalizedDisplayName)));
    }

    private String fingerprint(ProductCodeSelection selection) {
        return "BRAND:" + selection.brandRuleId()
                + "|SERIES:" + selection.seriesRuleId()
                + "|BODY_COLOR:" + selection.bodyColorRuleId()
                + "|LOCK_TYPE:" + selection.lockTypeRuleId()
                + "|CONNECTIVITY:" + selection.connectivityRuleId()
                + "|SALES_CHANNEL:" + selection.salesChannelRuleId()
                + "|OPERATING_ENTITY:" + selection.operatingEntityRuleId()
                + "|LANGUAGE:" + selection.languageRuleId();
    }

    private String text(Map<String, Object> row, String key) {
        Object value = value(row, key);
        return value == null ? "" : value.toString().trim();
    }

    private Object value(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
        }
        return null;
    }

    private String normalize(String value) {
        return value.replace('（', '(').replace('）', ')').replaceAll("[\\s_]+", "")
                .toLowerCase(Locale.ROOT);
    }

    public record ResolvedProductCode(String productCode, ProductCodeSelection selection, String ruleFingerprint) {
    }
}
