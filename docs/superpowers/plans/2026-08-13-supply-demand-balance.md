# Supply-Demand Balance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add one consistent supply-demand balance to inventory, sales-order, and procurement workflows, while renaming the current available-stock label to unlocked stock.

**Architecture:** Introduce a focused backend query service that aggregates default-warehouse inventory and outstanding sales demand per SKU. Reuse its SQL projection in inventory list and order/procurement product options; keep the stored inventory schema unchanged and calculate draft-order effects in Vue only.

**Tech Stack:** Java 17, Spring Boot, JdbcTemplate, JUnit 5/MockMvc, MySQL/H2-compatible SQL, Vue 3, TypeScript, Vitest, Vue Test Utils.

## Global Constraints

- `pendingDeliveryQuantity = Σ max(quantity - shipped_quantity, 0)` for every sales order whose status is not `CANCELLED`; fully shipped rows contribute zero.
- `supplyDemandBalance = actualQuantity + inTransitQuantity - pendingDeliveryQuantity` and must remain negative when demand exceeds supply.
- `purchaseShortageQuantity = max(-supplyDemandBalance, 0)`.
- Keep backend property `availableQuantity`; rename only user-facing inventory copy to “未锁定库存数量/未锁定库存”.
- Negative balances warn but never block saving an order or automatically overwrite purchase quantity.
- Do not add database columns, background synchronization, automatic purchase creation, safety-stock logic, or a new analysis page.

---

## File Structure

- Create `backend/app/src/main/java/com/internalops/workbench/SupplyDemandQueryService.java`: single owner of supply-demand SQL expressions and SKU result enrichment.
- Create `backend/app/src/test/java/com/internalops/workbench/SupplyDemandQueryServiceTest.java`: focused formula and order-status coverage.
- Modify `backend/app/src/main/java/com/internalops/workbench/SalesOrderCommandService.java`: enrich `/api/orders/skus` results.
- Modify `backend/app/src/main/java/com/internalops/workbench/WorkbenchQueryService.java`: add calculated inventory-list fields and sort mappings.
- Modify `backend/app/src/test/java/com/internalops/workbench/SalesOrderCommandApiTest.java`: verify order SKU API contract.
- Modify `backend/app/src/test/java/com/internalops/workbench/InventoryWorkbenchQueryApiTest.java`: verify inventory API contract, including a demand-only SKU edge case where applicable to current inventory-list semantics.
- Modify `frontend/src/api/workbench.ts`: extend `OrderSku` with the three new values.
- Modify `frontend/src/modules/module-config.ts`: rename the inventory column and add pending/balance columns.
- Modify `frontend/src/modules/module-config.test.ts`: lock column order and copy.
- Modify `frontend/src/components/EntityDialog.vue` and `EntityDialog.test.ts`: rename the editable inventory copy and render calculated values read-only.
- Modify `frontend/src/components/ModuleListPage.vue`: widths and negative balance cell treatment.
- Modify `frontend/src/components/OrderDialog.vue` and `OrderDialog.test.ts`: show live post-order supply-demand balance, including edit compensation.
- Modify `frontend/src/components/OrderAllocationDialog.vue` and `OrderAllocationDialog.test.ts`: rename current available-stock copy only.
- Modify `frontend/src/components/ManualPurchaseDialog.vue` and `ManualPurchaseDialog.test.ts`: show shortage guidance without mutating quantity.
- Modify `frontend/src/styles.css`: add supply-demand summary and danger-state rules beside the existing order-line styles without introducing a new styling system.

---

### Task 1: Central Backend Supply-Demand Projection

**Files:**
- Create: `backend/app/src/main/java/com/internalops/workbench/SupplyDemandQueryService.java`
- Create: `backend/app/src/test/java/com/internalops/workbench/SupplyDemandQueryServiceTest.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/SalesOrderCommandService.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/WorkbenchQueryService.java`
- Modify: `backend/app/src/test/java/com/internalops/workbench/SalesOrderCommandApiTest.java`
- Modify: `backend/app/src/test/java/com/internalops/workbench/InventoryWorkbenchQueryApiTest.java`

**Interfaces:**
- Produces: `SupplyDemandQueryService.bySkuIds(Collection<Long> skuIds): Map<Long, SupplyDemandSnapshot>`.
- Produces: `record SupplyDemandSnapshot(int actualQuantity, int availableQuantity, int inTransitQuantity, int pendingDeliveryQuantity, int supplyDemandBalance, int purchaseShortageQuantity)`.
- Consumers: `SalesOrderCommandService.skuOptions()` and inventory rows returned by `WorkbenchQueryService.query("inventory", ...)`.

- [ ] **Step 1: Write failing aggregate-service tests**

Create tests that insert one enabled default warehouse, SKUs, balances, and sales orders, then assert exact snapshots:

```java
@Test
void aggregatesInventoryTransitAndOutstandingUncancelledDemand() {
    // SKU 1: actual 10, locked 3, transit 4.
    // OPEN rows: 8 ordered/2 shipped and 5 ordered/5 shipped.
    // CANCELLED row: 20 ordered/0 shipped.
    SupplyDemandSnapshot value = service.bySkuIds(List.of(1L)).get(1L);
    assertEquals(10, value.actualQuantity());
    assertEquals(7, value.availableQuantity());
    assertEquals(4, value.inTransitQuantity());
    assertEquals(6, value.pendingDeliveryQuantity());
    assertEquals(8, value.supplyDemandBalance());
    assertEquals(0, value.purchaseShortageQuantity());
}

@Test
void returnsNegativeBalanceAndPositivePurchaseShortageWithoutInventoryRow() {
    // SKU 2 has an uncancelled 9-unit unshipped order and no balance row.
    SupplyDemandSnapshot value = service.bySkuIds(List.of(2L)).get(2L);
    assertEquals(0, value.actualQuantity());
    assertEquals(0, value.inTransitQuantity());
    assertEquals(9, value.pendingDeliveryQuantity());
    assertEquals(-9, value.supplyDemandBalance());
    assertEquals(9, value.purchaseShortageQuantity());
}
```

- [ ] **Step 2: Run the focused backend tests and verify failure**

Run: `mvn -f backend/app/pom.xml -Dmaven.repo.local=.m2 -Dtest=SupplyDemandQueryServiceTest test`

Expected: FAIL because `SupplyDemandQueryService` and `SupplyDemandSnapshot` do not exist.

- [ ] **Step 3: Implement the focused query service**

Implement one grouped inventory query scoped to the enabled default warehouse and one grouped demand query. Merge them in Java so a requested SKU with no inventory row still receives zeros plus its order demand:

```java
@Service
public class SupplyDemandQueryService {
    public record SupplyDemandSnapshot(
        int actualQuantity, int availableQuantity, int inTransitQuantity,
        int pendingDeliveryQuantity, int supplyDemandBalance,
        int purchaseShortageQuantity) {}

    public Map<Long, SupplyDemandSnapshot> bySkuIds(Collection<Long> skuIds) {
        // Initialize every requested id with zeroes; query with named IN placeholders.
        // Inventory: SUM(actual_quantity), SUM(actual_quantity-locked_quantity),
        // SUM(in_transit_quantity), joined to the enabled default warehouse.
        // Demand: SUM(GREATEST(i.quantity-i.shipped_quantity,0)) from
        // sales_order_item i JOIN sales_order o, WHERE o.status<>'CANCELLED'.
        // balance = actual + transit - pending; shortage = max(-balance, 0).
    }
}
```

Use `Collections.nCopies(ids.size(), "?")` for the placeholder list, return `Map.of()` for an empty collection, and convert all JDBC numeric values through `Number.intValue()`.

- [ ] **Step 4: Run aggregate-service tests and verify pass**

Run: `mvn -f backend/app/pom.xml -Dmaven.repo.local=.m2 -Dtest=SupplyDemandQueryServiceTest test`

Expected: PASS with both positive and negative balance cases.

- [ ] **Step 5: Write failing API contract tests**

In `SalesOrderCommandApiTest`, assert `/api/orders/skus` returns:

```java
.andExpect(jsonPath("$[0].inTransitQuantity").value(4))
.andExpect(jsonPath("$[0].pendingDeliveryQuantity").value(6))
.andExpect(jsonPath("$[0].supplyDemandBalance").value(8))
.andExpect(jsonPath("$[0].purchaseShortageQuantity").value(0));
```

In `InventoryWorkbenchQueryApiTest`, assert the inventory row has the same four values and still exposes `availableQuantity = actual_quantity - locked_quantity`.

- [ ] **Step 6: Run API tests and verify failure**

Run: `mvn -f backend/app/pom.xml -Dmaven.repo.local=.m2 -Dtest=SalesOrderCommandApiTest,InventoryWorkbenchQueryApiTest test`

Expected: FAIL because the JSON responses lack the new properties.

- [ ] **Step 7: Enrich both backend response paths**

Inject `SupplyDemandQueryService` into both services. In `skuOptions()`, collect returned SKU ids, fetch snapshots once, and append all six inventory/supply fields to each result map. In the inventory query post-processing block, collect each row's `skuId`, fetch once, and append `pendingDeliveryQuantity`, `supplyDemandBalance`, and `purchaseShortageQuantity`; retain that row's existing actual/available/transit values so inventory remains warehouse-row based.

Add key normalization and sort mappings:

```java
Map.entry("pendingdeliveryquantity", "pendingDeliveryQuantity"),
Map.entry("supplydemandbalance", "supplyDemandBalance"),
Map.entry("purchaseshortagequantity", "purchaseShortageQuantity")
```

Sort `pendingDeliveryQuantity` and `supplyDemandBalance` by their SQL expressions only if the existing module query safely supports the joined aggregates; otherwise exclude these calculated columns from the sortable array in Task 2.

- [ ] **Step 8: Run all touched backend tests**

Run: `mvn -f backend/app/pom.xml -Dmaven.repo.local=.m2 -Dtest=SupplyDemandQueryServiceTest,SalesOrderCommandApiTest,InventoryWorkbenchQueryApiTest test`

Expected: PASS.

- [ ] **Step 9: Commit the backend slice**

```powershell
git add backend/app/src/main/java/com/internalops/workbench/SupplyDemandQueryService.java backend/app/src/main/java/com/internalops/workbench/SalesOrderCommandService.java backend/app/src/main/java/com/internalops/workbench/WorkbenchQueryService.java backend/app/src/test/java/com/internalops/workbench/SupplyDemandQueryServiceTest.java backend/app/src/test/java/com/internalops/workbench/SalesOrderCommandApiTest.java backend/app/src/test/java/com/internalops/workbench/InventoryWorkbenchQueryApiTest.java
git commit -m "功能：统一计算库存供需余量"
```

---

### Task 2: Inventory Management Copy and Calculated Columns

**Files:**
- Modify: `frontend/src/modules/module-config.ts`
- Modify: `frontend/src/modules/module-config.test.ts`
- Modify: `frontend/src/components/EntityDialog.vue`
- Modify: `frontend/src/components/EntityDialog.test.ts`
- Modify: `frontend/src/components/ModuleListPage.vue`
- Modify: `frontend/src/styles.css`

**Interfaces:**
- Consumes inventory list properties `availableQuantity`, `pendingDeliveryQuantity`, `supplyDemandBalance`, and `purchaseShortageQuantity` from Task 1.
- Produces user-facing labels and danger presentation; no new API types.

- [ ] **Step 1: Write failing inventory UI tests**

Update `module-config.test.ts` to assert the sequence includes:

```ts
expect(inventory.columns).toEqual(expect.arrayContaining([
  '未锁定库存数量', '在途数量', '待交订单数量', '供需余量'
]))
expect(inventory.fields.slice(
  inventory.fields.indexOf('availableQuantity'),
  inventory.fields.indexOf('supplyDemandBalance') + 1
)).toEqual([
  'availableQuantity', 'oldestStockDate', 'inventoryAgeDays',
  'lockedQuantity', 'inTransitQuantity',
  'pendingDeliveryQuantity', 'supplyDemandBalance'
])
```

Update `EntityDialog.test.ts` to assert “未锁定库存数量” is present, “可用库存数量” is absent, and calculated pending/balance inputs are disabled when an inventory row is edited.

- [ ] **Step 2: Run frontend tests and verify failure**

Run: `npm --prefix frontend test -- --run src/modules/module-config.test.ts src/components/EntityDialog.test.ts`

Expected: FAIL on old copy and missing calculated fields.

- [ ] **Step 3: Implement inventory labels, columns, and negative rendering**

In `module-config.ts`, keep `availableQuantity` in place but rename its column to `未锁定库存数量`; insert `pendingDeliveryQuantity` and `supplyDemandBalance` immediately after `inTransitQuantity`. Mark only SQL-supported fields sortable.

In `EntityDialog.vue`, rename the label and add read-only field definitions:

```ts
{ key: 'pendingDeliveryQuantity', label: '待交订单数量', type: 'number', disabled: true },
{ key: 'supplyDemandBalance', label: '供需余量', type: 'number', disabled: true }
```

Ensure these calculated keys are removed from create/update request bodies. In `ModuleListPage.vue`, add widths for both fields and render a negative balance with existing `negative` styling plus accessible text `采购缺口 ${Math.abs(value)}`; nonnegative values render as plain numbers.

- [ ] **Step 4: Run inventory UI tests and build**

Run: `npm --prefix frontend test -- --run src/modules/module-config.test.ts src/components/EntityDialog.test.ts`

Expected: PASS.

Run: `npm --prefix frontend run build`

Expected: TypeScript and Vite build complete successfully.

- [ ] **Step 5: Commit the inventory UI slice**

```powershell
git add frontend/src/modules/module-config.ts frontend/src/modules/module-config.test.ts frontend/src/components/EntityDialog.vue frontend/src/components/EntityDialog.test.ts frontend/src/components/ModuleListPage.vue frontend/src/styles.css
git commit -m "功能：库存管理展示供需余量"
```

Before committing, inspect `git diff --cached --name-only` and unstage any unrelated `frontend/src` file accidentally included by the stylesheet glob.

---

### Task 3: Sales Order Live Post-Order Balance

**Files:**
- Modify: `frontend/src/api/workbench.ts`
- Modify: `frontend/src/components/OrderDialog.vue`
- Modify: `frontend/src/components/OrderDialog.test.ts`
- Modify: `frontend/src/components/OrderAllocationDialog.vue`
- Modify: `frontend/src/components/OrderAllocationDialog.test.ts`

**Interfaces:**
- Consumes Task 1's `OrderSku` values.
- Produces pure UI helper `postOrderSupplyDemandBalance(line, index): number` (or equivalently named computed helper) used by the order template.

- [ ] **Step 1: Extend the frontend API type**

Add required numeric properties to `OrderSku`:

```ts
inTransitQuantity: number
pendingDeliveryQuantity: number
supplyDemandBalance: number
purchaseShortageQuantity: number
```

Update existing test factories to default each field to zero.

- [ ] **Step 2: Write failing create/edit order tests**

In `OrderDialog.test.ts`, add:

```ts
it('shows live post-order balance for a new order', async () => {
  // API balance 7; choose SKU and enter quantity 10.
  // Assert actual/transit/pending/balance are shown and result is -3.
  // Assert text contains “下单后采购缺口 3”.
})

it('compensates the existing order remainder while editing', async () => {
  // API balance already includes this order's old remaining 4.
  // Change the same line remaining quantity to 6.
  // Assert preview is apiBalance + 4 - 6, not apiBalance - 6.
})
```

Update `OrderAllocationDialog.test.ts` to expect “未锁定库存” and reject the old “可用库存” heading.

- [ ] **Step 3: Run order UI tests and verify failure**

Run: `npm --prefix frontend test -- --run src/components/OrderDialog.test.ts src/components/OrderAllocationDialog.test.ts`

Expected: FAIL because the summary and copy do not exist.

- [ ] **Step 4: Implement live preview and edit compensation**

Capture each loaded order line's original remainder by SKU before edits. Calculate:

```ts
function postOrderSupplyDemandBalance(line: OrderLine) {
  const sku = skuFor(line)
  if (!sku) return 0
  const draftRemainder = Math.max(Number(line.quantity || 0) - Number(line.shippedQuantity || 0), 0)
  const originalRemainder = editingExistingOrder
    ? originalRemainderByLineId.get(line.id) ?? 0
    : 0
  return sku.supplyDemandBalance + originalRemainder - draftRemainder
}
```

Render actual, transit, pending, current balance, and post-order balance in the existing order inventory cell. When negative, apply `negative` and show `下单后采购缺口 N`; do not add validation errors or disable save. Rename the allocation dialog heading/copy from “可用库存” to “未锁定库存”.

- [ ] **Step 5: Run order tests and build**

Run: `npm --prefix frontend test -- --run src/components/OrderDialog.test.ts src/components/OrderAllocationDialog.test.ts`

Expected: PASS.

Run: `npm --prefix frontend run build`

Expected: PASS.

- [ ] **Step 6: Commit the order UI slice**

```powershell
git add frontend/src/api/workbench.ts frontend/src/components/OrderDialog.vue frontend/src/components/OrderDialog.test.ts frontend/src/components/OrderAllocationDialog.vue frontend/src/components/OrderAllocationDialog.test.ts
git commit -m "功能：订单展示下单后供需余量"
```

---

### Task 4: Procurement Shortage Guidance

**Files:**
- Modify: `frontend/src/components/ManualPurchaseDialog.vue`
- Modify: `frontend/src/components/ManualPurchaseDialog.test.ts`

**Interfaces:**
- Consumes `OrderSku` supply-demand properties from Tasks 1 and 3.
- Produces procurement-only visual guidance; the `ManualPurchaseData.quantity` request remains user-controlled.

- [ ] **Step 1: Write failing procurement guidance tests**

Add a negative-balance product fixture and assert after selection:

```ts
expect(wrapper.text()).toContain('实际库存 2')
expect(wrapper.text()).toContain('在途数量 3')
expect(wrapper.text()).toContain('待交订单数量 10')
expect(wrapper.text()).toContain('供需余量 -5')
expect(wrapper.text()).toContain('建议采购 5')
expect((wrapper.get('input[type="number"]').element as HTMLInputElement).value).toBe('1')
```

Also test a nonnegative fixture does not render “建议采购”.

- [ ] **Step 2: Run procurement UI tests and verify failure**

Run: `npm --prefix frontend test -- --run src/components/ManualPurchaseDialog.test.ts`

Expected: FAIL because no supply-demand summary is rendered.

- [ ] **Step 3: Implement selected-product supply-demand summary**

Below the product selector, render the four metrics from `selectedProduct`. When `supplyDemandBalance < 0`, render a danger hint using `purchaseShortageQuantity`:

```vue
<strong :class="{ negative: selectedProduct.supplyDemandBalance < 0 }">
  供需余量 {{ selectedProduct.supplyDemandBalance }}
</strong>
<small v-if="selectedProduct.purchaseShortageQuantity > 0" class="field-error">
  建议采购 {{ selectedProduct.purchaseShortageQuantity }}
</small>
```

Do not watch the suggestion, do not assign `form.quantity`, and keep MOQ validation unchanged.

- [ ] **Step 4: Run procurement tests and build**

Run: `npm --prefix frontend test -- --run src/components/ManualPurchaseDialog.test.ts`

Expected: PASS.

Run: `npm --prefix frontend run build`

Expected: PASS.

- [ ] **Step 5: Commit the procurement UI slice**

```powershell
git add frontend/src/components/ManualPurchaseDialog.vue frontend/src/components/ManualPurchaseDialog.test.ts
git commit -m "功能：采购展示供需缺口提醒"
```

---

### Task 5: Cross-Module Regression Verification

**Files:**
- Modify only files already listed above if verification exposes a defect.

**Interfaces:**
- Consumes all earlier task deliverables.
- Produces a verified feature with no schema migration and no workflow behavior change.

- [ ] **Step 1: Run the complete backend suite**

Run: `mvn -f backend/app/pom.xml -Dmaven.repo.local=.m2 test`

Expected: BUILD SUCCESS with all tests passing.

- [ ] **Step 2: Run the complete frontend suite**

Run: `npm --prefix frontend test -- --run`

Expected: all Vitest suites pass.

- [ ] **Step 3: Run the production frontend build**

Run: `npm --prefix frontend run build`

Expected: TypeScript check and Vite production build pass.

- [ ] **Step 4: Inspect final scope and formulas**

Run:

```powershell
git diff HEAD~4 --check
git status --short
Select-String -Path frontend\src\modules\module-config.ts,frontend\src\components\EntityDialog.vue,frontend\src\components\OrderAllocationDialog.vue -Pattern '可用库存数量|可用库存'
```

Expected: no whitespace errors; clean working tree after planned commits; no stale user-facing inventory-management/allocation copy. Do not replace legitimate after-sales wording unless it describes `actual - locked` and is in scope.

- [ ] **Step 5: Confirm acceptance cases manually through API/UI fixtures**

Verify these exact cases from automated fixtures or a local seeded environment:

1. `actual=10, transit=4, pending=6` displays balance `8` in inventory, order, and procurement.
2. `actual=0, transit=0, pending=9` displays balance `-9` and purchase shortage `9`.
3. Entering a new order quantity `10` against current balance `7` displays `下单后采购缺口 3` but still permits save.
4. Selecting a purchase product with shortage `5` keeps the entered purchase quantity unchanged.

- [ ] **Step 6: Commit only if verification required fixes**

If verification required fixes, stage each changed path explicitly as listed by `git status --short`, inspect `git diff --cached --name-only`, and commit with `git commit -m "修复：完善供需余量边界处理"`. If no fixes were needed, do not create an empty commit.

