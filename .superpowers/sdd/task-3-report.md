# Task 3 报告：按订单分组事务提交并复用库存锁定

## RED

- 基线指定命令先通过：现有 `ImportCommitServiceTest` 9 tests，0 failures，0 errors。
- 先新增订单分组提交 H2 集成测试；生产代码未改时运行指定命令：17 tests，3 failures，5 errors。新增 8 个测试全部因 ORDER 仍走“订单批次必须使用订单分组提交策略”拒绝分支而失败。
- 独立审阅发现三位小数价格会在现有订单总额 `setScale(2)` 处抛错；补充 `12.345` 回归测试并单独运行，1 test，1 error，异常为 `ArithmeticException: Rounding necessary`。

## GREEN

- 新增 `SalesOrderImportCommitService`，按 trim 后外部订单号分组，以源行号生成 `lineNo`，只使用 `_customerId`、`_skuId`、`_normalizedStatus` 构造 `SalesOrderRequest`。
- `ImportCommitService` 将 ORDER 在通用逐行提交前分流；专用服务只接受无错误的 PREVIEW 批次，拒绝已提交批次。
- 每组提交前以 `SELECT ... FOR UPDATE` 重查外部订单号，并沿用生产库已有的 `sales_order.external_order_no` 唯一约束兜底并发竞态。
- 复用 `SalesOrderCommandService.create`：正式订单走既有库存分配，草稿不锁库存；批次内任一组失败时订单、明细、锁定及批次状态整体回滚。
- 导入单价按订单数据库 `DECIMAL(18,2)` 契约使用 `HALF_UP` 规范；`12.345` 回归测试转 GREEN。
- 最终指定命令通过：19 tests，0 failures，0 errors，0 skipped。

测试命令：

`mvn "-Dmaven.repo.local=D:\aagmt\gmt-inventory-system\backend\.m2" -pl app -am "-Dtest=SalesOrderImportCommitServiceTest,ImportCommitServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

## 覆盖与自审

- 覆盖同单号两行合并一单两明细、源行号、结果 detail 与 committedRows。
- 覆盖正式订单库存余额/订单明细锁定及分配流水，覆盖草稿不锁。
- 覆盖客户资金账户、流水、申请三表的行数、金额、余额和版本快照不变。
- 覆盖预览后顺序重复、两个事务越过预查后的唯一约束竞态、批次第二组失败全量回滚、错误批次拒绝及重复提交拒绝。
- 测试仅使用 H2；未连接或写入真实开发数据库，未新增生产数据库迁移。
- 独立复审无 Critical / Important 问题；`git diff --check` 与暂存区差异检查均通过。

## 提交

- `a9e3026e8824325e1be88a379045a045f670e8ca` — 实现销售订单分组事务提交

## 顾虑

- 无阻断顾虑。并发安全依赖现有外部订单号唯一约束作为最终防线，事务内 `FOR UPDATE` 为提交前的提前拒绝检查。
- 未跟踪 `.learnings/` 为工作树既有无关内容，未纳入任务提交。
