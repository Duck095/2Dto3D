package com.exe.morphia3d.dto.response;

import java.util.List;

public record PaginatedProductResponse(List<ProductSummaryResponse> items, int page, int size,
                                       long totalElements, int totalPages) {
}
