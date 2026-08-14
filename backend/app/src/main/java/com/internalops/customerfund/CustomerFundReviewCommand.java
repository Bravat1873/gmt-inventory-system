package com.internalops.customerfund;

public record CustomerFundReviewCommand(boolean approved, String comment) {}