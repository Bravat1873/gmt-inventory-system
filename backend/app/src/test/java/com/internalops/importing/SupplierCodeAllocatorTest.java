package com.internalops.importing;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupplierCodeAllocatorTest {
    @Test
    void treatsExistingCodesCaseInsensitively() {
        assertThat(ImportCommitService.nextSupplierCode(Set.of("sup00001")))
                .isEqualTo("SUP00002");
    }

    @Test
    void continuesAfterTheHighestStrictFiveDigitSupplierCode() {
        assertThat(ImportCommitService.nextSupplierCode(Set.of("sup00009")))
                .isEqualTo("SUP00010");
    }

    @Test
    void wrapsToTheFirstAvailableCodeWhenTheHighestCodeIsUsed() {
        assertThat(ImportCommitService.nextSupplierCode(Set.of("SUP99999")))
                .isEqualTo("SUP00001");
    }

    @Test
    void rejectsWhenAllFiveDigitSupplierCodesAreUsed() {
        Set<String> codes = IntStream.rangeClosed(1, 99_999)
                .mapToObj(number -> "SUP" + String.format(Locale.ROOT, "%05d", number))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThatThrownBy(() -> ImportCommitService.nextSupplierCode(codes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("\u4F9B\u5E94\u5546\u7F16\u7801 SUP \u4E94\u4F4D\u5E8F\u53F7\u5DF2\u8017\u5C3D");
    }
}
