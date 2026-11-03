package com.exe.morphia3d.controller;

import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.AIModelUploadService;
import com.exe.morphia3d.service.DesignerModelUploadService;
import com.exe.morphia3d.service.MediaStorageException;
import com.exe.morphia3d.service.MediaValidationException;
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
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        uploadService = mock(DesignerModelUploadService.class);
        aiModelUploadService = mock(AIModelUploadService.class);
        Model3dController controller = new Model3dController(mock(Model3dService.class),
                aiModelUploadService, uploadService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
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
}
