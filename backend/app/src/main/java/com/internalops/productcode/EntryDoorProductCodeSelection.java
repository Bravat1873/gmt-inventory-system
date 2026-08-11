package com.internalops.productcode;

public record EntryDoorProductCodeSelection(
        Long brandRuleId,
        Long doorModelRuleId,
        Long securityGradeRuleId,
        Long baseMaterialRuleId,
        Long thicknessRuleId,
        Long finishColorRuleId) {
    public boolean complete() {
        return brandRuleId != null && doorModelRuleId != null && securityGradeRuleId != null
                && baseMaterialRuleId != null && thicknessRuleId != null && finishColorRuleId != null;
    }
}
