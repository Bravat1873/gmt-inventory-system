package com.internalops.workbench;

public record InventoryMovementCommand(String date, String direction, Integer quantity) {
}
