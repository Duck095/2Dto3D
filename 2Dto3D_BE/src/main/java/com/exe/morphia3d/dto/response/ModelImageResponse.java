package com.exe.morphia3d.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ModelImageResponse {
    private Integer id;

    private String imagePath;

    private Boolean isPrimary;

    private LocalDateTime uploadedAt;
}
