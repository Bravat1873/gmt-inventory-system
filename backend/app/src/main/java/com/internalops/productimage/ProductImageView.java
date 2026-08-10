package com.internalops.productimage;

public record ProductImageView(long id, long productId, String originalFilename, String contentType,
                               long fileSize, boolean primary, int sortOrder, String contentUrl) {
}
