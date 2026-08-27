# 三角色财务只读权限 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 以 `ADMIN`、`FINANCE`、`USER` 三种单角色限制用户管理和财务写操作，同时保留所有登录用户的查看权限。

**Architecture:** 角色能力集中在 `UserRole`，控制器在执行资金、发票及复核写操作前校验能力；Vue 组件接收当前角色并只渲染该角色可用的操作按钮。接口校验是安全边界，前端控制仅用于避免错误操作。

**Tech Stack:** Java 17, Spring Boot, JUnit 5, Vue 3, TypeScript, Vitest。

## Global Constraints

- 仅使用 `ADMIN`、`FINANCE`、`USER`，不新增数据库迁移。
- 不修改 `sys_user`、订单、采购、款项或发票业务数据。
- `ADMIN` 保留全部操作。
- `FINANCE` 与 `USER` 对用户管理均只读。
- `FINANCE` 在财务管理中只可查看，所有资金、发票和复核写接口返回 HTTP 403；`ADMIN` 和 `USER` 保留原有财务写操作。

### Task 1: 后端财务写操作授权

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/auth/UserRole.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/FinanceController.java`
- Modify: `backend/app/src/test/java/com/internalops/workbench/FinanceReviewApiTest.java`
- Modify: `backend/app/src/test/java/com/internalops/workbench/FinanceInvoiceApiTest.java`

**Interfaces:** `UserRole.canManageUsers()` 仅在 `ADMIN` 返回 `true`；`UserRole.canWriteFinance()` 仅对 `FINANCE` 返回 `false`；财务角色对财务写接口收到 `403` 和“无此操作权限”。

- [ ] **Step 1: Write the failing test**

```java
@Test
void financeUserCannotReviewReceipt() throws Exception {
    mvc.perform(post("/api/finance/orders/receipts/1/review")
            .cookie(login("finance", "123"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"approved\":true}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("无此操作权限"));
}
```

Add an equivalent invoice-save test for `POST /api/finance/SALES/1/invoices`, while keeping an administrator success case.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl app -Dtest=FinanceReviewApiTest,FinanceInvoiceApiTest test`

Expected: FAIL because a finance user can currently write finance records.

- [ ] **Step 3: Write minimal implementation**

```java
public boolean canWriteFinance() { return this != FINANCE; }

private void requireFinanceWrite() {
    if (!CurrentUser.required().role().canWriteFinance()) {
        throw new AccessDeniedException("无此操作权限");
    }
}
```

Call the guard from receipt registration, payment/receipt review, invoice review, invoice create and delete endpoints. Convert `AccessDeniedException` to HTTP 403. Leave all finance query endpoints open to logged-in users.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl app -Dtest=FinanceReviewApiTest,FinanceInvoiceApiTest test`

Expected: PASS. Finance users receive 403 and administrator workflows remain valid.

- [ ] **Step 5: Commit**

```powershell
git add backend/app/src/main/java/com/internalops/auth/UserRole.java backend/app/src/main/java/com/internalops/workbench/FinanceController.java backend/app/src/test/java/com/internalops/workbench/FinanceReviewApiTest.java backend/app/src/test/java/com/internalops/workbench/FinanceInvoiceApiTest.java
git commit -m "限制财务角色执行财务写操作"
```

### Task 2: 前端用户与财务列表操作控制

**Files:**
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/components/ModuleListPage.vue`
- Modify: `frontend/src/components/ModuleListPage.test.ts`

**Interfaces:** `ModuleListPage` uses its existing `currentUserRole` prop. Finance rows show only `[data-test="view-details"]` to `FINANCE`; `ADMIN` keeps existing finance actions. User creation and edit controls exist only for `ADMIN`.

- [ ] **Step 1: Write the failing test**

```ts
it('shows only view action in finance management for finance users', async () => {
  loadModule.mockResolvedValue({ items: [{ id: 1, cashDirection: 'RECEIVABLE', outstandingAmount: 120, pendingReviewCount: 1 }], total: 1, page: 1, pageSize: 10, totalPages: 1 })
  const wrapper = mount(ModuleListPage, { props: { module: financeModule, currentUserRole: 'FINANCE' } })
  await flushPromises()
  expect(wrapper.get('[data-test="view-details"]').text()).toBe('查看')
  expect(wrapper.find('[data-test="finance-receipt"]').exists()).toBe(false)
  expect(wrapper.find('[data-test="invoice"]').exists()).toBe(false)
  expect(wrapper.text()).not.toContain('复核')
})
```

Add `FINANCE` and `USER` user-management assertions for absent primary and edit buttons.

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- --run src/components/ModuleListPage.test.ts`

Expected: FAIL because finance users currently see finance write actions.

- [ ] **Step 3: Write minimal implementation**

```ts
const canManageFinance = computed(() => props.currentUserRole === 'ADMIN')
```

Gate finance registration, invoice, and review buttons using `canManageFinance`. Gate user primary/import/edit controls to `ADMIN`; retain matching `App.vue` handler checks to prevent direct event invocation.

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- --run src/components/ModuleListPage.test.ts src/App.test.ts; npm run build`

Expected: PASS and build completes with no TypeScript errors.

- [ ] **Step 5: Commit**

```powershell
git add frontend/src/App.vue frontend/src/components/ModuleListPage.vue frontend/src/components/ModuleListPage.test.ts
git commit -m "财务管理改为财务角色只读"
```

### Task 3: 本地部署验证

**Files:** No code changes.

- [ ] **Step 1: Rebuild local services**

Run: `docker compose up -d --build backend frontend`

Expected: Both services report `Up`.

- [ ] **Step 2: Verify health and data scope**

Run: `Invoke-WebRequest -UseBasicParsing http://localhost/ | Select-Object -ExpandProperty StatusCode`

Expected: `200`. Do not run any data-update SQL and do not create a Flyway migration.
