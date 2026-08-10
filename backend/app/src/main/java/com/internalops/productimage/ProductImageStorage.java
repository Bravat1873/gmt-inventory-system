package com.internalops.productimage;

import java.io.IOException;

public interface ProductImageStorage {
    void store(long productId, String storageKey, byte[] content) throws IOException;

    ProductImageFile read(String storageKey) throws IOException;

    void delete(String storageKey) throws IOException;
}
