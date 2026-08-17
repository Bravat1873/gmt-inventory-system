package com.internalops.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = {"/auth-schema.sql", "/supplier-management-schema.sql"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SupplierManagementApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void listsSuppliersByNameAndReturnsConfiguredProductsForPurchasing() throws Exception {
        Cookie session = login();
        mvc.perform(get("/api/workbench/supplier")
                        .cookie(session).param("page", "1").param("keyword", "贝朗").param("sort", "").param("direction", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].supplierName").value("贝朗供应商"))
                .andExpect(jsonPath("$.data.items[0].productCount").value(1));

        mvc.perform(get("/api/suppliers/201/products").cookie(session).param("keyword", "P90"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].skuCode").value("P90-001"))
                .andExpect(jsonPath("$.data[0].purchasePrice").value(220))
                .andExpect(jsonPath("$.data[0].moq").value(5))
                .andExpect(jsonPath("$.data[0].leadTimeDays").value(7));
    }

    @Test
    void listsConfiguredSuppliersByProductAndKeyword() throws Exception {
        Cookie session = login();
        mvc.perform(get("/api/products/101/suppliers").cookie(session).param("keyword", "贝朗"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].supplierId").value(201))
                .andExpect(jsonPath("$.data[0].supplierName").value("贝朗供应商"))
                .andExpect(jsonPath("$.data[0].purchasePrice").value(220))
                .andExpect(jsonPath("$.data[0].moq").value(5))
                .andExpect(jsonPath("$.data[0].leadTimeDays").value(7));
        mvc.perform(get("/api/products/101/suppliers").cookie(session).param("keyword", "不存在"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }
    @Test
    void supplierListExcludesDisabledSuppliersButHistoricalDetailRemainsAvailable() throws Exception {
        Cookie session = login();
        mvc.perform(get("/api/workbench/supplier").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(201));

        mvc.perform(get("/api/suppliers/202").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(202))
                .andExpect(jsonPath("$.data.enabled").value(false));
    }

    @Test
    void createsSupplierAndPersistsTheSuppliedProductConfiguration() throws Exception {
        Cookie session = login();
        mvc.perform(post("/api/suppliers").cookie(session)
                        .contentType("application/json")
                        .content("""
                                {"supplierName":"新供应商","contactName":"李四","phone":"13800138001",
                                 "bankAccount":"6222-202","products":[{"skuId":101,"purchasePrice":230.5000,"moq":8,"leadTimeDays":12}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.supplierName").value("新供应商"))
                .andExpect(jsonPath("$.data.products[0].skuId").value(101))
                .andExpect(jsonPath("$.data.products[0].purchasePrice").value(230.5))
                .andExpect(jsonPath("$.data.products[0].moq").value(8));
    }

    @Test
    void allowsTheSameProductToBeQuotedByMultipleSuppliers() throws Exception {
        Cookie session = login();
        mvc.perform(post("/api/suppliers").cookie(session)
                        .contentType("application/json")
                        .content("""
                                {"supplierName":"第二供应商","products":[{"skuId":101,"purchasePrice":230.0000,"moq":8,"leadTimeDays":12}]}
                                """))
                .andExpect(status().isOk());

        mvc.perform(get("/api/products/101/suppliers").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].supplierName").value("第二供应商"))
                .andExpect(jsonPath("$.data[1].supplierName").value("贝朗供应商"));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sku_supplier_config WHERE sku_id=101 AND enabled=TRUE", Integer.class))
                .isEqualTo(2);
    }
    @Test
    void createsSupplierWithTheCompleteProfileAndPreservesPhoneTaxAndAccountText() throws Exception {
        Cookie session = login();
        String response = mvc.perform(post("/api/suppliers").cookie(session)
                        .contentType("application/json")
                        .content("""
                                {"supplierName":"完整资料供应商","manufacturerCategory":"生产厂商",
                                 "manufacturerType":"战略供应商","supplierLocation":"浙江",
                                 "productAttribute":"智能锁","shortName":"完整资料","contactName":"王女士",
                                 "contactTitle":"销售经理","phone":" 0571 01234567 ","address":"杭州市滨江区",
                                 "currency":"CNY","taxRegistrationNo":" 0012345000 ",
                                 "bankAddress":"中国银行杭州分行","bankAccount":" 0012 3456 7890 ","products":[]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.manufacturerCategory").value("生产厂商"))
                .andExpect(jsonPath("$.data.manufacturerType").value("战略供应商"))
                .andExpect(jsonPath("$.data.supplierLocation").value("浙江"))
                .andExpect(jsonPath("$.data.productAttribute").value("智能锁"))
                .andExpect(jsonPath("$.data.shortName").value("完整资料"))
                .andExpect(jsonPath("$.data.contactTitle").value("销售经理"))
                .andExpect(jsonPath("$.data.address").value("杭州市滨江区"))
                .andExpect(jsonPath("$.data.currency").value("CNY"))
                .andExpect(jsonPath("$.data.bankAddress").value("中国银行杭州分行"))
                .andExpect(jsonPath("$.data.phone").value(" 0571 01234567 "))
                .andExpect(jsonPath("$.data.taxRegistrationNo").value(" 0012345000 "))
                .andExpect(jsonPath("$.data.bankAccount").value(" 0012 3456 7890 "))
                .andReturn().getResponse().getContentAsString();

        long id = objectMapper.readTree(response).path("data").path("id").asLong();
        assertThat(jdbc.queryForMap("SELECT phone,tax_registration_no,bank_account FROM supplier WHERE id=?", id))
                .containsEntry("PHONE", " 0571 01234567 ")
                .containsEntry("TAX_REGISTRATION_NO", " 0012345000 ")
                .containsEntry("BANK_ACCOUNT", " 0012 3456 7890 ");
    }

    @Test
    void updatesSupplierWithTheCompleteProfileContract() throws Exception {
        Cookie session = login();
        mvc.perform(put("/api/suppliers/201").cookie(session)
                        .contentType("application/json")
                        .content("""
                                {"supplierName":"更新资料供应商","manufacturerCategory":"代理商",
                                 "manufacturerType":"授权渠道","supplierLocation":"香港",
                                 "productAttribute":"电子模块","shortName":"更新资料","contactName":"林女士",
                                 "contactTitle":"商务经理","phone":" 00852 1234 ","address":"香港九龙",
                                 "currency":"HKD","taxRegistrationNo":" HK-TAX-001 ",
                                 "bankAddress":"HSBC Central","bankAccount":" HK-ACCOUNT-001 ",
                                 "products":[],"version":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.supplierName").value("更新资料供应商"))
                .andExpect(jsonPath("$.data.manufacturerCategory").value("代理商"))
                .andExpect(jsonPath("$.data.manufacturerType").value("授权渠道"))
                .andExpect(jsonPath("$.data.supplierLocation").value("香港"))
                .andExpect(jsonPath("$.data.productAttribute").value("电子模块"))
                .andExpect(jsonPath("$.data.shortName").value("更新资料"))
                .andExpect(jsonPath("$.data.contactTitle").value("商务经理"))
                .andExpect(jsonPath("$.data.address").value("香港九龙"))
                .andExpect(jsonPath("$.data.currency").value("HKD"))
                .andExpect(jsonPath("$.data.bankAddress").value("HSBC Central"))
                .andExpect(jsonPath("$.data.phone").value(" 00852 1234 "))
                .andExpect(jsonPath("$.data.taxRegistrationNo").value(" HK-TAX-001 "))
                .andExpect(jsonPath("$.data.bankAccount").value(" HK-ACCOUNT-001 "));
    }


    @Test
    void createsMultiplePurchaseInfosForOneSupplierProduct() throws Exception {
        Cookie session = login();
        mvc.perform(post("/api/suppliers").cookie(session)
                        .contentType("application/json")
                        .content("""
                                {"supplierName":"多报价供应商","products":[{"skuId":101,"purchaseInfos":[
                                  {"purchasePrice":230.5000,"moq":8,"leadTimeDays":12},
                                  {"purchasePrice":228.0000,"moq":20,"leadTimeDays":15}
                                ]}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.products.length()").value(1))
                .andExpect(jsonPath("$.data.products[0].purchaseInfos.length()").value(2))
                .andExpect(jsonPath("$.data.products[0].purchaseInfos[0].purchasePrice").value(228))
                .andExpect(jsonPath("$.data.products[0].purchaseInfos[1].purchasePrice").value(230.5));

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sku_supplier_purchase_info pi
                JOIN sku_supplier_config cfg ON cfg.id=pi.supplier_product_config_id
                WHERE cfg.sku_id=101 AND pi.enabled=TRUE
                """, Integer.class)).isEqualTo(3);
    }

    @Test
    void savesAConfiguredProductWithoutPurchaseValuesThenAllowsLaterCompletion() throws Exception {
        Cookie session = login();
        String created = mvc.perform(post("/api/suppliers").cookie(session)
                        .contentType("application/json")
                        .content("""
                                {"supplierName":"待补填供应商","products":[{"skuId":101}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.products[0].skuId").value(101))
                .andExpect(jsonPath("$.data.products[0].purchasePrice").doesNotExist())
                .andExpect(jsonPath("$.data.products[0].moq").doesNotExist())
                .andExpect(jsonPath("$.data.products[0].leadTimeDays").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        long id = objectMapper.readTree(created).path("data").path("id").asLong();
        int version = objectMapper.readTree(created).path("data").path("version").asInt();
        String completed = mvc.perform(put("/api/suppliers/{id}", id).cookie(session)
                        .contentType("application/json")
                        .content("""
                                {"supplierName":"待补填供应商","version":%d,
                                 "products":[{"skuId":101,"purchasePrice":12.50,"moq":20,"leadTimeDays":7}]}
                                """.formatted(version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.products[0].purchasePrice").value(12.5))
                .andExpect(jsonPath("$.data.products[0].moq").value(20))
                .andExpect(jsonPath("$.data.products[0].leadTimeDays").value(7))
                .andReturn().getResponse().getContentAsString();

        int completedVersion = objectMapper.readTree(completed).path("data").path("version").asInt();
        mvc.perform(put("/api/suppliers/{id}", id).cookie(session)
                        .contentType("application/json")
                        .content("""
                                {"supplierName":"待补填供应商","version":%d,
                                 "products":[{"skuId":101}]}
                                """.formatted(completedVersion)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.products[0].purchasePrice").value(12.5))
                .andExpect(jsonPath("$.data.products[0].moq").value(20))
                .andExpect(jsonPath("$.data.products[0].leadTimeDays").value(7));
    }

    private Cookie login() throws Exception {
        return mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("OPS_SESSION");
    }
}
