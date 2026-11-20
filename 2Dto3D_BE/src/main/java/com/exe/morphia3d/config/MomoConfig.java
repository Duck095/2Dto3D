package com.exe.morphia3d.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

@Component
@Profile("!qa")
@ConfigurationProperties(prefix = "momo")
@Validated
@Getter
@Setter
public class MomoConfig {
    @NotBlank(message = "momo.partner-code must not be blank")
    private String partnerCode;

    @NotBlank(message = "momo.access-key must not be blank")
    private String accessKey;

    @NotBlank(message = "momo.secret-key must not be blank")
    private String secretKey;
    private String endpoint;
    private String redirectUrl;
    private String ipnUrl;
    private String requestType;
}
