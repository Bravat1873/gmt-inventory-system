# Inventory Duplicate SKU First-Row-Wins Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make inventory imports merge duplicate SKUs into one product and one inventory record, using all values from the first Excel row.

**Architecture:** Deduplicate inventory rows in `ImportValidationService.validateAll` before conflict enrichment. The first normalized SKU remains eligible for validation and commit; later matches become traceable `IGNORED` rows, while the existing `ImportCommitService.upsertSku` remains the single path that creates or updates product master data.

**Tech Stack:** Java 17, Spring Boot 3.3, JdbcTemplate, JUnit 5, Maven

## Global Constraints

- Duplicate matching trims SKU and compares case-insensitively.
- The first occurrence owns product metadata and all inventory quantities; quantities are not summed.
- Later duplicate rows are ignored and cannot replace an invalid first row.
- Preserve the first row's SKU letter case after trimming.
- Reuse existing product upsert behavior; do not add schema changes.

---

### Task 1: Validate Duplicate Inventory Rows with First-Row-Wins Semantics

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/importing/ImportValidationService.java`
- Test: `backend/app/src/test/java/com/internalops/importing/ImportValidationServiceTest.java`

**Interfaces:**
- Consumes: `validateAll(ImportType, List<ParsedImportRow>)`
- Produces: ordered validated rows where later duplicate inventory SKUs have `ImportRowStatus.IGNORED`

- [ ] **Step 1: Write failing duplicate-row tests**

Add tests that pass two inventory rows with ` SXSEL_P90YZH70WPSE-A ` and `sxsel_p90yzh70wpse-a`, assert that the first is `VALID`, retains its quantities and trimmed SKU, and that the second is `IGNORED` with a message containing the first row number. Add a second test where the first row is invalid and assert that the second row stays ignored.

- [ ] **Step 2: Run the focused tests and verify failure**

Run: `mvn -Dmaven.repo.local=.m2 -pl app -Dtest=ImportValidationServiceTest test`

Expected: FAIL because duplicate rows are currently both marked `ERROR`.

- [ ] **Step 3: Implement ordered first-row-wins validation**

Replace duplicate count rejection with a `LinkedHashMap<String, Integer>` of normalized SKU to first row number. For each inventory row, trim the SKU, preserve the first spelling, validate the first occurrence normally, and return later occurrences as `IGNORED` with `重复 SKU，已采用第 <n> 行数据`.

- [ ] **Step 4: Run focused tests**

Run: `mvn -Dmaven.repo.local=.m2 -pl app -Dtest=ImportValidationServiceTest test`

Expected: PASS.

### Task 2: Prove Inventory Import Automatically Creates One Product

**Files:**
- Test: `backend/app/src/test/java/com/internalops/importing/ImportCommitServiceTest.java`

**Interfaces:**
- Consumes: validated rows persisted through `ImportBatchRepository.create`
- Produces: one `sku` row and one `inventory_balance` row using first-row values

- [ ] **Step 1: Write the integration regression test**

Create a batch from the validated duplicate rows, commit it with `UPSERT_KEEP_EXISTING_ON_BLANK`, and assert: SKU count is one, product model is the first row's model, inventory actual/in-transit/locked quantities equal the first row, and no values from the second row are accumulated.

- [ ] **Step 2: Run the focused integration test**

Run: `mvn -Dmaven.repo.local=.m2 -pl app -Dtest=ImportCommitServiceTest test`

Expected: PASS after Task 1 because `ImportCommitService` already calls `upsertSku` before writing inventory.

### Task 3: Regression Verification

**Files:**
- No production files beyond Task 1.

**Interfaces:**
- Consumes: completed validation and commit behavior
- Produces: verified backend build

- [ ] **Step 1: Run all import tests**

Run: `mvn -Dmaven.repo.local=.m2 -pl app -Dtest='com.internalops.importing.*Test' test`

Expected: PASS.

- [ ] **Step 2: Run the full backend test suite**

Run: `mvn -Dmaven.repo.local=.m2 test`

Expected: BUILD SUCCESS with zero test failures.

- [ ] **Step 3: Review the diff**

Run: `git diff --check` and `git diff -- backend/app/src/main/java/com/internalops/importing/ImportValidationService.java backend/app/src/test/java/com/internalops/importing/ImportValidationServiceTest.java backend/app/src/test/java/com/internalops/importing/ImportCommitServiceTest.java`

Expected: no whitespace errors; changes are limited to duplicate handling and regression tests.
