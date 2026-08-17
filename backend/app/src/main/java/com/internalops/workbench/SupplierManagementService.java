package com.internalops.workbench;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.util.ArrayList;
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
                        INSERT INTO supplier(
                            supplier_code,supplier_name,manufacturer_category,manufacturer_type,supplier_location,
                            product_attribute,short_name,contact_name,contact_title,phone,address,currency,
                            tax_registration_no,bank_address,bank_account,enabled)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,TRUE)
                        """,
                generatedCode(), trim(request.supplierName()), trimmedOrNull(request.manufacturerCategory()),
                trimmedOrNull(request.manufacturerType()), trimmedOrNull(request.supplierLocation()),
                trimmedOrNull(request.productAttribute()), trimmedOrNull(request.shortName()),
                trimmedOrNull(request.contactName()), trimmedOrNull(request.contactTitle()),
                preservedOrNull(request.phone()), trimmedOrNull(request.address()), trimmedOrNull(request.currency()),
                preservedOrNull(request.taxRegistrationNo()), trimmedOrNull(request.bankAddress()),
                preservedOrNull(request.bankAccount()));
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
                        SET supplier_name=?,manufacturer_category=?,manufacturer_type=?,supplier_location=?,
                            product_attribute=?,short_name=?,contact_name=?,contact_title=?,phone=?,address=?,currency=?,
                            tax_registration_no=?,bank_address=?,bank_account=?,version=version+1
                        WHERE id=? AND version=?
                        """,
                trim(request.supplierName()), trimmedOrNull(request.manufacturerCategory()),
                trimmedOrNull(request.manufacturerType()), trimmedOrNull(request.supplierLocation()),
                trimmedOrNull(request.productAttribute()), trimmedOrNull(request.shortName()),
                trimmedOrNull(request.contactName()), trimmedOrNull(request.contactTitle()),
                preservedOrNull(request.phone()), trimmedOrNull(request.address()), trimmedOrNull(request.currency()),
                preservedOrNull(request.taxRegistrationNo()), trimmedOrNull(request.bankAddress()),
                preservedOrNull(request.bankAccount()), id, request.version());
        if (changed == 0) {
            throw new IllegalStateException("供应商资料已被其他操作修改，请重新打开后再试");
        }
        replaceProducts(id, request.products());
        return queries.supplierDetail(id);
    }

    private void replaceProducts(long supplierId, List<SupplierProductConfigRequest> products) {
        jdbc.update("UPDATE sku_supplier_config SET enabled=FALSE,version=version+1 WHERE supplier_id=? AND enabled=TRUE", supplierId);
        if (products == null || products.isEmpty()) return;
        for (SupplierProductConfigRequest config : products) {
            List<SupplierPurchaseInfoRequest> infos = config.effectivePurchaseInfos();
            if (infos.isEmpty()) {
                infos = List.of(new SupplierPurchaseInfoRequest(null, null, null, null, null));
            }
            SupplierPurchaseInfoRequest latest = infos.get(0);
            int changed = jdbc.update("""
                    UPDATE sku_supplier_config
                    SET purchase_price=?,moq=?,lead_time_days=?,enabled=TRUE,version=version+1
                    WHERE sku_id=? AND supplier_id=?
                    """, price(latest), latest.moq(), latest.leadTimeDays(), config.skuId(), supplierId);
            if (changed == 0) {
                jdbc.update("""
                        INSERT INTO sku_supplier_config(sku_id,supplier_id,purchase_price,moq,lead_time_days,enabled)
                        VALUES(?,?,?,?,?,TRUE)
                        """, config.skuId(), supplierId, price(latest), latest.moq(), latest.leadTimeDays());
            }
            Long relationId = jdbc.queryForObject(
                    "SELECT id FROM sku_supplier_config WHERE sku_id=? AND supplier_id=?", Long.class,
                    config.skuId(), supplierId);
            replacePurchaseInfos(relationId, infos);
        }
    }

    private void replacePurchaseInfos(long relationId, List<SupplierPurchaseInfoRequest> infos) {
        Set<Long> retained = new HashSet<>();
        for (SupplierPurchaseInfoRequest info : infos) {
            if (info.id() == null) {
                long newId = insert("""
                        INSERT INTO sku_supplier_purchase_info(
                            supplier_product_config_id,purchase_price,moq,lead_time_days,enabled)
                        VALUES(?,?,?,?,TRUE)
                        """, relationId, price(info), info.moq(), info.leadTimeDays());
                retained.add(newId);
            } else {
                if (!retained.add(info.id())) throw new IllegalArgumentException("同一采购信息不能重复维护");
                if (info.version() == null) throw new IllegalArgumentException("缺少采购信息版本");
                int changed = jdbc.update("""
                        UPDATE sku_supplier_purchase_info
                        SET purchase_price=?,moq=?,lead_time_days=?,enabled=TRUE,version=version+1
                        WHERE id=? AND supplier_product_config_id=? AND version=?
                        """, price(info), info.moq(), info.leadTimeDays(), info.id(), relationId, info.version());
                if (changed == 0) throw new IllegalStateException("采购信息已被其他操作修改，请重新打开后再试");
            }
        }
        if (!retained.isEmpty()) {
            String placeholders = String.join(",", retained.stream().map(id -> "?").toList());
            List<Object> args = new ArrayList<>();
            args.add(relationId);
            args.addAll(retained);
            jdbc.update("UPDATE sku_supplier_purchase_info SET enabled=FALSE WHERE supplier_product_config_id=? AND id NOT IN (" + placeholders + ")", args.toArray());
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
            List<SupplierPurchaseInfoRequest> infos = config.effectivePurchaseInfos();
            Set<Long> infoIds = new HashSet<>();
            for (SupplierPurchaseInfoRequest info : infos) {
                if (info == null) throw new IllegalArgumentException("采购信息不能为空");
                if (info.purchasePrice() != null && (info.purchasePrice().signum() < 0 || info.purchasePrice().scale() > 4)) {
                    throw new IllegalArgumentException("采购单价必须是最多四位小数的非负数");
                }
                if (info.moq() != null && info.moq() <= 0) throw new IllegalArgumentException("最小起订量必须大于零");
                if (info.leadTimeDays() != null && info.leadTimeDays() < 0) throw new IllegalArgumentException("交货天数不能为负数");
                if (info.id() != null && !infoIds.add(info.id())) throw new IllegalArgumentException("同一采购信息不能重复维护");
            }        }
    }

    private BigDecimal price(SupplierPurchaseInfoRequest info) {
        return info.purchasePrice() == null ? null : info.purchasePrice().setScale(4, RoundingMode.UNNECESSARY);
    }

    private String trim(String value) {
        return value.trim();
    }

    private String trimmedOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String preservedOrNull(String value) {
        return value == null || value.isEmpty() ? null : value;
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
