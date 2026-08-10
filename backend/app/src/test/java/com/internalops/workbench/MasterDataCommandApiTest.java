package com.internalops.workbench;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = "/master-command-schema.sql")
class MasterDataCommandApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    private Cookie session;

    @BeforeEach
    void login() throws Exception {
        session = loginAs("admin");
    }

    private Cookie loginAs(String username) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("OPS_SESSION");
    }

    @Test
    void createsAndUpdatesCustomerWithOptimisticLocking() throws Exception {
        String body = mvc.perform(post("/api/workbench/customer").cookie(session).contentType("application/json")
                        .content("{\"customerName\":\"新客户\",\"contactName\":\"张三\",\"phone\":\"13800000000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerCode").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("data").path("id").asLong();

        mvc.perform(put("/api/workbench/customer/{id}", id).cookie(session).contentType("application/json")
                        .content("{\"customerName\":\"已修改客户\",\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));

        mvc.perform(put("/api/workbench/customer/{id}", id).cookie(session).contentType("application/json")
                        .content("{\"customerName\":\"冲突修改\",\"version\":0}"))
                .andExpect(status().isConflict());
    }

    @Test
    void adjustsInventoryAndWritesTransaction() throws Exception {
        mvc.perform(put("/api/workbench/inventory/1").cookie(session).contentType("application/json")
                        .content("{\"actualQuantity\":12,\"lockedQuantity\":2,\"inTransitQuantity\":3,\"reason\":\"盘点调整\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actualQuantity").value(12))
                .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    void keepsTheEnteredInventoryTotalWhenSavingInboundDetails() throws Exception {
        mvc.perform(put("/api/workbench/inventory/1").cookie(session).contentType("application/json")
                        .content("{\"actualQuantity\":6,\"lockedQuantity\":3,\"inTransitQuantity\":0,"
                                + "\"inventoryMovements\":[{\"date\":\"2026-08-07\",\"direction\":\"INBOUND\",\"quantity\":2}],\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actualQuantity").value(6))
                .andExpect(jsonPath("$.data.lockedQuantity").value(3));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transaction WHERE sku_id=1", Integer.class)).isEqualTo(2);
    }

    @Test
    void createsInventoryBySkuCodeAndUsesTheFiveLockedSources() throws Exception {
        mvc.perform(post("/api/workbench/inventory").cookie(session).contentType("application/json")
                        .content("{\"skuCode\":\"SKU002\",\"actualQuantity\":10,\"inTransitQuantity\":1,\"inventoryMovements\":[{\"date\":\"2026-08-06\",\"direction\":\"INBOUND\",\"quantity\":10},{\"date\":\"2026-08-06\",\"direction\":\"OUTBOUND\",\"quantity\":2}],"
                                + "\"lockedMingAiJunQiao\":2,\"lockedBoLeLongMi\":3,\"lockedLaos\":1,"
                                + "\"lockedBeiLang\":0,\"lockedMalaysia\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skuId").value(2))
                .andExpect(jsonPath("$.data.actualQuantity").value(18))
                .andExpect(jsonPath("$.data.lockedQuantity").value(8));
    }

    @Test
    void savesEditableInventoryProductFieldsAndAcceptsAnAvailableQuantity() throws Exception {
        mvc.perform(post("/api/workbench/inventory").cookie(session).contentType("application/json")
                        .content("{\"skuId\":2,\"actualQuantity\":10,\"availableQuantity\":10,\"lockedQuantity\":1,\"inTransitQuantity\":1,"
                                + "\"model\":\"P90\",\"configuration\":\"可视对讲\",\"productVersion\":\"工程款\","
                                + "\"color\":\"宇宙黑\",\"lockBody\":\"6068\",\"unit\":\"套\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actualQuantity").value(10));

        var sku = jdbc.queryForMap("SELECT model,configuration,product_version,color,lock_body,unit FROM sku WHERE id=2");
        assertThat(sku).containsEntry("model", "P90")
                .containsEntry("configuration", "可视对讲")
                .containsEntry("product_version", "工程款")
                .containsEntry("color", "宇宙黑")
                .containsEntry("lock_body", "6068")
                .containsEntry("unit", "套");
    }

    @Test
    void rejectsARepeatedUsernameWithAnActionableMessage() throws Exception {
        String body = "{\"username\":\"operator\",\"displayName\":\"操作员\",\"phone\":\"13800000000\"}";
        mvc.perform(post("/api/workbench/user").cookie(session).contentType("application/json").content(body))
                .andExpect(status().isOk());

        mvc.perform(post("/api/workbench/user").cookie(session).contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("用户名已存在，请换一个"));
    }

    @Test
    void financeCanUpdateBothProductPrices() throws Exception {
        Cookie financeSession = loginAs("finance");

        mvc.perform(put("/api/workbench/product/1").cookie(financeSession).contentType("application/json")
                        .content("{\"productName\":\"测试产品\",\"currentCost\":12.34,\"factoryPrice\":23.45,\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentCost").value(12.34))
                .andExpect(jsonPath("$.data.factoryPrice").value(23.45));
    }

    @Test
    void regularUserCannotSubmitEitherProductPrice() throws Exception {
        Cookie userSession = loginAs("regular-user");

        mvc.perform(put("/api/workbench/product/1").cookie(userSession).contentType("application/json")
                        .content("{\"productName\":\"测试产品\",\"currentCost\":12.34,\"version\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅财务或管理员可修改产品价格"));

        mvc.perform(put("/api/workbench/product/1").cookie(userSession).contentType("application/json")
                        .content("{\"productName\":\"测试产品\",\"factoryPrice\":23.45,\"version\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅财务或管理员可修改产品价格"));
    }

    @Test
    void regularUserPricePermissionPrecedesProductNameValidation() throws Exception {
        Cookie userSession = loginAs("regular-user");

        mvc.perform(post("/api/workbench/product").cookie(userSession).contentType("application/json")
                        .content("{\"currentCost\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅财务或管理员可修改产品价格"));
    }

    @Test
    void regularUserPricePermissionPrecedesNegativePriceValidation() throws Exception {
        Cookie userSession = loginAs("regular-user");

        mvc.perform(post("/api/workbench/product").cookie(userSession).contentType("application/json")
                        .content("{\"productName\":\"越权产品\",\"factoryPrice\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅财务或管理员可修改产品价格"));
    }

    @Test
    void regularUserPricePermissionPrecedesPriceDeserialization() throws Exception {
        Cookie userSession = loginAs("regular-user");

        mvc.perform(put("/api/workbench/product/1").cookie(userSession).contentType("application/json")
                        .content("{\"productName\":\"测试产品\",\"currentCost\":\"not-a-number\",\"version\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅财务或管理员可修改产品价格"));
    }

    @Test
    void regularUserPricePermissionPrecedesMissingVersionValidation() throws Exception {
        Cookie userSession = loginAs("regular-user");

        mvc.perform(put("/api/workbench/product/1").cookie(userSession).contentType("application/json")
                        .content("{\"productName\":\"测试产品\",\"factoryPrice\":23.45}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅财务或管理员可修改产品价格"));
    }

    @Test
    void regularUserCanUpdateProductModelWithoutClearingPrices() throws Exception {
        Cookie userSession = loginAs("regular-user");

        mvc.perform(put("/api/workbench/product/1").cookie(userSession).contentType("application/json")
                        .content("{\"productName\":\"测试产品\",\"model\":\"P90\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.model").value("P90"))
                .andExpect(jsonPath("$.data.currentCost").value(10.00))
                .andExpect(jsonPath("$.data.factoryPrice").value(20.00));

        assertThat(jdbc.queryForMap("SELECT current_cost,factory_price FROM sku WHERE id=1"))
                .containsEntry("current_cost", new java.math.BigDecimal("10.0000"))
                .containsEntry("factory_price", new java.math.BigDecimal("20.0000"));
    }

    @Test
    void regularUserCanMaintainUserDetailsButCannotAssignRoles() throws Exception {
        Cookie userSession = loginAs("regular-user");

        mvc.perform(put("/api/workbench/user/3").cookie(userSession).contentType("application/json")
                        .content("{\"displayName\":\"新操作员\",\"phone\":\"13900000000\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("新操作员"))
                .andExpect(jsonPath("$.data.role").value("USER"));

        mvc.perform(put("/api/workbench/user/3").cookie(userSession).contentType("application/json")
                        .content("{\"displayName\":\"新操作员\",\"role\":\"FINANCE\",\"version\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅管理员可分配用户角色"));
    }

    @Test
    void newUsersDefaultToUserAndAdminCanAssignAValidRole() throws Exception {
        mvc.perform(post("/api/workbench/user").cookie(session).contentType("application/json")
                        .content("{\"username\":\"new-user\",\"displayName\":\"新用户\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("USER"));

        mvc.perform(post("/api/workbench/user").cookie(session).contentType("application/json")
                        .content("{\"username\":\"new-finance\",\"displayName\":\"新财务\",\"role\":\"FINANCE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("FINANCE"));

        mvc.perform(get("/api/workbench/user").cookie(session).param("keyword", "new-finance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].role").value("FINANCE"));
    }

    @Test
    void adminCannotAssignAnInvalidRole() throws Exception {
        mvc.perform(post("/api/workbench/user").cookie(session).contentType("application/json")
                        .content("{\"username\":\"bad-role\",\"displayName\":\"非法角色\",\"role\":\"OWNER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("用户角色无效"));
    }

    @Test
    void regularUserCannotSubmitExplicitNullProductPrices() throws Exception {
        Cookie userSession = loginAs("regular-user");

        mvc.perform(put("/api/workbench/product/1").cookie(userSession).contentType("application/json")
                        .content("{\"productName\":\"测试产品\",\"currentCost\":null,\"version\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅财务或管理员可修改产品价格"));
        mvc.perform(put("/api/workbench/product/1").cookie(userSession).contentType("application/json")
                        .content("{\"productName\":\"测试产品\",\"factoryPrice\":null,\"version\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅财务或管理员可修改产品价格"));

        assertThat(jdbc.queryForMap("SELECT current_cost,factory_price FROM sku WHERE id=1"))
                .containsEntry("current_cost", new java.math.BigDecimal("10.0000"))
                .containsEntry("factory_price", new java.math.BigDecimal("20.0000"));
    }

    @Test
    void authorizedUsersCanClearAnExplicitNullPriceWhilePreservingMissingPrices() throws Exception {
        Cookie financeSession = loginAs("finance");

        mvc.perform(put("/api/workbench/product/1").cookie(financeSession).contentType("application/json")
                        .content("{\"productName\":\"测试产品\",\"currentCost\":null,\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentCost").doesNotExist())
                .andExpect(jsonPath("$.data.factoryPrice").value(20.00));

        mvc.perform(put("/api/workbench/product/1").cookie(session).contentType("application/json")
                        .content("{\"productName\":\"测试产品\",\"factoryPrice\":null,\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentCost").doesNotExist())
                .andExpect(jsonPath("$.data.factoryPrice").doesNotExist());

        assertThat(jdbc.queryForMap("SELECT current_cost,factory_price FROM sku WHERE id=1"))
                .containsEntry("current_cost", null)
                .containsEntry("factory_price", null);
    }

    @Test
    void regularUserCannotSubmitExplicitNullRole() throws Exception {
        Cookie userSession = loginAs("regular-user");

        mvc.perform(put("/api/workbench/user/3").cookie(userSession).contentType("application/json")
                        .content("{\"displayName\":\"操作员\",\"role\":null,\"version\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅管理员可分配用户角色"));
    }

    @Test
    void regularUserCannotSetPricesOrRolesOnCreateAndAdminCanUpdateARole() throws Exception {
        Cookie userSession = loginAs("regular-user");

        mvc.perform(post("/api/workbench/product").cookie(userSession).contentType("application/json")
                        .content("{\"skuCode\":\"SKU-USER\",\"productName\":\"越权产品\",\"currentCost\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅财务或管理员可修改产品价格"));
        mvc.perform(post("/api/workbench/user").cookie(userSession).contentType("application/json")
                        .content("{\"username\":\"forbidden-finance\",\"displayName\":\"越权财务\",\"role\":\"FINANCE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅管理员可分配用户角色"));

        mvc.perform(put("/api/workbench/user/3").cookie(session).contentType("application/json")
                        .content("{\"displayName\":\"操作员\",\"role\":\"FINANCE\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("FINANCE"));
        assertThat(jdbc.queryForObject("SELECT role FROM sys_user WHERE id=3", String.class)).isEqualTo("FINANCE");
    }

    @Test
    void updatingAMissingUserKeepsTheConflictResponse() throws Exception {
        mvc.perform(put("/api/workbench/user/999").cookie(session).contentType("application/json")
                        .content("{\"displayName\":\"不存在\",\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("数据已被其他操作修改，请重新打开后再试"));
    }

    @Test
    void createsASupplierWithEveryProfilePropertyAndPreservesExactTaxAndBankText() throws Exception {
        addSupplierProfileColumns();

        String response = mvc.perform(post("/api/workbench/supplier").cookie(session).contentType("application/json")
                        .content("""
                                {"supplierCode":"SUP-PROFILE","supplierName":"供应商档案",
                                 "manufacturerCategory":"制造商","manufacturerType":"源头工厂",
                                 "supplierLocation":"广东珠海","productAttribute":"智能门锁",
                                 "shortName":"珠海锁厂","contactName":"陈先生","contactTitle":"销售总监",
                                 "phone":"13800138000","address":"珠海市香洲区工业路 8 号","currency":"CNY",
                                 "taxRegistrationNo":"  91440400MA5F-TEST/001  ","bankAccount":"6222-0001",
                                 "bankAddress":"  Bank of China, Zhuhai / SWIFT: BKCHCNBJ45A  ","enabled":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.supplierCode").value("SUP-PROFILE"))
                .andExpect(jsonPath("$.data.supplierName").value("供应商档案"))
                .andExpect(jsonPath("$.data.manufacturerCategory").value("制造商"))
                .andExpect(jsonPath("$.data.manufacturerType").value("源头工厂"))
                .andExpect(jsonPath("$.data.supplierLocation").value("广东珠海"))
                .andExpect(jsonPath("$.data.productAttribute").value("智能门锁"))
                .andExpect(jsonPath("$.data.shortName").value("珠海锁厂"))
                .andExpect(jsonPath("$.data.contactName").value("陈先生"))
                .andExpect(jsonPath("$.data.contactTitle").value("销售总监"))
                .andExpect(jsonPath("$.data.phone").value("13800138000"))
                .andExpect(jsonPath("$.data.address").value("珠海市香洲区工业路 8 号"))
                .andExpect(jsonPath("$.data.currency").value("CNY"))
                .andExpect(jsonPath("$.data.taxRegistrationNo").value("  91440400MA5F-TEST/001  "))
                .andExpect(jsonPath("$.data.bankAccount").value("6222-0001"))
                .andExpect(jsonPath("$.data.bankAddress").value("  Bank of China, Zhuhai / SWIFT: BKCHCNBJ45A  "))
                .andReturn().getResponse().getContentAsString();

        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("data").path("id").asLong();
        assertThat(jdbc.queryForMap("SELECT tax_registration_no,bank_address FROM supplier WHERE id=?", id))
                .containsEntry("tax_registration_no", "  91440400MA5F-TEST/001  ")
                .containsEntry("bank_address", "  Bank of China, Zhuhai / SWIFT: BKCHCNBJ45A  ");
    }

    @Test
    void updatesEverySupplierProfilePropertyAndPreservesExistingSupplierProperties() throws Exception {
        addSupplierProfileColumns();
        jdbc.update("""
                        INSERT INTO supplier(supplier_code,supplier_name,contact_name,phone,bank_account,enabled)
                        VALUES('SUP-EXISTING','原供应商','原联系人','13000000000','OLD-ACCOUNT',TRUE)
                        """);
        long id = jdbc.queryForObject("SELECT id FROM supplier WHERE supplier_code='SUP-EXISTING'", Long.class);

        mvc.perform(put("/api/workbench/supplier/{id}", id).cookie(session).contentType("application/json")
                        .content("""
                                {"supplierName":"更新供应商","manufacturerCategory":"代理商",
                                 "manufacturerType":"授权渠道","supplierLocation":"中国香港",
                                 "productAttribute":"电子模块","shortName":"香港模组",
                                 "contactName":"林女士","contactTitle":"商务经理","phone":"13900139000",
                                 "address":"香港九龙 18 号","currency":"HKD",
                                 "taxRegistrationNo":"  HK-TAX: 88/001-A  ","bankAccount":"NEW-ACCOUNT",
                                 "bankAddress":"  HSBC Central; Branch #001  ","enabled":false,"version":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.supplierCode").value("SUP-EXISTING"))
                .andExpect(jsonPath("$.data.supplierName").value("更新供应商"))
                .andExpect(jsonPath("$.data.manufacturerCategory").value("代理商"))
                .andExpect(jsonPath("$.data.manufacturerType").value("授权渠道"))
                .andExpect(jsonPath("$.data.supplierLocation").value("中国香港"))
                .andExpect(jsonPath("$.data.productAttribute").value("电子模块"))
                .andExpect(jsonPath("$.data.shortName").value("香港模组"))
                .andExpect(jsonPath("$.data.contactName").value("林女士"))
                .andExpect(jsonPath("$.data.contactTitle").value("商务经理"))
                .andExpect(jsonPath("$.data.phone").value("13900139000"))
                .andExpect(jsonPath("$.data.address").value("香港九龙 18 号"))
                .andExpect(jsonPath("$.data.currency").value("HKD"))
                .andExpect(jsonPath("$.data.taxRegistrationNo").value("  HK-TAX: 88/001-A  "))
                .andExpect(jsonPath("$.data.bankAccount").value("NEW-ACCOUNT"))
                .andExpect(jsonPath("$.data.bankAddress").value("  HSBC Central; Branch #001  "))
                .andExpect(jsonPath("$.data.enabled").value(false));

        assertThat(jdbc.queryForMap("SELECT supplier_code,tax_registration_no,bank_address FROM supplier WHERE id=?", id))
                .containsEntry("supplier_code", "SUP-EXISTING")
                .containsEntry("tax_registration_no", "  HK-TAX: 88/001-A  ")
                .containsEntry("bank_address", "  HSBC Central; Branch #001  ");
    }

    @Test
    void queriesEverySupplierProfilePropertyWithCamelCaseAliases() throws Exception {
        addSupplierProfileColumns();
        jdbc.update("""
                        INSERT INTO supplier(
                            supplier_code,supplier_name,manufacturer_category,manufacturer_type,supplier_location,
                            product_attribute,short_name,contact_name,contact_title,phone,address,currency,
                            tax_registration_no,bank_account,bank_address,enabled)
                        VALUES('SUP-QUERY','查询供应商','生产商','工贸一体','深圳','智能家居','深圳智家',
                               '周先生','总经理','13700137000','深圳市宝安区','USD',
                               '  US-EIN 12-3456789  ','001-QUERY','  Citibank N.A. / New York  ',TRUE)
                        """);
        long id = jdbc.queryForObject("SELECT id FROM supplier WHERE supplier_code='SUP-QUERY'", Long.class);

        mvc.perform(get("/api/workbench/supplier").cookie(session)
                        .param("keyword", "SUP-QUERY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].supplierCode").value("SUP-QUERY"))
                .andExpect(jsonPath("$.data.items[0].supplierName").value("查询供应商"))
                .andExpect(jsonPath("$.data.items[0].manufacturerCategory").value("生产商"))
                .andExpect(jsonPath("$.data.items[0].manufacturerType").value("工贸一体"))
                .andExpect(jsonPath("$.data.items[0].supplierLocation").value("深圳"))
                .andExpect(jsonPath("$.data.items[0].productAttribute").value("智能家居"))
                .andExpect(jsonPath("$.data.items[0].shortName").value("深圳智家"))
                .andExpect(jsonPath("$.data.items[0].contactName").value("周先生"))
                .andExpect(jsonPath("$.data.items[0].contactTitle").value("总经理"))
                .andExpect(jsonPath("$.data.items[0].phone").value("13700137000"))
                .andExpect(jsonPath("$.data.items[0].address").value("深圳市宝安区"))
                .andExpect(jsonPath("$.data.items[0].currency").value("USD"))
                .andExpect(jsonPath("$.data.items[0].taxRegistrationNo").value("  US-EIN 12-3456789  "))
                .andExpect(jsonPath("$.data.items[0].bankAccount").value("001-QUERY"))
                .andExpect(jsonPath("$.data.items[0].bankAddress").value("  Citibank N.A. / New York  "));

        mvc.perform(get("/api/suppliers/{id}", id).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.supplierCode").value("SUP-QUERY"))
                .andExpect(jsonPath("$.data.supplierName").value("查询供应商"))
                .andExpect(jsonPath("$.data.manufacturerCategory").value("生产商"))
                .andExpect(jsonPath("$.data.manufacturerType").value("工贸一体"))
                .andExpect(jsonPath("$.data.supplierLocation").value("深圳"))
                .andExpect(jsonPath("$.data.productAttribute").value("智能家居"))
                .andExpect(jsonPath("$.data.shortName").value("深圳智家"))
                .andExpect(jsonPath("$.data.contactName").value("周先生"))
                .andExpect(jsonPath("$.data.contactTitle").value("总经理"))
                .andExpect(jsonPath("$.data.phone").value("13700137000"))
                .andExpect(jsonPath("$.data.address").value("深圳市宝安区"))
                .andExpect(jsonPath("$.data.currency").value("USD"))
                .andExpect(jsonPath("$.data.taxRegistrationNo").value("  US-EIN 12-3456789  "))
                .andExpect(jsonPath("$.data.bankAccount").value("001-QUERY"))
                .andExpect(jsonPath("$.data.bankAddress").value("  Citibank N.A. / New York  "));
    }

    private void addSupplierProfileColumns() {
        jdbc.execute("ALTER TABLE supplier ADD COLUMN contact_name VARCHAR(100)");
        jdbc.execute("ALTER TABLE supplier ADD COLUMN phone VARCHAR(40)");
        jdbc.execute("ALTER TABLE supplier ADD COLUMN bank_account VARCHAR(120)");
        jdbc.execute("ALTER TABLE supplier ADD COLUMN updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
        jdbc.execute("ALTER TABLE supplier ADD COLUMN manufacturer_category VARCHAR(100)");
        jdbc.execute("ALTER TABLE supplier ADD COLUMN manufacturer_type VARCHAR(100)");
        jdbc.execute("ALTER TABLE supplier ADD COLUMN supplier_location VARCHAR(100)");
        jdbc.execute("ALTER TABLE supplier ADD COLUMN product_attribute VARCHAR(100)");
        jdbc.execute("ALTER TABLE supplier ADD COLUMN short_name VARCHAR(120)");
        jdbc.execute("ALTER TABLE supplier ADD COLUMN contact_title VARCHAR(100)");
        jdbc.execute("ALTER TABLE supplier ADD COLUMN address VARCHAR(500)");
        jdbc.execute("ALTER TABLE supplier ADD COLUMN currency VARCHAR(100)");
        jdbc.execute("ALTER TABLE supplier ADD COLUMN tax_registration_no VARCHAR(100)");
        jdbc.execute("ALTER TABLE supplier ADD COLUMN bank_address VARCHAR(500)");
    }
}
