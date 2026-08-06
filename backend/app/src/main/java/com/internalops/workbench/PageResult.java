package com.internalops.workbench;

import java.util.List;

public record PageResult<T>(List<T> items, long total, int page, int pageSize, int totalPages) {
    public static <T> PageResult<T> of(List<T> items, long total, int page) {
        int pages = (int) Math.ceil(total / (double) ListQuery.PAGE_SIZE);
        return new PageResult<>(items, total, page, ListQuery.PAGE_SIZE, pages);
    }
}
