package com.exe.morphia3d.controller;

import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.AdminProductCatalogService;
import com.exe.morphia3d.service.AIModelUploadService;
import com.exe.morphia3d.service.DesignerModelUploadService;
import com.exe.morphia3d.service.MediaPersistenceException;
import com.exe.morphia3d.service.MediaStorageException;
import com.exe.morphia3d.service.MediaValidationException;
import com.exe.morphia3d.service.SnapshotPreparationException;
import com.exe.morphia3d.service.Model3dService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MediaUploadExceptionHandlerTest {

    private DesignerModelUploadService uploadService;
    private AIModelUploadService aiModelUploadService;
    private Model3dService model3dService;
    private AdminProductCatalogService adminCatalog;
    private com.exe.morphia3d.feature.FeatureGate featureGate;
    private com.exe.morphia3d.feature.FeatureGateProperties gateProperties;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        uploadService = mock(DesignerModelUploadService.class);
        aiModelUploadService = mock(AIModelUploadService.class);
        model3dService = mock(Model3dService.class);
        adminCatalog = mock(AdminProductCatalogService.class);
        Model3dController controller = new Model3dController(model3dService,
                aiModelUploadService, uploadService);
        gateProperties = new com.exe.morphia3d.feature.FeatureGateProperties();
        featureGate = new com.exe.morphia3d.feature.FeatureGate(gateProperties);
        AdminProductController adminController = new AdminProductController(adminCatalog, uploadService, model3dService,
                featureGate);
        mockMvc = MockMvcBuilders.standaloneSetup(controller, adminController)
                .setControllerAdvice(new MediaUploadExceptionHandler()).build();
    }

    @Test
    void mapsMalformedFilesToBadRequestWithoutDetails() throws Exception {
        when(uploadService.create(any(), any(), any(), any()))
                .thenThrow(new MediaValidationException(MediaValidationException.Reason.MALFORMED_REQUEST));

        mockUpload().andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("The upload request or a required file is invalid"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void mapsValidationAndMultipartBoundsToExpectedStatuses() throws Exception {
        when(uploadService.create(any(), any(), any(), any()))
                .thenThrow(new MediaValidationException(MediaValidationException.Reason.TOO_LARGE));
        mockUpload().andExpect(status().isPayloadTooLarge());

        doThrow(new MediaValidationException(MediaValidationException.Reason.UNSUPPORTED_MIME_TYPE))
                .when(uploadService).create(any(), any(), any(), any());
        mockUpload().andExpect(status().isUnsupportedMediaType());

        doThrow(new MaxUploadSizeExceededException(10)).when(uploadService).create(any(), any(), any(), any());
        mockUpload().andExpect(status().isPayloadTooLarge());

        doThrow(new MediaValidationException(MediaValidationException.Reason.TOO_LARGE))
                .when(aiModelUploadService).create(any(), any(), any(), any());
        mockAiUpload().andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value("The upload exceeds the configured size or image limits"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void mapsProviderFailuresWithoutLeakingProviderText() throws Exception {
        when(uploadService.create(any(), any(), any(), any()))
                .thenThrow(new MediaStorageException(MediaStorageException.Reason.PROVIDER_UNAVAILABLE));

        mockUpload().andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Media storage is temporarily unavailable"));

        doThrow(new MediaStorageException(MediaStorageException.Reason.INVALID_PROVIDER_RESPONSE))
                .when(uploadService).create(any(), any(), any(), any());
        mockUpload().andExpect(status().isBadGateway());
    }

    @Test
    void adminRouteMapsMalformedMultipartToTheExistingBadRequestContract() throws Exception {
        mockMvc.perform(multipart("/api/admin/products")
                        .file(new MockMultipartFile("data", "", MediaType.APPLICATION_JSON_VALUE,
                                "{\"name\":\"Desk lamp\"}".getBytes()))
                        .file(new MockMultipartFile("images", "preview.png", "image/png", new byte[]{1}))
                        .principal(adminPrincipal()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("The multipart request is malformed or incomplete"));
    }

    @Test
    void adminRouteMapsInvalidMediaAndMultipartLimitsToExistingStatuses() throws Exception {
        when(uploadService.create(any(), any(), any(), any()))
                .thenThrow(new MediaValidationException(MediaValidationException.Reason.INVALID_GLB));
        adminUpload().andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").value(
                        "Only supported image, GLB, and 3MF content is accepted"));

        doThrow(new MediaValidationException(MediaValidationException.Reason.TOO_LARGE))
                .when(uploadService).create(any(), any(), any(), any());
        adminUpload().andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value("The upload exceeds the configured size or image limits"));

        doThrow(new MaxUploadSizeExceededException(10)).when(uploadService).create(any(), any(), any(), any());
        adminUpload().andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value("The multipart request exceeds the configured size limit"));
    }

    @Test
    void adminRouteMapsProviderAndPersistenceFailuresWithoutLeakingDetails() throws Exception {
        when(uploadService.create(any(), any(), any(), any()))
                .thenThrow(new MediaStorageException(MediaStorageException.Reason.PROVIDER_UNAVAILABLE));
        adminUpload().andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Media storage is temporarily unavailable"));

        doThrow(MediaPersistenceException.rolledBack(new IllegalStateException("sensitive database detail")))
                .when(uploadService).create(any(), any(), any(), any());
        adminUpload().andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("The model could not be saved"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sensitive database detail"))));
    }

    @Test
    void adminRouteSanitizesSnapshotPreparationFailures() throws Exception {
        doThrow(new SnapshotPreparationException(
                "C:\\private\\tmp\\secret.glb SQL password=secret", null))
                .when(uploadService).createWithSnapshot(any(), any(), any(), any());
        gateProperties.getAdminProductSnapshotUpload().setEnabled(true);

        adminUpload().andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("The trusted model upload could not be prepared"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret"))));
    }


    private org.springframework.test.web.servlet.ResultActions mockUpload() throws Exception {
        User user = User.builder().id(42).role(Role.DESIGNER).name("Designer")
                .email("designer@example.com").password("encoded").build();
        UserPrincipal principal = new UserPrincipal(user);
        return mockMvc.perform(multipart("/api/designer-models")
                .file(new MockMultipartFile("data", "", MediaType.APPLICATION_JSON_VALUE,
                        "{\"name\":\"Desk lamp\",\"slug\":\"desk-lamp\"}".getBytes()))
                .file(new MockMultipartFile("images", "preview.png", "image/png", new byte[]{1}))
                .file(new MockMultipartFile("model", "model.glb", "model/gltf-binary", new byte[]{1}))
                .principal(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())));
    }

    private org.springframework.test.web.servlet.ResultActions mockAiUpload() throws Exception {
        User user = User.builder().id(43).role(Role.USER).name("AI user")
                .email("ai-user@example.com").password("encoded").build();
        UserPrincipal principal = new UserPrincipal(user);
        return mockMvc.perform(multipart("/api/ai-models")
                .file(new MockMultipartFile("data", "", MediaType.APPLICATION_JSON_VALUE,
                        "{\"name\":\"AI model\"}".getBytes()))
                .file(new MockMultipartFile("images", "preview.png", "image/png", new byte[]{1}))
                .file(new MockMultipartFile("model", "model.glb", "model/gltf-binary", new byte[]{1}))
                .principal(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())));
    }

    private org.springframework.test.web.servlet.ResultActions adminUpload() throws Exception {
        return mockMvc.perform(multipart("/api/admin/products")
                .file(new MockMultipartFile("data", "", MediaType.APPLICATION_JSON_VALUE,
                        "{\"name\":\"Desk lamp\"}".getBytes()))
                .file(new MockMultipartFile("images", "preview.png", "image/png", new byte[]{1}))
                .file(new MockMultipartFile("model", "model.glb", "model/gltf-binary", new byte[]{1}))
                .principal(adminPrincipal()));
    }

    private UsernamePasswordAuthenticationToken adminPrincipal() {
        User user = User.builder().id(44).role(Role.ADMIN).name("Admin")
                .email("admin@example.com").password("encoded").build();
        UserPrincipal principal = new UserPrincipal(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
