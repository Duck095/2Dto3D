package com.exe.morphia3d.service;

public class OrderCartEmptyException extends RuntimeException {
    public OrderCartEmptyException() {
        super("Cart is empty; an order cannot be created");
    }
}
