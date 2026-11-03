package com.exe.morphia3d.controller;

import com.exe.morphia3d.dto.request.DesignerModelRequest;
import com.exe.morphia3d.dto.request.AIModelRequest;
import com.exe.morphia3d.dto.response.ModelResponse;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.AIModelUploadService;
import com.exe.morphia3d.service.DesignerModelUploadService;
import com.exe.morphia3d.service.Model3dService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class Model3dControllerTest {

    private Model3dService model3dService;
    private AIModelUploadService aiUploadService;
    private DesignerModelUploadService designerUploadService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        model3dService = mock(Model3dService.class);
        aiUploadService = mock(AIModelUploadService.class);
        designerUploadService = mock(DesignerModelUploadService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new Model3dController(model3dService, aiUploadService, designerUploadService)).build();
    }

    @Test
    void designerModelIdBindsFromPath() throws Exception {
        when(model3dService.getDesignerModelById(7)).thenReturn(ModelResponse.builder().id(7).build());

        mockMvc.perform(get("/api/designer-models/7"))
                .andExpect(status().isOk());

        verify(model3dService).getDesignerModelById(7);
    }

    @Test
    void designerModelListDoesNotRequireGetRequestBody() throws Exception {
        when(model3dService.getAllModelsBySourceType(ModelType.DESIGNER_MODEL)).thenReturn(List.of());

        mockMvc.perform(get("/api/designer-models"))
                .andExpect(status().isOk());

        verify(model3dService).getAllModelsBySourceType(ModelType.DESIGNER_MODEL);
    }

    @Test
    void designerModelOwnershipComesFromAuthenticatedPrincipal() throws Exception {
        User user = User.builder()
                .id(42)
                .name("Designer")
                .email("designer@example.com")
                .password("encoded")
                .build();
        UserPrincipal principal = new UserPrincipal(user);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        when(designerUploadService.create(any(), any(), any(), any()))
                .thenReturn(ModelResponse.builder().id(1).build());

        MockMultipartFile data = new MockMultipartFile(
                "data", "", MediaType.APPLICATION_JSON_VALUE,
                "{\"designerId\":999,\"name\":\"Owned model\"}".getBytes());
        MockMultipartFile image = new MockMultipartFile(
                "images", "preview.png", MediaType.IMAGE_PNG_VALUE, pngBytes());
        MockMultipartFile model = new MockMultipartFile(
                "model", "shape.glb", "model/gltf-binary", validGlbBytes());

        mockMvc.perform(multipart("/api/designer-models")
                        .file(data)
                        .file(image)
                        .file(model)
                        .principal(authentication))
                .andExpect(status().isOk());

        ArgumentCaptor<UserPrincipal> principalCaptor = ArgumentCaptor.forClass(UserPrincipal.class);
        ArgumentCaptor<DesignerModelRequest> metadataCaptor = ArgumentCaptor.forClass(DesignerModelRequest.class);
        verify(designerUploadService).create(principalCaptor.capture(), metadataCaptor.capture(),
                any(), any());
        assertThat(principalCaptor.getValue().getId()).isEqualTo(42);
        assertThat(metadataCaptor.getValue().getSlug()).isNull();
    }

    @Test
    void aiModelIdBindsFromPathAndReadUsesAuthenticatedOwner() throws Exception {
        UsernamePasswordAuthenticationToken authentication = authenticationForUser(42);
        when(model3dService.getAIModelById(42, 7)).thenReturn(ModelResponse.builder().id(7).build());

        mockMvc.perform(get("/api/ai-models/7").principal(authentication))
                .andExpect(status().isOk());

        verify(model3dService).getAIModelById(42, 7);
    }

    @Test
    void aiModelOwnershipComesFromAuthenticatedPrincipal() throws Exception {
        UsernamePasswordAuthenticationToken authentication = authenticationForUser(42);
        when(aiUploadService.create(any(), any(), any(), any())).thenReturn(ModelResponse.builder().id(1).build());

        MockMultipartFile data = new MockMultipartFile(
                "data", "", MediaType.APPLICATION_JSON_VALUE,
                "{\"ownerId\":999,\"filePath3d\":\"attacker/model\",\"name\":\"Owned AI model\"}".getBytes());
        MockMultipartFile image = new MockMultipartFile(
                "images", "preview.png", MediaType.IMAGE_PNG_VALUE, pngBytes());
        MockMultipartFile model = new MockMultipartFile(
                "model", "shape.glb", "model/gltf-binary", validGlbBytes());

        mockMvc.perform(multipart("/api/ai-models")
                        .file(data)
                        .file(image)
                        .file(model)
                        .principal(authentication))
                .andExpect(status().isOk());

        ArgumentCaptor<UserPrincipal> principalCaptor = ArgumentCaptor.forClass(UserPrincipal.class);
        ArgumentCaptor<AIModelRequest> requestCaptor = ArgumentCaptor.forClass(AIModelRequest.class);
        verify(aiUploadService).create(principalCaptor.capture(), requestCaptor.capture(), any(), any());
        assertThat(principalCaptor.getValue().getId()).isEqualTo(42);
        assertThat(requestCaptor.getValue().getOwnerId()).isEqualTo(999);
        assertThat(requestCaptor.getValue().getFilePath3d()).isEqualTo("attacker/model");
    }

    private UsernamePasswordAuthenticationToken authenticationForUser(int id) {
        User user = User.builder()
                .id(id)
                .name("User")
                .email("user@example.com")
                .password("encoded")
                .build();
        UserPrincipal principal = new UserPrincipal(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private byte[] pngBytes() {
        try {
            BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private byte[] validGlbBytes() {
        return new byte[]{'g', 'l', 'T', 'F', 2, 0, 0, 0, 12, 0, 0, 0};
    }
}
