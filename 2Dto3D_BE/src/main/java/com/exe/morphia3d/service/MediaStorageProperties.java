package com.exe.morphia3d.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "media.storage")
public class MediaStorageProperties {

    private int connectTimeoutMillis = 5_000;
    private int connectionRequestTimeoutMillis = 5_000;
    private int requestTimeoutMillis = 60_000;

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public void setConnectTimeoutMillis(int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    public int getConnectionRequestTimeoutMillis() {
        return connectionRequestTimeoutMillis;
    }

    public void setConnectionRequestTimeoutMillis(int connectionRequestTimeoutMillis) {
        this.connectionRequestTimeoutMillis = connectionRequestTimeoutMillis;
    }

    public int getRequestTimeoutMillis() {
        return requestTimeoutMillis;
    }

    public void setRequestTimeoutMillis(int requestTimeoutMillis) {
        this.requestTimeoutMillis = requestTimeoutMillis;
    }

    public void validate() {
        if (connectTimeoutMillis <= 0 || connectionRequestTimeoutMillis <= 0 || requestTimeoutMillis <= 0) {
            throw new IllegalStateException("Cloudinary timeout settings must be positive");
        }
    }
}
