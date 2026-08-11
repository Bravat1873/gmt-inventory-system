package com.internalops.workbench;

import java.util.List;

public record OrderAllocationRequest(Integer version, List<Item> items) {
    public record Item(Integer lineNo, Integer lockedQuantity) {}
}
