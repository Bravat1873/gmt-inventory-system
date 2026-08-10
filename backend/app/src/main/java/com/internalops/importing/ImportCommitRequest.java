package com.internalops.importing;

public record ImportCommitRequest(ImportConflictPolicy conflictPolicy, SupplierMode supplierMode) {
    public ImportConflictPolicy resolvedPolicy() {
        return conflictPolicy == null ? ImportConflictPolicy.UPSERT_KEEP_EXISTING_ON_BLANK : conflictPolicy;
    }

    public SupplierMode resolvedSupplierMode() {
        return supplierMode == null ? SupplierMode.OVERWRITE : supplierMode;
    }

    public enum SupplierMode {
        OVERWRITE,
        REPLACE_ALL
    }
}
