package com.internalops.importing;

public record ImportCommitRequest(ImportConflictPolicy conflictPolicy) {
    public ImportConflictPolicy resolvedPolicy() {
        return conflictPolicy == null ? ImportConflictPolicy.UPSERT_KEEP_EXISTING_ON_BLANK : conflictPolicy;
    }
}
