package com.internalops.workbench;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@Sql(scripts = "/inventory-workbench-schema.sql", config = @SqlConfig(encoding = "UTF-8"))
class InventoryWorkbenchQueryApiTest {
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    @Test
    void returnsEveryLockedAllocationAsAnExtensibleList() throws Exception {
        jdbc.update("INSERT INTO inventory_locked_allocation(inventory_balance_id,lock_source,quantity) VALUES(1,'新加坡',6)");
        mvc.perform(get("/api/workbench/inventory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].lockedAllocations.length()").value(6))
                .andExpect(jsonPath("$.data.items[0].lockedAllocations[0].lockSource").value("铭爱钧乔"))
                .andExpect(jsonPath("$.data.items[0].lockedAllocations[0].quantity").value(1))
                .andExpect(jsonPath("$.data.items[0].lockedAllocations[5].lockSource").value("新加坡"))
                .andExpect(jsonPath("$.data.items[0].lockedAllocations[5].quantity").value(6))
                .andExpect(jsonPath("$.data.items[0].lockedMingAiJunQiao").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].movementCount").value(2));
    }

    @Test
    void returnsProductMasterDataForInventoryRows() throws Exception {
        mvc.perform(get("/api/workbench/inventory?sort=id&direction=asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].productCode").value("SXSEL_P90"))
                .andExpect(jsonPath("$.data.items[0].model").value("P90"))
                .andExpect(jsonPath("$.data.items[0].productType").value("SMART_LOCK"))
                .andExpect(jsonPath("$.data.items[0].productConfiguration").value("可视对讲 + 指纹"))
                .andExpect(jsonPath("$.data.items[0].configuration").exists());
    }

    @Test    void excludesInTransitStockFromAvailableQuantity() throws Exception {
        mvc.perform(get("/api/workbench/inventory?sort=id&direction=asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].actualQuantity").value(20))
                .andExpect(jsonPath("$.data.items[0].lockedQuantity").value(15))
                .andExpect(jsonPath("$.data.items[0].inTransitQuantity").value(5))
                .andExpect(jsonPath("$.data.items[0].availableQuantity").value(5))
                .andExpect(jsonPath("$.data.items[0].pendingDeliveryQuantity").value(6))
                .andExpect(jsonPath("$.data.items[0].supplyDemandBalance").value(19))
                .andExpect(jsonPath("$.data.items[0].purchaseShortageQuantity").value(0))
                .andExpect(jsonPath("$.data.items[1].supplyDemandBalance").value(-6))
                .andExpect(jsonPath("$.data.items[1].purchaseShortageQuantity").value(6));
    }
    @Test
    void normalizesRealExcelOutboundAndIgnoresInitialImportSnapshotForFifoAge() throws Exception {
        mvc.perform(get("/api/workbench/inventory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].oldestStockDate").value("2026-06-01"))
                .andExpect(jsonPath("$.data.items[0].inventoryAgeDays")
                        .value(Math.toIntExact(ChronoUnit.DAYS.between(LocalDate.of(2026, 6, 1), LocalDate.now()))));
    }

    @Test
    void returnsNullAgeFieldsWhenActualStockIsZeroOrMovementsAreInsufficient() throws Exception {
        mvc.perform(get("/api/workbench/inventory?sort=id&direction=asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[1].actualQuantity").value(0))
                .andExpect(jsonPath("$.data.items[1].oldestStockDate").value(nullValue()))
                .andExpect(jsonPath("$.data.items[1].inventoryAgeDays").value(nullValue()))
                .andExpect(jsonPath("$.data.items[2].actualQuantity").value(21))
                .andExpect(jsonPath("$.data.items[2].oldestStockDate").value(nullValue()))
                .andExpect(jsonPath("$.data.items[2].inventoryAgeDays").value(nullValue()));
    }

    @Test
    void loadsAllCurrentPageInventoryTransactionsWithOneQuery() {
        QueryCountingDataSource monitoredDataSource = new QueryCountingDataSource(dataSource);
        JdbcTemplate monitoredJdbc = new JdbcTemplate(monitoredDataSource);
        WorkbenchQueryService service = new WorkbenchQueryService(monitoredJdbc, new SupplyDemandQueryService(monitoredJdbc));

        PageResult<Map<String, Object>> page = service.query(
                "inventory", new ListQuery(1, "", "id", "asc"));

        assertThat(page.items()).extracting(item -> ((Number) item.get("id")).longValue())
                .containsExactly(1L, 2L, 3L);
        assertThat(page.items()).allSatisfy(item -> assertThat(item)
                .containsKeys("movementSummary", "oldestStockDate", "inventoryAgeDays"));
        assertThat(monitoredDataSource.transactionQueries()).isEqualTo(1);
    }

    @Test
    void returnsDynamicInventoryMovementsByDate() throws Exception {
        mvc.perform(get("/api/workbench/inventory/1/movements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].date").value("2026-06-22"))
                .andExpect(jsonPath("$.data[0].direction").value("出库"))
                .andExpect(jsonPath("$.data[0].quantity").value(7))
                .andExpect(jsonPath("$.data[0].sourceColumn").value("T:0622出库"))
                .andExpect(jsonPath("$.data[1].date").value("2026-08-04"))
                .andExpect(jsonPath("$.data[1].direction").value("入库"));
    }

    private static final class QueryCountingDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private final AtomicInteger transactionQueries = new AtomicInteger();

        private QueryCountingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return monitor(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return monitor(delegate.getConnection(username, password));
        }

        private Connection monitor(Connection connection) {
            return (Connection) Proxy.newProxyInstance(
                    InventoryWorkbenchQueryApiTest.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().startsWith("prepareStatement") && arguments != null
                                && arguments.length > 0 && arguments[0] instanceof String sql
                                && sql.contains("inventory_transaction") && sql.contains("operated_at")) {
                            transactionQueries.incrementAndGet();
                        }
                        try {
                            return method.invoke(connection, arguments);
                        } catch (InvocationTargetException exception) {
                            throw exception.getCause();
                        }
                    });
        }

        private int transactionQueries() {
            return transactionQueries.get();
        }
    }
}
