package com.internalops.numbering;

public enum DocumentType {
    SALES_ORDER("DD"), AFTER_SALES("SH"), PROCUREMENT_REVIEW("QR"), PURCHASE_ORDER("CG");

    private final String prefix;

    DocumentType(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }
}
