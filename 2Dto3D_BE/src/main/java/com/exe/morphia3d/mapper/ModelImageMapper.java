package com.exe.morphia3d.mapper;

import com.exe.morphia3d.dto.response.ModelImageResponse;
import com.exe.morphia3d.entity.ModelImage;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ModelImageMapper {
    ModelImageResponse toResponse(ModelImage modelImage);
}
