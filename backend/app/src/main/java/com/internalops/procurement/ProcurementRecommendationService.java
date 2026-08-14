package com.internalops.procurement;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class ProcurementRecommendationService {
    public Optional<Recommendation> recommend(long skuId, int shortageQuantity, List<Candidate> candidates) {
        if (shortageQuantity <= 0) return Optional.empty();
        return candidates.stream()
                .filter(candidate -> candidate.purchasePrice() != null && candidate.purchasePrice().signum() >= 0)
                .filter(candidate -> candidate.minimumOrderQuantity() >= 1)
                .map(candidate -> {
                    int quantity = Math.max(shortageQuantity, candidate.minimumOrderQuantity());
                    BigDecimal amount = candidate.purchasePrice().multiply(BigDecimal.valueOf(quantity));
                    return new Recommendation(skuId, candidate.supplierId(), candidate.purchaseInfoId(),
                            shortageQuantity, candidate.minimumOrderQuantity(), quantity,
                            candidate.purchasePrice(), amount, candidate.leadTimeDays());
                })
                .min(Comparator.comparing(Recommendation::estimatedAmount)
                        .thenComparing(Recommendation::purchasePrice)
                        .thenComparingInt(Recommendation::minimumOrderQuantity)
                        .thenComparingLong(Recommendation::supplierId));
    }

    public record Candidate(long supplierId, long purchaseInfoId, BigDecimal purchasePrice,
                            int minimumOrderQuantity, int leadTimeDays) {}

    public record Recommendation(long skuId, long supplierId, long purchaseInfoId,
                                 int shortageQuantity, int minimumOrderQuantity, int suggestedQuantity,
                                 BigDecimal purchasePrice, BigDecimal estimatedAmount, int leadTimeDays) {}
}
