package com.internalops.importing;

import java.util.Map;

public record ImportCommitRequest(ImportConflictPolicy conflictPolicy, SupplierMode supplierMode,
                                  Map<Long, ProductConflictAction> productConflictActions) {
    public ImportCommitRequest(ImportConflictPolicy conflictPolicy, SupplierMode supplierMode) {
        this(conflictPolicy, supplierMode, Map.of());
    }

    public ImportCommitRequest {
        productConflictActions = productConflictActions == null ? Map.of() : Map.copyOf(productConflictActions);
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
