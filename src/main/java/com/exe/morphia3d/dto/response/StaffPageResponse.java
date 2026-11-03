package com.exe.morphia3d.dto.response;

import com.exe.morphia3d.entity.StaffProfile;
import org.springframework.data.domain.Page;

import java.util.List;

public record StaffPageResponse(
        List<StaffProfileResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static StaffPageResponse from(Page<StaffProfile> page) {
        return new StaffPageResponse(page.getContent().stream().map(StaffProfileResponse::from).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
