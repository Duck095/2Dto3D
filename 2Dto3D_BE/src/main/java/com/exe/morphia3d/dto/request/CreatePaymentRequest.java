package com.exe.morphia3d.dto.request;

import com.exe.morphia3d.enums.PaymentMethod;
import lombok.Data;

@Data
public class CreatePaymentRequest {
    private Integer orderId;
    private PaymentMethod method; // BANK_TRANSFER là lựa chọn duy nhất cho giao dịch mới
}
