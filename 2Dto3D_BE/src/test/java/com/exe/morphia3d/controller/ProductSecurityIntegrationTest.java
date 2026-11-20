package com.exe.morphia3d.controller;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.enums.ModelStatus;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.repository.Model3dRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = Morphia3dApplication.class,
        properties = "spring.datasource.url=jdbc:h2:mem:product_security;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@TestPropertySource(properties = "app.features.ai.enabled=true")
@Transactional
class ProductSecurityIntegrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired private Model3dRepository modelRepository;

    private MockMvc mockMvc;
    private Integer activeDesignerId;
    private Integer pendingDesignerId;
    private Integer inactiveDesignerId;
    private Integer activeAiId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        activeDesignerId = save("Public chair", "public-chair", ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE);
        pendingDesignerId = save("Pending chair", "pending-chair", ModelType.DESIGNER_MODEL, ModelStatus.PENDING);
        inactiveDesignerId = save("Inactive chair", "inactive-chair", ModelType.DESIGNER_MODEL, ModelStatus.INACTIVE);
        activeAiId = save("Private model", "private-model", ModelType.AI_MODEL, ModelStatus.ACTIVE);
    }

    @Test
    void anonymousUserCanReadCatalogAndHiddenDetailReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(activeDesignerId))
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/api/products/{id}", activeDesignerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(activeDesignerId));

        mockMvc.perform(get("/api/products/999999")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/products/{id}", pendingDesignerId)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/products/{id}", inactiveDesignerId)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/products/{id}", activeAiId)).andExpect(status().isNotFound());

        mockMvc.perform(get("/api/designer-models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(activeDesignerId));
        mockMvc.perform(get("/api/designer-models/{id}", pendingDesignerId)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/designer-models/{id}", inactiveDesignerId)).andExpect(status().isNotFound());

        mockMvc.perform(get("/api/ai-models/{id}", activeAiId)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/designer-models")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/designer-models").with(user("regular-user").roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/ai-models")).andExpect(status().isUnauthorized());
    }

    private Integer save(String name, String slug, ModelType type, ModelStatus status) {
        return modelRepository.saveAndFlush(Model3d.builder().name(name).slug(slug)
                .sourceType(type).status(status).build()).getId();
    }
}
