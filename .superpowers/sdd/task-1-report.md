# Task 1 报告：订单工作簿解析与行级校验

## RED

- 先新增 `SalesOrderExcelParserTest` 与 `SalesOrderImportValidationServiceTest`。
- 运行指定 Maven 命令，预期失败：`ImportType.ORDER` 与 `SalesOrderImportValidationService` 尚不存在（3 个编译错误）。

## GREEN

- 增加 ORDER 导入类型、订单工作簿解析器及行级校验服务。
- 解析器输出 21 个指定键；校验器完成客户/产品唯一已启用匹配、状态映射、日期/类型/数量/价格/MOQ/客户料号检查，并写入 resolved IDs。
- `ImportValidationService` 接入 ORDER 行校验。
- 为保持新增枚举后的现有代码可编译，按范围决策在 `ImportCommitService` 增加 ORDER 的拒绝性保护分支；不实现订单提交。

## 验证

测试命令：

`mvn "-Dmaven.repo.local=D:\aagmt\gmt-inventory-system\backend\.m2" -pl app -am "-Dtest=SalesOrderExcelParserTest,SalesOrderImportValidationServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

结果：5 tests，0 failures，0 errors。

自审：`git diff --check` 通过。

## 提交

- 待提交。

## 顾虑

- ORDER 的跨行一致性、订单分组及提交策略留给后续 Task 2/Task 3；当前提交服务明确拒绝 ORDER 以防止绕过这些步骤。
