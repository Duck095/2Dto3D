package com.exe.morphia3d.controller;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.dto.request.AIModelRequest;
import com.exe.morphia3d.dto.response.ModelResponse;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.UserStatus;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.AIModelPersistenceService;
import com.exe.morphia3d.service.MediaAssetLifecycleService;
import com.exe.morphia3d.service.MediaAssetOperationLease;
import com.exe.morphia3d.service.MediaAssetReconciler;
import com.exe.morphia3d.service.MediaStorageService;
import com.exe.morphia3d.service.MediaUploadIntent;
import com.exe.morphia3d.service.MediaUploadResult;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = Morphia3dApplication.class)
@ActiveProfiles("test")
class AIModelUploadSecurityIntegrationTest {

    @Autowired private WebApplicationContext context;
    @MockitoBean private MediaAssetLifecycleService lifecycle;
    @MockitoBean private MediaStorageService storage;
    @MockitoBean private AIModelPersistenceService persistence;
    @MockitoBean private MediaAssetReconciler reconciler;

    private MockMvc mockMvc;
    private MediaAssetOperationLease operation;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        operation = new MediaAssetOperationLease(UUID.randomUUID(), UUID.randomUUID(), "ai-security-test",
                List.of(601L, 602L));
        when(lifecycle.createUploadIntents(any(), anyList())).thenReturn(operation);
        when(lifecycle.claimUpload(eq(operation), anyLong())).thenAnswer(call -> {
            long id = call.getArgument(1);
            return new MediaUploadIntent(id, operation.operationId(), operation.leaseToken(),
                    id == 601L ? MediaKind.MODEL_3D : MediaKind.IMAGE);
        });
        when(storage.upload(any(), any())).thenAnswer(call -> {
            MediaUploadIntent intent = call.getArgument(0);
            String format = intent.mediaKind() == MediaKind.MODEL_3D ? "glb" : "png";
            return new MediaUploadResult(MediaProvider.CLOUDINARY, "test-cloud", "asset-" + intent.mediaAssetId(),
                    intent.publicId(), intent.mediaKind(), intent.resourceType(), intent.deliveryType(), format,
                    intent.mediaKind() == MediaKind.MODEL_3D ? 12 : pngBytes().length, 1,
                    "https://res.cloudinary.com/test-cloud/" + intent.resourceType() + "/upload/v1/"
                            + intent.publicId() + (intent.mediaKind() == MediaKind.IMAGE ? ".png" : ""));
        });
        when(persistence.persist(any(), any(), eq(operation))).thenReturn(ModelResponse.builder().id(8).build());
    }

    @Test
    void securityChainRejectsAnonymousAndWrongRoleBeforeIntentOrProviderCalls() throws Exception {
        upload(null).andExpect(status().isUnauthorized());
        upload(authenticationFor(Role.DESIGNER, 50)).andExpect(status().isForbidden());
        verifyNoInteractions(lifecycle, storage, persistence);
    }

    @Test
    void securityChainAllowsUserAndAdminAndPassesAuthenticatedOwner() throws Exception {
        upload(authenticationFor(Role.USER, 51)).andExpect(status().isOk());
        upload(authenticationFor(Role.ADMIN, 52)).andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(UserPrincipal.class);
        verify(persistence, times(2)).persist(captor.capture(), any(AIModelRequest.class), eq(operation));
        assertThat(captor.getAllValues()).extracting(UserPrincipal::getId).containsExactly(51, 52);
        verify(lifecycle, times(2)).createUploadIntents(any(), anyList());
        verify(storage, times(4)).upload(any(), any());
    }

    @Test
    void securityChainRejectsAuthenticatedTokenWithoutApplicationPrincipalBeforeSideEffects() throws Exception {
        var missing = UsernamePasswordAuthenticationToken.authenticated(null, null,
                AuthorityUtils.createAuthorityList("ROLE_USER"));
        var invalid = UsernamePasswordAuthenticationToken.authenticated("not-a-principal", null,
                AuthorityUtils.createAuthorityList("ROLE_USER"));
        upload(authentication(missing)).andExpect(status().isForbidden());
        upload(authentication(invalid)).andExpect(status().isForbidden());
        verifyNoInteractions(lifecycle, storage, persistence);
    }

    @Test
    void authenticatedInvalidMediaGetsSafeHttpErrorBeforeIntentOrProviderCalls() throws Exception {
        var request = multipart("/api/ai-models")
                .file(new MockMultipartFile("data", "", MediaType.APPLICATION_JSON_VALUE,
                        "{\"name\":\"AI model\"}".getBytes()))
                .file(new MockMultipartFile("images", "preview.png", "text/plain", pngBytes()))
                .file(new MockMultipartFile("model", "shape.glb", "model/gltf-binary", validGlbBytes()));

        mockMvc.perform(request.with(authenticationFor(Role.USER, 53)))
                .andExpect(status().isUnsupportedMediaType());

        verifyNoInteractions(lifecycle, storage, persistence);
    }

    private org.springframework.test.web.servlet.ResultActions upload(RequestPostProcessor processor) throws Exception {
        var request = multipart("/api/ai-models")
                .file(new MockMultipartFile("data", "", MediaType.APPLICATION_JSON_VALUE,
                        "{\"ownerId\":999,\"filePath3d\":\"forged/model\",\"name\":\"AI model\"}".getBytes()))
                .file(new MockMultipartFile("images", "preview.png", "image/png", pngBytes()))
                .file(new MockMultipartFile("model", "shape.glb", "model/gltf-binary", validGlbBytes()));
        if (processor != null) request.with(processor);
        return mockMvc.perform(request);
    }

    private RequestPostProcessor authenticationFor(Role role, int id) {
        User user = User.builder().id(id).role(role).status(UserStatus.ACTIVE).name("AI uploader")
                .email("ai-uploader-" + id + "@example.com").password("encoded").build();
        UserPrincipal principal = new UserPrincipal(user);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static byte[] pngBytes() {
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
