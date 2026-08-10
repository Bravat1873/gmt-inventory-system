package com.internalops.productimage;

import java.util.List;

public record ProductImageOrderRequest(List<Long> imageIds) {
}
