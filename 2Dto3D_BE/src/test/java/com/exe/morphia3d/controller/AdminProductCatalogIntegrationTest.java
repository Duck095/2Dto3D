package com.exe.morphia3d.controller;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.dto.response.ModelResponse;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.ModelImage;
import com.exe.morphia3d.enums.ModelStatus;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.DesignerModelUploadService;
import com.exe.morphia3d.service.Model3dService;
import com.exe.morphia3d.feature.FeatureGate;
import com.exe.morphia3d.service.admin3d.Admin3dRestrictedMutationGateway;
import com.exe.morphia3d.repository.Model3dRepository;
import com.exe.morphia3d.repository.ModelImageRepository;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = Morphia3dApplication.class)
@ActiveProfiles("test")
@Transactional
class AdminProductCatalogIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private Model3dRepository models;
    @Autowired private ModelImageRepository modelImages;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    @MockitoBean private DesignerModelUploadService uploadService;
    @MockitoBean private Model3dService model3dService;
    @MockitoBean private FeatureGate featureGate;
    @MockitoBean private Admin3dRestrictedMutationGateway restrictedMutations;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        doAnswer(invocation -> {
            Integer id = invocation.getArgument(0);
            boolean updateName = invocation.getArgument(1);
            String name = invocation.getArgument(2);
            boolean updateDescription = invocation.getArgument(3);
            String description = invocation.getArgument(4);
            boolean updateCategory = invocation.getArgument(5);
            String category = invocation.getArgument(6);
            jdbc.update("UPDATE model_3d SET name = CASE WHEN ? THEN ? ELSE name END, "
                            + "description = CASE WHEN ? THEN ? ELSE description END, "
                            + "category = CASE WHEN ? THEN ? ELSE category END WHERE model_id = ?",
                    updateName, name, updateDescription, description, updateCategory, category, id);
            return null;
        }).when(restrictedMutations).edit(any(Integer.class), any(Boolean.class), any(),
                any(Boolean.class), any(), any(Boolean.class), any());
        doAnswer(invocation -> {
            jdbc.update("UPDATE model_3d SET model_status='INACTIVE' WHERE model_id=?",
                    (Integer) invocation.getArgument(0));
            return null;
        }).when(restrictedMutations).archive(any(Integer.class));
    }

    @Test
    void adminCanListEditArchiveAndHideDesignerCatalogProductsWithoutChangingAssets() throws Exception {
        Model3d draft = saveModel("Draft product", ModelType.DESIGNER_MODEL, ModelStatus.PENDING);
        Model3d active = saveModel("Published product", ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE);
        saveModel("Private AI model", ModelType.AI_MODEL, ModelStatus.PENDING);

        mvc.perform(get("/api/admin/products").with(user("catalog-admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].sourceType").value("DESIGNER_MODEL"));

        mvc.perform(patch("/api/admin/products/{id}", draft.getId())
                        .with(user("catalog-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Edited draft","description":"New description","category":"Home"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Edited draft"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        verify(restrictedMutations).edit(draft.getId(), true, "Edited draft", true, "New description", true, "Home");

        mvc.perform(post("/api/admin/products/{id}/archive", active.getId())
                        .with(user("catalog-admin").roles("ADMIN")))
                .andExpect(status().isNoContent());
        verify(restrictedMutations).archive(active.getId());
        assertThat(models.findById(active.getId()).orElseThrow().getStatus()).isEqualTo(ModelStatus.INACTIVE);
        mvc.perform(get("/api/products/{id}", active.getId())).andExpect(status().isNotFound());
        mvc.perform(get("/api/products").param("q", "Published product"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        verifyNoInteractions(uploadService, model3dService);
    }

    @Test
    void adminCreateUsesTheExistingValidatedUploadOrchestratorAndCreatesDraft() throws Exception {
        ModelResponse created = ModelResponse.builder().id(987).sourceType(ModelType.DESIGNER_MODEL)
                .userId(721).name("Admin draft").status(ModelStatus.PENDING).build();
        when(uploadService.create(any(UserPrincipal.class), any(), any(), any())).thenReturn(created);

        mvc.perform(multipart("/api/admin/products")
                        .file(jsonPart("data", "{\"name\":\"Admin draft\",\"designerId\":99999,\"filePath3d\":\"client-path\"}"))
                        .file(new MockMultipartFile("images", "image.png", "image/png", new byte[]{1}))
                        .file(new MockMultipartFile("model", "model.glb", "model/gltf-binary", new byte[]{1}))
                        .with(adminPrincipal()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(uploadService).create(any(UserPrincipal.class), any(), any(), any());
        verify(uploadService, org.mockito.Mockito.never()).createWithSnapshot(any(), any(), any(), any());
    }

    @Test
    void adminCreateUsesSnapshotUploadOnlyWhenDedicatedGateIsEnabled() throws Exception {
        ModelResponse created = ModelResponse.builder().id(988).sourceType(ModelType.DESIGNER_MODEL)
                .userId(721).name("Admin snapshot draft").status(ModelStatus.PENDING).build();
        org.mockito.Mockito.when(featureGate.isAdminProductSnapshotUploadEnabled()).thenReturn(true);
        when(uploadService.createWithSnapshot(any(UserPrincipal.class), any(), any(), any())).thenReturn(created);

        mvc.perform(multipart("/api/admin/products")
                        .file(jsonPart("data", "{\"name\":\"Admin snapshot draft\"}"))
                        .file(new MockMultipartFile("images", "image.png", "image/png", new byte[]{1}))
                        .file(new MockMultipartFile("model", "model.glb", "model/gltf-binary", new byte[]{1}))
                        .with(adminPrincipal()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(988));

        verify(uploadService).createWithSnapshot(any(UserPrincipal.class), any(), any(), any());
        verify(uploadService, org.mockito.Mockito.never()).create(any(), any(), any(), any());
    }

    @Test
    void snapshotUploadFailureDoesNotRetryThroughLegacyPath() throws Exception {
        org.mockito.Mockito.when(featureGate.isAdminProductSnapshotUploadEnabled()).thenReturn(true);
        when(uploadService.createWithSnapshot(any(UserPrincipal.class), any(), any(), any()))
                .thenThrow(new com.exe.morphia3d.service.SnapshotPreparationException(
                        "C:\\private\\tmp\\sensitive.glb", null));

        mvc.perform(multipart("/api/admin/products")
                        .file(jsonPart("data", "{\"name\":\"Must fail closed\"}"))
                        .file(new MockMultipartFile("images", "image.png", "image/png", new byte[]{1}))
                        .file(new MockMultipartFile("model", "model.glb", "model/gltf-binary", new byte[]{1}))
                        .with(adminPrincipal()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("The trusted model upload could not be prepared"));

        verify(uploadService).createWithSnapshot(any(UserPrincipal.class), any(), any(), any());
        verify(uploadService, org.mockito.Mockito.never()).create(any(), any(), any(), any());
    }

    @Test
    void publishAliasDelegatesToExistingValidatedPublicationStateMachine() throws Exception {
        mvc.perform(post("/api/admin/products/{id}/publish", 17)
                        .with(user("catalog-admin").roles("ADMIN")))
                .andExpect(status().isNoContent());
        verify(model3dService).publishDesignerModel(17);
    }

    @Test
    void nonAdminCannotReadOrMutateAdminCatalogAndRejectionsHaveNoSideEffects() throws Exception {
        Model3d draft = saveModel("Protected draft", ModelType.DESIGNER_MODEL, ModelStatus.PENDING);

        mvc.perform(get("/api/admin/products")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/products").with(user("customer").roles("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/products").with(user("designer").roles("DESIGNER")))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/admin/products/{id}", draft.getId())
                        .with(user("customer").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Tampered\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/admin/products")
                        .file(jsonPart("data", "{}"))
                        .file(new MockMultipartFile("images", "image.png", "image/png", new byte[]{1}))
                        .file(new MockMultipartFile("model", "model.glb", "model/gltf-binary", new byte[]{1})))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/admin/products")
                        .file(jsonPart("data", "{}"))
                        .file(new MockMultipartFile("images", "image.png", "image/png", new byte[]{1}))
                        .file(new MockMultipartFile("model", "model.glb", "model/gltf-binary", new byte[]{1}))
                        .with(user("customer").roles("USER")))
                .andExpect(status().isForbidden());

        assertThat(models.findById(draft.getId()).orElseThrow().getName()).isEqualTo("Protected draft");
        verifyNoInteractions(uploadService, model3dService);
    }

    @Test
    void invalidPatchDoesNotMutateProduct() throws Exception {
        Model3d draft = saveModel("Valid draft", ModelType.DESIGNER_MODEL, ModelStatus.PENDING);

        mvc.perform(patch("/api/admin/products/{id}", draft.getId())
                        .with(user("catalog-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());

        assertThat(models.findById(draft.getId()).orElseThrow().getName()).isEqualTo("Valid draft");
    }

    @Test
    void materialAndColorWritesRemainAdminOnlyAndMaterialRetirementIsNonDestructive() throws Exception {
        mvc.perform(post("/api/materials/create")
                        .with(user("customer").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"PLA\",\"type\":\"PLA\",\"pricePerGram\":0.05,\"stockQuantity\":100,\"unit\":\"g\",\"colors\":[]}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/colors/create")
                        .with(user("designer").roles("DESIGNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Blue\",\"hexCode\":\"#0000FF\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/materials/create")
                        .with(user("catalog-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"PLA\",\"type\":\"PLA\",\"pricePerGram\":0.05,\"stockQuantity\":100,\"unit\":\"g\",\"colors\":[]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/colors/create")
                        .with(user("catalog-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Blue\",\"hexCode\":\"#0000FF\"}"))
                .andExpect(status().isOk());

        Integer materialId = jdbcMaterialId();
        Integer colorId = jdbc.queryForObject("select color_id from colors where name = 'Blue'", Integer.class);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/materials/{id}", materialId)
                        .with(user("customer").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"PETG\",\"type\":\"PETG\",\"pricePerGram\":0.06,\"stockQuantity\":75,\"unit\":\"g\",\"colors\":[]}"))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/materials/{id}", materialId)
                        .with(user("catalog-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"PETG\",\"type\":\"PETG\",\"pricePerGram\":0.06,\"stockQuantity\":75,\"unit\":\"g\",\"colors\":[{\"colorId\":" + colorId + ",\"quantity\":75}]}"))
                .andExpect(status().isNoContent());
        entityManager.flush();
        assertThat(jdbc.queryForObject("select count(*) from material_colors where material_id = ?",
                Integer.class, materialId)).isEqualTo(1);
        entityManager.clear();
        mvc.perform(get("/api/materials/").with(user("catalog-admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("PETG"))
                .andExpect(jsonPath("$[0].colors.length()").value(1));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/materials/{id}", materialId)
                        .with(user("catalog-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Broken batch\",\"type\":\"PETG\",\"pricePerGram\":0.06,\"stockQuantity\":75,\"unit\":\"g\",\"colors\":[{\"colorId\":" + colorId + ",\"quantity\":75},{\"colorId\":" + colorId + ",\"quantity\":25}]}"))
                .andExpect(status().isBadRequest());
        entityManager.flush();
        entityManager.clear();
        assertThat(jdbc.queryForObject("select name from materials where material_id = ?", String.class,
                materialId)).isEqualTo("PETG");
        assertThat(jdbc.queryForObject("select count(*) from material_colors where material_id = ?",
                Integer.class, materialId)).isEqualTo(1);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/materials/{id}/remove", materialId)
                        .with(user("catalog-admin").roles("ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/materials/{id}", materialId)
                        .with(user("catalog-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Retired PETG\",\"type\":\"PETG\",\"pricePerGram\":0.06,\"stockQuantity\":75,\"unit\":\"g\",\"colors\":[{\"colorId\":" + colorId + ",\"quantity\":50}]}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/materials/").with(user("catalog-admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Retired PETG"))
                .andExpect(jsonPath("$[0].isAvailable").value("UNAVAILABLE"));
    }

    @Test
    void materialPutDistinguishesOmittedNullAndEmptyColorsWithoutAffectingSharedColors() throws Exception {
        mvc.perform(post("/api/colors/create").with(user("catalog-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"AT material red\",\"hexCode\":\"#AA0000\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/colors/create").with(user("catalog-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"AT material blue\",\"hexCode\":\"#0000AA\"}"))
                .andExpect(status().isOk());
        Integer redId = jdbc.queryForObject("select color_id from colors where name = 'AT material red'", Integer.class);
        Integer blueId = jdbc.queryForObject("select color_id from colors where name = 'AT material blue'", Integer.class);

        mvc.perform(post("/api/materials/create").with(user("catalog-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"AT primary PLA\",\"type\":\"PLA\",\"pricePerGram\":0.05,"
                                + "\"stockQuantity\":100,\"unit\":\"g\",\"colors\":["
                                + "{\"colorId\":" + redId + ",\"quantity\":11},"
                                + "{\"colorId\":" + blueId + ",\"quantity\":22}]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/materials/create").with(user("catalog-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"AT shared PETG\",\"type\":\"PETG\",\"pricePerGram\":0.06,"
                                + "\"stockQuantity\":75,\"unit\":\"g\",\"colors\":["
                                + "{\"colorId\":" + blueId + ",\"quantity\":33}]}"))
                .andExpect(status().isOk());
        Integer primaryId = jdbc.queryForObject(
                "select material_id from materials where name = 'AT primary PLA'", Integer.class);
        Integer sharedMaterialId = jdbc.queryForObject(
                "select material_id from materials where name = 'AT shared PETG'", Integer.class);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/materials/{id}/remove", primaryId)
                        .with(user("catalog-admin").roles("ADMIN")))
                .andExpect(status().isOk());

        mvc.perform(put("/api/materials/{id}", primaryId).with(user("catalog-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"AT omitted update\",\"type\":\"PLA\","
                                + "\"pricePerGram\":0.05,\"stockQuantity\":100,\"unit\":\"g\"}"))
                .andExpect(status().isNoContent());
        assertMaterialMetadataAndAssociations(primaryId, "AT omitted update", "UNAVAILABLE",
                List.of(redId, blueId), List.of(11d, 22d));

        mvc.perform(put("/api/materials/{id}", primaryId).with(user("catalog-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"AT explicit null update\",\"type\":\"PETG\","
                                + "\"pricePerGram\":0.06,\"stockQuantity\":75,\"unit\":\"g\",\"colors\":null}"))
                .andExpect(status().isNoContent());
        assertMaterialMetadataAndAssociations(primaryId, "AT explicit null update", "UNAVAILABLE",
                List.of(redId, blueId), List.of(11d, 22d));

        mvc.perform(put("/api/materials/{id}", primaryId).with(user("catalog-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"AT empty update\",\"type\":\"PLA\","
                                + "\"pricePerGram\":0.05,\"stockQuantity\":100,\"unit\":\"g\",\"colors\":[]}"))
                .andExpect(status().isNoContent());
        assertMaterialMetadataAndAssociations(primaryId, "AT empty update", "UNAVAILABLE", List.of(), List.of());
        assertThat(jdbc.queryForList("select color_id from material_colors where material_id = ?", Integer.class,
                sharedMaterialId)).containsExactly(blueId);
        assertThat(jdbc.queryForObject("select count(*) from colors where color_id in (?, ?)", Integer.class,
                redId, blueId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from materials where material_id = ?", Integer.class,
                primaryId)).isEqualTo(1);
    }

    @Test
    void lookingUpUnknownColorReturnsNotFoundInsteadOfServerError() throws Exception {
        mvc.perform(get("/api/colors/{colorName}", "AT missing color")
                        .with(user("catalog-admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void creatingMaterialWithUnknownColorIdReturnsNotFoundInsteadOfServerError() throws Exception {
        mvc.perform(post("/api/materials/create")
                        .with(user("catalog-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"AT missing color reference\",\"type\":\"PLA\","
                                + "\"pricePerGram\":0.05,\"stockQuantity\":100,\"unit\":\"g\","
                                + "\"colors\":[{\"colorId\":2147483647,\"quantity\":1}]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void malformedAdminProductPatchReturns400WithoutMutationOrUploadSpecificError() throws Exception {
        Model3d product = savePublishedProductWithMediaReferences();
        String filePathBefore = jdbc.queryForObject("select file_path_3d from model_3d where model_id = ?",
                String.class, product.getId());
        Integer imagesBefore = jdbc.queryForObject("select count(*) from model_images where model_id = ?",
                Integer.class, product.getId());

        var malformed = mvc.perform(patch("/api/admin/products/{id}", product.getId())
                        .with(user("catalog-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andReturn();
        assertProductPatchUnchanged(product, filePathBefore, imagesBefore);
        assertThat(malformed.getResponse().getContentAsString()).doesNotContain("multipart", "trace", "stackTrace");
    }

    @Test
    void invalidMetadataAndBindingAdminPatchRequestsReturnSafeContextual400WithoutMutation() throws Exception {
        Model3d product = savePublishedProductWithMediaReferences();
        String filePathBefore = jdbc.queryForObject("select file_path_3d from model_3d where model_id = ?",
                String.class, product.getId());
        Integer imagesBefore = jdbc.queryForObject("select count(*) from model_images where model_id = ?",
                Integer.class, product.getId());

        var invalidMetadata = mvc.perform(patch("/api/admin/products/{id}", product.getId())
                        .with(user("catalog-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest()).andReturn();
        assertProductPatchUnchanged(product, filePathBefore, imagesBefore);
        assertThat(invalidMetadata.getResponse().getContentAsString())
                .doesNotContain("multipart", "trace", "stackTrace", "internal server error");

        var bindingError = mvc.perform(patch("/api/admin/products/{id}", product.getId())
                        .with(user("catalog-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":{\"unexpected\":true}}"))
                .andExpect(status().isBadRequest()).andReturn();
        assertProductPatchUnchanged(product, filePathBefore, imagesBefore);
        assertThat(bindingError.getResponse().getContentAsString())
                .doesNotContain("multipart", "trace", "stackTrace", "internal server error");
    }

    private void assertMaterialMetadataAndAssociations(Integer materialId, String expectedName,
                                                        String expectedStatus, List<Integer> colorIds,
                                                        List<Double> quantities) {
        entityManager.flush();
        entityManager.clear();
        assertThat(jdbc.queryForObject("select name from materials where material_id = ?", String.class, materialId))
                .isEqualTo(expectedName);
        assertThat(jdbc.queryForObject("select is_available from materials where material_id = ?",
                String.class, materialId)).isEqualTo(expectedStatus);
        assertThat(jdbc.queryForList("select color_id from material_colors where material_id = ? order by color_id",
                Integer.class, materialId)).containsExactlyElementsOf(colorIds.stream().sorted().toList());
        for (int index = 0; index < colorIds.size(); index++) {
            assertThat(jdbc.queryForObject("select quantity from material_colors where material_id = ? and color_id = ?",
                    Double.class, materialId, colorIds.get(index))).isEqualTo(quantities.get(index));
        }
    }

    private void assertProductPatchUnchanged(Model3d product, String filePathBefore, Integer imagesBefore) {
        entityManager.clear();
        Model3d current = models.findById(product.getId()).orElseThrow();
        assertThat(current.getName()).isEqualTo("Patch baseline");
        assertThat(current.getDescription()).isEqualTo("Description");
        assertThat(current.getCategory()).isEqualTo("Catalog");
        assertThat(current.getStatus()).isEqualTo(ModelStatus.ACTIVE);
        assertThat(jdbc.queryForObject("select file_path_3d from model_3d where model_id = ?", String.class,
                product.getId())).isEqualTo(filePathBefore);
        assertThat(jdbc.queryForObject("select count(*) from model_images where model_id = ?", Integer.class,
                product.getId())).isEqualTo(imagesBefore);
    }

    private Model3d savePublishedProductWithMediaReferences() {
        Model3d product = saveModel("Patch baseline", ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE);
        product.setFilePath3d("qa-attached/model.glb");
        product = models.saveAndFlush(product);
        modelImages.saveAndFlush(ModelImage.builder().model(product).imagePath("qa-attached/preview.png")
                .isPrimary(true).build());
        return product;
    }

    private Integer jdbcMaterialId() {
        return jdbc.queryForObject("select material_id from materials where name = 'PLA'", Integer.class);
    }

    private Model3d saveModel(String name, ModelType type, ModelStatus status) {
        return models.saveAndFlush(Model3d.builder().sourceType(type).status(status).name(name)
                .slug("slug-" + name.toLowerCase().replace(' ', '-')).description("Description")
                .category("Catalog").build());
    }

    private MockMultipartFile jsonPart(String name, String json) {
        return new MockMultipartFile(name, "", MediaType.APPLICATION_JSON_VALUE,
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private RequestPostProcessor adminPrincipal() {
        com.exe.morphia3d.entity.User admin = new com.exe.morphia3d.entity.User();
        admin.setId(721);
        UserPrincipal principal = new UserPrincipal(admin);
        return authentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
    }
}
