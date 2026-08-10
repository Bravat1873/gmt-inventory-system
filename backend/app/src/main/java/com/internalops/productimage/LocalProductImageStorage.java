package com.internalops.productimage;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Component
public final class LocalProductImageStorage implements ProductImageStorage {
    private final Path root;

    @Autowired
    public LocalProductImageStorage(@Value("${internal-ops.product-image-root}") String root) {
        this(Path.of(root));
    }

    LocalProductImageStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void store(long productId, String storageKey, byte[] content) throws IOException {
        Path target = resolve(storageKey);
        Files.createDirectories(target.getParent());
        Files.write(target, content, StandardOpenOption.CREATE_NEW);
    }

    @Override
    public ProductImageFile read(String storageKey) throws IOException {
        byte[] content = Files.readAllBytes(resolve(storageKey));
        return new ProductImageFile(content, content.length);
    }

    @Override
    public void delete(String storageKey) throws IOException {
        Files.deleteIfExists(resolve(storageKey));
    }

    private Path resolve(String storageKey) {
        Path resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Invalid product image storage path");
        }
        return resolved;
    }
}
