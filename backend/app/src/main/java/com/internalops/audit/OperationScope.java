package com.internalops.audit;

/** Request-scoped identity shared by commands and the undo endpoint. */
public final class OperationScope {
    private static final ThreadLocal<Long> ID = new ThreadLocal<>();
    private OperationScope() { }
    public static Long current() { return ID.get(); }
    static void set(long id) { ID.set(id); }
    static void clear() { ID.remove(); }
}
