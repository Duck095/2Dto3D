package com.exe.morphia3d.dto.request;

import com.exe.morphia3d.enums.OrderStatus;
import lombok.Data;

@Data
public class UpdateOrderStatusRequest {
    private OrderStatus status;
}
