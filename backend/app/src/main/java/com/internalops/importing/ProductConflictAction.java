package com.internalops.importing;

public enum ProductConflictAction {
    OVERWRITE,
    /** @deprecated Legacy alias for OVERWRITE. */
    KEEP,
    SKIP
}
