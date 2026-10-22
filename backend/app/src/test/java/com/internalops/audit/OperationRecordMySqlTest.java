package com.internalops.audit;

import com.fasterxml.jackson.databind.*;
import com.internalops.InternalOpsApplication;
import com.internalops.api.ApiResponse;
import com.internalops.auth.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MySQL triggers + Flyway + HTTP transactions; deliberately requires a disposable database. */
@SpringBootTest(classes=InternalOpsApplication.class,properties={"app.operation-audit.enabled=true","spring.flyway.enabled=true"})
@AutoConfigureMockMvc
@Import(OperationRecordMySqlTest.FixtureController.class)
@EnabledIfEnvironmentVariable(named="OPERATION_AUDIT_MYSQL_URL",matches=".*gmt_audit_test_.*")
class OperationRecordMySqlTest {
    @TempDir static java.nio.file.Path imageRoot;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        String url=System.getenv("OPERATION_AUDIT_MYSQL_URL");
        if(url==null||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:3306/gmt_audit_test_[a-zA-Z0-9_]+\\?.*"))throw new IllegalStateException("Only a disposable audit database is allowed");
        registry.add("spring.datasource.url",()->url);
        registry.add("spring.datasource.username",()->"root");
        registry.add("spring.datasource.password",()->System.getenv("OPERATION_AUDIT_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");
        registry.add("internal-ops.product-image-root",()->imageRoot.toString());
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired com.internalops.importing.ImportBatchRepository imports;
    Cookie admin,owner,other;
    long customer,order;
    String suffix;
    @BeforeEach void setup() throws Exception {
        suffix=UUID.randomUUID().toString().substring(0,8);
        admin=login("admin");
        jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,role) VALUES(?,'123','操作人员甲','USER'),(?,'123','操作人员乙','USER')","owner-"+suffix,"other-"+suffix);
        owner=login("owner-"+suffix);other=login("other-"+suffix);
        jdbc.update("INSERT INTO customer(customer_code,customer_name) VALUES(?,?)","AT"+suffix,"审计测试客户"+suffix);
        customer=jdbc.queryForObject("SELECT id FROM customer WHERE customer_code=?",Long.class,"AT"+suffix);
        jdbc.update("INSERT INTO customer_fund_account(customer_id,balance) VALUES(?,500)",customer);
        jdbc.update("INSERT INTO sales_order(order_no,customer_id,status,total_amount) VALUES(?,?,'WAITING_STOCK',100)","AT-"+suffix,customer);
        order=jdbc.queryForObject("SELECT id FROM sales_order WHERE order_no=?",Long.class,"AT-"+suffix);
    }
    @Test void paymentCapturesAllLinkedRowsAndRestoresThemAtomically()throws Exception {
        String path="/api/finance/orders/"+order+"/pay";
        mvc.perform(post(path).cookie(admin).contentType("application/json").content("{\"expectedAmount\":100}")).andExpect(status().isOk());
        long id=last(path);
        var detail=detail(id,admin);
        assertThat(detail.path("canUndo").asBoolean()).isTrue();
        assertThat(detail.path("operation").path("actorUsername").asText()).isEqualTo("admin");
        assertThat(tables(id)).contains("customer_fund_account","customer_fund_ledger","customer_receipt","sales_order");
        assertThat(balance()).isEqualByComparingTo("400");
        undo(id,admin).andExpect(status().isOk());
        assertThat(balance()).isEqualByComparingTo("500");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_fund_ledger WHERE customer_id=?",Integer.class,customer)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_receipt WHERE sales_order_id=?",Integer.class,order)).isZero();
        assertThat(detail(id,admin).path("operation").path("status").asText()).isEqualTo("REVERSED");
        long reversal=detail(id,admin).path("operation").path("reversedByOperationId").asLong();
        assertThat(detail(reversal,admin).path("operation").path("reversalReason").asText()).isEqualTo("录入有误，恢复原状态");
        undo(id,admin).andExpect(status().isConflict());
        undo(reversal,admin).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_context",Integer.class)).isZero();
    }
    @Test void ownersCanUndoOwnOperationsButCannotUndoOthers()throws Exception {
        long id=rename(owner,"甲修改");
        assertThat(detail(id,other).path("canUndo").asBoolean()).isFalse();
        undo(id,other).andExpect(status().isForbidden());
        assertThat(name()).isEqualTo("甲修改");
        undo(id,owner).andExpect(status().isOk());
        assertThat(name()).isEqualTo("审计测试客户"+suffix);
        long next=rename(owner,"再次修改");undo(next,admin).andExpect(status().isOk());
    }
    @Test void laterOperationsAndLinkedChildRowsBlockUndo()throws Exception {
        long first=rename(owner,"第一次");long second=rename(owner,"第二次");
        assertThat(detail(first,owner).path("blockers").get(0).path("id").asLong()).isEqualTo(second);
        undo(first,owner).andExpect(status().isConflict());
        undo(second,owner).andExpect(status().isOk());
        undo(first,owner).andExpect(status().isOk());
        long parent=rename(owner,"有后续单据");
        String path="/api/audit-fixture/"+customer+"/order";
        mvc.perform(post(path).cookie(owner)).andExpect(status().isOk());
        undo(parent,owner).andExpect(status().isConflict());
        assertThat(detail(parent,owner).path("blockers").size()).isPositive();
    }
    @Test void externalChangesNeverGetOverwrittenAndPartialUndoRollsBack()throws Exception {
        String path="/api/audit-fixture/"+customer+"/multi";
        mvc.perform(post(path).cookie(owner)).andExpect(status().isOk());long id=last(path);
        // This untracked write simulates maintenance outside the application.
        jdbc.update("UPDATE customer SET customer_name='外部更新' WHERE id=?",customer);
        undo(id,owner).andExpect(status().isConflict());
        assertThat(name()).isEqualTo("外部更新");
        assertThat(balance()).isEqualByComparingTo("450");
        assertThat(detail(id,owner).path("operation").path("status").asText()).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_context",Integer.class)).isZero();
    }
    @Test void failedRequestRollsBackEveryChangeAndStillRecordsFailure()throws Exception {
        String path="/api/audit-fixture/"+customer+"/fail";
        mvc.perform(post(path).cookie(owner)).andExpect(status().isBadRequest());
        assertThat(name()).isEqualTo("审计测试客户"+suffix);assertThat(balance()).isEqualByComparingTo("500");
        var record=detail(last(path),owner).path("operation");
        assertThat(record.path("status").asText()).isEqualTo("FAILED");assertThat(record.path("changeCount").asInt()).isZero();
    }
    @Test void patchExportAndLoginAreRecordedWithoutSecrets()throws Exception {
        String path="/api/audit-fixture/"+customer;
        mvc.perform(patch(path).cookie(owner).contentType("application/json").content("{\"name\":\"补丁更新\"}")).andExpect(status().isOk());
        assertThat(last(path)).isPositive();
        mvc.perform(get("/api/audit-fixture/export").cookie(owner)).andExpect(status().isOk()).andExpect(content().string("csv-data"));
        assertThat(detail(last("/api/audit-fixture/export"),owner).path("canUndo").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_record WHERE action='登录系统' AND actor_username=?",Integer.class,"owner-"+suffix)).isPositive();
        String userPath="/api/workbench/user";
        mvc.perform(post(userPath).cookie(admin).contentType("application/json").content("{\"username\":\"created-"+suffix+"\",\"displayName\":\"新增人员\",\"password\":\"AuditSecret123!\",\"role\":\"USER\"}")).andExpect(status().isOk());
        var detail=detail(last(userPath),admin);
        assertThat(detail.toString()).doesNotContain("AuditSecret123!","password_hash","token_hash");
        assertThat(detail.path("canUndo").asBoolean()).isFalse();
    }
    @Test void concurrentUndoExecutesOnlyOnce()throws Exception {
        long id=rename(owner,"只撤回一次");var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<Integer> attempt=()->{start.await();return undo(id,owner).andReturn().getResponse().getStatus();};
            var a=pool.submit(attempt);var b=pool.submit(attempt);start.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        } finally {pool.shutdownNow();}
        assertThat(name()).isEqualTo("审计测试客户"+suffix);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_record WHERE reverses_id=?",Integer.class,id)).isEqualTo(1);
    }
    @Test void schemaHasCaptureForAllBusinessTables() {
        int tables=jdbc.queryForObject("SELECT COUNT(*) FROM operation_audit_table",Integer.class);
        int triggers=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME LIKE 'op_%'",Integer.class);
        assertThat(tables).isGreaterThan(30);assertThat(triggers).isEqualTo(tables*3+1);
        assertThat(jdbc.queryForList("SELECT table_name FROM operation_audit_table",String.class)).contains("customer_fund_account","sku_supplier_purchase_info","document_number_sequence","after_sales_event");
    }
    @Test void filterCanSearchActorDatesAndAssociatedBusiness()throws Exception {
        long id=rename(owner,"可检索客户"+suffix);
        mvc.perform(get("/api/operation-records").cookie(admin).param("actor","owner-"+suffix).param("keyword","可检索客户"+suffix).param("module","客户管理").param("status","SUCCESS"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(id));
    }
    @Test void orderInventoryAndAutomaticProcurementShareOneUndo()throws Exception {
        long sku=product();
        jdbc.update("INSERT INTO supplier(supplier_code,supplier_name) VALUES(?,?)","SUP"+suffix,"审计供应商"+suffix);
        long supplier=jdbc.queryForObject("SELECT id FROM supplier WHERE supplier_code=?",Long.class,"SUP"+suffix);
        jdbc.update("INSERT INTO sku_supplier_config(sku_id,supplier_id,purchase_price,moq,lead_time_days) VALUES(?,?,5,1,1)",sku,supplier);
        long config=jdbc.queryForObject("SELECT id FROM sku_supplier_config WHERE sku_id=?",Long.class,sku);
        jdbc.update("INSERT INTO sku_supplier_purchase_info(supplier_product_config_id,purchase_price,moq,lead_time_days) VALUES(?,5,1,1)",config);
        var command=Map.of("customerId",customer,"orderDate","2026-10-22","orderType","工程订单","status","PENDING_CUSTOMER_PAYMENT","salesperson","甲",
                "items",List.of(Map.of("skuId",sku,"quantity",5,"salePrice",new BigDecimal("10.12345678"))));
        var response=mvc.perform(post("/api/orders").cookie(owner).contentType("application/json").content(json.writeValueAsString(command))).andExpect(status().isOk()).andReturn();
        long newOrder=json.readTree(response.getResponse().getContentAsByteArray()).path("data").path("id").asLong();
        long id=last("/api/orders");
        assertThat(tables(id)).contains("sales_order","sales_order_item","inventory_balance","procurement_suggestion","procurement_suggestion_item","shortage_coverage","document_number_sequence");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shortage_coverage c JOIN sales_order_item i ON i.id=c.sales_order_item_id WHERE i.sales_order_id=?",Integer.class,newOrder)).isPositive();
        int sequence=jdbc.queryForObject("SELECT current_value FROM document_number_sequence WHERE document_type='SALES_ORDER' AND `year_month`='202610'",Integer.class);
        undo(id,owner).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_order WHERE id=?",Integer.class,newOrder)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_balance WHERE sku_id=?",Integer.class,sku)).isZero();
        assertThat(jdbc.queryForObject("SELECT current_value FROM document_number_sequence WHERE document_type='SALES_ORDER' AND `year_month`='202610'",Integer.class)).isEqualTo(sequence);
    }
    @Test void deletingAndRestoringImagesAlsoRestoresTheFile()throws Exception {
        long sku=product();var buffer=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",buffer);
        byte[] bytes=buffer.toByteArray();
        var uploaded=mvc.perform(multipart("/api/products/"+sku+"/images").file(new MockMultipartFile("files","原图.png","image/png",bytes)).cookie(owner)).andExpect(status().isOk()).andReturn();
        long image=json.readTree(uploaded.getResponse().getContentAsByteArray()).path("data").get(0).path("id").asLong();
        String path="/api/products/"+sku+"/images/"+image;
        mvc.perform(delete(path).cookie(owner)).andExpect(status().isOk());
        mvc.perform(get("/api/product-images/"+image+"/content").cookie(owner)).andExpect(status().isNotFound());
        undo(last(path),owner).andExpect(status().isOk());
        mvc.perform(get("/api/product-images/"+image+"/content").cookie(owner)).andExpect(status().isOk()).andExpect(content().bytes(bytes));
    }
    @Test void auditPersistenceFailureRollsBackBusinessData()throws Exception {
        jdbc.execute("CREATE TRIGGER audit_test_reject_change BEFORE INSERT ON operation_change FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test audit unavailable'");
        try {
            mvc.perform(patch("/api/audit-fixture/"+customer).cookie(owner).contentType("application/json").content("{\"name\":\"不能提交\"}")).andExpect(status().isInternalServerError());
            assertThat(name()).isEqualTo("审计测试客户"+suffix);
        } finally {jdbc.execute("DROP TRIGGER audit_test_reject_change");}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_context",Integer.class)).isZero();
    }
    @Test void undoKeepsVersionsIncreasingAndDoesNotReviveStaleForms()throws Exception {
        long first=rename(owner,"改名");
        int version=jdbc.queryForObject("SELECT version FROM customer WHERE id=?",Integer.class,customer);
        undo(first,owner).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT version FROM customer WHERE id=?",Integer.class,customer)).isGreaterThan(version);
        assertThat(jdbc.update("UPDATE customer SET customer_name='过期表单' WHERE id=? AND version=?",customer,version)).isZero();
    }
    @Test void partialOrderImportIsCapturedAsOneOperationAndCanBeUndone()throws Exception {
        long sku=product();var good=new LinkedHashMap<String,Object>();
        good.put("externalOrderNo","IMPORT-"+suffix);good.put("_customerId",customer);good.put("_skuId",sku);
        good.put("orderDate","2026-10-22");good.put("orderType","工程订单");good.put("salesperson","甲");
        good.put("quantity",2);good.put("salePrice",new BigDecimal("12.34"));
        var bad=new LinkedHashMap<>(good);bad.put("externalOrderNo","INVALID-"+suffix);bad.put("_skuId",Long.MAX_VALUE);
        long batch=imports.create(com.internalops.importing.ImportType.ORDER,"test-orders.xlsx","hash-"+suffix,
                List.of(new com.internalops.importing.ParsedImportRow("订单",2,com.internalops.importing.ImportRowStatus.VALID,good,null),
                        new com.internalops.importing.ParsedImportRow("订单",3,com.internalops.importing.ImportRowStatus.VALID,bad,null)));
        String path="/api/imports/"+batch+"/commit";
        mvc.perform(post(path).cookie(owner).contentType("application/json").content("{}")).andExpect(status().isOk());
        long operation=last(path);
        assertThat(tables(operation)).contains("sales_order","sales_order_item","import_batch","import_row");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_order WHERE external_order_no=?",Integer.class,"IMPORT-"+suffix)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_order WHERE external_order_no=?",Integer.class,"INVALID-"+suffix)).isZero();
        undo(operation,owner).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_order WHERE external_order_no=?",Integer.class,"IMPORT-"+suffix)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM import_batch WHERE id=?",String.class,batch)).isEqualTo("PREVIEW");
    }
    private long product() {
        jdbc.update("INSERT INTO sku(product_code,business_unique_id,customer_part_number,product_name,current_cost) VALUES(?,?,?,'测试门锁',5)","AT"+suffix,"AT"+suffix+"::P","P"+suffix);
        return jdbc.queryForObject("SELECT id FROM sku WHERE product_code=?",Long.class,"AT"+suffix);
    }
    private Cookie login(String username)throws Exception {return mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("username",username,"password","123")))).andExpect(status().isOk()).andReturn().getResponse().getCookie("OPS_SESSION");}
    private long rename(Cookie user,String name)throws Exception {String path="/api/audit-fixture/"+customer;mvc.perform(patch(path).cookie(user).contentType("application/json").content(json.writeValueAsString(Map.of("name",name)))).andExpect(status().isOk());return last(path);}
    private long last(String path){return jdbc.queryForObject("SELECT MAX(id) FROM operation_record WHERE request_path=?",Long.class,path);}
    private JsonNode detail(long id,Cookie user)throws Exception{return json.readTree(mvc.perform(get("/api/operation-records/"+id).cookie(user)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");}
    private ResultActions undo(long id,Cookie user)throws Exception{return mvc.perform(post("/api/operation-records/"+id+"/undo").cookie(user).contentType("application/json").content("{\"reason\":\"录入有误，恢复原状态\"}"));}
    private BigDecimal balance(){return jdbc.queryForObject("SELECT balance FROM customer_fund_account WHERE customer_id=?",BigDecimal.class,customer);}
    private String name(){return jdbc.queryForObject("SELECT customer_name FROM customer WHERE id=?",String.class,customer);}
    private List<String> tables(long id){return jdbc.queryForList("SELECT DISTINCT table_name FROM operation_change WHERE operation_id=?",String.class,id);}

    @RestController @RequestMapping("/api/audit-fixture")
    static class FixtureController {
        private final JdbcTemplate jdbc;
        FixtureController(JdbcTemplate jdbc){this.jdbc=jdbc;}
        @PatchMapping("/{id}") ApiResponse<Void> rename(@PathVariable long id,@RequestBody Map<String,String> body){jdbc.update("UPDATE customer SET customer_name=?,version=version+1 WHERE id=?",body.get("name"),id);return ApiResponse.ok(null);}
        @PostMapping("/{id}/multi") ApiResponse<Void> multi(@PathVariable long id){jdbc.update("UPDATE customer SET customer_name='联动修改' WHERE id=?",id);jdbc.update("UPDATE customer_fund_account SET balance=450,version=version+1 WHERE customer_id=?",id);return ApiResponse.ok(null);}
        @PostMapping("/{id}/fail") ApiResponse<Void> fail(@PathVariable long id){multi(id);throw new IllegalArgumentException("测试业务校验失败");}
        @PostMapping("/{id}/order") ApiResponse<Void> child(@PathVariable long id){jdbc.update("INSERT INTO sales_order(order_no,customer_id,status,total_amount) VALUES(?,?,'DRAFT',10)","CHILD-"+UUID.randomUUID(),id);return ApiResponse.ok(null);}
        @GetMapping("/export") String export(){return "csv-data";}
    }
}
