package com.internalops.auth;

public record CurrentUser(long id, String username, String displayName, UserRole role) {
    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();
    public CurrentUser(long id, String username, String displayName) {
        this(id, username, displayName, UserRole.USER);
    }
    public static void set(CurrentUser user) { HOLDER.set(user); }
    public static void clear() { HOLDER.remove(); }
    public static CurrentUser get() { return HOLDER.get(); }
    public static CurrentUser required() {
        CurrentUser user = HOLDER.get();
        if (user == null) throw new IllegalStateException("请先登录");
        return user;
    }
}
