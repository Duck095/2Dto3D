package com.exe.morphia3d.service;

public class OrderStatusTransitionException extends IllegalStateException {
    public OrderStatusTransitionException(String message) {
        super(message);
    }
}
