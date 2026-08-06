package com.internalops.importing;

public enum ImportConflictPolicy {
    UPSERT_KEEP_EXISTING_ON_BLANK,
    SKIP_CONFLICTS,
    UPDATE_EXISTING_ONLY
}
