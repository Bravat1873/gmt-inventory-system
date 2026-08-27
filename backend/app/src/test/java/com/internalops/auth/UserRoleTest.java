package com.internalops.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserRoleTest {
    @Test
    void onlyAdministratorCanWriteFinance() {
        assertThat(UserRole.ADMIN.canWriteFinance()).isTrue();
        assertThat(UserRole.FINANCE.canWriteFinance()).isFalse();
        assertThat(UserRole.USER.canWriteFinance()).isFalse();
    }
}
