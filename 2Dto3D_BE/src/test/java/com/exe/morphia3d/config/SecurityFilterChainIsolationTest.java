package com.exe.morphia3d.config;

import com.exe.morphia3d.controller.PaymentController;
import com.exe.morphia3d.controller.DesignArtifactController;
import com.exe.morphia3d.controller.DesignPipelineController;
import com.exe.morphia3d.controller.DesignProjectController;
import com.exe.morphia3d.controller.DesignProjectExceptionHandler;
import com.exe.morphia3d.design.DesignArtifactStore;
import com.exe.morphia3d.design.DesignPipelineCommandService;
import com.exe.morphia3d.design.DesignPipelineStatusService;
import com.exe.morphia3d.design.DesignProjectException;
import com.exe.morphia3d.design.DesignProjectService;
import com.exe.morphia3d.design.DesignProjectTimelineService;
import com.exe.morphia3d.design.DesignQuotaException;
import com.exe.morphia3d.design.ProviderUnavailableException;
import com.exe.morphia3d.entity.DesignProject;
import com.exe.morphia3d.entity.DesignProjectInput;
import com.exe.morphia3d.repository.DesignModelVersionRepository;
import com.exe.morphia3d.repository.DesignPipelineJobRepository;
import com.exe.morphia3d.repository.DesignProjectInputRepository;
import com.exe.morphia3d.repository.DesignProjectRepository;
import com.exe.morphia3d.repository.DesignReferenceImageVersionRepository;
import com.exe.morphia3d.repository.DesignReferenceSetRepository;
import com.exe.morphia3d.repository.DesignReferenceSetVersionRepository;
import com.exe.morphia3d.repository.DesignReferenceSetViewRepository;
import com.exe.morphia3d.feature.FeatureGateFilter;
import com.exe.morphia3d.feature.QaMediaUploadFilter;
import com.exe.morphia3d.security.JwtAuthFilter;
import com.exe.morphia3d.security.JwtUtil;
import com.exe.morphia3d.security.UserDetailsServiceImpl;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.PaymentService;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.UserStatus;
import com.exe.morphia3d.dto.request.CreatePaymentAttemptRequest;
import com.exe.morphia3d.dto.response.PaymentAttemptResponse;
import com.exe.morphia3d.dto.response.PaymentReadinessResponse;
import com.exe.morphia3d.domain.payment.PaymentAttemptStatus;
import com.exe.morphia3d.enums.PaymentMethod;
import com.exe.morphia3d.enums.PaymentProvider;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class SecurityFilterChainIsolationTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(IsolatedSecurityConfiguration.class);

    @Test
    void nonQaSecurityChainBuildsInRequiredOrderWithoutPersistenceAndDisablesServletJwtRegistration() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertDatabaseFree(context);
            List<jakarta.servlet.Filter> filters = context.getBean(org.springframework.security.web.SecurityFilterChain.class)
                    .getFilters();
            assertThat(indexOf(filters, FeatureGateFilter.class)).isLessThan(indexOf(filters, JwtAuthFilter.class));
            assertThat(indexOf(filters, QaMediaUploadFilter.class)).isEqualTo(-1);
            assertThat(filters.stream().filter(JwtAuthFilter.class::isInstance)).hasSize(1);
            FilterRegistrationBean<?> jwtServletRegistration = context.getBean(
                    "jwtAuthFilterRegistration", FilterRegistrationBean.class);
            assertThat(jwtServletRegistration.isEnabled()).isFalse();
        });
    }

    @Test
    void qaSecurityChainInstallsMediaThenFeatureThenJwtAndBlocksMultipartBeforeAuthorization() throws Exception {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("qa"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertDatabaseFree(context);
                    List<jakarta.servlet.Filter> filters = context.getBean(
                            org.springframework.security.web.SecurityFilterChain.class).getFilters();
                    int media = indexOf(filters, QaMediaUploadFilter.class);
                    int feature = indexOf(filters, FeatureGateFilter.class);
                    int jwt = indexOf(filters, JwtAuthFilter.class);
                    assertThat(media).isGreaterThanOrEqualTo(0).isLessThan(feature);
                    assertThat(feature).isLessThan(jwt);

                    MockMvc mvc = mvc(context);
                    try {
                        mvc.perform(multipart("/api/admin/products")
                                        .file(new MockMultipartFile("file", "part.glb",
                                                MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[]{1})))
                                .andExpect(status().isServiceUnavailable());
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    }
                });
    }

    @Test
    void qaMediaLiveKeepsAdminAuthorizationAndBlocksDesignerAndFutureMultipartRoutes() throws Exception {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("qa"))
                .withPropertyValues("app.qa-media.live-enabled=true", "app.qa-media.live-approved=true",
                        "app.qa-media.cloud-name=isolated-qa-cloud", "app.qa-media.api-key=123456789012",
                        "app.qa-media.api-secret=nonfunctional-test-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MockMvc mvc = mvc(context);
                    RouteProbe probe = context.getBean(RouteProbe.class);
                    MockMultipartFile file = new MockMultipartFile("file", "part.png",
                            MediaType.IMAGE_PNG_VALUE, new byte[]{1});
                    try {
                        mvc.perform(multipart("/api/admin/products").file(file))
                                .andExpect(status().isUnauthorized());
                        mvc.perform(multipart("/api/admin/products").file(file).with(user("buyer").roles("USER")))
                                .andExpect(status().isForbidden());
                        mvc.perform(multipart("/api/admin/products").file(file).with(user("admin").roles("ADMIN")))
                                .andExpect(status().isOk());
                        mvc.perform(multipart("/api/designer-models").file(file).with(user("admin").roles("ADMIN")))
                                .andExpect(status().isServiceUnavailable());
                        mvc.perform(multipart("/api/future-upload").file(file).with(user("admin").roles("ADMIN")))
                                .andExpect(status().isServiceUnavailable());
                        mvc.perform(multipart("/api/ai-models").file(file))
                                .andExpect(status().isServiceUnavailable())
                                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                                        .contains("\"featureFamily\":\"AI\""));
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    }
                    assertThat(probe.calls("admin-upload")).isEqualTo(1);
                });
    }

    @Test
    void disabledPaymentRoutesReturn503OnlyAfterAuthenticationAndDoNotReachServices() throws Exception {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertDatabaseFree(context);
            MockMvc mvc = mvc(context);
            PaymentService payments = context.getBean(PaymentService.class);
            RouteProbe probe = context.getBean(RouteProbe.class);
            try {
                mvc.perform(get("/api/ai-models/7")).andExpect(disabledResponse("AI"));
                mvc.perform(get("/api/cart")).andExpect(disabledResponse("CART"));
                mvc.perform(get("/api/orders/7")).andExpect(disabledResponse("ORDER"));
                mvc.perform(get("/api/payments/readiness").param("orderId", "7"))
                        .andExpect(status().isUnauthorized());
                mvc.perform(get("/api/payments/readiness").param("orderId", "7")
                                .with(authentication(authFor(42, Role.USER))))
                        .andExpect(paymentDisabledResponse());
                mvc.perform(post("/api/payments/attempts").with(authentication(authFor(42, Role.USER)))
                                .header("Idempotency-Key", "key-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"orderId\":7,\"method\":\"MOMO\"}"))
                        .andExpect(paymentDisabledResponse());
                mvc.perform(get("/api/admin/products")).andExpect(status().isUnauthorized());
                mvc.perform(get("/api/admin/staff")).andExpect(status().isUnauthorized());
                mvc.perform(get("/api/admin/tasks")).andExpect(status().isUnauthorized());
                mvc.perform(get("/api/v1/me/tasks")).andExpect(status().isUnauthorized());
            } catch (Exception error) {
                throw new AssertionError(error);
            }
            verifyNoInteractions(payments);
            assertThat(probe.totalCalls()).isZero();
        });
    }

    @Test
    void enabledPaymentContractRequiresUserRoleAndDoesNotExposeLegacyRedirectOrIpnRoutes() throws Exception {
        runner.withPropertyValues("app.features.ai.enabled=true", "app.features.cart.enabled=true",
                        "app.features.order.enabled=true", "app.features.checkout.enabled=true",
                        "app.features.payment.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertDatabaseFree(context);
                    MockMvc mvc = mvc(context);
                    PaymentService payments = context.getBean(PaymentService.class);
                    RouteProbe probe = context.getBean(RouteProbe.class);
                    try {
                        mvc.perform(get("/api/ai-models/7")).andExpect(status().isUnauthorized());
                        mvc.perform(get("/api/ai-models/7").with(user("buyer").roles("USER")))
                                .andExpect(status().isOk());
                        mvc.perform(get("/api/cart").with(user("buyer").roles("USER")))
                                .andExpect(status().isOk());
                        mvc.perform(get("/api/orders/7").with(user("buyer").roles("USER")))
                                .andExpect(status().isOk());
                        mvc.perform(post("/api/orders").with(user("buyer").roles("USER")))
                                .andExpect(status().isOk());
                        mvc.perform(post("/api/orders/from-cart-item/9").with(user("buyer").roles("USER")))
                                .andExpect(status().isOk());

                        mvc.perform(get("/api/admin/products").with(user("buyer").roles("USER")))
                                .andExpect(status().isForbidden());
                        mvc.perform(get("/api/admin/products").with(user("admin").roles("ADMIN")))
                                .andExpect(status().isOk());
                        mvc.perform(get("/api/admin/staff").with(user("buyer").roles("USER")))
                                .andExpect(status().isForbidden());
                        mvc.perform(get("/api/admin/staff").with(user("admin").roles("ADMIN")))
                                .andExpect(status().isOk());
                        mvc.perform(get("/api/admin/tasks").with(user("buyer").roles("USER")))
                                .andExpect(status().isForbidden());
                        mvc.perform(get("/api/admin/tasks").with(user("admin").roles("ADMIN")))
                                .andExpect(status().isOk());
                        mvc.perform(get("/api/v1/me/tasks").with(user("buyer").roles("USER")))
                                .andExpect(status().isForbidden());
                        mvc.perform(get("/api/v1/me/tasks").with(authentication(
                                        new UsernamePasswordAuthenticationToken("employee", "n/a",
                                                AuthorityUtils.createAuthorityList("internal.employee.access")))))
                                .andExpect(status().isOk());

                        UUID attemptId = UUID.fromString("f5ae8520-5472-4bc6-a6d4-c99cc86092ee");
                        PaymentReadinessResponse readiness = new PaymentReadinessResponse(7, true, null,
                                125000L, 10000L, 135000L, Map.of(PaymentProvider.VNPAY, false, PaymentProvider.MOMO, false));
                        PaymentAttemptResponse attempt = new PaymentAttemptResponse(attemptId, 7,
                                PaymentProvider.MOMO, 135000L, PaymentAttemptStatus.INITIATED, false, null, null);
                        org.mockito.Mockito.when(payments.getReadiness(42, 7)).thenReturn(readiness);
                        org.mockito.Mockito.when(payments.createAttempt(42,
                                new CreatePaymentAttemptRequest(7, PaymentMethod.MOMO), "key-1")).thenReturn(attempt);
                        org.mockito.Mockito.when(payments.getAttempt(42, attemptId)).thenReturn(attempt);
                        mvc.perform(get("/api/payments/readiness").param("orderId", "7")
                                        .with(authentication(authFor(42, Role.USER))))
                                .andExpect(status().isOk());
                        mvc.perform(post("/api/payments/attempts").with(authentication(authFor(42, Role.USER)))
                                        .header("Idempotency-Key", "key-1")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"orderId\":7,\"method\":\"MOMO\"}"))
                                .andExpect(status().isOk());
                        mvc.perform(get("/api/payments/attempts/" + attemptId)
                                        .with(authentication(authFor(42, Role.USER))))
                                .andExpect(status().isOk());
                        mvc.perform(get("/api/payments/readiness").param("orderId", "7")
                                        .with(authentication(authFor(42, Role.DESIGNER))))
                                .andExpect(status().isForbidden());
                        mvc.perform(get("/api/payments/vnpay/return")
                                        .with(authentication(authFor(42, Role.USER))))
                                .andExpect(status().isNotFound());
                        mvc.perform(post("/api/payments/momo/ipn").with(authentication(authFor(42, Role.USER)))
                                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                                .andExpect(status().isNotFound());
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    }
                    verify(payments).getReadiness(42, 7);
                    verify(payments).createAttempt(42, new CreatePaymentAttemptRequest(7, PaymentMethod.MOMO), "key-1");
                    verify(payments).getAttempt(42, UUID.fromString("f5ae8520-5472-4bc6-a6d4-c99cc86092ee"));
                    assertThat(probe.calls("ai")).isEqualTo(1);
                    assertThat(probe.calls("cart")).isEqualTo(1);
                    assertThat(probe.calls("order")).isEqualTo(1);
                    assertThat(probe.calls("checkout")).isEqualTo(2);
                    assertThat(probe.calls("admin-staff")).isEqualTo(1);
                    assertThat(probe.calls("admin-tasks")).isEqualTo(1);
                    assertThat(probe.calls("employee-tasks")).isEqualTo(1);
                });
    }

    @Test
    void aiStudioPipelineReturnsControlledAuthenticationValidationAndStateErrors() throws Exception {
        runner.withPropertyValues("app.features.ai.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            MockMvc mvc = mvc(context);
            UUID projectId = UUID.randomUUID();
            DesignPipelineCommandService commands = context.getBean(DesignPipelineCommandService.class);
            DesignProjectRepository projects = context.getBean(DesignProjectRepository.class);
            try {
                mvc.perform(get("/api/ai-studio/projects/{projectId}/pipeline", projectId))
                        .andExpect(status().isUnauthorized());

                mvc.perform(get("/api/ai-studio/projects/{projectId}/pipeline", projectId)
                                .with(authentication(authFor(42, Role.DESIGNER))))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("DESIGN_ACCESS_DENIED"));

                mvc.perform(post("/api/ai-studio/projects/{projectId}/references/generate", projectId)
                                .with(authentication(authFor(42, Role.USER)))
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("DESIGN_INVALID_REQUEST"));

                mvc.perform(post("/api/ai-studio/projects/{projectId}/models/generate", projectId)
                                .with(authentication(authFor(42, Role.USER)))
                                .header("Idempotency-Key", "missing-reference")
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("DESIGN_INVALID_REQUEST"));

                String longFeedback = "x".repeat(2001);
                mvc.perform(post("/api/ai-studio/projects/{projectId}/references/generate", projectId)
                                .with(authentication(authFor(42, Role.USER)))
                                .header("Idempotency-Key", "long-feedback")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"revisionFeedback\":\"" + longFeedback + "\"}"))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("DESIGN_INVALID_REQUEST"));

                mvc.perform(get("/api/ai-studio/projects/{projectId}/pipeline", projectId)
                                .with(authentication(authFor(42, Role.USER))))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("DESIGN_PROJECT_NOT_FOUND"));
                org.mockito.Mockito.verify(projects).findByIdAndOwnerUserId(projectId, 42);

                DesignProject existingProject = new DesignProject();
                existingProject.setId(projectId);
                when(projects.findByIdAndOwnerUserId(projectId, 42)).thenReturn(Optional.of(existingProject));
                mvc.perform(get("/api/ai-studio/projects/{projectId}/pipeline", projectId)
                                .with(authentication(authFor(42, Role.USER))))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("DESIGN_REFERENCE_SET_NOT_FOUND"));

                doThrow(new DesignProjectException("DESIGN_APPROVAL_NOT_ALLOWED")).when(commands)
                        .enqueueReferences(42, projectId, "state-conflict", null, null);
                mvc.perform(post("/api/ai-studio/projects/{projectId}/references/generate", projectId)
                                .with(authentication(authFor(42, Role.USER))).header("Idempotency-Key", "state-conflict")
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("DESIGN_APPROVAL_NOT_ALLOWED"));

                doThrow(new DesignQuotaException("DESIGN_DAILY_LIMIT_EXCEEDED")).when(commands)
                        .enqueueReferences(42, projectId, "quota", null, null);
                mvc.perform(post("/api/ai-studio/projects/{projectId}/references/generate", projectId)
                                .with(authentication(authFor(42, Role.USER))).header("Idempotency-Key", "quota")
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().isTooManyRequests())
                        .andExpect(jsonPath("$.code").value("DESIGN_DAILY_LIMIT_EXCEEDED"));

                doThrow(new ProviderUnavailableException("test-only provider unavailable")).when(commands)
                        .enqueueReferences(42, projectId, "provider-unavailable", null, null);
                mvc.perform(post("/api/ai-studio/projects/{projectId}/references/generate", projectId)
                                .with(authentication(authFor(42, Role.USER))).header("Idempotency-Key", "provider-unavailable")
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.code").value("DESIGN_PROVIDER_UNAVAILABLE"));

                DesignProjectService projectService = context.getBean(DesignProjectService.class);
                var validProject = new com.exe.morphia3d.design.DesignProjectResponse(projectId, "figure", null,
                        "anime-figure", "TEXT", "character", "normalized", "v1", "DRAFT",
                        java.time.Instant.EPOCH, List.of());
                when(projectService.getForOwner(projectId, 42)).thenReturn(validProject);
                mvc.perform(get("/api/ai-studio/projects/{projectId}", projectId)
                                .with(authentication(authFor(42, Role.USER))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                        .andExpect(jsonPath("$.status").value("DRAFT"));
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        });
    }

    @Test
    void aiStudioArtifactReturnsNotFoundForOtherOwnersAndServiceUnavailableForStoreFailure() throws Exception {
        runner.withPropertyValues("app.features.ai.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            MockMvc mvc = mvc(context);
            UUID projectId = UUID.randomUUID();
            UUID inputId = UUID.randomUUID();
            DesignProjectRepository projects = context.getBean(DesignProjectRepository.class);
            DesignProjectInputRepository inputs = context.getBean(DesignProjectInputRepository.class);
            DesignArtifactStore artifacts = context.getBean(DesignArtifactStore.class);
            try {
                mvc.perform(get("/api/ai-studio/projects/{projectId}/inputs/{inputId}/content", projectId, inputId)
                                .with(authentication(authFor(42, Role.USER))))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("DESIGN_ARTIFACT_NOT_FOUND"));
                org.mockito.Mockito.verify(projects).findByIdAndOwnerUserId(projectId, 42);

                DesignProject project = new DesignProject();
                project.setId(projectId);
                when(projects.findByIdAndOwnerUserId(projectId, 42)).thenReturn(Optional.of(project));
                DesignProjectInput input = new DesignProjectInput();
                input.setId(inputId);
                input.setProjectId(projectId);
                input.setOwnerUserId(42);
                input.setArtifactKey("stored-input");
                input.setMimeType(MediaType.IMAGE_PNG_VALUE);
                when(inputs.findByIdAndProjectIdAndOwnerUserId(inputId, projectId, 42)).thenReturn(Optional.of(input));
                when(artifacts.read(42, projectId, "stored-input"))
                        .thenThrow(new java.io.FileNotFoundException("artifact missing"))
                        .thenThrow(new java.io.IOException("store unavailable"))
                        .thenReturn(new byte[]{1, 2, 3});

                mvc.perform(get("/api/ai-studio/projects/{projectId}/inputs/{inputId}/content", projectId, inputId)
                                .with(authentication(authFor(42, Role.USER))))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("DESIGN_ARTIFACT_NOT_FOUND"));

                mvc.perform(get("/api/ai-studio/projects/{projectId}/inputs/{inputId}/content", projectId, inputId)
                                .with(authentication(authFor(42, Role.USER))))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.code").value("DESIGN_ARTIFACT_STORE_UNAVAILABLE"));

                mvc.perform(get("/api/ai-studio/projects/{projectId}/inputs/{inputId}/content", projectId, inputId)
                                .with(authentication(authFor(42, Role.USER))))
                        .andExpect(status().isOk())
                        .andExpect(content().contentType(MediaType.IMAGE_PNG))
                        .andExpect(content().bytes(new byte[]{1, 2, 3}));
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        });
    }

    private static MockMvc mvc(org.springframework.context.ApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                .apply(springSecurity())
                .build();
    }

    private static UsernamePasswordAuthenticationToken authFor(Integer id, Role role) {
        User user = User.builder().id(id).name("Payment test").email("payment-test@example.invalid")
                .password("test-only").role(role).status(UserStatus.ACTIVE).build();
        UserPrincipal principal = new UserPrincipal(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private static ResultMatcher disabledResponse(String family) {
        return result -> {
            assertThat(result.getResponse().getStatus()).isEqualTo(503);
            assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
            assertThat(result.getResponse().getContentAsString())
                    .contains("\"code\":\"FEATURE_DISABLED\"", "\"featureFamily\":\"" + family + "\"");
        };
    }

    private static ResultMatcher paymentDisabledResponse() {
        return result -> {
            assertThat(result.getResponse().getStatus()).isEqualTo(503);
            assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(result.getResponse().getContentAsString())
                    .contains("\"status\":503", "\"error\":\"PAYMENT_FEATURE_DISABLED\"");
        };
    }

    private static void assertDatabaseFree(org.springframework.context.ApplicationContext context) {
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(context.getBeansOfType(EntityManagerFactory.class)).isEmpty();
        assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
    }

    private static int indexOf(List<jakarta.servlet.Filter> filters, Class<?> type) {
        for (int i = 0; i < filters.size(); i++) {
            if (type.isInstance(filters.get(i))) return i;
        }
        return -1;
    }

    @Configuration(proxyBeanMethods = false)
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, FeatureGateConfiguration.class, PaymentController.class,
            com.exe.morphia3d.controller.PaymentApiExceptionHandler.class, DesignProjectController.class,
            DesignPipelineController.class, DesignArtifactController.class, DesignProjectExceptionHandler.class,
            TestRoutes.class})
    static class IsolatedSecurityConfiguration {
        @Bean UserDetailsServiceImpl userDetailsService() { return mock(UserDetailsServiceImpl.class); }
        @Bean JwtUtil jwtUtil() { return mock(JwtUtil.class); }
        @Bean JwtAuthFilter jwtAuthFilter(JwtUtil jwt, UserDetailsServiceImpl users) {
            return new JwtAuthFilter(jwt, users);
        }
        @Bean PaymentService paymentService() { return mock(PaymentService.class); }
        @Bean RouteProbe routeProbe() { return new RouteProbe(); }
        @Bean DesignProjectService designProjectService() { return mock(DesignProjectService.class); }
        @Bean DesignProjectTimelineService designProjectTimelineService() { return mock(DesignProjectTimelineService.class); }
        @Bean DesignPipelineCommandService designPipelineCommandService() { return mock(DesignPipelineCommandService.class); }
        @Bean DesignProjectRepository designProjectRepository() { return mock(DesignProjectRepository.class); }
        @Bean DesignProjectInputRepository designProjectInputRepository() { return mock(DesignProjectInputRepository.class); }
        @Bean DesignReferenceSetRepository designReferenceSetRepository() { return mock(DesignReferenceSetRepository.class); }
        @Bean DesignReferenceSetVersionRepository designReferenceSetVersionRepository() { return mock(DesignReferenceSetVersionRepository.class); }
        @Bean DesignReferenceSetViewRepository designReferenceSetViewRepository() { return mock(DesignReferenceSetViewRepository.class); }
        @Bean DesignReferenceImageVersionRepository designReferenceImageVersionRepository() { return mock(DesignReferenceImageVersionRepository.class); }
        @Bean DesignModelVersionRepository designModelVersionRepository() { return mock(DesignModelVersionRepository.class); }
        @Bean DesignPipelineJobRepository designPipelineJobRepository() { return mock(DesignPipelineJobRepository.class); }
        @Bean DesignArtifactStore designArtifactStore() { return mock(DesignArtifactStore.class); }
        @Bean DesignPipelineStatusService designPipelineStatusService(DesignProjectRepository projects,
                DesignReferenceSetRepository sets, DesignReferenceSetVersionRepository references,
                DesignReferenceSetViewRepository views, DesignModelVersionRepository models,
                DesignPipelineJobRepository jobs) {
            return new DesignPipelineStatusService(projects, sets, references, views, models, jobs);
        }
    }

    static class RouteProbe {
        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        void hit(String route) { calls.computeIfAbsent(route, ignored -> new AtomicInteger()).incrementAndGet(); }
        int calls(String route) { return calls.getOrDefault(route, new AtomicInteger()).get(); }
        int totalCalls() { return calls.values().stream().mapToInt(AtomicInteger::get).sum(); }
    }

    @RestController
    static class TestRoutes {
        private final RouteProbe probe;
        TestRoutes(RouteProbe probe) { this.probe = probe; }

        @GetMapping("/api/ai-models/{id}") String ai() { probe.hit("ai"); return "ai"; }
        @GetMapping("/api/cart") String cart() { probe.hit("cart"); return "cart"; }
        @GetMapping("/api/orders/{id}") String order() { probe.hit("order"); return "order"; }
        @PostMapping("/api/orders") String checkout() { probe.hit("checkout"); return "checkout"; }
        @PostMapping("/api/orders/from-cart-item/{id}") String checkoutItem() { probe.hit("checkout"); return "checkout"; }
        @GetMapping("/api/admin/products") String adminProducts() { probe.hit("admin-products"); return "products"; }
        @GetMapping("/api/admin/staff") String adminStaff() { probe.hit("admin-staff"); return "staff"; }
        @GetMapping("/api/admin/tasks") String adminTasks() { probe.hit("admin-tasks"); return "tasks"; }
        @GetMapping("/api/v1/me/tasks") String employeeTasks() { probe.hit("employee-tasks"); return "tasks"; }
        @PostMapping(path = "/api/admin/products", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        String upload() { probe.hit("admin-upload"); return "upload"; }
    }
}
