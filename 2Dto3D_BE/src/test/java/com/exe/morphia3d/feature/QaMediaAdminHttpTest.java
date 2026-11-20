package com.exe.morphia3d.feature;

import com.exe.morphia3d.config.FeatureGateConfiguration;
import com.exe.morphia3d.config.QaDisabledProviderConfiguration;
import com.exe.morphia3d.config.SecurityConfig;
import com.exe.morphia3d.controller.AdminProductController;
import com.exe.morphia3d.controller.PaymentController;
import com.exe.morphia3d.controller.PaymentApiExceptionHandler;
import com.exe.morphia3d.dto.response.ModelResponse;
import com.exe.morphia3d.security.JwtAuthFilter;
import com.exe.morphia3d.security.JwtUtil;
import com.exe.morphia3d.security.UserDetailsServiceImpl;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.feature.DisabledPaymentGatewayService;
import com.exe.morphia3d.service.AdminProductCatalogService;
import com.exe.morphia3d.service.DesignerModelUploadService;
import com.exe.morphia3d.service.Model3dService;
import com.exe.morphia3d.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class QaMediaAdminHttpTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(AdminMediaSecurityTestConfiguration.class)
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("qa"));

    @Test
    void defaultQaReturns503BeforeCallingAdminUploadService() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            MockMvc mvc = mvc(context);
            DesignerModelUploadService upload = context.getBean(DesignerModelUploadService.class);
            try {
                mvc.perform(uploadRequest().with(authentication(adminAuthentication())))
                        .andExpect(status().isServiceUnavailable());
            } catch (Exception error) {
                throw new AssertionError(error);
            }
            verifyNoInteractions(upload);
        });
    }

    @Test
    void approvedMediaLivePreservesAnonymousUserAndAdminBehaviorAtTheRealController() {
        runner.withPropertyValues("app.qa-media.live-enabled=true", "app.qa-media.live-approved=true",
                        "app.qa-media.cloud-name=isolated-qa-cloud", "app.qa-media.api-key=123456789012",
                        "app.qa-media.api-secret=nonfunctional-test-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MockMvc mvc = mvc(context);
                    DesignerModelUploadService upload = context.getBean(DesignerModelUploadService.class);
                    assertThat(context.getBean("vnPayService")).isInstanceOf(DisabledPaymentGatewayService.class);
                    assertThat(context.getBean("momoService")).isInstanceOf(DisabledPaymentGatewayService.class);
                    when(upload.create(any(), any(), anyList(), any())).thenReturn(mock(ModelResponse.class));
                    try {
                        mvc.perform(uploadRequest()).andExpect(status().isUnauthorized());
                        mvc.perform(uploadRequest().with(user("buyer").roles("USER")))
                                .andExpect(status().isForbidden());
                        mvc.perform(uploadRequest().with(authentication(adminAuthentication())))
                                .andExpect(status().isCreated());
                        mvc.perform(get("/api/cart")).andExpect(status().isServiceUnavailable())
                                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                                        .contains("\"featureFamily\":\"CART\""));
                        mvc.perform(get("/api/quotes")).andExpect(status().isServiceUnavailable())
                                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                                        .contains("\"featureFamily\":\"QUOTE\""));
                        mvc.perform(get("/api/orders/9")).andExpect(status().isServiceUnavailable())
                                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                                        .contains("\"featureFamily\":\"ORDER\""));
                        mvc.perform(post("/api/checkout")).andExpect(status().isServiceUnavailable())
                                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                                        .contains("\"featureFamily\":\"CHECKOUT\""));
                        mvc.perform(get("/api/payments/readiness").param("orderId", "9"))
                                .andExpect(status().isUnauthorized());
                        mvc.perform(get("/api/payments/readiness").param("orderId", "9")
                                        .with(authentication(adminAuthentication())))
                                .andExpect(status().isServiceUnavailable())
                                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                                        .contains("\"error\":\"PAYMENT_FEATURE_DISABLED\""));
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    }
                    verify(upload).create(any(UserPrincipal.class), any(), anyList(), any());
                });
    }

    private static MockMultipartHttpServletRequestBuilder uploadRequest() {
        return multipart("/api/admin/products")
                .file(new MockMultipartFile("data", "data.json", "application/json", "{}".getBytes()))
                .file(new MockMultipartFile("images", "image.png", "image/png", new byte[]{1}))
                .file(new MockMultipartFile("model", "model.glb", "model/gltf-binary", new byte[]{1}));
    }

    private static UsernamePasswordAuthenticationToken adminAuthentication() {
        UserPrincipal principal = mock(UserPrincipal.class);
        when(principal.getId()).thenReturn(7);
        return new UsernamePasswordAuthenticationToken(principal, "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static MockMvc mvc(org.springframework.context.ApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                .apply(springSecurity()).build();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, FeatureGateConfiguration.class, QaDisabledProviderConfiguration.class,
            AdminProductController.class, PaymentController.class, PaymentApiExceptionHandler.class})
    static class AdminMediaSecurityTestConfiguration {
        @Bean UserDetailsServiceImpl userDetailsService() { return mock(UserDetailsServiceImpl.class); }
        @Bean JwtUtil jwtUtil() { return mock(JwtUtil.class); }
        @Bean JwtAuthFilter jwtAuthFilter(JwtUtil jwt, UserDetailsServiceImpl users) {
            return new JwtAuthFilter(jwt, users);
        }
        @Bean AdminProductCatalogService catalog() { return mock(AdminProductCatalogService.class); }
        @Bean DesignerModelUploadService upload() { return mock(DesignerModelUploadService.class); }
        @Bean Model3dService publication() { return mock(Model3dService.class); }
        @Bean PaymentService paymentService() { return mock(PaymentService.class); }
    }
}
