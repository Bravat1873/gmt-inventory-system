package com.internalops.workbench;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class SupplierManagementService {
    private final JdbcTemplate jdbc;
    private final WorkbenchQueryService queries;

    public SupplierManagementService(JdbcTemplate jdbc, WorkbenchQueryService queries) {
        this.jdbc = jdbc;
        this.queries = queries;
    }

    @Transactional
    public Map<String, Object> create(SupplierCommandRequest request) {
        validate(request);
        long id = insert("""
                        INSERT INTO supplier(supplier_code,supplier_name,contact_name,phone,bank_account,enabled)
                        VALUES(?,?,?,?,?,TRUE)
                        """,
                generatedCode(), trim(request.supplierName()), emptyToNull(request.contactName()),
                emptyToNull(request.phone()), emptyToNull(request.bankAccount()));
        replaceProducts(id, request.products());
        return queries.supplierDetail(id);
    }

    @Transactional
    public Map<String, Object> update(long id, SupplierCommandRequest request) {
        validate(request);
        if (request.version() == null) {
            throw new IllegalArgumentException("缺少数据版本，请重新打开后再试");
        }
        int changed = jdbc.update("""
                        UPDATE supplier
                        SET supplier_name=?,contact_name=?,phone=?,bank_account=?,version=version+1
                        WHERE id=? AND version=?
                        """,
                trim(request.supplierName()), emptyToNull(request.contactName()), emptyToNull(request.phone()),
                emptyToNull(request.bankAccount()), id, request.version());
        if (changed == 0) {
            throw new IllegalStateException("供应商资料已被其他操作修改，请重新打开后再试");
        }
        replaceProducts(id, request.products());
        return queries.supplierDetail(id);
    }

    private void replaceProducts(long supplierId, List<SupplierProductConfigRequest> products) {
        jdbc.update("UPDATE sku_supplier_config SET enabled=FALSE,version=version+1 WHERE supplier_id=? AND enabled=TRUE", supplierId);
        if (products == null || products.isEmpty()) {
            return;
        }
        for (SupplierProductConfigRequest config : products) {
            int changed = jdbc.update("""
                            UPDATE sku_supplier_config
                            SET supplier_id=?,purchase_price=?,moq=?,lead_time_days=?,enabled=TRUE,version=version+1
                            WHERE sku_id=?
                            """,
                    supplierId, price(config), config.moq(), config.leadTimeDays(), config.skuId());
            if (changed == 0) {
                jdbc.update("""
                                INSERT INTO sku_supplier_config(sku_id,supplier_id,purchase_price,moq,lead_time_days,enabled)
                                VALUES(?,?,?,?,?,TRUE)
                                """,
                        config.skuId(), supplierId, price(config), config.moq(), config.leadTimeDays());
            }
        }
    }

    private void validate(SupplierCommandRequest request) {
        if (request == null || request.supplierName() == null || request.supplierName().isBlank()) {
            throw new IllegalArgumentException("供应商名称不能为空");
        }
        List<SupplierProductConfigRequest> products = request.products() == null ? List.of() : request.products();
        Set<Long> skuIds = new HashSet<>();
        for (SupplierProductConfigRequest config : products) {
            if (config == null || config.skuId() == null || config.skuId() <= 0) {
                throw new IllegalArgumentException("请选择供应产品");
            }
            if (!skuIds.add(config.skuId())) {
                throw new IllegalArgumentException("同一产品不能重复维护");
            }
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM sku WHERE id=? AND enabled=TRUE", Integer.class, config.skuId());
            if (exists == null || exists == 0) {
                throw new IllegalArgumentException("供应产品不存在或已停用");
            }
            if (config.purchasePrice() == null || config.purchasePrice().signum() < 0 || config.purchasePrice().scale() > 4) {
                throw new IllegalArgumentException("采购单价必须是最多四位小数的非负数");
            }
            if (config.moq() == null || config.moq() <= 0) {
                throw new IllegalArgumentException("最小起订量必须大于零");
            }
            if (config.leadTimeDays() == null || config.leadTimeDays() < 0) {
                throw new IllegalArgumentException("交货天数不能为负数");
            }
        }
    }

    private BigDecimal price(SupplierProductConfigRequest config) {
        return config.purchasePrice().setScale(4, RoundingMode.UNNECESSARY);
    }

    private String trim(String value) {
        return value.trim();
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String generatedCode() {
        return "SUP" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private long insert(String sql, Object... parameters) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            return statement;
        }, holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }
}
