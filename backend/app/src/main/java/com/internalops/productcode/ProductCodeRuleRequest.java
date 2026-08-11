package com.internalops.productcode;

public record ProductCodeRuleRequest(
        ProductCodeCategory category, String displayName, String code,
        Boolean enabled, Integer sortOrder, String remark, Integer version) {
}
