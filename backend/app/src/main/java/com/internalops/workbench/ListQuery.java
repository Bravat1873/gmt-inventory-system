package com.internalops.workbench;

public record ListQuery(int page, String keyword, String sort, String direction) {
    public static final int PAGE_SIZE = 10;

    public ListQuery {
        page = Math.max(1, page);
        keyword = keyword == null ? "" : keyword.trim();
        sort = sort == null ? "" : sort.trim();
        direction = direction == null || direction.isBlank() ? "desc" : direction.trim().toLowerCase();
        if (!direction.equals("asc") && !direction.equals("desc")) {
            throw new IllegalArgumentException("排序方向只能是升序或降序");
        }
    }

    public int offset() {
        return (page - 1) * PAGE_SIZE;
    }
}
