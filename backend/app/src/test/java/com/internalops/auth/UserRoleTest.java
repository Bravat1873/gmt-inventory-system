package com.internalops.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserRoleTest {
    @Test
    void administratorsAndFinanceUsersCanWriteFinanceButOnlyAdministratorsCanMaintainInvoices() {
        assertThat(UserRole.ADMIN.canWriteFinance()).isTrue();
        assertThat(UserRole.FINANCE.canWriteFinance()).isTrue();
        assertThat(UserRole.USER.canWriteFinance()).isFalse();
        assertThat(UserRole.ADMIN.canMaintainInvoices()).isTrue();
        assertThat(UserRole.FINANCE.canMaintainInvoices()).isFalse();
        assertThat(UserRole.USER.canMaintainInvoices()).isFalse();
    }
}
