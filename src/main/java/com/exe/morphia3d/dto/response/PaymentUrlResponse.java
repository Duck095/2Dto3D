package com.exe.morphia3d.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PaymentUrlResponse {
    private String paymentUrl; // FE redirect user sang URL này
    private Integer paymentId;
}