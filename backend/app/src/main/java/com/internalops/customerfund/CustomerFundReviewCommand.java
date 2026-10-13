package com.internalops.customerfund;

import java.math.BigDecimal;

public record CustomerFundReviewCommand(boolean approved, String comment, BigDecimal confirmedAmount) {
    public CustomerFundReviewCommand(boolean approved, String comment) { this(approved, comment, null); }
}
