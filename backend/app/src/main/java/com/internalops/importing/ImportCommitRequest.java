package com.internalops.importing;

import java.util.Map;

public record ImportCommitRequest(ImportConflictPolicy conflictPolicy, SupplierMode supplierMode,
                                  Map<Long, ProductConflictAction> productConflictActions,
                                  Map<Long, String> conflictActions) {
    public ImportCommitRequest(ImportConflictPolicy conflictPolicy, SupplierMode supplierMode) {
        this(conflictPolicy, supplierMode, Map.of(), Map.of());
    }

    public ImportCommitRequest(ImportConflictPolicy conflictPolicy, SupplierMode supplierMode,
                               Map<Long, ProductConflictAction> productConflictActions) {
        this(conflictPolicy, supplierMode, productConflictActions, Map.of());
    }

    public ImportCommitRequest {
        productConflictActions = productConflictActions == null ? Map.of() : Map.copyOf(productConflictActions);
        conflictActions = conflictActions == null ? Map.of() : Map.copyOf(conflictActions);
    }

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
