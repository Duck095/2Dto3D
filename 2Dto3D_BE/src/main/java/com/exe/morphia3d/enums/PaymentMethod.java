package com.exe.morphia3d.enums;

public enum PaymentMethod {
    BANK_TRANSFER,
    /** Historical values remain readable; PaymentServiceImpl rejects them for new attempts. */
    VNPAY,
    MOMO
}
