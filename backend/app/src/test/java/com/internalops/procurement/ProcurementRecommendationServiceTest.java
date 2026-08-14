package com.internalops.procurement;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcurementRecommendationServiceTest {
    private final ProcurementRecommendationService service = new ProcurementRecommendationService();

    @Test
    void choosesLowestEstimatedTotalAfterApplyingSupplierMoq() {
        var result = service.recommend(101L, 6, List.of(
                new ProcurementRecommendationService.Candidate(201L, 1001L, new BigDecimal("10"), 20, 7),
                new ProcurementRecommendationService.Candidate(202L, 1002L, new BigDecimal("15"), 6, 3)
        )).orElseThrow();

        assertThat(result.supplierId()).isEqualTo(202L);
        assertThat(result.suggestedQuantity()).isEqualTo(6);
        assertThat(result.estimatedAmount()).isEqualByComparingTo("90");
    }

    @Test
    void breaksEqualTotalsByUnitPriceThenMoqThenSupplierId() {
        var result = service.recommend(101L, 10, List.of(
                new ProcurementRecommendationService.Candidate(203L, 1003L, new BigDecimal("10"), 10, 2),
                new ProcurementRecommendationService.Candidate(202L, 1002L, new BigDecimal("10"), 10, 2)
        )).orElseThrow();

        assertThat(result.supplierId()).isEqualTo(202L);
    }

    @Test
    void returnsNothingWithoutShortageOrValidCandidate() {
        assertThat(service.recommend(101L, 0, List.of(
                new ProcurementRecommendationService.Candidate(201L, 1001L, BigDecimal.TEN, 1, 1)
        ))).isEmpty();
        assertThat(service.recommend(101L, 3, List.of())).isEmpty();
    }
}
