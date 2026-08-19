package com.internalops.productcode;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductUniqueIdTest {
    @Test
    void trimsAndUppercasesBothParts() {
        assertThat(ProductUniqueId.from(" g_t5 ", " g8t5 ")).isEqualTo("G_T5::G8T5");
    }

    @Test
    void rejectsBlankProductCode() {
        assertThatThrownBy(() -> ProductUniqueId.from(" ", "G8T5"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("产品编号和客户料号不能为空");
    }

    @Test
    void rejectsBlankCustomerPartNumber() {
        assertThatThrownBy(() -> ProductUniqueId.from("G_T5", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("产品编号和客户料号不能为空");
    }
}
