package com.internalops.workbench;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryAgeCalculatorTest {
    private final InventoryAgeCalculator calculator = new InventoryAgeCalculator();

    @Test
    void returnsTheOldestRemainingInboundBatchAfterFifoOutboundConsumption() {
        Optional<InventoryAgeCalculator.InventoryAge> age = calculator.calculate(
                List.of(
                        movement(2, "2026-08-03T09:00:00", 6),
                        movement(1, "2026-08-03T09:00:00", 4),
                        movement(3, "2026-08-08T09:00:00", -5),
                        movement(4, "2026-08-10T09:00:00", 8)),
                13,
                LocalDate.of(2026, 8, 15));

        assertThat(age).contains(new InventoryAgeCalculator.InventoryAge(LocalDate.of(2026, 8, 3), 12));
    }

    @Test
    void returnsEmptyWhenNoActualStockRemains() {
        Optional<InventoryAgeCalculator.InventoryAge> age = calculator.calculate(
                List.of(movement(1, "2026-08-03T09:00:00", 4), movement(2, "2026-08-04T09:00:00", -4)),
                0,
                LocalDate.of(2026, 8, 15));

        assertThat(age).isEmpty();
    }

    @Test
    void returnsEmptyWhenMovementRemainderDoesNotMatchActualStock() {
        Optional<InventoryAgeCalculator.InventoryAge> age = calculator.calculate(
                List.of(movement(1, "2026-08-03T09:00:00", 4)),
                3,
                LocalDate.of(2026, 8, 15));

        assertThat(age).isEmpty();
    }

    @Test
    void returnsEmptyWhenOutboundExceedsRecordedInboundBatches() {
        Optional<InventoryAgeCalculator.InventoryAge> age = calculator.calculate(
                List.of(movement(1, "2026-08-03T09:00:00", 4), movement(2, "2026-08-04T09:00:00", -5)),
                1,
                LocalDate.of(2026, 8, 15));

        assertThat(age).isEmpty();
    }

    private InventoryAgeCalculator.Movement movement(long id, String operatedAt, int actualDelta) {
        return new InventoryAgeCalculator.Movement(id, LocalDateTime.parse(operatedAt), actualDelta);
    }
}
