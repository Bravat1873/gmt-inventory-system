# 产品管理子菜单展开状态 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 产品管理子菜单展开后，切换到其他管理页面仍保持展开，只有用户主动操作时才折叠。

**Architecture:** 保留 `App.vue` 中现有的 `productMenuOpen` 本地响应式状态，将其与模块切换解耦。通过 App 组件测试覆盖产品菜单展开、跨模块切换和主动折叠行为。

**Tech Stack:** Vue 3、TypeScript、Vitest、Vue Test Utils。

## Global Constraints

- 切换非产品模块不得修改 `productMenuOpen`。
- 从其他模块进入产品管理时必须展开子菜单。
- 当前已在产品管理时再次点击产品管理，继续切换展开状态。
- 不增加 localStorage 或其他持久化。

---

### Task 1: 产品子菜单跨模块保持展开

**Files:**
- Modify: `frontend/src/App.vue`
- Test: `frontend/src/App.test.ts`

**Interfaces:**
- Consumes: existing `navigateModule(key: ModuleKey)`.
- Produces: product submenu visibility independent from `activeModule` changes.

- [ ] **Step 1: Write the failing navigation test**

In `frontend/src/App.test.ts`, mount `App`, click the product navigation button to expose the subitem, then click the customer navigation button and assert the subitem remains rendered:

```ts
await wrapper.get('[data-module="product"]').trigger('click')
expect(wrapper.text()).toContain('产品编号规则')
await wrapper.get('[data-module="customer"]').trigger('click')
expect(wrapper.text()).toContain('产品编号规则')
```

Also keep or add an assertion that clicking product again while already active can collapse the submenu.

- [ ] **Step 2: Run the focused test and verify RED**

```powershell
npm test -- --run src/App.test.ts
```

Expected: FAIL after navigating to customer because `navigateModule` currently sets `productMenuOpen.value = false` for every non-product module.

- [ ] **Step 3: Implement the minimal state change**

Remove only the non-product assignment:

```ts
productMenuOpen.value = false
```

from the non-product branch of `navigateModule`. Keep the product branch unchanged so entering product opens the submenu and clicking product while already active toggles it.

- [ ] **Step 4: Run verification**

```powershell
npm test -- --run src/App.test.ts
npm run build
```

Expected: all App tests pass and the production build exits 0.

- [ ] **Step 5: Commit**

```powershell
git add frontend/src/App.vue frontend/src/App.test.ts
git commit -m "保持产品子菜单展开状态"
```