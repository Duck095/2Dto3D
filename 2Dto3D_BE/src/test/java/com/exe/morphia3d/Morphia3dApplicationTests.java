package com.exe.morphia3d;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = Morphia3dApplication.class)
@ActiveProfiles("test")
class Morphia3dApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context.getBeansOfType(com.exe.morphia3d.service.payment.payos.PayosSensitiveDataCipher.class)).isEmpty();
        assertThat(context.getBeansOfType(com.exe.morphia3d.service.payment.payos.PayosPaymentPersistenceService.class)).isEmpty();
        assertThat(context.getBeansOfType(com.exe.morphia3d.service.PayosWebhookProcessingService.class)).isEmpty();
        assertThat(context.getBeansOfType(com.exe.morphia3d.service.PayosReconciliationProcessingService.class)).isEmpty();
        assertThat(context.getBean(com.exe.morphia3d.service.impl.PaymentServiceImpl.class)).isNotNull();
    }

}
