# 双产品表全量替换导入实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将指定Excel中的两个产品清单按当前产品编号规则生成最终编号，经重复冲突人工确认后，事务性清理开发环境产品相关业务数据并导入确认后的产品和供应商报价。

**Architecture:** 在现有导入框架中新增`PRODUCT`类型，解析器只负责识别两个工作表和规范化原始字段，独立的产品导入验证器负责匹配当前编号规则、生成编号、标记冲突及供应商校验。提交端使用专用全量替换服务，在管理员权限、开发开关、规则指纹和冲突决策均通过后执行单事务清理与写入；前端产品导入始终停留在预览阶段，直到用户处理冲突并二次确认。

**Tech Stack:** Java 17、Spring Boot 3、JdbcTemplate、Apache POI、Flyway、MySQL 8、Vue 3、TypeScript、Vitest、`@oai/artifact-tool`。

## Global Constraints

- 只解析`GMT库存产品清单`和`贝朗库存产品清单`，忽略`库存数量`。
- Excel产品编号允许为空；无论原表是否填写，最终编号都按提交时确认的当前编号规则生成。
- 缺失文本写`NULL`，销售最小起订量默认`1`，供应商含税价默认`0`，单位使用系统默认值。
- 最终编号重复必须人工选择保留行或跳过；未处理冲突禁止提交。
- 全量替换只允许管理员，并受开发环境配置开关保护。
- 清理与导入必须在单个事务内完成，任何失败都完整回滚。
- 保留客户、供应商、用户、权限和产品编号规则；第三个库存工作表本次不导入。
- 不覆盖用户原始Excel文件，生成带更新说明的新文件用于最终导入。

---

### Task 1: 新增产品工作簿解析器

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/importing/ImportType.java`
- Modify: `backend/app/src/main/java/com/internalops/importing/ExcelImportParser.java`
- Create: `backend/app/src/main/java/com/internalops/importing/ProductWorkbookExcelParser.java`
- Create: `backend/app/src/test/java/com/internalops/importing/ProductWorkbookExcelParserTest.java`
- Test fixture: `backend/app/src/test/resources/import/product-workbook-two-sheets.xlsx`

**Interfaces:**
- Produces: `ImportType.PRODUCT`。
- Produces: `ProductWorkbookExcelParser.parse(Workbook): List<ParsedImportRow>`，每行数据键固定为`sourceProductCode`、`productCategory`、`materialType`、`customerMaterialCode`、`brand`、`series`、`bodyColor`、`lockType`、`connectivity`、`salesChannel`、`operatingEntity`、`language`、`codeSuffix`、`model`、`materialSpecification`、`productConfiguration`、`salesMinimumOrderQuantity`、`supplierName`、`supplierTaxPrice`。

- [ ] **Step 1: 编写失败的双工作表解析测试**

```java
@Test
void parsesOnlyTheTwoProductSheetsAndAllowsBlankSourceProductCode() throws Exception {
    try (InputStream input = getClass().getResourceAsStream("/import/product-workbook-two-sheets.xlsx")) {
        List<ParsedImportRow> rows = new ExcelImportParser().parse(ImportType.PRODUCT, input);
        assertThat(rows).hasSize(17);
        assertThat(rows).extracting(ParsedImportRow::sheetName)
                .containsOnly("GMT库存产品清单", "贝朗库存产品清单");
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.data().get("sourceProductCode")).isEqualTo("");
            assertThat(row.data().get("brand")).isNotNull();
        });
    }
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -pl app -am -Dtest=ProductWorkbookExcelParserTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，`ImportType.PRODUCT`或`ProductWorkbookExcelParser`不存在。

- [ ] **Step 3: 实现固定工作表与标题别名解析**

```java
public final class ProductWorkbookExcelParser {
    private static final List<String> SHEETS = List.of("GMT库存产品清单", "贝朗库存产品清单");

    public List<ParsedImportRow> parse(Workbook workbook) {
        List<ParsedImportRow> result = new ArrayList<>();
        for (String sheetName : SHEETS) {
            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet == null) throw new IllegalArgumentException("缺少产品工作表：" + sheetName);
            Map<String,Integer> headers = normalizedHeaders(sheet.getRow(0));
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Map<String,Object> data = readProductRow(sheet.getRow(index), headers);
                if (!isBusinessRow(data)) continue;
                result.add(new ParsedImportRow(sheetName, index + 1, ImportRowStatus.VALID, data, null));
            }
        }
        return result;
    }
}
```

标题规范化必须去除空白、换行、全角括号，并将`型号（系列号+第几代）`、拆列后的`型号`统一为`model`，将`物料规格（不用写，会自动生成）`统一为`materialSpecification`。`isBusinessRow`不能依赖产品编号，必须检查客户料号、型号、规格、配置、供应商和编码要素中至少一项存在。

- [ ] **Step 4: 运行解析测试**

Run: `mvn -pl app -am -Dtest=ProductWorkbookExcelParserTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS，解析17行且不包含`库存数量`。

- [ ] **Step 5: 提交解析器**

```bash
git add backend/app/src/main/java/com/internalops/importing/ImportType.java backend/app/src/main/java/com/internalops/importing/ExcelImportParser.java backend/app/src/main/java/com/internalops/importing/ProductWorkbookExcelParser.java backend/app/src/test/java/com/internalops/importing/ProductWorkbookExcelParserTest.java backend/app/src/test/resources/import/product-workbook-two-sheets.xlsx
git commit -m "增加双产品表导入解析"
```

### Task 2: 按当前编号规则解析并生成最终产品编号

**Files:**
- Create: `backend/app/src/main/java/com/internalops/importing/ProductImportCodeResolver.java`
- Create: `backend/app/src/main/java/com/internalops/importing/ProductImportValidationService.java`
- Modify: `backend/app/src/main/java/com/internalops/importing/ImportValidationService.java`
- Modify: `backend/app/src/main/java/com/internalops/importing/ImportPreviewService.java`
- Test: `backend/app/src/test/java/com/internalops/importing/ProductImportValidationServiceTest.java`
- Test schema: `backend/app/src/test/resources/product-import-schema.sql`

**Interfaces:**
- Consumes: Task 1标准化字段。
- Produces: `ProductImportCodeResolver.resolve(Map<String,Object>): ResolvedProductCode`。
- Produces: `ResolvedProductCode(String productCode, ProductCodeSelection selection, String ruleFingerprint)`。
- Produces: 预览字段`productCode`、`_ruleFingerprint`、`_conflict`、`_conflictGroup`、`_conflictAction`，并保留`sourceProductCode`供显示。

- [ ] **Step 1: 编写失败的编号与冲突测试**

```java
@Test
void ignoresSourceCodeAndGeneratesFromCurrentRules() {
    Map<String,Object> data = row("错误的原表编号", "GMT", "T7", "瀑布银", "10", "Wifi", "平面", "深圳", "中文版");
    ParsedImportRow result = service.validate(3, "GMT库存产品清单", 9, data);
    assertThat(result.data().get("sourceProductCode")).isEqualTo("错误的原表编号");
    assertThat(result.data().get("productCode")).isEqualTo("G_T7PBY10WPSC");
    assertThat(result.data().get("_ruleFingerprint")).isNotNull();
}

@Test
void marksGeneratedDuplicatesAsUnresolvedConflicts() {
    List<ParsedImportRow> rows = service.validateAll(List.of(rowAt(9), rowAt(10)));
    assertThat(rows).allSatisfy(row -> {
        assertThat(row.data().get("_conflict")).isEqualTo(true);
        assertThat(row.data().get("_conflictAction")).isEqualTo("UNRESOLVED");
    });
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -pl app -am -Dtest=ProductImportValidationServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，产品规则解析服务不存在。

- [ ] **Step 3: 实现规则文本匹配、编号生成和规则指纹**

```java
public ResolvedProductCode resolve(Map<String,Object> data) {
    ProductCodeSelection selection = new ProductCodeSelection(
        ruleId(BRAND, text(data, "brand")), ruleId(SERIES, text(data, "series")),
        ruleId(BODY_COLOR, text(data, "bodyColor")), ruleId(LOCK_TYPE, text(data, "lockType")),
        ruleId(CONNECTIVITY, text(data, "connectivity")), ruleId(SALES_CHANNEL, text(data, "salesChannel")),
        ruleId(OPERATING_ENTITY, text(data, "operatingEntity")), ruleId(LANGUAGE, text(data, "language"))
    );
    String base = generator.generate(selection);
    String code = generator.appendSuffix(base, text(data, "codeSuffix"));
    return new ResolvedProductCode(code, selection, fingerprint(selection));
}
```

匹配时同时接受规则`code`、`display_name`及Excel中`编码_显示名`形式；例如`W_Wifi`、`Wifi`、`W`均应匹配同一启用规则。无法唯一匹配时返回明确行错误。

- [ ] **Step 4: 实现批次级最终编号冲突分组**

```java
Map<String,List<ParsedImportRow>> groups = validRows.stream()
    .collect(groupingBy(row -> text(row.data(), "productCode").toUpperCase(Locale.ROOT), LinkedHashMap::new, toList()));
```

只对组大小大于1的行写入相同`_conflictGroup`，并设置`_conflictAction="UNRESOLVED"`。非冲突行设置`_conflict=false`。

- [ ] **Step 5: 运行产品验证及现有导入测试**

Run: `mvn -pl app -am -Dtest=ProductImportValidationServiceTest,ImportValidationServiceTest,ImportPreviewApiTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

- [ ] **Step 6: 提交编号验证**

```bash
git add backend/app/src/main/java/com/internalops/importing/ProductImportCodeResolver.java backend/app/src/main/java/com/internalops/importing/ProductImportValidationService.java backend/app/src/main/java/com/internalops/importing/ImportValidationService.java backend/app/src/main/java/com/internalops/importing/ImportPreviewService.java backend/app/src/test/java/com/internalops/importing/ProductImportValidationServiceTest.java backend/app/src/test/resources/product-import-schema.sql
git commit -m "按当前规则生成导入产品编号"
```

### Task 3: 实现管理员专用的事务性产品全量替换

**Files:**
- Create: `backend/app/src/main/java/com/internalops/importing/ProductReplaceImportService.java`
- Modify: `backend/app/src/main/java/com/internalops/importing/ImportCommitRequest.java`
- Modify: `backend/app/src/main/java/com/internalops/importing/ImportCommitService.java`
- Modify: `backend/app/src/main/resources/application.yaml`
- Test: `backend/app/src/test/java/com/internalops/importing/ProductReplaceImportServiceTest.java`
- Test: `backend/app/src/test/java/com/internalops/importing/ProductReplaceImportApiTest.java`
- Test schema: `backend/app/src/test/resources/product-replace-import-schema.sql`

**Interfaces:**
- Consumes: `ImportCommitRequest.productConflictActions(): Map<Long, ProductConflictAction>`。
- Produces: `ProductConflictAction`枚举`KEEP`、`SKIP`。
- Produces: `ProductReplaceImportService.replace(ImportBatchView, Map<Long,ProductConflictAction>): ImportBatchView`。
- Configuration: `app.import.product-replace-enabled: ${PRODUCT_REPLACE_IMPORT_ENABLED:false}`。

- [ ] **Step 1: 编写失败的权限、开关、冲突和回滚测试**

```java
@Test
void rejectsReplaceWhenFeatureFlagIsDisabled() {
    assertThatThrownBy(() -> service.replace(batch(), Map.of()))
        .hasMessageContaining("未启用产品全量替换");
}

@Test
void rollsBackDeletesWhenOneProductInsertFails() {
    assertThatThrownBy(() -> enabledService.replace(batchWithInvalidSupplier(), decisions()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sku", Integer.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_order", Integer.class)).isEqualTo(1);
}
```

API测试必须验证`ADMIN`成功，`FINANCE`和`USER`均返回403或业务拒绝。

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -pl app -am -Dtest=ProductReplaceImportServiceTest,ProductReplaceImportApiTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，替换服务和提交字段不存在。

- [ ] **Step 3: 实现提交前防护与冲突决策校验**

```java
if (!replaceEnabled) throw new IllegalStateException("当前环境未启用产品全量替换");
if (CurrentUser.required().role() != UserRole.ADMIN) throw new IllegalStateException("仅管理员可执行产品全量替换");
validateRuleFingerprint(batch.rows());
List<ImportRowView> selected = resolveConflictActions(batch.rows(), actions);
if (selected.isEmpty()) throw new IllegalArgumentException("没有可导入的产品");
```

每个冲突组必须恰好0或1行`KEEP`；存在`UNRESOLVED`、同组多行`KEEP`或请求引用非本批次行时拒绝提交。

- [ ] **Step 4: 实现事务性依赖清理**

在`@Transactional`方法中按实际外键顺序删除产品相关交易数据。实现前先用以下查询核对当前MySQL依赖，并把测试中缺失的引用表补入删除清单：

```sql
SELECT TABLE_NAME,COLUMN_NAME,REFERENCED_TABLE_NAME
FROM information_schema.KEY_COLUMN_USAGE
WHERE REFERENCED_TABLE_SCHEMA=DATABASE()
  AND REFERENCED_TABLE_NAME IN ('sku','sales_order','sales_order_item','purchase_order','purchase_order_item','procurement_suggestion','procurement_suggestion_item');
```

清理服务必须使用显式表名和`JdbcTemplate.update("DELETE FROM ...")`，不得使用`FOREIGN_KEY_CHECKS=0`。保留`customer`、`supplier`、`sys_user`、`product_code_rule`；删除顺序至少覆盖售后退款/明细、资金中订单关联、收付款与发票、收货明细、短缺覆盖、库存流水与余额、成本历史、产品图片、供应商配置、采购与订单明细/主表、采购建议明细/主表，最后删除`sku`。

- [ ] **Step 5: 实现产品与供应商报价写入**

```java
long skuId = insertSku(row.data(), resolved.selection(), resolved.productCode());
if (!text(row.data(), "supplierName").isBlank()) {
    long supplierId = requiredSupplierId(text(row.data(), "supplierName"));
    jdbc.update("INSERT INTO sku_supplier_config(sku_id,supplier_id,purchase_price,moq,lead_time_days,enabled) VALUES(?,?,?,?,0,TRUE)",
        skuId, supplierId, decimalOrZero(row.data().get("supplierTaxPrice")), 1);
}
```

`sku`写入现有字段：`product_code`、`sku_code`、规则ID、`model`、`product_name`、`product_category`、`product_type`、`material_type`、`customer_material_code`、颜色、锁体、配置、规格、后缀和销售最小起订量。文本缺失用`NULL`，约束字段使用设计中的默认值。

- [ ] **Step 6: 运行替换服务与导入回归测试**

Run: `mvn -pl app -am -Dtest=ProductReplaceImportServiceTest,ProductReplaceImportApiTest,ImportCommitApiTest,ImportCommitServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS，且回滚测试证明旧数据未被部分删除。

- [ ] **Step 7: 提交事务替换实现**

```bash
git add backend/app/src/main/java/com/internalops/importing/ProductReplaceImportService.java backend/app/src/main/java/com/internalops/importing/ImportCommitRequest.java backend/app/src/main/java/com/internalops/importing/ImportCommitService.java backend/app/src/main/resources/application.yaml backend/app/src/test/java/com/internalops/importing/ProductReplaceImportServiceTest.java backend/app/src/test/java/com/internalops/importing/ProductReplaceImportApiTest.java backend/app/src/test/resources/product-replace-import-schema.sql
git commit -m "增加产品全量替换事务导入"
```

### Task 4: 增加产品预览、冲突人工确认和二次确认界面

**Files:**
- Modify: `frontend/src/api/imports.ts`
- Modify: `frontend/src/api/imports.test.ts`
- Modify: `frontend/src/components/ImportPanel.vue`
- Modify: `frontend/src/components/ImportPanel.test.ts`
- Modify: `frontend/src/modules/module-config.ts`

**Interfaces:**
- Extends: `ImportType`增加`PRODUCT`。
- Produces: `commitProductReplace(id:number, actions:Record<number,'KEEP'|'SKIP'>): Promise<ImportBatch>`。
- Consumes: 后端行字段`productCode`、`sourceProductCode`、`_conflictGroup`、`_conflictAction`。

- [ ] **Step 1: 编写失败的产品预览与冲突测试**

```ts
it('does not auto-commit product imports and requires every duplicate group to be resolved', async () => {
  previewImport.mockResolvedValue(productConflictBatch)
  const wrapper = mount(ImportPanel, { props: { type: 'PRODUCT', title: '导入产品' } })
  await selectFile(wrapper, '产品.xlsx')
  expect(commitImport).not.toHaveBeenCalled()
  expect(wrapper.get('[data-test="product-conflict-keep-21"]').exists()).toBe(true)
  expect(wrapper.get('[data-test="commit-product-replace"]').attributes('disabled')).toBeDefined()
})

it('sends explicit keep and skip decisions after irreversible confirmation', async () => {
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  // select KEEP for row 21 and SKIP for row 22, then commit
  expect(commitProductReplace).toHaveBeenCalledWith(8, { 21: 'KEEP', 22: 'SKIP' })
})
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `npm test -- --run src/components/ImportPanel.test.ts src/api/imports.test.ts`

Expected: FAIL，`PRODUCT`流程和冲突控件不存在。

- [ ] **Step 3: 实现产品专用API类型和提交请求**

```ts
export type ImportType = 'CUSTOMER' | 'COST' | 'INVENTORY' | 'SUPPLIER' | 'PRODUCT'
export type ProductConflictAction = 'KEEP' | 'SKIP'

export function commitProductReplace(id:number, actions:Record<number,ProductConflictAction>) {
  return request<ImportBatch>(`/api/imports/${id}/commit`, {
    method:'POST', headers:{'Content-Type':'application/json'},
    body:JSON.stringify({ productConflictActions: actions })
  })
}
```

- [ ] **Step 4: 实现产品预览和人工冲突选择**

产品预览表固定显示状态、来源、原表参考编号、系统计算编号、型号、配置、供应商、价格和错误。冲突组显示单选式“保留此行”和逐行“跳过”，选择`KEEP`时自动把同组其余行设为`SKIP`。普通有效行默认`KEEP`；错误行不可保留。未处理冲突、存在错误行或没有保留行时禁用提交。

- [ ] **Step 5: 实现不可逆二次确认**

```ts
if (!window.confirm(`全量替换将删除现有产品及相关订单、库存和采购开发数据，并导入 ${keepCount} 个产品。确定继续吗？`)) return
await commitProductReplace(previewBatch.value.batchId, productActions.value)
```

遮罩不关闭弹窗；关闭和取消按钮只退出预览，不提交。

- [ ] **Step 6: 运行前端测试和构建**

Run: `npm test -- --run src/components/ImportPanel.test.ts src/api/imports.test.ts src/App.test.ts`

Expected: PASS。

Run: `npm run build`

Expected: PASS。

- [ ] **Step 7: 提交前端流程**

```bash
git add frontend/src/api/imports.ts frontend/src/api/imports.test.ts frontend/src/components/ImportPanel.vue frontend/src/components/ImportPanel.test.ts frontend/src/modules/module-config.ts
git commit -m "增加产品导入冲突确认界面"
```

### Task 5: 更新Excel导入说明并生成受控导入文件

**Files:**
- Source: `C:/Users/linfu/Documents/xwechat_files/wxid_3npmpu5sgt7i12_87e6/msg/file/2026-08/吉门第库存汇总表20260814(1)(1).xlsx`
- Create: `outputs/product-replace-import/吉门第库存汇总表20260814-产品导入版.xlsx`
- Create during work: one auditable `.mjs` workbook edit script in a writable temporary directory; remove or leave untracked after output verification。

**Interfaces:**
- Produces: 保持17条产品原始值不变、仅更新两个产品工作表说明的新Excel文件。
- Consumes: Task 1解析器和Task 4上传入口。

- [ ] **Step 1: 读取表格工具必需文档并标记一次编辑操作**

Run bundled Node with:

```text
Get-Content -Raw C:/Users/linfu/.codex/plugins/cache/openai-primary-runtime/spreadsheets/26.813.12317/skills/spreadsheets/artifact_tool_docs/API_QUICK_START.md
Get-Content -Raw C:/Users/linfu/.codex/plugins/cache/openai-primary-runtime/spreadsheets/26.813.12317/skills/spreadsheets/style_guidelines.md
node container_tools/mark_artifact_operation_started.mjs --operation-kind edit --expected-output-count 1 --output-format xlsx
```

Expected: 标记命令成功；只执行一次。

- [ ] **Step 2: 用`@oai/artifact-tool`导入源工作簿并检查两个工作表**

脚本必须读取两个工作表的标题和说明区域，记录17条产品行的关键字段快照；不得使用`openpyxl`或覆盖原始文件。

- [ ] **Step 3: 更新两个工作表的说明文字**

在现有说明区域加入：

```text
产品编号可留空；系统导入时按当前产品编号规则重新计算，已填写编号仅供参考。
若多行计算出相同产品编号，必须在导入预览中人工选择保留行或跳过。
```

匹配原表字体、边框、填充和换行，不改变正式产品数据。

- [ ] **Step 4: 导出、检查关键范围并渲染两个工作表**

输出到`outputs/product-replace-import/吉门第库存汇总表20260814-产品导入版.xlsx`。使用`workbook.inspect`检查说明和代表性数据行，扫描公式错误；分别渲染两个产品工作表并确认文字未截断、17条产品值未变化。

- [ ] **Step 5: 使用后端解析测试验证新文件**

Run: `mvn -pl app -am -Dtest=ProductWorkbookExcelParserTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS，仍解析17条。

### Task 6: 在本地开发数据库演练并执行全量替换

**Files:**
- No source code files。
- Input: `outputs/product-replace-import/吉门第库存汇总表20260814-产品导入版.xlsx`

**Interfaces:**
- Consumes: Tasks 1–5完整产品导入流程。
- Produces: 本地开发数据库中按当前规则生成的产品和供应商报价。

- [ ] **Step 1: 备份并确认目标数据库是开发环境**

Run: `docker compose ps` and inspect backend environment/database host。导出数据库备份到明确文件名，不删除现有Docker卷。

Expected: 后端连接本地开发MySQL；备份命令成功且备份文件非空。

- [ ] **Step 2: 启用仅本地产品替换开关并重启后端**

Set `PRODUCT_REPLACE_IMPORT_ENABLED=true` only in local development compose/environment, then run:

```text
docker compose up -d --build backend frontend
```

Expected: MySQL容器保持原实例，前后端启动成功。

- [ ] **Step 3: 通过真实导入接口生成预览**

以管理员会话上传新Excel，检查预览：总有效产品数、生成编号、错误数、冲突组和供应商匹配结果。

Expected: 若当前规则完整且无计算重复，应显示17条可保留产品；若存在规则缺失或计算重复，停止提交并把真实预览结果交给用户处理，不能自行猜测。

- [ ] **Step 4: 在无未处理冲突后执行二次确认和提交**

Expected: 提交成功；返回创建数、跳过数和清理结果。若任一行失败，验证旧数据仍存在并修正根因后重新预览。

- [ ] **Step 5: 核对导入结果**

Run read-only SQL checks:

```sql
SELECT COUNT(*) AS product_count, COUNT(DISTINCT UPPER(product_code)) AS unique_code_count FROM sku;
SELECT product_code,COUNT(*) FROM sku GROUP BY UPPER(product_code) HAVING COUNT(*)>1;
SELECT COUNT(*) FROM sku_supplier_config;
SELECT s.product_code,s.model,s.sales_minimum_order_quantity,sp.supplier_name,c.purchase_price
FROM sku s LEFT JOIN sku_supplier_config c ON c.sku_id=s.id
LEFT JOIN supplier sp ON sp.id=c.supplier_id ORDER BY s.product_code;
```

Expected: 产品数等于最终保留行数，唯一编号数相等，无重复编号；供应商与价格与预览一致。

### Task 7: 全量回归、文档记录与提交

**Files:**
- Modify: `README.md`（补充产品全量替换开关和开发环境限制）
- Modify: `docs/superpowers/plans/2026-08-14-product-workbook-replace-import.md`（勾选完成项）

- [ ] **Step 1: 运行后端相关回归**

Run:

```text
mvn -pl app -am -Dtest=ProductWorkbookExcelParserTest,ProductImportValidationServiceTest,ProductReplaceImportServiceTest,ProductReplaceImportApiTest,ImportValidationServiceTest,ImportPreviewApiTest,ImportCommitApiTest,ImportCommitServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 全部PASS。

- [ ] **Step 2: 运行前端相关回归和生产构建**

Run:

```text
npm test -- --run src/components/ImportPanel.test.ts src/api/imports.test.ts src/App.test.ts
npm run build
```

Expected: 全部PASS，生产构建成功。

- [ ] **Step 3: 检查工作区和数据库证据**

Run: `git diff --check`、`git status --short`，并保留产品数量、唯一编号数量、供应商报价数量及HTTP健康检查结果。不得加入现有未跟踪`data/`。

- [ ] **Step 4: 提交最终文档调整**

```bash
git add README.md docs/superpowers/plans/2026-08-14-product-workbook-replace-import.md
git commit -m "补充产品全量替换导入说明"
```

- [ ] **Step 5: 发布边界**

默认只完成本地开发数据库导入。推送GitHub或更新`192.168.60.169`服务器必须沿用用户明确授权；服务器部署时保持`PRODUCT_REPLACE_IMPORT_ENABLED=false`，除非用户再次明确要求在服务器执行不可逆全量替换。
