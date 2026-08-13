package com.internalops.workbench;

import com.internalops.auth.CurrentUser;
import com.internalops.auth.UserRole;
import com.internalops.productcode.ProductCodeGenerator;
import com.internalops.productcode.EntryDoorProductCodeSelection;
import com.internalops.productcode.ProductCodeSelection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Service
public class MasterDataCommandService {
    private final JdbcTemplate jdbc;
    private final ProductCodeGenerator productCodeGenerator;

    public MasterDataCommandService(JdbcTemplate jdbc, ProductCodeGenerator productCodeGenerator) {
        this.jdbc = jdbc;
        this.productCodeGenerator = productCodeGenerator;
    }

    @Transactional
    public Map<String, Object> create(String module, EntityCommandRequest request) {
        return create(module, request, EntityCommandFields.inferred(request));
    }

    @Transactional
    public Map<String, Object> create(String module, EntityCommandRequest request, EntityCommandFields fields) {
        return switch (module) {
            case "customer" -> createCustomer(request);
            case "user" -> createUser(request, fields);
            case "product" -> createProduct(request, fields);
            case "supplier" -> createSupplier(request, fields);
            case "inventory" -> createInventory(request, fields);
            default -> throw new IllegalArgumentException("该模块不支持手工新增");
        };
    }

    @Transactional
    public Map<String, Object> update(String module, long id, EntityCommandRequest request) {
        return update(module, id, request, EntityCommandFields.inferred(request));
    }

    @Transactional
    public Map<String, Object> update(String module, long id, EntityCommandRequest request, EntityCommandFields fields) {
        requireVersion(request);
        return switch (module) {
            case "customer" -> updateCustomer(id, request);
            case "user" -> updateUser(id, request, fields);
            case "product" -> updateProduct(id, request, fields);
            case "supplier" -> updateSupplier(id, request, fields);
            case "inventory" -> updateInventory(id, request, fields);
            default -> throw new IllegalArgumentException("该模块不支持手工修改");
        };
    }

    private Map<String, Object> createCustomer(EntityCommandRequest r) {
        requireText(r.customerName(), "客户名称不能为空");
        String code = textOr(r.customerCode(), generatedCode("C"));
        long id = insert("INSERT INTO customer(customer_code,customer_name,contact_name,phone,address,business_contact_name,business_contact_phone,order_contact_name,order_contact_phone,finance_contact_name,finance_contact_phone,invoice_title,taxpayer_id,invoice_address,invoice_phone,bank_name,bank_account,enabled) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                code, r.customerName().trim(), businessName(r), businessPhone(r), r.address(), businessName(r), businessPhone(r),
                r.orderContactName(), r.orderContactPhone(), r.financeContactName(), r.financeContactPhone(), r.invoiceTitle(),
                r.taxpayerId(), r.invoiceAddress(), r.invoicePhone(), r.bankName(), r.bankAccount(), enabled(r));
        return customer(id);
    }

    private Map<String, Object> updateCustomer(long id, EntityCommandRequest r) {
        requireText(r.customerName(), "客户名称不能为空");
        int changed = jdbc.update("UPDATE customer SET customer_name=?,contact_name=?,phone=?,address=?,business_contact_name=?,business_contact_phone=?,order_contact_name=?,order_contact_phone=?,finance_contact_name=?,finance_contact_phone=?,invoice_title=?,taxpayer_id=?,invoice_address=?,invoice_phone=?,bank_name=?,bank_account=?,enabled=?,version=version+1 WHERE id=? AND version=?",
                r.customerName().trim(), businessName(r), businessPhone(r), r.address(), businessName(r), businessPhone(r),
                r.orderContactName(), r.orderContactPhone(), r.financeContactName(), r.financeContactPhone(), r.invoiceTitle(),
                r.taxpayerId(), r.invoiceAddress(), r.invoicePhone(), r.bankName(), r.bankAccount(), enabled(r), id, r.version());
        conflictIfUnchanged(changed);
        return customer(id);
    }

    private Map<String, Object> createSupplier(EntityCommandRequest r, EntityCommandFields fields) {
        requireText(fields.supplierText("supplierName"), "供应商名称不能为空");
        String code = textOr(fields.supplierText("supplierCode"), generatedCode("SUP"));
        long id = insert("""
                        INSERT INTO supplier(
                            supplier_code,supplier_name,contact_name,phone,bank_account,
                            manufacturer_category,manufacturer_type,supplier_location,product_attribute,
                            short_name,contact_title,address,currency,tax_registration_no,bank_address,enabled)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """,
                code, fields.supplierText("supplierName").trim(), r.contactName(), r.phone(), fields.supplierText("bankAccount"),
                fields.supplierText("manufacturerCategory"), fields.supplierText("manufacturerType"),
                fields.supplierText("supplierLocation"), fields.supplierText("productAttribute"),
                fields.supplierText("shortName"), fields.supplierText("contactTitle"),
                fields.supplierText("address"), fields.supplierText("currency"),
                fields.supplierText("taxRegistrationNo"), fields.supplierText("bankAddress"), enabled(r));
        return supplier(id);
    }

    private Map<String, Object> updateSupplier(long id, EntityCommandRequest r, EntityCommandFields fields) {
        requireText(fields.supplierText("supplierName"), "供应商名称不能为空");
        int changed = jdbc.update("""
                        UPDATE supplier
                        SET supplier_name=?,contact_name=?,phone=?,bank_account=?,
                            manufacturer_category=?,manufacturer_type=?,supplier_location=?,product_attribute=?,
                            short_name=?,contact_title=?,address=?,currency=?,tax_registration_no=?,bank_address=?,
                            enabled=?,version=version+1
                        WHERE id=? AND version=?
                        """,
                fields.supplierText("supplierName").trim(), r.contactName(), r.phone(), fields.supplierText("bankAccount"),
                fields.supplierText("manufacturerCategory"), fields.supplierText("manufacturerType"),
                fields.supplierText("supplierLocation"), fields.supplierText("productAttribute"),
                fields.supplierText("shortName"), fields.supplierText("contactTitle"),
                fields.supplierText("address"), fields.supplierText("currency"),
                fields.supplierText("taxRegistrationNo"), fields.supplierText("bankAddress"),
                enabled(r), id, r.version());
        conflictIfUnchanged(changed);
        return supplier(id);
    }

    private Map<String, Object> createUser(EntityCommandRequest r, EntityCommandFields fields) {
        requireUserManagementPermission();
        requireText(r.username(), "用户名不能为空");
        requireText(r.displayName(), "姓名不能为空");
        if (!fields.passwordPresent() || r.password() == null || r.password().trim().isEmpty()) {
            throw new IllegalArgumentException("初始密码不能为空");
        }
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username=?", Integer.class, r.username().trim());
        if (existing != null && existing > 0) throw new IllegalArgumentException("用户名已存在，请换一个");
        UserRole role = fields.rolePresent() ? requestedRole(r) : UserRole.USER;
        long id = insert("INSERT INTO sys_user(username,password_hash,display_name,phone,enabled,role) VALUES(?,?,?,?,?,?)",
                r.username().trim(), r.password().trim(), r.displayName().trim(), r.phone(), enabled(r), role.name());
        return user(id);
    }

    private Map<String, Object> updateUser(long id, EntityCommandRequest r, EntityCommandFields fields) {
        requireUserManagementPermission();
        requireText(r.displayName(), "姓名不能为空");
        UserRole role = fields.rolePresent() ? requestedRole(r) : existingRole(id);
        boolean updatePassword = fields.passwordPresent() && r.password() != null && !r.password().trim().isEmpty();
        int changed = jdbc.update("UPDATE sys_user SET display_name=?,phone=?,enabled=?,role=?,password_hash=CASE WHEN ? THEN ? ELSE password_hash END,version=version+1 WHERE id=? AND version=?",
                r.displayName().trim(), r.phone(), enabled(r), role.name(), updatePassword,
                updatePassword ? r.password().trim() : null, id, r.version());
        conflictIfUnchanged(changed);
        return user(id);
    }

    private Map<String, Object> createProduct(EntityCommandRequest r, EntityCommandFields fields) {
        validateProduct(r);
        requireProductPricePermission(fields);
        String eanCode = validateEan(r.eanCode(), null);
        String productType = requiredProductType(r.productType());
        String materialType = requiredMaterialType(r.materialType());
        ProductCodeSelection smart = selection(r);
        EntryDoorProductCodeSelection door = doorSelection(r);
        String codeSuffix = normalizeSuffix(r.codeSuffix());
        String productCode = productCodeGenerator.appendSuffix(generateProductCode(productType, smart, door), codeSuffix);
        String customerCode = r.customerCode() != null ? r.customerCode() : r.skuCode();
        String materialSpecification = materialSpecification(r.brandRuleId(), r.model(), r.bodyColorRuleId(), r.lockTypeRuleId(), r.languageRuleId());
        try {
            long id = insert("INSERT INTO sku(product_code,code_suffix,ean_code,product_type,material_type,sku_code,model,product_name,color,lock_body,product_version,configuration,product_configuration,unit,current_cost,factory_price,product_remark,enabled,brand_rule_id,series_rule_id,body_color_rule_id,lock_type_rule_id,connectivity_rule_id,sales_channel_rule_id,operating_entity_rule_id,language_rule_id,door_model_rule_id,security_grade_rule_id,base_material_rule_id,thickness_rule_id,finish_color_rule_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    productCode, codeSuffix, eanCode, productType, materialType, customerCode, r.model(), r.productName().trim(), r.color(), r.lockBody(), r.productVersion(), materialSpecification, r.productConfiguration(),
                    textOr(r.unit(), "件"), r.currentCost(), r.factoryPrice(), r.remark(), enabled(r),
                    r.brandRuleId(), r.seriesRuleId(), r.bodyColorRuleId(), r.lockTypeRuleId(), r.connectivityRuleId(),
                    r.salesChannelRuleId(), r.operatingEntityRuleId(), r.languageRuleId(), r.doorModelRuleId(), r.securityGradeRuleId(),
                    r.baseMaterialRuleId(), r.thicknessRuleId(), r.finishColorRuleId());
            jdbc.update("UPDATE sku SET sales_minimum_order_quantity=? WHERE id=?", salesMinimumOrderQuantity(r), id);
            saveSupplierConfig(id, r);
            return product(id);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("产品编号已存在：" + productCode);
        }
    }

    private Map<String, Object> updateProduct(long id, EntityCommandRequest r, EntityCommandFields fields) {
        validateProduct(r);
        requireProductPricePermission(fields);
        Map<String,Object> current = jdbc.queryForMap("SELECT product_code,code_suffix,ean_code,product_type,material_type,model,brand_rule_id,series_rule_id,body_color_rule_id,lock_type_rule_id,connectivity_rule_id,sales_channel_rule_id,operating_entity_rule_id,language_rule_id,door_model_rule_id,security_grade_rule_id,base_material_rule_id,thickness_rule_id,finish_color_rule_id FROM sku WHERE id=?", id);
        String productType = r.productType() == null ? String.valueOf(current.get("product_type")) : requiredProductType(r.productType());
        String materialType = r.materialType() == null ? String.valueOf(current.get("material_type")) : requiredMaterialType(r.materialType());
        ProductCodeSelection smart = new ProductCodeSelection(
                chosen(r.brandRuleId(), current.get("brand_rule_id")), chosen(r.seriesRuleId(), current.get("series_rule_id")),
                chosen(r.bodyColorRuleId(), current.get("body_color_rule_id")), chosen(r.lockTypeRuleId(), current.get("lock_type_rule_id")),
                chosen(r.connectivityRuleId(), current.get("connectivity_rule_id")), chosen(r.salesChannelRuleId(), current.get("sales_channel_rule_id")),
                chosen(r.operatingEntityRuleId(), current.get("operating_entity_rule_id")), chosen(r.languageRuleId(), current.get("language_rule_id")));
        EntryDoorProductCodeSelection door = new EntryDoorProductCodeSelection(
                chosen(r.brandRuleId(), current.get("brand_rule_id")), chosen(r.doorModelRuleId(), current.get("door_model_rule_id")),
                chosen(r.securityGradeRuleId(), current.get("security_grade_rule_id")), chosen(r.baseMaterialRuleId(), current.get("base_material_rule_id")),
                chosen(r.thicknessRuleId(), current.get("thickness_rule_id")), chosen(r.finishColorRuleId(), current.get("finish_color_rule_id")));
        String baseProductCode = "UNCLASSIFIED".equals(productType) ? baseCode(String.valueOf(current.get("product_code")), current.get("code_suffix")) : generateProductCode(productType, smart, door);
        String codeSuffix = r.codeSuffix() == null ? normalizeSuffix((String) current.get("code_suffix")) : normalizeSuffix(r.codeSuffix());
        String productCode = productCodeGenerator.appendSuffix(baseProductCode, codeSuffix);
        String eanCode = r.eanCode() == null ? (String) current.get("ean_code") : validateEan(r.eanCode(), id);
        String customerCode = r.customerCode() != null ? r.customerCode() : r.skuCode();
        String model = r.model() != null ? r.model() : (String) current.get("model");
        String materialSpecification = materialSpecification(smart.brandRuleId(), model, smart.bodyColorRuleId(), smart.lockTypeRuleId(), smart.languageRuleId());
        try {
            int changed = jdbc.update("UPDATE sku SET product_code=?,product_type=?,material_type=?,sku_code=?,model=?,product_name=?,color=?,lock_body=?,product_version=?,configuration=?,product_configuration=COALESCE(?,product_configuration),unit=?,current_cost=CASE WHEN ? THEN ? ELSE current_cost END,factory_price=CASE WHEN ? THEN ? ELSE factory_price END,product_remark=?,enabled=?,brand_rule_id=?,series_rule_id=?,body_color_rule_id=?,lock_type_rule_id=?,connectivity_rule_id=?,sales_channel_rule_id=?,operating_entity_rule_id=?,language_rule_id=?,door_model_rule_id=?,security_grade_rule_id=?,base_material_rule_id=?,thickness_rule_id=?,finish_color_rule_id=?,code_suffix=?,ean_code=?,version=version+1 WHERE id=? AND version=?",
                    productCode, productType, materialType, customerCode, model, r.productName().trim(), r.color(), r.lockBody(), r.productVersion(), materialSpecification, r.productConfiguration(), textOr(r.unit(), "件"),
                    fields.currentCostPresent(), r.currentCost(), fields.factoryPricePresent(), r.factoryPrice(), r.remark(), enabled(r),
                    smart.brandRuleId(), smart.seriesRuleId(), smart.bodyColorRuleId(), smart.lockTypeRuleId(), smart.connectivityRuleId(),
                    smart.salesChannelRuleId(), smart.operatingEntityRuleId(), smart.languageRuleId(), door.doorModelRuleId(), door.securityGradeRuleId(),
                    door.baseMaterialRuleId(), door.thicknessRuleId(), door.finishColorRuleId(), codeSuffix, eanCode, id, r.version());
            conflictIfUnchanged(changed);
            if (r.salesMinimumOrderQuantity() != null) jdbc.update("UPDATE sku SET sales_minimum_order_quantity=? WHERE id=?", r.salesMinimumOrderQuantity(), id);
            saveSupplierConfig(id, r);
            return product(id);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("产品编号已存在：" + productCode);
        }
    }

    private String normalizeSuffix(String suffix) {
        return suffix == null ? "" : suffix.trim();
    }

    private String validateEan(String value, Long currentId) {
        String ean = value == null ? "" : value.trim();
        if (ean.isEmpty()) return null;
        if (!ean.matches("69\\d{10}")) throw new IllegalArgumentException("EAN码必须是以69开头的12位数字");
        Integer count = currentId == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM sku WHERE ean_code=?", Integer.class, ean)
                : jdbc.queryForObject("SELECT COUNT(*) FROM sku WHERE ean_code=? AND id<>?", Integer.class, ean, currentId);
        if (count != null && count > 0) throw new IllegalStateException("EAN码已存在");
        return ean;
    }

    private String baseCode(String productCode, Object suffixValue) {
        String suffix = normalizeSuffix((String) suffixValue);
        String marker = "-" + suffix;
        return !suffix.isEmpty() && productCode.endsWith(marker) ? productCode.substring(0, productCode.length() - marker.length()) : productCode;
    }

    private String requiredProductType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"SMART_LOCK".equals(type) && !"ENTRY_DOOR".equals(type))
            throw new IllegalArgumentException("请选择产品分类：智能锁或入户门");
        return type;
    }

    private String requiredMaterialType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"FINISHED_PRODUCT".equals(type) && !"PART".equals(type))
            throw new IllegalArgumentException("请选择物料类型：成品或零件");
        return type;
    }

    private String materialSpecification(Long brandRuleId, String model, Long bodyColorRuleId, Long lockTypeRuleId, Long languageRuleId) {
        return String.join(" / ", Arrays.stream(new String[]{
                        ruleDisplayName(brandRuleId, "BRAND"),
                        model,
                        ruleDisplayName(bodyColorRuleId, "BODY_COLOR"),
                        ruleDisplayName(lockTypeRuleId, "LOCK_TYPE"),
                        ruleDisplayName(languageRuleId, "LANGUAGE")})
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList());
    }

    private String ruleDisplayName(Long ruleId, String category) {
        if (ruleId == null) return null;
        List<String> names = jdbc.queryForList(
                "SELECT display_name FROM product_code_rule WHERE id=? AND category=?", String.class, ruleId, category);
        if (names.isEmpty()) throw new IllegalArgumentException("产品编号规则无效：" + category);
        return names.get(0);
    }
    private String generateProductCode(String type, ProductCodeSelection smart, EntryDoorProductCodeSelection door) {
        return "ENTRY_DOOR".equals(type) ? productCodeGenerator.generateEntryDoor(door) : productCodeGenerator.generate(smart);
    }

    private ProductCodeSelection selection(EntityCommandRequest r) {
        return new ProductCodeSelection(r.brandRuleId(), r.seriesRuleId(), r.bodyColorRuleId(), r.lockTypeRuleId(),
                r.connectivityRuleId(), r.salesChannelRuleId(), r.operatingEntityRuleId(), r.languageRuleId());
    }

    private EntryDoorProductCodeSelection doorSelection(EntityCommandRequest r) {
        return new EntryDoorProductCodeSelection(r.brandRuleId(), r.doorModelRuleId(), r.securityGradeRuleId(),
                r.baseMaterialRuleId(), r.thicknessRuleId(), r.finishColorRuleId());
    }
    private Long chosen(Long requested, Object existing) {
        return requested != null ? requested : existing == null ? null : ((Number) existing).longValue();
    }
    private void saveSupplierConfig(long skuId, EntityCommandRequest r) {
        if (r.supplierId() == null) return;
        if (r.purchasePrice() == null || r.purchasePrice().signum() < 0) throw new IllegalArgumentException("采购单价不能为负数");
        if (r.moq() == null || r.moq() <= 0) throw new IllegalArgumentException("最小起订量必须为正数");
        int changed = jdbc.update("UPDATE sku_supplier_config SET supplier_id=?,purchase_price=?,moq=?,lead_time_days=?,enabled=TRUE,version=version+1 WHERE sku_id=?",
                r.supplierId(), r.purchasePrice(), r.moq(), r.leadTimeDays() == null ? 0 : r.leadTimeDays(), skuId);
        if (changed == 0) jdbc.update("INSERT INTO sku_supplier_config(sku_id,supplier_id,purchase_price,moq,lead_time_days,enabled) VALUES(?,?,?,?,?,TRUE)",
                skuId, r.supplierId(), r.purchasePrice(), r.moq(), r.leadTimeDays() == null ? 0 : r.leadTimeDays());
    }

    private Map<String, Object> createInventory(EntityCommandRequest r, EntityCommandFields fields) {
        long skuId = inventorySkuId(r);
        Long warehouseId = jdbc.queryForObject("SELECT id FROM warehouse WHERE is_default=TRUE AND enabled=TRUE ORDER BY id LIMIT 1", Long.class);
        int locked = lockedQuantity(r, fields), transit = quantity(r.inTransitQuantity());
        int baseActual = requestedActualQuantity(r, locked, transit);
        List<InventoryMovementCommand> movements = inventoryMovements(r);
        int actual = baseActual + movementDelta(movements);
        if (actual < 0) throw new IllegalArgumentException("出库数量不能超过实际库存");
        validateBalance(actual, locked, transit);
        updateInventorySkuDetails(skuId, r);
        long id = insert("INSERT INTO inventory_balance(warehouse_id,sku_id,actual_quantity,locked_quantity,in_transit_quantity,source_supplier_name,inventory_remark) VALUES(?,?,?,?,?,?,?)",
                warehouseId, skuId, actual, locked, transit, r.sourceSupplierName(), r.inventoryRemark());
        saveLockedAllocations(id, r, fields);
        if (baseActual != 0 || locked != 0 || transit != 0) {
            writeTransaction(warehouseId, skuId, 0, baseActual, 0, locked, 0, transit, r.reason());
        }
        writeInventoryMovements(warehouseId, skuId, baseActual, movements);
        return inventory(id);
    }

    private Map<String, Object> updateInventory(long id, EntityCommandRequest r, EntityCommandFields fields) {
        Map<String, Object> old = jdbc.queryForMap("SELECT warehouse_id,sku_id,actual_quantity,locked_quantity,in_transit_quantity,version FROM inventory_balance WHERE id=? FOR UPDATE", id);
        if (((Number) old.get("version")).intValue() != r.version()) throw new IllegalStateException("数据已被其他操作修改，请重新打开后再试");
        List<InventoryMovementCommand> movements = inventoryMovements(r);
        int actualBefore = ((Number) old.get("actual_quantity")).intValue();
        int locked = lockedQuantity(r, fields), transit = quantity(r.inTransitQuantity());
        int requestedActual = requestedActualQuantity(r, locked, transit);
        int movementDelta = movementDelta(movements);
        // 用户修改了库存汇总时，以输入值作为保存后的实际库存；仅新增出入库明细而未修改汇总时，才自动累加明细。
        int actual = !movements.isEmpty() && requestedActual == actualBefore
                ? actualBefore + movementDelta
                : requestedActual;
        if (actual < 0) throw new IllegalArgumentException("出库数量不能超过实际库存");
        validateBalance(actual, locked, transit);
        int changed = jdbc.update("UPDATE inventory_balance SET actual_quantity=?,locked_quantity=?,in_transit_quantity=?,source_supplier_name=?,inventory_remark=?,version=version+1 WHERE id=? AND version=?",
                actual, locked, transit, r.sourceSupplierName(), r.inventoryRemark(), id, r.version());
        conflictIfUnchanged(changed);
        saveLockedAllocations(id, r, fields);
        long warehouseId = ((Number) old.get("warehouse_id")).longValue();
        long skuId = ((Number) old.get("sku_id")).longValue();
        updateInventorySkuDetails(skuId, r);
        if (!movements.isEmpty()) {
            // 让每条流水的前后库存与最终库存保持一致。若用户手工修正过汇总，先记录一笔调整，再登记入/出库明细。
            int movementBase = actual - movementDelta;
            if (movementBase < 0) throw new IllegalArgumentException("入/出库明细与实际库存数量不匹配");
            if (movementBase != actualBefore) {
                writeTransaction(warehouseId, skuId, actualBefore, movementBase,
                        ((Number) old.get("locked_quantity")).intValue(), locked,
                        ((Number) old.get("in_transit_quantity")).intValue(), transit, r.reason());
            }
            writeInventoryMovements(warehouseId, skuId, movementBase, movements);
        } else {
            writeTransaction(warehouseId, skuId, actualBefore, actual,
                    ((Number) old.get("locked_quantity")).intValue(), locked,
                    ((Number) old.get("in_transit_quantity")).intValue(), transit, r.reason());
        }
        return inventory(id);
    }

    private long inventorySkuId(EntityCommandRequest r) {
        if (r.skuId() != null) return r.skuId();
        requireText(r.skuCode(), "请选择物料编号 SKU");
        List<Long> ids = jdbc.queryForList("SELECT id FROM sku WHERE sku_code=?", Long.class, r.skuCode().trim());
        if (ids.isEmpty()) throw new IllegalArgumentException("物料编号不存在，请先新增产品");
        return ids.get(0);
    }

    private int requestedActualQuantity(EntityCommandRequest r, int locked, int transit) {
        if (r.availableQuantity() == null) return quantity(r.actualQuantity());
        int actual = quantity(r.availableQuantity()) + locked - transit;
        if (actual < 0) throw new IllegalArgumentException("可用库存与在途、锁定数量不匹配");
        return actual;
    }

    private void updateInventorySkuDetails(long skuId, EntityCommandRequest r) {
        if (r.productType() == null && r.model() == null && r.configuration() == null && r.productVersion() == null
                && r.color() == null && r.lockBody() == null && r.unit() == null) return;
        String productType = r.productType() == null ? null : requiredProductType(r.productType());
        jdbc.update("UPDATE sku SET product_type=COALESCE(?,product_type),model=COALESCE(?,model),configuration=COALESCE(?,configuration),product_version=COALESCE(?,product_version),color=COALESCE(?,color),lock_body=COALESCE(?,lock_body),unit=COALESCE(?,unit),version=version+1 WHERE id=?",
                productType, r.model(), r.configuration(), r.productVersion(), r.color(), r.lockBody(), r.unit(), skuId);
    }

    private int lockedQuantity(EntityCommandRequest r, EntityCommandFields fields) {
        if (fields.lockedAllocationsPresent()) {
            int locked = quantity(r.lockedQuantity());
            validateLockedAllocations(fields.lockedAllocations(), locked);
            return locked;
        }
        List<Integer> allocations = Arrays.asList(r.lockedMingAiJunQiao(), r.lockedBoLeLongMi(), r.lockedLaos(), r.lockedBeiLang(), r.lockedMalaysia());
        boolean hasAllocations = allocations.stream().anyMatch(value -> value != null);
        if (!hasAllocations) return quantity(r.lockedQuantity());
        return allocations.stream().mapToInt(this::quantity).sum();
    }

    private void validateLockedAllocations(List<InventoryLockedAllocationCommand> allocations, int locked) {
        Set<String> sources = new HashSet<>();
        int total = 0;
        for (InventoryLockedAllocationCommand allocation : allocations) {
            String source = allocation.lockSource() == null ? "" : allocation.lockSource().trim();
            if (source.isEmpty()) throw new IllegalArgumentException("地点名称不能为空");
            if (!sources.add(source)) throw new IllegalArgumentException("地点名称不能重复");
            if (allocation.quantity() == null || allocation.quantity() < 0) throw new IllegalArgumentException("地点锁定数量必须是非负整数");
            total += allocation.quantity();
        }
        if (total > locked) throw new IllegalArgumentException("地点锁定数量合计不能超过已锁定数量");
    }

    private void saveLockedAllocations(long inventoryId, EntityCommandRequest r, EntityCommandFields fields) {
        if (fields.lockedAllocationsPresent()) {
            jdbc.update("DELETE FROM inventory_locked_allocation WHERE inventory_balance_id=?", inventoryId);
            for (InventoryLockedAllocationCommand allocation : fields.lockedAllocations()) {
                if (allocation.quantity() > 0) jdbc.update("INSERT INTO inventory_locked_allocation(inventory_balance_id,lock_source,quantity) VALUES(?,?,?)",
                        inventoryId, allocation.lockSource().trim(), allocation.quantity());
            }
            return;
        }
        List<LockedAllocation> allocations = List.of(
                new LockedAllocation("铭爱钧乔", r.lockedMingAiJunQiao()),
                new LockedAllocation("博乐龙米", r.lockedBoLeLongMi()),
                new LockedAllocation("老挝", r.lockedLaos()),
                new LockedAllocation("贝朗", r.lockedBeiLang()),
                new LockedAllocation("马来西亚", r.lockedMalaysia()));
        if (allocations.stream().noneMatch(allocation -> allocation.quantity() != null)) return;
        for (LockedAllocation allocation : allocations) {
            int quantity = allocation.quantity() == null ? 0 : quantity(allocation.quantity());
            int changed = jdbc.update("UPDATE inventory_locked_allocation SET quantity=? WHERE inventory_balance_id=? AND lock_source=?",
                    quantity, inventoryId, allocation.source());
            if (changed == 0) jdbc.update("INSERT INTO inventory_locked_allocation(inventory_balance_id,lock_source,quantity) VALUES(?,?,?)",
                    inventoryId, allocation.source(), quantity);
        }
    }

    private record LockedAllocation(String source, Integer quantity) {}

    private void writeTransaction(long warehouseId, long skuId, int actualBefore, int actualAfter,
                                  int lockedBefore, int lockedAfter, int transitBefore, int transitAfter, String reason) {
        jdbc.update("INSERT INTO inventory_transaction(warehouse_id,sku_id,transaction_type,business_type,business_no,actual_delta,locked_delta,transit_delta,actual_before,actual_after,locked_before,locked_after,transit_before,transit_after) VALUES(?,?,'MANUAL_ADJUST','INVENTORY',?,?,?,?,?,?,?,?,?,?)",
                warehouseId, skuId, textOr(reason, "手工调整"), actualAfter-actualBefore, lockedAfter-lockedBefore, transitAfter-transitBefore,
                actualBefore, actualAfter, lockedBefore, lockedAfter, transitBefore, transitAfter);
    }

    private List<InventoryMovementCommand> inventoryMovements(EntityCommandRequest request) {
        if (request.inventoryMovements() != null && !request.inventoryMovements().isEmpty()) {
            return request.inventoryMovements().stream().filter(movement -> movement.quantity() != null && movement.quantity() > 0)
                    .map(this::validMovement).toList();
        }
        List<InventoryMovementCommand> legacy = new java.util.ArrayList<>();
        if (movementQuantity(request.inboundQuantity()) > 0) legacy.add(new InventoryMovementCommand(request.movementDate(), "INBOUND", request.inboundQuantity()));
        if (movementQuantity(request.outboundQuantity()) > 0) legacy.add(new InventoryMovementCommand(request.movementDate(), "OUTBOUND", request.outboundQuantity()));
        return legacy;
    }

    private InventoryMovementCommand validMovement(InventoryMovementCommand movement) {
        if (!"INBOUND".equals(movement.direction()) && !"OUTBOUND".equals(movement.direction())) throw new IllegalArgumentException("入/出库类型不正确");
        if (movement.quantity() == null || movement.quantity() <= 0) throw new IllegalArgumentException("入库或出库数量必须大于零");
        movementTimestamp(movement.date());
        return movement;
    }

    private int movementDelta(List<InventoryMovementCommand> movements) {
        return movements.stream().mapToInt(movement -> "INBOUND".equals(movement.direction()) ? movement.quantity() : -movement.quantity()).sum();
    }

    private void writeInventoryMovements(long warehouseId, long skuId, int actualBefore, List<InventoryMovementCommand> movements) {
        int current = actualBefore;
        for (InventoryMovementCommand movement : movements) {
            int delta = "INBOUND".equals(movement.direction()) ? movement.quantity() : -movement.quantity();
            int after = current + delta;
            jdbc.update("INSERT INTO inventory_transaction(warehouse_id,sku_id,transaction_type,business_type,business_no,actual_delta,locked_delta,transit_delta,actual_before,actual_after,locked_before,locked_after,transit_before,transit_after,operated_at) VALUES(?,?,?,'INVENTORY',?,?,0,0,?,?,0,0,0,0,?)",
                    warehouseId, skuId, "INBOUND".equals(movement.direction()) ? "MANUAL_INBOUND" : "MANUAL_OUTBOUND",
                    "INBOUND".equals(movement.direction()) ? "手工入库" : "手工出库", delta, current, after, movementTimestamp(movement.date()));
            current = after;
        }
    }

    private Timestamp movementTimestamp(String movementDate) {
        if (movementDate == null || movementDate.isBlank()) return Timestamp.valueOf(LocalDateTime.now());
        try {
            return Timestamp.valueOf(LocalDate.parse(movementDate).atStartOfDay());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("入/出库日期格式不正确");
        }
    }

    private Map<String, Object> customer(long id) {
        return map(jdbc.queryForMap("SELECT id,customer_code,customer_name,contact_name,phone,address,business_contact_name,business_contact_phone,order_contact_name,order_contact_phone,finance_contact_name,finance_contact_phone,invoice_title,taxpayer_id,invoice_address,invoice_phone,bank_name,bank_account,enabled,version FROM customer WHERE id=?", id),
                "customer_code","customerCode","customer_name","customerName","contact_name","contactName",
                "business_contact_name","businessContactName","business_contact_phone","businessContactPhone",
                "order_contact_name","orderContactName","order_contact_phone","orderContactPhone",
                "finance_contact_name","financeContactName","finance_contact_phone","financeContactPhone",
                "invoice_title","invoiceTitle","taxpayer_id","taxpayerId","invoice_address","invoiceAddress",
                "invoice_phone","invoicePhone","bank_name","bankName","bank_account","bankAccount");
    }
    private String businessName(EntityCommandRequest r) { return r.businessContactName() != null ? r.businessContactName() : r.contactName(); }
    private String businessPhone(EntityCommandRequest r) { return r.businessContactPhone() != null ? r.businessContactPhone() : r.phone(); }
    private Map<String, Object> user(long id) {
        return map(jdbc.queryForMap("SELECT id,username,display_name,phone,enabled,role,version FROM sys_user WHERE id=?", id), "display_name","displayName");
    }
    private Map<String, Object> product(long id) {
        return map(jdbc.queryForMap("""
                SELECT s.id,s.product_code,s.code_suffix,s.ean_code,s.product_type,s.material_type,s.sku_code,s.model,s.product_name,s.color,s.lock_body,s.product_version,s.configuration,s.product_configuration,s.unit,
                       s.current_cost,s.factory_price,s.sales_minimum_order_quantity,s.product_remark,s.enabled,s.version,
                       s.brand_rule_id,s.series_rule_id,s.body_color_rule_id,s.lock_type_rule_id,s.connectivity_rule_id,
                       s.sales_channel_rule_id,s.operating_entity_rule_id,s.language_rule_id,
                       br.display_name AS brand,sr.display_name AS series,bcr.display_name AS body_color,
                       ltr.display_name AS lock_type,cr.display_name AS connectivity,scr.display_name AS sales_channel,
                       oer.display_name AS operating_entity,lr.display_name AS language
                FROM sku s
                LEFT JOIN product_code_rule br ON br.id=s.brand_rule_id
                LEFT JOIN product_code_rule sr ON sr.id=s.series_rule_id
                LEFT JOIN product_code_rule bcr ON bcr.id=s.body_color_rule_id
                LEFT JOIN product_code_rule ltr ON ltr.id=s.lock_type_rule_id
                LEFT JOIN product_code_rule cr ON cr.id=s.connectivity_rule_id
                LEFT JOIN product_code_rule scr ON scr.id=s.sales_channel_rule_id
                LEFT JOIN product_code_rule oer ON oer.id=s.operating_entity_rule_id
                LEFT JOIN product_code_rule lr ON lr.id=s.language_rule_id
                WHERE s.id=?
                """, id),
                "product_code","productCode","code_suffix","codeSuffix","ean_code","eanCode","product_type","productType","material_type","materialType","sku_code","customerCode","product_name","productName","lock_body","lockBody","sales_minimum_order_quantity","salesMinimumOrderQuantity",
                "product_version","productVersion","product_configuration","productConfiguration","current_cost","currentCost","factory_price","factoryPrice","product_remark","remark",
                "brand_rule_id","brandRuleId","series_rule_id","seriesRuleId","body_color_rule_id","bodyColorRuleId",
                "lock_type_rule_id","lockTypeRuleId","connectivity_rule_id","connectivityRuleId","sales_channel_rule_id","salesChannelRuleId",
                "operating_entity_rule_id","operatingEntityRuleId","language_rule_id","languageRuleId","body_color","bodyColor",
                "lock_type","lockType","sales_channel","salesChannel","operating_entity","operatingEntity");
    }    private Map<String, Object> supplier(long id) {
        return map(jdbc.queryForMap("""
                        SELECT id,supplier_code,supplier_name,manufacturer_category,manufacturer_type,
                               supplier_location,product_attribute,short_name,contact_name,contact_title,
                               phone,address,currency,tax_registration_no,bank_account,bank_address,enabled,version
                        FROM supplier WHERE id=?
                        """, id),
                "supplier_code","supplierCode","supplier_name","supplierName",
                "manufacturer_category","manufacturerCategory","manufacturer_type","manufacturerType",
                "supplier_location","supplierLocation","product_attribute","productAttribute",
                "short_name","shortName","contact_name","contactName","contact_title","contactTitle",
                "tax_registration_no","taxRegistrationNo","bank_account","bankAccount","bank_address","bankAddress");
    }
    private Map<String, Object> inventory(long id) {
        return map(jdbc.queryForMap("SELECT id,sku_id,actual_quantity,locked_quantity,in_transit_quantity,source_supplier_name,inventory_remark,version FROM inventory_balance WHERE id=?", id),
                "sku_id","skuId","actual_quantity","actualQuantity","locked_quantity","lockedQuantity","in_transit_quantity","inTransitQuantity","source_supplier_name","sourceSupplierName","inventory_remark","inventoryRemark");
    }

    private Map<String, Object> map(Map<String, Object> source, String... aliases) {
        Map<String, String> names = new LinkedHashMap<>();
        for (int i=0;i<aliases.length;i+=2) names.put(aliases[i], aliases[i+1]);
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((k,v) -> result.put(names.getOrDefault(k.toLowerCase(), k),v));
        return result;
    }

    private long insert(String sql, Object... parameters) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int i=0;i<parameters.length;i++) statement.setObject(i+1, parameters[i]);
            return statement;
        }, keys);
        if (keys.getKey() == null) throw new IllegalStateException("保存失败，未生成数据编号");
        return keys.getKey().longValue();
    }

    private void validateProduct(EntityCommandRequest r) {
        requireText(r.productName(), "产品名称不能为空");
        if (r.currentCost() != null && r.currentCost().signum() < 0) throw new IllegalArgumentException("成本不能为负数");
        if (r.factoryPrice() != null && r.factoryPrice().signum() < 0) throw new IllegalArgumentException("转厂价格不能为负数");
        if (r.salesMinimumOrderQuantity() != null && r.salesMinimumOrderQuantity() <= 0) throw new IllegalArgumentException("销售最小起订量必须为正数");
    }
    private int salesMinimumOrderQuantity(EntityCommandRequest request) {
        return request.salesMinimumOrderQuantity() == null ? 1 : request.salesMinimumOrderQuantity();
    }

    private void requireProductPricePermission(EntityCommandFields fields) {
        if ((fields.currentCostPresent() || fields.factoryPricePresent())
                && !CurrentUser.required().role().canEditProductPrice()) {
            throw new IllegalArgumentException("仅财务或管理员可修改产品价格");
        }
    }
    private UserRole requestedRole(EntityCommandRequest request) {
        if (!CurrentUser.required().role().canManageRoles()) {
            throw new IllegalArgumentException("仅管理员可分配用户角色");
        }
        return parseRole(request.role());
    }
    private UserRole parseRole(String value) {
        if (value == null) throw new IllegalArgumentException("用户角色无效");
        try {
            return UserRole.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("用户角色无效");
        }
    }
    private UserRole existingRole(long id) {
        List<String> roles = jdbc.queryForList("SELECT role FROM sys_user WHERE id=?", String.class, id);
        if (roles.isEmpty()) throw new IllegalStateException("数据已被其他操作修改，请重新打开后再试");
        return UserRole.valueOf(roles.get(0));
    }
    private void requireUserManagementPermission() {
        if (!CurrentUser.required().role().canManageRoles()) {
            throw new IllegalArgumentException("仅管理员可管理用户账号");
        }
    }
    private void validateBalance(int actual, int locked, int transit) {
        if (locked > actual + transit) throw new IllegalArgumentException("锁定数量不能超过实际库存与在途库存之和");
    }
    private int quantity(Integer value) {
        if (value == null || value < 0) throw new IllegalArgumentException("库存数量不能为空且不能为负数");
        return value;
    }
    private int movementQuantity(Integer value) {
        if (value == null) return 0;
        if (value < 0) throw new IllegalArgumentException("入库和出库数量不能为负数");
        return value;
    }
    private void requireVersion(EntityCommandRequest r) { if (r.version() == null) throw new IllegalArgumentException("缺少数据版本，请重新打开后再试"); }
    private void conflictIfUnchanged(int changed) { if (changed == 0) throw new IllegalStateException("数据已被其他操作修改，请重新打开后再试"); }
    private void requireText(String value, String message) { if (value == null || value.isBlank()) throw new IllegalArgumentException(message); }
    private boolean enabled(EntityCommandRequest r) { return r.enabled() == null || r.enabled(); }
    private String textOr(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    private String generatedCode(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(); }
}
