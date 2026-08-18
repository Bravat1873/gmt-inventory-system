# 订单库存分配卡片遮挡修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让“手动分配库存”弹窗在任意产品数量下都完整显示每张产品卡片及黄色分配提示，并由列表区域承担滚动。

**Architecture:** 保留现有 Vue 模板和接口，仅修正 Flex 子项的尺寸策略。使用源文件级布局回归测试锁定 `flex: 0 0 auto`，再通过 Vitest、生产构建和浏览器进行分层验证。

**Tech Stack:** Vue 3、TypeScript、CSS Flexbox、Vitest、Playwright

## Global Constraints

- 不修改订单、库存或锁定数量计算。
- 不调整弹窗字段内容或数据接口。
- 不重构其他订单页面。
- 产品较多时由 `.allocation-product-panels` 纵向滚动。

---

### Task 1: 防止库存分配产品卡片被压缩

**Files:**
- Create: `frontend/src/components/OrderAllocationDialog.layout.test.ts`
- Modify: `frontend/src/styles.css`（`.allocation-product-panel` 规则）

**Interfaces:**
- Consumes: `OrderAllocationDialog.vue` 已有的 `.allocation-product-panels`、`.allocation-product-panel` 和 `.allocation-target-field` 类名。
- Produces: `.allocation-product-panel { flex: 0 0 auto; }` 布局保证，供任意数量的订单明细共同使用。

- [ ] **Step 1: 写入失败的布局回归测试**

```ts
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, it } from 'vitest'

it('keeps every allocation product panel at its content height and scrolls the list', () => {
  const styles = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8')
  const listStyles = styles.match(/\.allocation-product-panels\s*\{([^}]*)\}/s)?.[1] ?? ''
  const panelStyles = styles.match(/\.allocation-product-panel\s*\{([^}]*)\}/s)?.[1] ?? ''

  expect(listStyles).toMatch(/overflow:\s*auto/)
  expect(panelStyles).toMatch(/flex:\s*0\s+0\s+auto/)
})
```

- [ ] **Step 2: 运行测试并确认因缺少不可收缩规则而失败**

Run: `cd frontend && npm test -- OrderAllocationDialog.layout.test.ts`

Expected: FAIL，`panelStyles` 不匹配 `/flex:\s*0\s+0\s+auto/`。

- [ ] **Step 3: 添加最小 CSS 修复**

将 `frontend/src/styles.css` 中的产品卡片规则改为：

```css
.allocation-product-panel { display:grid; grid-template-columns:1fr 220px; flex:0 0 auto; overflow:hidden; border:1px solid #cfd4d9; border-radius:6px; background:#fff; }
```

- [ ] **Step 4: 运行目标测试并确认通过**

Run: `cd frontend && npm test -- OrderAllocationDialog.layout.test.ts`

Expected: PASS，1 个测试通过。

- [ ] **Step 5: 运行相关组件测试**

Run: `cd frontend && npm test -- OrderAllocationDialog.test.ts OrderAllocationDialog.layout.test.ts`

Expected: PASS，弹窗提交行为和布局规则均通过。

- [ ] **Step 6: 运行全量前端测试和生产构建**

Run: `cd frontend && npm test`

Expected: 所有 Vitest 测试通过。

Run: `cd frontend && npm run build`

Expected: `vue-tsc -b && vite build` 退出码为 0。

- [ ] **Step 7: 浏览器验证少量和多量产品订单**

打开本地订单管理页面，分别进入包含 3 条和超过 7 条明细的订单，点击“分配库存”。验证：

1. 每张卡片均显示标题、五个库存指标、黄色分配区域、输入框和“可分配 0 至 N”。
2. 多量产品场景通过中间列表纵向滚动查看，不压缩产品卡片。
3. 弹窗底部“取消”和“确认分配”始终可见。

- [ ] **Step 8: 提交修复**

```bash
git add frontend/src/components/OrderAllocationDialog.layout.test.ts frontend/src/styles.css
git commit -m "修复库存分配卡片内容被遮挡"
```
