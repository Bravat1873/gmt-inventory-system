package com.internalops.workbench;

public record InventoryLockedAllocationCommand(String lockSource, Integer quantity) {}
