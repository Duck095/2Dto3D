package com.exe.morphia3d.mapper;

import com.exe.morphia3d.dto.response.ModelResponse;
import com.exe.morphia3d.entity.Model3d;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring",
        uses = {ModelPartMapper.class, ModelImageMapper.class})
public interface ModelMapper {
    @Mapping(target = "userId", source = "user.id")
    @Mapping(target = "modelParts", source = "parts")
    @Mapping(target = "modelImages", source = "images")
    @Mapping(target = "volumeCm3", ignore = true)
    ModelResponse toResponse(Model3d model3d);
}
