package com.exe.morphia3d.mapper;

import com.exe.morphia3d.dto.response.ColorResponse;
import com.exe.morphia3d.entity.Color;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ColorMapper {

    @Mapping(target = "colorId", source = "id")
    ColorResponse toResponse(Color color);
}