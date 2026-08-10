package com.internalops.workbench;

import com.internalops.auth.CurrentUser;
import com.internalops.auth.UserRole;
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
import java.util.UUID;

@Service
public class MasterDataCommandService {
    private final JdbcTemplate jdbc;

    public MasterDataCommandService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
            case "inventory" -> createInventory(request);
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
            case "inventory" -> updateInventory(id, request);
            default -> throw new IllegalArgumentException("该模块不支持手工修改");
        };
    }

    private Map<String, Object> createCustomer(EntityCommandRequest r) {
        requireText(r.customerName(), "客户名称不能为空");
        String code = textOr(r.customerCode(), generatedCode("C"));
        long id = insert("INSERT INTO customer(customer_code,customer_name,contact_name,phone,address,enabled) VALUES(?,?,?,?,?,?)",
                code, r.customerName().trim(), r.contactName(), r.phone(), r.address(), enabled(r));
        return customer(id);
    }

    private Map<String, Object> updateCustomer(long id, EntityCommandRequest r) {
        requireText(r.customerName(), "客户名称不能为空");
        int changed = jdbc.update("UPDATE customer SET customer_name=?,contact_name=?,phone=?,address=?,enabled=?,version=version+1 WHERE id=? AND version=?",
                r.customerName().trim(), r.contactName(), r.phone(), r.address(), enabled(r), id, r.version());
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
        requireText(r.username(), "用户名不能为空");
        requireText(r.displayName(), "姓名不能为空");
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username=?", Integer.class, r.username().trim());
        if (existing != null && existing > 0) throw new IllegalArgumentException("用户名已存在，请换一个");
        UserRole role = fields.rolePresent() ? requestedRole(r) : UserRole.USER;
        long id = insert("INSERT INTO sys_user(username,password_hash,display_name,phone,enabled,role) VALUES(?,?,?,?,?,?)",
                r.username().trim(), "{noop}internal", r.displayName().trim(), r.phone(), enabled(r), role.name());
        return user(id);
    }

    private Map<String, Object> updateUser(long id, EntityCommandRequest r, EntityCommandFields fields) {
        requireText(r.displayName(), "姓名不能为空");
        UserRole role = fields.rolePresent() ? requestedRole(r) : existingRole(id);
        int changed = jdbc.update("UPDATE sys_user SET display_name=?,phone=?,enabled=?,role=?,version=version+1 WHERE id=? AND version=?",
                r.displayName().trim(), r.phone(), enabled(r), role.name(), id, r.version());
        conflictIfUnchanged(changed);
        return user(id);
    }

    private Map<String, Object> createProduct(EntityCommandRequest r, EntityCommandFields fields) {
        validateProduct(r);
        requireProductPricePermission(fields);
        String code = textOr(r.skuCode(), generatedCode("SKU"));
        long id = insert("INSERT INTO sku(sku_code,model,product_name,color,lock_body,product_version,configuration,unit,current_cost,factory_price,product_remark,enabled) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                code, r.model(), r.productName().trim(), r.color(), r.lockBody(), r.productVersion(), r.configuration(),
                textOr(r.unit(), "件"), r.currentCost(), r.factoryPrice(), r.remark(), enabled(r));
        saveSupplierConfig(id, r);
        return product(id);
    }

    private Map<String, Object> updateProduct(long id, EntityCommandRequest r, EntityCommandFields fields) {
        validateProduct(r);
        requireProductPricePermission(fields);
        int changed = jdbc.update("UPDATE sku SET model=?,product_name=?,color=?,lock_body=?,product_version=?,configuration=?,unit=?,current_cost=CASE WHEN ? THEN ? ELSE current_cost END,factory_price=CASE WHEN ? THEN ? ELSE factory_price END,product_remark=?,enabled=?,version=version+1 WHERE id=? AND version=?",
                r.model(), r.productName().trim(), r.color(), r.lockBody(), r.productVersion(), r.configuration(), textOr(r.unit(), "件"),
                fields.currentCostPresent(), r.currentCost(), fields.factoryPricePresent(), r.factoryPrice(),
                r.remark(), enabled(r), id, r.version());
        conflictIfUnchanged(changed);
        saveSupplierConfig(id, r);
        return product(id);
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

    private Map<String, Object> createInventory(EntityCommandRequest r) {
        long skuId = inventorySkuId(r);
        Long warehouseId = jdbc.queryForObject("SELECT id FROM warehouse WHERE is_default=TRUE AND enabled=TRUE ORDER BY id LIMIT 1", Long.class);
        int locked = lockedQuantity(r), transit = quantity(r.inTransitQuantity());
        int baseActual = requestedActualQuantity(r, locked, transit);
        List<InventoryMovementCommand> movements = inventoryMovements(r);
        int actual = baseActual + movementDelta(movements);
        if (actual < 0) throw new IllegalArgumentException("出库数量不能超过实际库存");
        validateBalance(actual, locked, transit);
        updateInventorySkuDetails(skuId, r);
        long id = insert("INSERT INTO inventory_balance(warehouse_id,sku_id,actual_quantity,locked_quantity,in_transit_quantity,source_supplier_name,inventory_remark) VALUES(?,?,?,?,?,?,?)",
                warehouseId, skuId, actual, locked, transit, r.sourceSupplierName(), r.inventoryRemark());
        saveLockedAllocations(id, r);
        if (baseActual != 0 || locked != 0 || transit != 0) {
            writeTransaction(warehouseId, skuId, 0, baseActual, 0, locked, 0, transit, r.reason());
        }
        writeInventoryMovements(warehouseId, skuId, baseActual, movements);
        return inventory(id);
    }

    private Map<String, Object> updateInventory(long id, EntityCommandRequest r) {
        Map<String, Object> old = jdbc.queryForMap("SELECT warehouse_id,sku_id,actual_quantity,locked_quantity,in_transit_quantity,version FROM inventory_balance WHERE id=? FOR UPDATE", id);
        if (((Number) old.get("version")).intValue() != r.version()) throw new IllegalStateException("数据已被其他操作修改，请重新打开后再试");
        List<InventoryMovementCommand> movements = inventoryMovements(r);
        int actualBefore = ((Number) old.get("actual_quantity")).intValue();
        int locked = lockedQuantity(r), transit = quantity(r.inTransitQuantity());
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
        saveLockedAllocations(id, r);
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
        if (r.model() == null && r.configuration() == null && r.productVersion() == null
                && r.color() == null && r.lockBody() == null && r.unit() == null) return;
        jdbc.update("UPDATE sku SET model=COALESCE(?,model),configuration=COALESCE(?,configuration),product_version=COALESCE(?,product_version),color=COALESCE(?,color),lock_body=COALESCE(?,lock_body),unit=COALESCE(?,unit),version=version+1 WHERE id=?",
                r.model(), r.configuration(), r.productVersion(), r.color(), r.lockBody(), r.unit(), skuId);
    }

    private int lockedQuantity(EntityCommandRequest r) {
        List<Integer> allocations = Arrays.asList(r.lockedMingAiJunQiao(), r.lockedBoLeLongMi(), r.lockedLaos(), r.lockedBeiLang(), r.lockedMalaysia());
        boolean hasAllocations = allocations.stream().anyMatch(value -> value != null);
        if (!hasAllocations) return quantity(r.lockedQuantity());
        return allocations.stream().mapToInt(this::quantity).sum();
    }

    private void saveLockedAllocations(long inventoryId, EntityCommandRequest r) {
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
        return map(jdbc.queryForMap("SELECT id,customer_code,customer_name,contact_name,phone,address,enabled,version FROM customer WHERE id=?", id),
                "customer_code","customerCode","customer_name","customerName","contact_name","contactName");
    }
    private Map<String, Object> user(long id) {
        return map(jdbc.queryForMap("SELECT id,username,display_name,phone,enabled,role,version FROM sys_user WHERE id=?", id), "display_name","displayName");
    }
    private Map<String, Object> product(long id) {
        return map(jdbc.queryForMap("SELECT id,sku_code,model,product_name,color,lock_body,product_version,configuration,unit,current_cost,factory_price,product_remark,enabled,version FROM sku WHERE id=?", id),
                "sku_code","skuCode","product_name","productName","lock_body","lockBody","product_version","productVersion","current_cost","currentCost","factory_price","factoryPrice","product_remark","remark");
    }
    private Map<String, Object> supplier(long id) {
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
