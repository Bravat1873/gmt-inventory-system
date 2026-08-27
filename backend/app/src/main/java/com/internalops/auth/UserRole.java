package com.internalops.auth;

public enum UserRole {
    ADMIN, FINANCE, USER;

    public boolean canEditProductPrice() {
        return this == ADMIN || this == FINANCE;
    }

    public boolean canManageRoles() {
        return this == ADMIN;
    }

    public boolean canManageUsers() {
        return this == ADMIN;
    }

    public boolean canWriteFinance() {
        return this != FINANCE;
    }
}
