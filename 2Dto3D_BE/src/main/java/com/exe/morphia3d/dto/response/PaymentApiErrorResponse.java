package com.exe.morphia3d.dto.response;

public record PaymentApiErrorResponse(int status, String error, String message) {
}
