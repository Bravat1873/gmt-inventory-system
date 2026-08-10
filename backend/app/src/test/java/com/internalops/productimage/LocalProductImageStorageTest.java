package com.internalops.productimage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalProductImageStorageTest {
    @TempDir
    Path root;

    @Test
    void stores_reads_and_deletes_under_the_configured_root() throws Exception {
        var storage = new LocalProductImageStorage(root);

        storage.store(42L, "42/abc.jpg", "image".getBytes(UTF_8));

        assertThat(storage.read("42/abc.jpg").content()).isEqualTo("image".getBytes(UTF_8));
        storage.delete("42/abc.jpg");
        assertThatThrownBy(() -> storage.read("42/abc.jpg")).isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void rejects_paths_that_escape_the_root() {
        var storage = new LocalProductImageStorage(root);

        assertThatThrownBy(() -> storage.store(42L, "../escape.jpg", new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
