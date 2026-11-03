package com.exe.morphia3d.controller;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.ModelImage;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.UserStatus;
import com.exe.morphia3d.repository.Model3dRepository;
import com.exe.morphia3d.repository.UserRepository;
import com.exe.morphia3d.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = Morphia3dApplication.class)
@ActiveProfiles("test")
@Transactional
class AIModelReadSecurityIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private Model3dRepository models;

    private MockMvc mockMvc;
    private User owner;
    private User anotherUser;
    private User admin;
    private Integer legacyAiId;
    private Integer designerId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        owner = saveUser(Role.USER, "owner");
        anotherUser = saveUser(Role.USER, "other");
        admin = saveUser(Role.ADMIN, "admin");

        Model3d legacy = models.saveAndFlush(Model3d.builder().sourceType(ModelType.AI_MODEL).user(owner)
                .name("Legacy AI model").filePath3d("legacy-ai/model-public-id").build());
        legacy.getImages().add(ModelImage.builder().model(legacy).imagePath("legacy-ai/image-public-id")
                .isPrimary(true).build());
        legacyAiId = models.saveAndFlush(legacy).getId();
        designerId = models.saveAndFlush(Model3d.builder().sourceType(ModelType.DESIGNER_MODEL).user(owner)
                .name("Designer model").filePath3d("legacy-designer/model-public-id").build()).getId();
    }

    @Test
    void authenticatedOwnerReadsLegacyAiRecordAndPublicIds() throws Exception {
        mockMvc.perform(get("/api/ai-models/{id}", legacyAiId).with(authenticate(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(legacyAiId))
                .andExpect(jsonPath("$.sourceType").value("AI_MODEL"))
                .andExpect(jsonPath("$.filePath3d").value("legacy-ai/model-public-id"))
                .andExpect(jsonPath("$.modelImages[0].imagePath").value("legacy-ai/image-public-id"));
    }

    @Test
    void anotherUserCannotReadAiModel() throws Exception {
        mockMvc.perform(get("/api/ai-models/{id}", legacyAiId).with(authenticate(anotherUser)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminWithoutOwnershipCannotReadAiModel() throws Exception {
        mockMvc.perform(get("/api/ai-models/{id}", legacyAiId).with(authenticate(admin)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousCannotReadAiModel() throws Exception {
        mockMvc.perform(get("/api/ai-models/{id}", legacyAiId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingAiModelReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/ai-models/{id}", 999999).with(authenticate(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void designerModelIdOnAiDetailEndpointReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/ai-models/{id}", designerId).with(authenticate(owner)))
                .andExpect(status().isNotFound());
    }

    private User saveUser(Role role, String label) {
        return users.saveAndFlush(User.builder().name("AI " + label).email("ai-read-" + label + "-"
                + java.util.UUID.randomUUID() + "@example.com").password("encoded")
                .role(role).status(UserStatus.ACTIVE).build());
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor authenticate(User user) {
        UserPrincipal principal = new UserPrincipal(user);
        return authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }
}
