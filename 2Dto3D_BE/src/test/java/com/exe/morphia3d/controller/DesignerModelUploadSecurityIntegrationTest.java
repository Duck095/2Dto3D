package com.exe.morphia3d.controller;

import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.UserStatus;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.DesignerModelPersistenceService;
import com.exe.morphia3d.service.MediaAssetLifecycleService;
import com.exe.morphia3d.service.MediaAssetOperationLease;
import com.exe.morphia3d.service.MediaStorageService;
import com.exe.morphia3d.service.MediaUploadIntent;
import com.exe.morphia3d.service.MediaUploadResult;
import com.exe.morphia3d.service.MediaAssetReconciler;
import com.exe.morphia3d.service.MediaPersistenceException;
import com.exe.morphia3d.service.MediaStorageException;
import com.exe.morphia3d.dto.response.ModelResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class DesignerModelUploadSecurityIntegrationTest {

    @Autowired private WebApplicationContext context;
    @MockitoBean private MediaAssetLifecycleService lifecycle;
    @MockitoBean private MediaStorageService storage;
    @MockitoBean private DesignerModelPersistenceService persistence;
    @MockitoBean private MediaAssetReconciler reconciler;

    private MockMvc mockMvc;
    private MediaAssetOperationLease operation;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        operation = new MediaAssetOperationLease(UUID.randomUUID(), UUID.randomUUID(), "security-test",
                List.of(501L, 502L));
        when(lifecycle.createUploadIntents(any(), anyList())).thenReturn(operation);
        when(lifecycle.claimUpload(eq(operation), anyLong())).thenAnswer(invocation -> {
            long id = invocation.getArgument(1);
            MediaKind kind = id == 501L ? MediaKind.MODEL_3D : MediaKind.IMAGE;
            return new MediaUploadIntent(id, operation.operationId(), operation.leaseToken(), kind);
        });
        when(storage.upload(any(), any())).thenAnswer(invocation -> {
            MediaUploadIntent intent = invocation.getArgument(0);
            String format = intent.mediaKind() == MediaKind.MODEL_3D ? "glb" : "png";
            return new MediaUploadResult(MediaProvider.CLOUDINARY, "test-cloud", "asset-" + intent.mediaAssetId(),
                    intent.publicId(), intent.mediaKind(), intent.resourceType(), intent.deliveryType(), format,
                    intent.mediaKind() == MediaKind.MODEL_3D ? 12 : 68, 1,
                    "https://res.cloudinary.com/test-cloud/" + intent.resourceType() + "/upload/v1/"
                            + intent.publicId());
        });
        when(persistence.persist(any(), any(), eq(operation))).thenReturn(ModelResponse.builder().id(1).build());
    }

    @Test
    void fullSecurityChainRejectsAnonymousAndUserBeforeCreatingIntentsOrCallingStorage() throws Exception {
        upload(null).andExpect(status().isUnauthorized());
        upload(user("ordinary-user").roles("USER")).andExpect(status().isForbidden());

        verifyNoInteractions(lifecycle, storage, persistence);
    }

    @Test
    void fullSecurityChainAllowsDesignerAndAdminUploads() throws Exception {
        upload(authenticationFor(Role.DESIGNER, 41)).andExpect(status().isOk());
        upload(authenticationFor(Role.ADMIN, 42)).andExpect(status().isOk());

        verify(lifecycle, times(2)).createUploadIntents(any(), anyList());
        verify(storage, times(4)).upload(any(), any());
        verify(persistence, times(2)).persist(any(), any(), eq(operation));
    }

    @Test
    void adminUploadMapsProviderFailureAndRequestsSafeCompensation() throws Exception {
        doThrow(new MediaStorageException(MediaStorageException.Reason.PROVIDER_UNAVAILABLE))
                .when(storage).upload(any(), any());

        adminUpload().andExpect(status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("Media storage is temporarily unavailable"));

        verify(lifecycle).requestCleanup(operation, operation.mediaAssetIds());
        verify(persistence, never()).persist(any(), any(), any());
    }

    @Test
    void adminUploadMapsRolledBackPersistenceFailureAndDoesNotPublish() throws Exception {
        when(persistence.persist(any(), any(), eq(operation)))
                .thenThrow(MediaPersistenceException.rolledBack(new IllegalStateException("private db detail")));

        adminUpload().andExpect(status().isInternalServerError())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("The model could not be saved"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private db detail"))));

        verify(lifecycle).requestCleanup(operation, operation.mediaAssetIds());
        verify(persistence).persist(any(), any(), eq(operation));
    }

    @Test
    void adminUploadRejectsMalformedOrInvalidGlbBeforeCreatingMediaIntents() throws Exception {
        mockMvc.perform(multipart("/api/admin/products")
                        .file(new MockMultipartFile("data", "", MediaType.APPLICATION_JSON_VALUE,
                                "{\"name\":\"Admin draft\"}".getBytes()))
                        .file(new MockMultipartFile("images", "preview.png", "image/png", pngBytes()))
                        .with(authenticationFor(Role.ADMIN, 43)))
                .andExpect(status().isBadRequest());

        adminUploadWithGlb(new byte[]{1, 2, 3}).andExpect(status().isUnsupportedMediaType())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("Only supported JPEG, PNG, WebP, and GLB content is accepted"));
        verifyNoInteractions(lifecycle, storage, persistence);
    }

    @Test
    void fullSecurityChainRejectsMissingOrInvalidApplicationPrincipalBeforeUpload() throws Exception {
        var missingPrincipal = UsernamePasswordAuthenticationToken.authenticated(null, null,
                AuthorityUtils.createAuthorityList("ROLE_DESIGNER"));
        var invalidPrincipal = UsernamePasswordAuthenticationToken.authenticated("not-a-user-principal", null,
                AuthorityUtils.createAuthorityList("ROLE_DESIGNER"));

        upload(authentication(missingPrincipal)).andExpect(status().isForbidden());
        upload(authentication(invalidPrincipal)).andExpect(status().isForbidden());

        verifyNoInteractions(lifecycle, storage, persistence);
    }

    private org.springframework.test.web.servlet.ResultActions upload(RequestPostProcessor processor) throws Exception {
        return uploadTo("/api/designer-models", processor, validGlbBytes());
    }

    private org.springframework.test.web.servlet.ResultActions adminUpload() throws Exception {
        return uploadTo("/api/admin/products", authenticationFor(Role.ADMIN, 44), validGlbBytes());
    }

    private org.springframework.test.web.servlet.ResultActions adminUploadWithGlb(byte[] glbBytes) throws Exception {
        return uploadTo("/api/admin/products", authenticationFor(Role.ADMIN, 44), glbBytes);
    }

    private org.springframework.test.web.servlet.ResultActions uploadTo(String path,
                                                                         RequestPostProcessor processor,
                                                                         byte[] glbBytes) throws Exception {
        var request = multipart(path)
                .file(new MockMultipartFile("data", "", MediaType.APPLICATION_JSON_VALUE,
                        "{\"name\":\"Legacy model\"}".getBytes()))
                .file(new MockMultipartFile("images", "preview.png", "image/png", pngBytes()))
                .file(new MockMultipartFile("model", "shape.glb", "model/gltf-binary", glbBytes));
        if (processor != null) {
            request.with(processor);
        }
        return mockMvc.perform(request);
    }

    private RequestPostProcessor authenticationFor(Role role, int id) {
        User user = User.builder().id(id).role(role).status(UserStatus.ACTIVE)
                .name("Upload principal").email("upload-" + id + "@example.com").password("encoded").build();
        UserPrincipal principal = new UserPrincipal(user);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private byte[] pngBytes() {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private byte[] validGlbBytes() {
        return new byte[]{'g', 'l', 'T', 'F', 2, 0, 0, 0, 12, 0, 0, 0};
    }
}
