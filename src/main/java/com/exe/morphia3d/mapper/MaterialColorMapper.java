package com.exe.morphia3d.mapper;

import com.exe.morphia3d.dto.response.ColorResponse;
import com.exe.morphia3d.entity.MaterialColor;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", uses = ColorMapper.class)
public interface MaterialColorMapper {

    @Mapping(target = "colorId", source = "color.id")
    @Mapping(target = "name", source = "color.name")
    @Mapping(target = "hexCode", source = "color.hexCode")
    ColorResponse toResponse(MaterialColor materialColor);
}