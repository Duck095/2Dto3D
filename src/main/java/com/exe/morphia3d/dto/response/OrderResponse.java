package com.exe.morphia3d.dto.response;

import com.exe.morphia3d.enums.OrderStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class OrderResponse {
    private Integer id;
    private OrderStatus status;
    private Double totalAmount;
    private LocalDateTime orderDate;
    private LocalDateTime completedDate;
    private List<OrderItemResponse> items;

}