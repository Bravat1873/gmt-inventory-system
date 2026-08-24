package com.internalops.numbering;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Sql(scripts = "/document-number-schema.sql")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentNumberServiceTest {
    @Autowired DocumentNumberService service;
    @Autowired JdbcTemplate jdbc;

    @Test
    void allocatesIndependentMonthlySequencesForEachDocumentType() {
        LocalDate august = LocalDate.of(2026, 8, 14);

        assertThat(service.next(DocumentType.SALES_ORDER, august)).isEqualTo("DD20260800001");
        assertThat(service.next(DocumentType.SALES_ORDER, august)).isEqualTo("DD20260800002");
        assertThat(service.next(DocumentType.AFTER_SALES, august)).isEqualTo("SH20260800001");
        assertThat(service.next(DocumentType.PROCUREMENT_REVIEW, august)).isEqualTo("QR20260800001");
        assertThat(service.next(DocumentType.PURCHASE_ORDER, august)).isEqualTo("CG20260800001");
        assertThat(service.next(DocumentType.SALES_OUTBOUND, august)).isEqualTo("CK20260800001");
        assertThat(service.next(DocumentType.SALES_ORDER, LocalDate.of(2026, 9, 1)))
                .isEqualTo("DD20260900001");
    }

    @Test
    void allocatesUniqueNumbersUnderConcurrentRequests() throws Exception {
        LocalDate date = LocalDate.of(2026, 10, 1);
        List<Callable<String>> tasks = java.util.stream.IntStream.range(0, 20)
                .mapToObj(index -> (Callable<String>) () -> service.next(DocumentType.SALES_ORDER, date))
                .toList();
        var executor = Executors.newFixedThreadPool(8);
        try {
            var values = executor.invokeAll(tasks).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }).toList();

            assertThat(new HashSet<>(values)).hasSize(20);
            assertThat(values).allMatch(value -> value.matches("DD202610\\d{5}"));
            assertThat(values.stream().map(value -> Integer.parseInt(value.substring(8))).sorted().toList())
                    .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 20).boxed().toList());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsAllocationAfterMonthlyLimit() {
        jdbc.update("INSERT INTO document_number_sequence(document_type,year_month,current_value) VALUES (?,?,?)",
                DocumentType.SALES_ORDER.name(), "202611", 99999);

        assertThatThrownBy(() -> service.next(DocumentType.SALES_ORDER, LocalDate.of(2026, 11, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("本月单号已达到 99999 上限");
    }
}
