package com.exe.morphia3d.dto.request;

import lombok.Data;

@Data
public class CreateOrderRequest {
    private Integer cartItemId; // null = checkout toàn bộ cart, có giá trị = checkout 1 item
}