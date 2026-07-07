package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import java.util.List;

import lombok.Data;

/**
 * PagedResponseWrapper — generic paginated payload for the Login Activity
 * module tables (mirrors the pagination metadata style of UsersResponseWrapper).
 */
@Data
public class PagedResponseWrapper<T> {

    private List<T> content;
    private int currentPage;
    private int pageSize;
    private long totalElements;
    private int totalPages;

    public static <T> PagedResponseWrapper<T> of(List<T> content, int page, int size, long total) {
        PagedResponseWrapper<T> w = new PagedResponseWrapper<>();
        w.setContent(content);
        w.setCurrentPage(page);
        w.setPageSize(size);
        w.setTotalElements(total);
        w.setTotalPages(size > 0 ? (int) Math.ceil((double) total / size) : 0);
        return w;
    }
}
