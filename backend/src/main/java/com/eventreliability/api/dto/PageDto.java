package com.eventreliability.api.dto;

import java.util.List;

/** A simple page envelope for list endpoints (§15). */
public record PageDto<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static <T> PageDto<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PageDto<>(content, page, size, totalElements, totalPages);
    }
}
