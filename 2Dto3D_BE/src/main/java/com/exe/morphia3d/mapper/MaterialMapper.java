package com.exe.morphia3d.mapper;

import com.exe.morphia3d.dto.response.MaterialResponse;
import com.exe.morphia3d.entity.Material;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", uses = MaterialColorMapper.class)
public interface MaterialMapper {

    @Mapping(target = "colors", source = "materialColors")
    MaterialResponse toResponse(Material material);
}
