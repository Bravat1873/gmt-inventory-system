package com.internalops.importing;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@Sql(scripts = {"/import-schema.sql", "/import-commit-schema.sql"})
class ImportPreviewApiTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ImportBatchRepository repository;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void includesExistingCustomerSnapshotForNameConflicts() throws Exception {
        jdbc.update("INSERT INTO customer(customer_code,customer_name,contact_name,phone,enabled) VALUES(?,?,?,?,TRUE)",
                "C-CONFLICT", "同名客户", "旧联系人", "13800000000");
        var file = new MockMultipartFile("file", "客户.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", customerWorkbook("同名客户"));

        mvc.perform(multipart("/api/imports/preview").file(file).param("type", "CUSTOMER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].data._conflict").value(true))
                .andExpect(jsonPath("$.data.rows[0].data._existingRecordId").isNumber())
                .andExpect(jsonPath("$.data.rows[0].data._existingValues.customerName").value("同名客户"))
                .andExpect(jsonPath("$.data.rows[0].data._existingValues.phone").value("13800000000"));
    }

    @Test
    void revalidatesEveryProductRowAfterEditingADuplicateCandidate() throws Exception {
        createProductRuleTable();
        Map<String, Object> duplicate = new LinkedHashMap<>(productData("PART-001"));
        duplicate.put("_conflict", true);
        duplicate.put("_conflictGroup", "stale-conflict");
        long batchId = repository.create(ImportType.PRODUCT, "products.xlsx", "product-revalidate", List.of(
                new ParsedImportRow("产品", 2, ImportRowStatus.VALID, duplicate, null),
                new ParsedImportRow("产品", 3, ImportRowStatus.VALID, duplicate, null)));
        long rowId = repository.findBatch(batchId).rows().get(0).id();

        mvc.perform(put("/api/imports/{batchId}/rows/{rowId}", batchId, rowId)
                        .contentType("application/json")
                        .content("{\"data\":{\"sourceProductCode\":\"参考编号\",\"brand\":\"G\",\"series\":\"T7\",\"bodyColor\":\"PBY\",\"lockType\":\"10\",\"connectivity\":\"W\",\"salesChannel\":\"P\",\"operatingEntity\":\"S\",\"language\":\"C\",\"customerPartNumber\":\"PART-002\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data._conflict").value(false));

        mvc.perform(get("/api/imports/{batchId}", batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].data._conflict").value(false))
                .andExpect(jsonPath("$.data.rows[1].data._conflict").value(false));
    }

    @Test
    void rejectsRowChangesWhileBatchIsCommitting() throws Exception {
        long batchId = repository.create(ImportType.CUSTOMER, "customers.xlsx", "committing-edit", List.of(
                new ParsedImportRow("客户", 2, ImportRowStatus.VALID, Map.of("customerName", "原客户"), null)));
        long rowId = repository.findBatch(batchId).rows().get(0).id();
        repository.markCommitting(batchId);

        mvc.perform(post("/api/imports/{batchId}/rows", batchId)
                        .contentType("application/json").content("{\"data\":{\"customerName\":\"新增客户\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅预览中的批次可以修改"));
        mvc.perform(put("/api/imports/{batchId}/rows/{rowId}", batchId, rowId)
                        .contentType("application/json").content("{\"data\":{\"customerName\":\"修改客户\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅预览中的批次可以修改"));

        mvc.perform(get("/api/imports/{batchId}", batchId))
                .andExpect(jsonPath("$.data.rows[0].data.customerName").value("原客户"));
    }

    @Test
    void previewsLegacyXlsSupplierWorkbook() throws Exception {
        var file = new MockMultipartFile("file", "供应商.xls", "application/vnd.ms-excel", supplierXlsWorkbook());

        mvc.perform(multipart("/api/imports/preview").file(file).param("type", "SUPPLIER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.importType").value("SUPPLIER"))
                .andExpect(jsonPath("$.data.validRows").value(1))
                .andExpect(jsonPath("$.data.rows[0].data.supplierName").value("旧版供应商"));
    }

    @Test
    void createsANewImportBatchAndAllowsManualCorrection() throws Exception {
        byte[] bytes = customerWorkbook();
        var file = new MockMultipartFile("file", "客户.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);

        String first = mvc.perform(multipart("/api/imports/preview").file(file).param("type", "CUSTOMER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validRows").value(1))
                .andExpect(jsonPath("$.data.rows[0].data.customerName").value("测试客户"))
                .andReturn().getResponse().getContentAsString();
        long batchId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(first).path("data").path("batchId").asLong();

        var sameFile = new MockMultipartFile("file", "客户.xlsx", file.getContentType(), bytes);
        String second = mvc.perform(multipart("/api/imports/preview").file(sameFile).param("type", "CUSTOMER"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long secondBatchId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(second).path("data").path("batchId").asLong();
        assertNotEquals(batchId, secondBatchId);

        String added = mvc.perform(post("/api/imports/{batchId}/rows", batchId)
                        .contentType("application/json").content("{\"data\":{\"customerName\":\"\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ERROR"))
                .andReturn().getResponse().getContentAsString();
        long rowId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(added).path("data").path("id").asLong();

        mvc.perform(put("/api/imports/{batchId}/rows/{rowId}", batchId, rowId)
                        .contentType("application/json").content("{\"data\":{\"customerName\":\"手工客户\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VALID"));

        mvc.perform(get("/api/imports/{batchId}", batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validRows").value(2));
    }

    @Test
    void createsANewBatchForAnAlreadyCommittedInventoryWorkbook() throws Exception {
        byte[] bytes = inventoryWorkbook();
        var file = new MockMultipartFile("file", "库存.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
        String first = mvc.perform(multipart("/api/imports/preview").file(file).param("type", "INVENTORY"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long batchId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(first).path("data").path("batchId").asLong();
        repository.markCommitted(batchId, 1, Map.of());

        var sameFile = new MockMultipartFile("file", "库存.xlsx", file.getContentType(), bytes);
        String second = mvc.perform(multipart("/api/imports/preview").file(sameFile).param("type", "INVENTORY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PREVIEW"))
                .andExpect(jsonPath("$.data.rows[0].data.lockedBreakdown2").value(7))
                .andReturn().getResponse().getContentAsString();
        long secondBatchId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(second).path("data").path("batchId").asLong();
        assertNotEquals(batchId, secondBatchId);
    }

    @Test
    void createsANewBatchForAnAlreadyCommittedCostWorkbook() throws Exception {
        byte[] bytes = costWorkbook();
        var file = new MockMultipartFile("file", "cost.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
        String first = mvc.perform(multipart("/api/imports/preview").file(file).param("type", "COST"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long batchId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(first).path("data").path("batchId").asLong();
        repository.markCommitted(batchId, 1, Map.of());

        var sameFile = new MockMultipartFile("file", "cost.xlsx", file.getContentType(), bytes);
        String second = mvc.perform(multipart("/api/imports/preview").file(sameFile).param("type", "COST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PREVIEW"))
                .andReturn().getResponse().getContentAsString();
        long secondBatchId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(second).path("data").path("batchId").asLong();
        assertNotEquals(batchId, secondBatchId);
    }

    private byte[] customerWorkbook() throws Exception {
        return customerWorkbook("测试客户");
    }

    private Map<String, Object> productData(String customerPartNumber) {
        return Map.of("sourceProductCode", "参考编号", "brand", "G", "series", "T7", "bodyColor", "PBY",
                "lockType", "10", "connectivity", "W", "salesChannel", "P", "operatingEntity", "S",
                "language", "C", "customerPartNumber", customerPartNumber);
    }

    private void createProductRuleTable() {
        jdbc.execute("CREATE TABLE product_code_rule (id BIGINT PRIMARY KEY, category VARCHAR(40) NOT NULL, code VARCHAR(40) NOT NULL, display_name VARCHAR(120) NOT NULL, enabled BOOLEAN NOT NULL)");
        String[][] rules = {{"BRAND", "G"}, {"SERIES", "T7"}, {"BODY_COLOR", "PBY"}, {"LOCK_TYPE", "10"},
                {"CONNECTIVITY", "W"}, {"SALES_CHANNEL", "P"}, {"OPERATING_ENTITY", "S"}, {"LANGUAGE", "C"}};
        for (int index = 0; index < rules.length; index++) {
            jdbc.update("INSERT INTO product_code_rule(id,category,code,display_name,enabled) VALUES(?,?,?,?,TRUE)",
                    index + 1L, rules[index][0], rules[index][1], rules[index][1]);
        }
    }

    private byte[] customerWorkbook(String name) throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            workbook.createSheet("Sheet1").createRow(0).createCell(0).setCellValue(name);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] supplierXlsWorkbook() throws Exception {
        try (var workbook = new HSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Sheet1");
            sheet.createRow(0).createCell(0).setCellValue("供应商通讯录");
            var header = sheet.createRow(1);
            header.createCell(0).setCellValue("供应商名称");
            header.createCell(1).setCellValue("联系方式");
            var row = sheet.createRow(2);
            row.createCell(0).setCellValue("旧版供应商");
            row.createCell(1).setCellValue("00123");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] inventoryWorkbook() throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("库存");
            var header = sheet.createRow(0);
            header.createCell(1).setCellValue("客户料号 SKU");
            header.createCell(2).setCellValue("型号");
            header.createCell(8).setCellValue("实际库存数量");
            header.createCell(9).setCellValue("可用库存数量");
            header.createCell(15).setCellValue("在途数量");
            var subHeader = sheet.createRow(1);
            subHeader.createCell(10).setCellValue("铭爱钧乔");
            subHeader.createCell(11).setCellValue("博乐龙米");
            subHeader.createCell(12).setCellValue("老挝");
            subHeader.createCell(13).setCellValue("贝朗");
            subHeader.createCell(14).setCellValue("马来西亚");
            var row = sheet.createRow(2);
            row.createCell(1).setCellValue("SKU-REIMPORT");
            row.createCell(2).setCellValue("P90");
            row.createCell(8).setCellValue(7);
            row.createCell(9).setCellValue(0);
            row.createCell(11).setCellValue(7);
            row.createCell(15).setCellValue(0);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] costWorkbook() throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("产品成本");
            var header = sheet.createRow(1);
            String[] names = {"序号", "客户料号", "型号", "颜色", "锁体", "物料规格", "成本单价（含税）"};
            for (int i = 0; i < names.length; i++) header.createCell(i).setCellValue(names[i]);
            var row = sheet.createRow(2);
            row.createCell(2).setCellValue("P90");
            row.createCell(6).setCellValue(465);
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
