package com.internalops.workbench;

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
        return switch (module) {
            case "customer" -> createCustomer(request);
            case "user" -> createUser(request);
            case "product" -> createProduct(request);
            case "inventory" -> createInventory(request);
            default -> throw new IllegalArgumentException("该模块不支持手工新增");
        };
    }

    @Transactional
    public Map<String, Object> update(String module, long id, EntityCommandRequest request) {
        requireVersion(request);
        return switch (module) {
            case "customer" -> updateCustomer(id, request);
            case "user" -> updateUser(id, request);
            case "product" -> updateProduct(id, request);
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

    private Map<String, Object> createUser(EntityCommandRequest r) {
        requireText(r.username(), "用户名不能为空");
        requireText(r.displayName(), "姓名不能为空");
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username=?", Integer.class, r.username().trim());
        if (existing != null && existing > 0) throw new IllegalArgumentException("用户名已存在，请换一个");
        long id = insert("INSERT INTO sys_user(username,password_hash,display_name,phone,enabled) VALUES(?,?,?,?,?)",
                r.username().trim(), "{noop}internal", r.displayName().trim(), r.phone(), enabled(r));
        return user(id);
    }

    private Map<String, Object> updateUser(long id, EntityCommandRequest r) {
        requireText(r.displayName(), "姓名不能为空");
        int changed = jdbc.update("UPDATE sys_user SET display_name=?,phone=?,enabled=?,version=version+1 WHERE id=? AND version=?",
                r.displayName().trim(), r.phone(), enabled(r), id, r.version());
        conflictIfUnchanged(changed);
        return user(id);
    }

    private Map<String, Object> createProduct(EntityCommandRequest r) {
        validateProduct(r);
        String code = textOr(r.skuCode(), generatedCode("SKU"));
        long id = insert("INSERT INTO sku(sku_code,model,product_name,color,lock_body,product_version,configuration,unit,current_cost,factory_price,product_remark,enabled) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                code, r.model(), r.productName().trim(), r.color(), r.lockBody(), r.productVersion(), r.configuration(),
                textOr(r.unit(), "件"), r.currentCost(), r.factoryPrice(), r.remark(), enabled(r));
        saveSupplierConfig(id, r);
        return product(id);
    }

    private Map<String, Object> updateProduct(long id, EntityCommandRequest r) {
        validateProduct(r);
        int changed = jdbc.update("UPDATE sku SET model=?,product_name=?,color=?,lock_body=?,product_version=?,configuration=?,unit=?,current_cost=?,factory_price=?,product_remark=?,enabled=?,version=version+1 WHERE id=? AND version=?",
                r.model(), r.productName().trim(), r.color(), r.lockBody(), r.productVersion(), r.configuration(), textOr(r.unit(), "件"),
                r.currentCost(), r.factoryPrice(), r.remark(), enabled(r), id, r.version());
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
        int baseActual = quantity(r.actualQuantity());
        List<InventoryMovementCommand> movements = inventoryMovements(r);
        int actual = baseActual + movementDelta(movements);
        if (actual < 0) throw new IllegalArgumentException("出库数量不能超过实际库存");
        int locked = lockedQuantity(r), transit = quantity(r.inTransitQuantity());
        validateBalance(actual, locked, transit);
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
        int actual = !movements.isEmpty() ? actualBefore + movementDelta(movements) : quantity(r.actualQuantity());
        if (actual < 0) throw new IllegalArgumentException("出库数量不能超过实际库存");
        int locked = lockedQuantity(r), transit = quantity(r.inTransitQuantity());
        validateBalance(actual, locked, transit);
        int changed = jdbc.update("UPDATE inventory_balance SET actual_quantity=?,locked_quantity=?,in_transit_quantity=?,source_supplier_name=?,inventory_remark=?,version=version+1 WHERE id=? AND version=?",
                actual, locked, transit, r.sourceSupplierName(), r.inventoryRemark(), id, r.version());
        conflictIfUnchanged(changed);
        saveLockedAllocations(id, r);
        long warehouseId = ((Number) old.get("warehouse_id")).longValue();
        long skuId = ((Number) old.get("sku_id")).longValue();
        if (!movements.isEmpty()) {
            writeInventoryMovements(warehouseId, skuId, actualBefore, movements);
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
        return map(jdbc.queryForMap("SELECT id,username,display_name,phone,enabled,version FROM sys_user WHERE id=?", id), "display_name","displayName");
    }
    private Map<String, Object> product(long id) {
        return map(jdbc.queryForMap("SELECT id,sku_code,model,product_name,color,lock_body,product_version,configuration,unit,current_cost,factory_price,product_remark,enabled,version FROM sku WHERE id=?", id),
                "sku_code","skuCode","product_name","productName","lock_body","lockBody","product_version","productVersion","current_cost","currentCost","factory_price","factoryPrice","product_remark","remark");
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
