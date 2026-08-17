# Product Import Generated Code and Specification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure product Excel imports ignore source product numbers and material specifications, generate both from system rules, and repair the current 17 products without changing inventory or supplier relationships.

**Architecture:** Introduce a focused `MaterialSpecificationGenerator` beside `ProductCodeGenerator`, backed by `product_code_rule.display_name`. Inject it into both manual product commands and product replacement imports so every write path shares one implementation. Keep inventory and supplier data untouched while recalculating only `sku.configuration` for existing products.

**Tech Stack:** Java 17, Spring Boot, JdbcTemplate, JUnit 5, AssertJ, Apache POI, Maven, MySQL 8, Docker Compose.

## Global Constraints

- Product number is generated from brand, series, body color, lock type, connectivity, sales channel, operating entity, language, and optional suffix.
- Material specification is generated as `品牌 / 型号 / 物料颜色 / 锁体类型 / 语言`, omitting blank segments.
- Excel source values for product number and material specification never participate in preview validation or persistence.
- The current 17 product IDs, 17 inventory balances, inventory quantities, inventory suppliers, supplier configurations, and purchase prices must remain unchanged during data repair.
- The third worksheet named `库存数量` remains outside product import scope.

---

### Task 1: Shared Material Specification Generator

**Files:**
- Create: `backend/app/src/main/java/com/internalops/productcode/MaterialSpecificationGenerator.java`
- Create: `backend/app/src/test/java/com/internalops/productcode/MaterialSpecificationGeneratorTest.java`

**Interfaces:**
- Consumes: `JdbcTemplate` and rule IDs from `product_code_rule`.
- Produces: `String MaterialSpecificationGenerator.generate(Long brandRuleId, String model, Long bodyColorRuleId, Long lockTypeRuleId, Long languageRuleId)`.

- [ ] **Step 1: Write the failing generator test**

```java
@SpringBootTest
@Sql(scripts = {"/import-schema.sql", "/product-replace-import-schema.sql"})
class MaterialSpecificationGeneratorTest {
    @Autowired MaterialSpecificationGenerator generator;

    @Test
    void joinsRuleDisplayNamesAndModelInBusinessOrder() {
        assertThat(generator.generate(1L, "P90", 3L, 4L, 8L))
                .isEqualTo("BRAVAT / P90 / 宇宙黑 / 7068 / 中文版");
    }

    @Test
    void omitsBlankModelButRejectsWrongRuleCategory() {
        assertThat(generator.generate(1L, " ", 3L, 4L, 8L))
                .isEqualTo("BRAVAT / 宇宙黑 / 7068 / 中文版");
        assertThatThrownBy(() -> generator.generate(2L, "P90", 3L, 4L, 8L))
                .hasMessageContaining("BRAND");
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```powershell
mvn -f backend/pom.xml -pl app -am '-Dtest=MaterialSpecificationGeneratorTest' test
```

Expected: compilation failure because `MaterialSpecificationGenerator` does not exist.

- [ ] **Step 3: Implement the minimal shared generator**

```java
@Service
public class MaterialSpecificationGenerator {
    private final JdbcTemplate jdbc;

    public MaterialSpecificationGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String generate(Long brandRuleId, String model, Long bodyColorRuleId,
                           Long lockTypeRuleId, Long languageRuleId) {
        return Stream.of(
                        displayName(brandRuleId, ProductCodeCategory.BRAND),
                        model,
                        displayName(bodyColorRuleId, ProductCodeCategory.BODY_COLOR),
                        displayName(lockTypeRuleId, ProductCodeCategory.LOCK_TYPE),
                        displayName(languageRuleId, ProductCodeCategory.LANGUAGE))
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(Collectors.joining(" / "));
    }

    private String displayName(Long id, ProductCodeCategory category) {
        if (id == null) return null;
        List<String> names = jdbc.queryForList(
                "SELECT display_name FROM product_code_rule WHERE id=? AND category=? AND enabled=TRUE",
                String.class, id, category.name());
        if (names.size() != 1) throw new IllegalArgumentException("产品编号规则无效：" + category.name());
        return names.get(0);
    }
}
```

- [ ] **Step 4: Run the generator test and verify GREEN**

Run the Step 2 command. Expected: `Tests run: 2, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit the generator**

```powershell
git add backend/app/src/main/java/com/internalops/productcode/MaterialSpecificationGenerator.java backend/app/src/test/java/com/internalops/productcode/MaterialSpecificationGeneratorTest.java
git commit -m "功能：统一生成产品物料规格"
```

---

### Task 2: Use System-Generated Values in Manual and Excel Product Writes

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/workbench/MasterDataCommandService.java`
- Modify: `backend/app/src/main/java/com/internalops/importing/ProductWorkbookExcelParser.java`
- Modify: `backend/app/src/main/java/com/internalops/importing/ProductReplaceImportService.java`
- Modify: `backend/app/src/test/java/com/internalops/importing/ProductWorkbookExcelParserTest.java`
- Modify: `backend/app/src/test/java/com/internalops/importing/ProductReplaceImportServiceTest.java`
- Modify: constructor call sites found by `Select-String -Path backend/app/src/**/*.java -Pattern 'new ProductReplaceImportService|new MasterDataCommandService'`.

**Interfaces:**
- Consumes: `MaterialSpecificationGenerator.generate(...)` from Task 1 and existing `ProductImportCodeResolver.ResolvedProductCode`.
- Produces: product import rows without `sourceProductCode` or `materialSpecification`; persisted `sku.product_code` and `sku.configuration` generated only by system rules.

- [ ] **Step 1: Change parser assertions first**

Replace the old assertions that expected source fields with:

```java
assertThat(rows).allSatisfy(row -> assertThat(row.data())
        .doesNotContainKeys("sourceProductCode", "materialSpecification")
        .containsEntry("brand", "BR")
        .containsEntry("model", "D51-GEN2"));
```

Keep the workbook fixture cells populated with deliberately wrong values such as `WRONG-CODE` and `WRONG-SPEC` to prove they are ignored.

- [ ] **Step 2: Add a failing replacement-service assertion**

In `deletesProductDependenciesInForeignKeyOrderAndPreservesMasterData`, keep `materialSpecification = "测试规格"` in input data but change the database assertion to:

```java
assertThat(jdbc.queryForObject("SELECT configuration FROM sku", String.class))
        .isEqualTo("BRAVAT / P90 / 宇宙黑 / 7068 / 中文版");
```

Also retain the existing assertion that `product_code` equals `BR_P90YZH70WPZC-A`, proving the source value is not used.

- [ ] **Step 3: Run focused tests and verify RED**

```powershell
mvn -f backend/pom.xml -pl app -am '-Dtest=ProductWorkbookExcelParserTest,ProductReplaceImportServiceTest,MasterDataCommandApiTest' test
```

Expected failures: parser still exposes source fields and replacement import persists `测试规格`.

- [ ] **Step 4: Make the parser ignore both source columns**

In `ProductWorkbookExcelParser`:

```java
private static final List<String> TEXT_FIELDS = List.of(
        "productCategory", "materialType", "customerMaterialCode", "brand", "series",
        "bodyColor", "lockType", "connectivity", "salesChannel", "operatingEntity", "language",
        "codeSuffix", "model", "productConfiguration", "supplierName");
```

Remove `sourceProductCode` and `materialSpecification` from header mapping and from `PRODUCT_DETAIL_FIELDS`. Do not remove support for the physical Excel columns; unknown headers are intentionally ignored.

- [ ] **Step 5: Inject and use the shared generator in manual product commands**

Change the constructor to:

```java
public MasterDataCommandService(JdbcTemplate jdbc, ProductCodeGenerator productCodeGenerator,
                                MaterialSpecificationGenerator materialSpecificationGenerator) {
    this.jdbc = jdbc;
    this.productCodeGenerator = productCodeGenerator;
    this.materialSpecificationGenerator = materialSpecificationGenerator;
}
```

Replace both private `materialSpecification(...)` calls with `materialSpecificationGenerator.generate(...)`, then delete the duplicate private `materialSpecification` and `ruleDisplayName` methods.

- [ ] **Step 6: Generate specification during product replacement**

Inject `MaterialSpecificationGenerator` into `ProductReplaceImportService`. In `insertProduct`, replace:

```java
String configuration = nullableText(data, "materialSpecification");
```

with:

```java
String configuration = materialSpecificationGenerator.generate(
        rules.brandRuleId(), model, rules.bodyColorRuleId(), rules.lockTypeRuleId(), rules.languageRuleId());
```

Update explicit constructor calls in tests to pass the shared generator.

- [ ] **Step 7: Run focused tests and verify GREEN**

Run the Step 3 command. Expected: all focused tests pass with zero failures and errors.

- [ ] **Step 8: Run the complete backend suite**

```powershell
mvn -f backend/pom.xml test
```

Expected: `BUILD SUCCESS` with zero test failures.

- [ ] **Step 9: Commit the behavior change**

```powershell
git add backend/app/src/main/java/com/internalops/productcode/MaterialSpecificationGenerator.java backend/app/src/main/java/com/internalops/workbench/MasterDataCommandService.java backend/app/src/main/java/com/internalops/importing/ProductWorkbookExcelParser.java backend/app/src/main/java/com/internalops/importing/ProductReplaceImportService.java backend/app/src/test/java/com/internalops/productcode/MaterialSpecificationGeneratorTest.java backend/app/src/test/java/com/internalops/importing/ProductWorkbookExcelParserTest.java backend/app/src/test/java/com/internalops/importing/ProductReplaceImportServiceTest.java
git commit -m "修复：产品导入忽略表内编号和规格"
```

---

### Task 3: Deploy and Repair the Current 17 Products Without Touching Relationships

**Files:**
- No source files created or modified.
- Operational target: local Docker Compose MySQL and backend services.

**Interfaces:**
- Consumes: persisted `sku` rule IDs and `product_code_rule.display_name` after Tasks 1-2.
- Produces: recalculated `sku.configuration` values only.

- [ ] **Step 1: Capture pre-repair invariants**

```powershell
docker compose exec -T mysql sh -c 'mysql -N -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -e "SELECT COUNT(*) FROM sku; SELECT COUNT(*),SUM(actual_quantity),SUM(in_transit_quantity),SUM(source_supplier_name IS NOT NULL) FROM inventory_balance; SELECT COUNT(*),COUNT(DISTINCT sku_id) FROM sku_supplier_config WHERE enabled=TRUE;"'
```

Expected: 17 products, 17 balances, actual total 0, transit total 540, 17 populated inventory suppliers, and 17 enabled product-supplier links.

- [ ] **Step 2: Rebuild and start the backend**

```powershell
docker compose up -d --build backend
```

Expected: backend container starts successfully while MySQL data volume remains intact.

- [ ] **Step 3: Repair only material specifications transactionally**

Run one MySQL transaction that updates `sku.configuration` from rule display names and model:

```sql
START TRANSACTION;
UPDATE sku s
LEFT JOIN product_code_rule brand ON brand.id=s.brand_rule_id AND brand.category='BRAND'
LEFT JOIN product_code_rule color ON color.id=s.body_color_rule_id AND color.category='BODY_COLOR'
LEFT JOIN product_code_rule lock_rule ON lock_rule.id=s.lock_type_rule_id AND lock_rule.category='LOCK_TYPE'
LEFT JOIN product_code_rule language_rule ON language_rule.id=s.language_rule_id AND language_rule.category='LANGUAGE'
SET s.configuration = CONCAT_WS(' / ',
    NULLIF(TRIM(brand.display_name),''),
    NULLIF(TRIM(s.model),''),
    NULLIF(TRIM(color.display_name),''),
    NULLIF(TRIM(lock_rule.display_name),''),
    NULLIF(TRIM(language_rule.display_name),'')),
    s.version=s.version+1;
COMMIT;
```

Execute through `docker compose exec -T mysql` using the container-provided database environment variables; do not embed database credentials in repository files.

- [ ] **Step 4: Verify generated values and unchanged relationships**

```sql
SELECT COUNT(*) FROM sku;
SELECT COUNT(*) FROM sku
WHERE configuration = CONCAT_WS(' / ',
    NULLIF(TRIM((SELECT display_name FROM product_code_rule WHERE id=sku.brand_rule_id)),''),
    NULLIF(TRIM(model),''),
    NULLIF(TRIM((SELECT display_name FROM product_code_rule WHERE id=sku.body_color_rule_id)),''),
    NULLIF(TRIM((SELECT display_name FROM product_code_rule WHERE id=sku.lock_type_rule_id)),''),
    NULLIF(TRIM((SELECT display_name FROM product_code_rule WHERE id=sku.language_rule_id)),''));
SELECT COUNT(*),SUM(actual_quantity),SUM(in_transit_quantity),SUM(source_supplier_name IS NOT NULL) FROM inventory_balance;
SELECT COUNT(*),COUNT(DISTINCT sku_id) FROM sku_supplier_config WHERE enabled=TRUE;
```

Expected: both product counts are 17; inventory remains 17 / 0 / 540 / 17; supplier links remain 17 / 17.

- [ ] **Step 5: Verify runtime safety switch and health**

```powershell
Invoke-RestMethod http://localhost/api/system/health
docker compose exec -T backend sh -c 'test "${PRODUCT_REPLACE_IMPORT_ENABLED:-false}" = "false" && echo product-replace-disabled'
```

Expected: health response is successful and output contains `product-replace-disabled`.

---

## Final Verification

- [ ] Run `mvn -f backend/pom.xml test` and confirm `BUILD SUCCESS`.
- [ ] Confirm all 17 `sku.product_code` values equal a fresh generation from their saved rule IDs and suffixes.
- [ ] Confirm all 17 `sku.configuration` values equal the shared five-segment specification generator output.
- [ ] Confirm product count, inventory count and quantities, inventory suppliers, supplier links, and purchase prices are unchanged from the pre-repair snapshot.
- [ ] Confirm no data from the `库存数量` worksheet exists in the 17-product result.
