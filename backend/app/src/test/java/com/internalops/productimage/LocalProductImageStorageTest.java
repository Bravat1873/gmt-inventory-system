package com.internalops.productimage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalProductImageStorageTest {
    @TempDir
    Path root;

    @Test
    void creates_storage_from_the_configured_root_in_a_spring_context() {
        new ApplicationContextRunner()
                .withPropertyValues("internal-ops.product-image-root=" + root)
                .withBean(LocalProductImageStorage.class)
                .run(context -> assertThat(context).hasSingleBean(LocalProductImageStorage.class));
    }

    @Test
    void uses_the_default_root_when_the_property_is_not_provided() {
        String storageKey = "context-default-root/" + System.nanoTime() + ".bin";
        byte[] content = "default-image".getBytes(UTF_8);

        new ApplicationContextRunner()
                .withInitializer(context -> {
                    context.getEnvironment().getPropertySources()
                            .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    context.getEnvironment().getPropertySources()
                            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                })
                .withBean(PropertySourcesPlaceholderConfigurer.class)
                .withBean(LocalProductImageStorage.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(LocalProductImageStorage.class);
                    var storage = context.getBean(LocalProductImageStorage.class);

                    storage.store(42L, storageKey, content);

                    assertThat(storage.read(storageKey).content()).isEqualTo(content);
                    storage.delete(storageKey);
                });
    }

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
