package com.internalops.workbench;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

public class InventoryAgeCalculator {
    public Optional<InventoryAge> calculate(List<Movement> movements, int actualQuantity, LocalDate today) {
        if (actualQuantity <= 0) {
            return Optional.empty();
        }

        Deque<Batch> batches = new ArrayDeque<>();
        for (Movement movement : movements.stream()
                .sorted(Comparator.comparing(Movement::operatedAt).thenComparing(Movement::id))
                .toList()) {
            if (movement.actualDelta() > 0) {
                batches.addLast(new Batch(movement.operatedAt().toLocalDate(), movement.actualDelta()));
            } else if (movement.actualDelta() < 0 && !consumeOldest(batches, -movement.actualDelta())) {
                return Optional.empty();
            }
        }

        int remainingQuantity = batches.stream().mapToInt(Batch::quantity).sum();
        if (remainingQuantity != actualQuantity || batches.isEmpty()) {
            return Optional.empty();
        }
        LocalDate oldestStockDate = batches.peekFirst().stockDate();
        return Optional.of(new InventoryAge(oldestStockDate, Math.toIntExact(ChronoUnit.DAYS.between(oldestStockDate, today))));
    }

    private boolean consumeOldest(Deque<Batch> batches, int quantity) {
        int remainingToConsume = quantity;
        while (remainingToConsume > 0 && !batches.isEmpty()) {
            Batch oldest = batches.peekFirst();
            int consumed = Math.min(oldest.quantity(), remainingToConsume);
            remainingToConsume -= consumed;
            if (consumed == oldest.quantity()) {
                batches.removeFirst();
            } else {
                batches.removeFirst();
                batches.addFirst(new Batch(oldest.stockDate(), oldest.quantity() - consumed));
            }
        }
        return remainingToConsume == 0;
    }

    public record Movement(long id, LocalDateTime operatedAt, int actualDelta) {
    }

    public record InventoryAge(LocalDate oldestStockDate, int inventoryAgeDays) {
    }

    private record Batch(LocalDate stockDate, int quantity) {
    }
}
