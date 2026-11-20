package com.exe.morphia3d.dto.response;

public record ProductSummaryResponse(Integer id, String name, String slug, String description,
                                     String category, String thumbnailUrl) {
}
