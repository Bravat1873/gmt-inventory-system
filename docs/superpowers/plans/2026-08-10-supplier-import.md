# Supplier Spreadsheet Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐供应商资料字段，并支持用户在预览后选择覆盖更新或全量替换导入 `.xls`/`.xlsx`。

**Architecture:** 复用现有导入批次、预览和提交框架，增加独立供应商解析器与提交分支。数据库用迁移补列；全量替换采用停用文件外记录而非物理删除，以保护采购历史外键。前端复用现有 ImportPanel，只增加供应商入口、策略选择和确认提示。

**Tech Stack:** Java 21、Spring Boot 3.3、Apache POI、JdbcTemplate、Flyway、Vue 3、TypeScript、Vitest、MySQL 8。

## Global Constraints

- 保持现有供应商页面风格。
- 支持 `.xls` 与 `.xlsx`，自动定位包含“供应商名称”的表头行。
- 电话、税号、银行账户按文本保存，保留显示值、空格和前导零。
- “覆盖更新”默认；“全量替换”必须二次确认且任何错误行都会阻止提交。
- 全量替换不得破坏采购历史引用，文件外供应商改为停用。
- 工作树已有用户修改；每项任务先记录目标文件差异，只暂存本任务 hunks。

---

### Task 1: 供应商字段迁移与读写模型

**Files:**
- Create: `backend/app/src/main/resources/db/migration/V27__supplier_profile_fields.sql`
- Modify: `backend/app/src/main/java/com/internalops/workbench/EntityCommandFields.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/MasterDataCommandService.java`
- Modify: `backend/app/src/main/java/com/internalops/workbench/WorkbenchQueryService.java`
- Test: `backend/app/src/test/java/com/internalops/workbench/MasterDataCommandApiTest.java`

**Interfaces:** Produces `manufacturerCategory`, `manufacturerType`, `supplierLocation`, `productAttribute`, `shortName`, `contactTitle`, `address`, `currency`, `taxRegistrationNo`, `bankAddress` while preserving existing supplier properties.

- [ ] Write failing create/update/query tests covering every new property and exact text preservation for tax/bank values.
- [ ] Run `mvn -Dmaven.repo.local=.m2 -pl app -am -Dtest=MasterDataCommandApiTest,WorkbenchQueryApiTest -Dsurefire.failIfNoSpecifiedTests=false test`; expect missing-column/mapping failures.
- [ ] Add nullable VARCHAR columns (100 for classifications/title/currency, 120 short name, 500 addresses, 100 tax number) and extend allowed fields, SQL writes and supplier SELECT aliases.
- [ ] Re-run focused tests; expect PASS.
- [ ] Commit only the migration, mappings and focused tests as `feat: extend supplier profiles`.

### Task 2: Excel 解析与两种提交策略

**Files:**
- Create: `backend/app/src/main/java/com/internalops/importing/SupplierExcelParser.java`
- Modify: `backend/app/src/main/java/com/internalops/importing/ImportType.java`
- Modify: `backend/app/src/main/java/com/internalops/importing/ImportPreviewService.java`
- Modify: `backend/app/src/main/java/com/internalops/importing/ImportCommitRequest.java`
- Modify: `backend/app/src/main/java/com/internalops/importing/ImportCommitService.java`
- Test: `backend/app/src/test/java/com/internalops/importing/SupplierExcelParserTest.java`
- Test: `backend/app/src/test/java/com/internalops/importing/ImportCommitApiTest.java`

**Interfaces:** Produces `ImportType.SUPPLIER` and supplier modes `OVERWRITE | REPLACE_ALL`; parser emits Task 1 property names.

- [ ] Generate failing in-memory HSSF and XSSF tests with a title row plus 14 Chinese headers; assert header discovery, mappings, displayed phone/tax/bank text, required name and duplicate-name validation.
- [ ] Run `mvn -Dmaven.repo.local=.m2 -pl app -am -Dtest=SupplierExcelParserTest -Dsurefire.failIfNoSpecifiedTests=false test`; expect missing parser/type failure.
- [ ] Implement exact mapping: 厂商分类→manufacturerCategory, 厂商类型→manufacturerType, 供应商地点→supplierLocation, 产品属性→productAttribute, 简称→shortName, 供应商名称→supplierName, 联系人→contactName, 职称→contactTitle, 联系方式→phone, 供应商地址→address, 币种→currency, 税务登记号→taxRegistrationNo, 开户地址→bankAddress, 开户账户→bankAccount.
- [ ] Add failing commit tests: OVERWRITE retains matched IDs, fully replaces fields including blanks, adds new names and keeps absent suppliers enabled; REPLACE_ALL retains matched IDs, disables absent suppliers, preserves purchase references and rolls back invalid batches.
- [ ] Implement a transactional commit: lock suppliers, match normalized name, generate collision-free `SUPxxxxx` codes, reactivate matches, and disable absent IDs only for REPLACE_ALL.
- [ ] Run focused tests and `mvn -Dmaven.repo.local=.m2 test`; expect all PASS.
- [ ] Commit parser, import service, schemas and tests as `feat: import supplier spreadsheets`.

### Task 3: 原风格供应商页面与导入策略界面

**Files:**
- Modify: `frontend/src/modules/module-config.ts`
- Modify: `frontend/src/components/SupplierDialog.vue`
- Modify: `frontend/src/components/ImportPanel.vue`
- Modify: `frontend/src/api/imports.ts`
- Modify corresponding `*.test.ts` files and `frontend/src/styles.css`.

**Interfaces:** Supplier config exposes `importType: 'SUPPLIER'`; commit sends `supplierMode: 'OVERWRITE' | 'REPLACE_ALL'`.

- [ ] Write failing tests for supplier import action/fields, dialog submission, default 覆盖更新, full-replace warning/second confirmation, and API request body.
- [ ] Run `npm test -- --run src/modules/module-config.test.ts src/components/SupplierDialog.test.ts src/components/ImportPanel.test.ts src/api/imports.test.ts`; expect missing UI/type failures.
- [ ] Add fields and compact strategy radio controls using existing typography, buttons and dialog layout. Full-replace confirmation text: `全量替换将停用文件中不存在的供应商，是否继续？`.
- [ ] Run `npm test -- --run` and `npm run build`; expect all PASS/build success.
- [ ] Commit isolated frontend hunks as `feat: add supplier import workflow`.

### Task 4: 用户文件验收、本地导入与文档

**Files:**
- Modify: `README.md`
- Source only: `C:/Users/linfu/Documents/xwechat_files/wxid_3npmpu5sgt7i12_87e6/temp/RWTemp/2026-08/2330cdc08dad7ff4fcab73ce674013a4/供应商联系方式.xls`

**Interfaces:** Consumes the running authenticated preview/commit API.

- [ ] Restart backend and preview the supplied `.xls`; assert 5 suppliers and all 14 mapped columns, including representative 浙江威欧希科技股份有限公司 tax/bank text.
- [ ] Commit this first requested load with `REPLACE_ALL`; assert exactly five file suppliers enabled, prior suppliers disabled and historical foreign keys valid.
- [ ] Run final verification:

```powershell
mvn -Dmaven.repo.local=.m2 test
npm test -- --run
npm run build
docker compose config
```

- [ ] Document preview safety, both modes, soft-disable behavior and backup recommendation.
- [ ] Commit README as `docs: document supplier imports`.
