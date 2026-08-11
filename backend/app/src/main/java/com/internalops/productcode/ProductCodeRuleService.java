package com.internalops.productcode;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ProductCodeRuleService {
    private final JdbcTemplate jdbc;

    public ProductCodeRuleService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String,Object>> list(ProductCodeCategory category, boolean includeDisabled) {
        String sql = "SELECT id,category,code,display_name AS displayName,enabled,sort_order AS sortOrder,remark,version," +
                " EXISTS(SELECT 1 FROM sku s WHERE s.brand_rule_id=r.id OR s.series_rule_id=r.id OR s.body_color_rule_id=r.id" +
                " OR s.lock_type_rule_id=r.id OR s.connectivity_rule_id=r.id OR s.sales_channel_rule_id=r.id" +
                " OR s.operating_entity_rule_id=r.id OR s.language_rule_id=r.id) AS referenced" +
                " FROM product_code_rule r WHERE category=?" + (includeDisabled ? "" : " AND enabled=TRUE") +
                " ORDER BY sort_order,id";
        return jdbc.queryForList(sql, category.name());
    }

    @Transactional
    public Map<String,Object> create(ProductCodeRuleRequest request) {
        Values v = values(request);
        try {
            jdbc.update("INSERT INTO product_code_rule(category,code,display_name,enabled,sort_order,remark) VALUES(?,?,?,?,?,?)",
                    v.category.name(), v.code, v.name, v.enabled, v.sortOrder, v.remark);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("同一分类中的编码不能重复");
        }
        Long id = jdbc.queryForObject("SELECT id FROM product_code_rule WHERE category=? AND code=?", Long.class,
                v.category.name(), v.code);
        return find(id);
    }

    @Transactional
    public Map<String,Object> update(long id, ProductCodeRuleRequest request) {
        Values v = values(request);
        Map<String,Object> old = find(id);
        if (!String.valueOf(old.get("category")).equals(v.category.name()))
            throw new IllegalStateException("已创建的规则不能更换分类");
        if (referenced(id) && !String.valueOf(old.get("code")).equals(v.code))
            throw new IllegalStateException("该编码已被产品使用，不能修改编码");
        int version = request.version() == null ? ((Number) old.get("version")).intValue() : request.version();
        try {
            int changed = jdbc.update("UPDATE product_code_rule SET code=?,display_name=?,enabled=?,sort_order=?,remark=?,version=version+1 WHERE id=? AND version=?",
                    v.code, v.name, v.enabled, v.sortOrder, v.remark, id, version);
            if (changed == 0) throw new IllegalStateException("规则已被其他用户修改，请刷新后重试");
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("同一分类中的编码不能重复");
        }
        return find(id);
    }

    @Transactional
    public void delete(long id) {
        find(id);
        if (referenced(id)) throw new IllegalStateException("该编码已被产品使用，只能停用，不能删除");
        jdbc.update("DELETE FROM product_code_rule WHERE id=?", id);
    }

    private Map<String,Object> find(long id) {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT id,category,code,display_name AS displayName,enabled,sort_order AS sortOrder,remark,version FROM product_code_rule WHERE id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("编码规则不存在");
        Map<String,Object> row = rows.get(0);
        row.put("referenced", referenced(id));
        return row;
    }

    private boolean referenced(long id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sku WHERE brand_rule_id=? OR series_rule_id=? OR body_color_rule_id=? OR lock_type_rule_id=? OR connectivity_rule_id=? OR sales_channel_rule_id=? OR operating_entity_rule_id=? OR language_rule_id=?",
                Integer.class, id,id,id,id,id,id,id,id);
        return count != null && count > 0;
    }

    private Values values(ProductCodeRuleRequest request) {
        if (request == null || request.category() == null) throw new IllegalArgumentException("请选择编码分类");
        String name = request.displayName() == null ? "" : request.displayName().trim();
        String code = request.code() == null ? "" : request.code().trim().toUpperCase(Locale.ROOT);
        if (name.isEmpty()) throw new IllegalArgumentException("请填写显示名称");
        if (!code.matches("^[A-Z0-9]+$")) throw new IllegalArgumentException("编码只能包含英文字母和数字");
        return new Values(request.category(), name, code, request.enabled() == null || request.enabled(),
                request.sortOrder() == null ? 0 : request.sortOrder(), request.remark());
    }

    private record Values(ProductCodeCategory category, String name, String code, boolean enabled, int sortOrder, String remark) {}
}
