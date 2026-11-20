package com.exe.morphia3d.mapper;

import com.exe.morphia3d.dto.response.OrderItemResponse;
import com.exe.morphia3d.entity.OrderItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface OrderItemMapper {
    @Mapping(target = "modelId", source = "model.id")
    OrderItemResponse toResponse(OrderItem orderItem);
}
