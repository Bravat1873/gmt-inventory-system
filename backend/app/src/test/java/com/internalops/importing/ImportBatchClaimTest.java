package com.internalops.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Sql(scripts = {"/import-schema.sql", "/import-commit-schema.sql"})
class ImportBatchClaimTest {
    @Autowired ImportBatchRepository repository;
    @Autowired ImportCommitService commitService;
    @Autowired ImportPreviewService previewService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void lockedBatchLoadsRowsOnlyAfterTheClaimIsAcquired() throws Exception {
        long batchId = supplierBatch("锁前供应商", "claim-repository");
        try (ClaimedBatchUpdate update = holdBatchLockWithUpdatedRow(batchId, "锁后供应商")) {
            Future<ImportBatchView> claimed = update.executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(status -> repository.findBatchForUpdate(batchId)));

            assertThatThrownBy(() -> claimed.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            update.release();

            assertThat(claimed.get(5, TimeUnit.SECONDS).rows().get(0).data())
                    .containsEntry("supplierName", "锁后供应商");
        }
    }

    @Test
    void commitClaimsBatchBeforeReadingItsRows() throws Exception {
        long batchId = supplierBatch("锁前供应商", "claim-service");
        try (ClaimedBatchUpdate update = holdBatchLockWithUpdatedRow(batchId, "锁后供应商")) {
            Future<ImportBatchView> committed = update.executor.submit(() ->
                    commitService.commit(batchId, null, ImportCommitRequest.SupplierMode.OVERWRITE));

            assertThatThrownBy(() -> committed.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            update.release();
            committed.get(5, TimeUnit.SECONDS);

            assertThat(jdbc.queryForList("SELECT supplier_name FROM supplier", String.class))
                    .containsExactly("锁后供应商");
        }
    }

    @Test
    void addingARowWaitsForTheBatchClaimAndRejectsACommittedBatch() throws Exception {
        long batchId = supplierBatch("原供应商", "claim-add-row");
        try (ClaimedBatchUpdate commit = holdBatchLockAndMarkCommitted(batchId)) {
            Future<ImportRowView> added = commit.executor.submit(() ->
                    previewService.add(batchId, new ImportRowRequest(Map.of("supplierName", "迟到供应商"))));

            assertThatThrownBy(() -> added.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            commit.release();

            assertThatThrownBy(() -> added.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class)
                    .hasRootCauseMessage("已提交批次不能修改");
            assertThat(repository.findRows(batchId)).hasSize(1);
        }
    }

    @Test
    void updatingARowWaitsForTheBatchClaimAndRejectsACommittedBatch() throws Exception {
        long batchId = supplierBatch("原供应商", "claim-update-row");
        long rowId = repository.findRows(batchId).get(0).id();
        try (ClaimedBatchUpdate commit = holdBatchLockAndMarkCommitted(batchId)) {
            Future<ImportRowView> updated = commit.executor.submit(() ->
                    previewService.update(batchId, rowId,
                            new ImportRowRequest(Map.of("supplierName", "迟到供应商"))));

            assertThatThrownBy(() -> updated.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            commit.release();

            assertThatThrownBy(() -> updated.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class)
                    .hasRootCauseMessage("已提交批次不能修改");
            assertThat(repository.findRow(batchId, rowId).data())
                    .containsEntry("supplierName", "原供应商");
        }
    }

    private long supplierBatch(String supplierName, String hash) {
        return repository.create(ImportType.SUPPLIER, "supplier.xlsx", hash, List.of(
                new ParsedImportRow("Sheet1", 2, ImportRowStatus.VALID,
                        Map.of("supplierName", supplierName), null)));
    }

    private ClaimedBatchUpdate holdBatchLockWithUpdatedRow(long batchId, String supplierName) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> writer = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM import_batch WHERE id=? FOR UPDATE", Long.class, batchId);
            try {
                jdbc.update("UPDATE import_row SET normalized_data=? WHERE batch_id=?",
                        objectMapper.writeValueAsString(Map.of("supplierName", supplierName)), batchId);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            updated.countDown();
            awaitRelease(release);
        }));
        assertThat(updated.await(5, TimeUnit.SECONDS)).isTrue();
        return new ClaimedBatchUpdate(executor, writer, release);
    }

    private ClaimedBatchUpdate holdBatchLockAndMarkCommitted(long batchId) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> writer = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM import_batch WHERE id=? FOR UPDATE", Long.class, batchId);
            jdbc.update("UPDATE import_batch SET status='COMMITTED' WHERE id=?", batchId);
            updated.countDown();
            awaitRelease(release);
        }));
        assertThat(updated.await(5, TimeUnit.SECONDS)).isTrue();
        return new ClaimedBatchUpdate(executor, writer, release);
    }

    private void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("release timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static final class ClaimedBatchUpdate implements AutoCloseable {
        private final ExecutorService executor;
        private final Future<?> writer;
        private final CountDownLatch release;

        private ClaimedBatchUpdate(ExecutorService executor, Future<?> writer, CountDownLatch release) {
            this.executor = executor;
            this.writer = writer;
            this.release = release;
        }

        private void release() throws Exception {
            release.countDown();
            writer.get(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
