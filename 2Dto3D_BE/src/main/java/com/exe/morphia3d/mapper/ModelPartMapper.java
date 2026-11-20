package com.exe.morphia3d.mapper;

import com.exe.morphia3d.dto.response.ModelPartResponse;
import com.exe.morphia3d.entity.ModelPart;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring", uses = {ColorMapper.class})
public interface ModelPartMapper {
    ModelPartResponse toResponse(ModelPart modelPart);
}
