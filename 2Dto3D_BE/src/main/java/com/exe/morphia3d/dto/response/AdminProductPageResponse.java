package com.exe.morphia3d.dto.response;

import java.util.List;

public record AdminProductPageResponse(List<ModelResponse> items, int page, int size,
                                      long totalElements, int totalPages) {
}
