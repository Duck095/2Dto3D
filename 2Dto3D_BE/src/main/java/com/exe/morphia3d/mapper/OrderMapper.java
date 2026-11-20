package com.exe.morphia3d.mapper;

import com.exe.morphia3d.dto.response.OrderResponse;
import com.exe.morphia3d.entity.Order;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", uses = {OrderItemMapper.class})
public interface OrderMapper {
    @Mapping(target = "items", ignore = true)
    OrderResponse toResponse(Order order);
}
