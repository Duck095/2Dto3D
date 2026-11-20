package com.exe.morphia3d.service;

public class OrderConfigurationUnavailableException extends RuntimeException {
    public OrderConfigurationUnavailableException() {
        super("Model or print configuration is unavailable for checkout");
    }
}
