package com.internalops.productcode;

public record ProductCodeSelection(
        Long brandRuleId, Long seriesRuleId, Long bodyColorRuleId, Long lockTypeRuleId,
        Long connectivityRuleId, Long salesChannelRuleId, Long operatingEntityRuleId, Long languageRuleId) {
    public boolean complete() {
        return brandRuleId != null && seriesRuleId != null && bodyColorRuleId != null && lockTypeRuleId != null
                && connectivityRuleId != null && salesChannelRuleId != null && operatingEntityRuleId != null && languageRuleId != null;
    }
}
