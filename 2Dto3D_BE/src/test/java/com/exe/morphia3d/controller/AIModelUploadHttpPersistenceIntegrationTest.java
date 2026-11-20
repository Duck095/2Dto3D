package com.exe.morphia3d.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.dto.request.AIModelRequest;
import com.exe.morphia3d.dto.request.ModelPartRequest;
import com.exe.morphia3d.entity.MediaAsset;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.MediaAssetState;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.UserStatus;
import com.exe.morphia3d.repository.MediaAssetRepository;
import com.exe.morphia3d.repository.Model3dRepository;
import com.exe.morphia3d.repository.UserRepository;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.MediaAssetLifecycleService;
import com.exe.morphia3d.service.MediaAssetOperationLease;
import com.exe.morphia3d.service.MediaAssetReconciler;
import com.exe.morphia3d.service.MediaStorageService;
import com.exe.morphia3d.service.MediaUploadIntent;
import com.exe.morphia3d.service.MediaUploadResult;
import com.exe.morphia3d.service.ValidatedMedia;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = Morphia3dApplication.class,
        properties = "media.lifecycle.reconciliation-interval-millis=86400000")
@ActiveProfiles("test")
@TestPropertySource(properties = "app.features.ai.enabled=true")
class AIModelUploadHttpPersistenceIntegrationTest {

    @Autowired private WebApplicationContext context;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired private UserRepository users;
    @Autowired private Model3dRepository models;
    @Autowired private MediaAssetRepository assets;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private MediaStorageService storage;
    @MockitoBean private MediaAssetReconciler reconciler;
    @MockitoSpyBean private MediaAssetLifecycleService lifecycle;

    private MockMvc mockMvc;
    private final List<Integer> fixtureUserIds = new ArrayList<>();
    private final Set<Long> fixtureAssetIds = new LinkedHashSet<>();
    private boolean cleanupObservedReadyAssets;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        fixtureUserIds.clear();
        fixtureAssetIds.clear();
        cleanupObservedReadyAssets = false;

        doAnswer(invocation -> {
            MediaAssetOperationLease operation = (MediaAssetOperationLease) invocation.callRealMethod();
            fixtureAssetIds.addAll(operation.mediaAssetIds());
            return operation;
        }).when(lifecycle).createUploadIntents(any(UUID.class), anyList());

        doAnswer(invocation -> {
            Collection<Long> assetIds = invocation.getArgument(1);
            cleanupObservedReadyAssets = assets.findAllById(assetIds).size() == assetIds.size()
                    && assets.findAllById(assetIds).stream()
                    .allMatch(asset -> asset.getState() == MediaAssetState.READY);
            return invocation.callRealMethod();
        }).when(lifecycle).requestCleanup(any(MediaAssetOperationLease.class), any(Collection.class));

        when(storage.upload(any(MediaUploadIntent.class), any(ValidatedMedia.class))).thenAnswer(invocation -> {
            MediaUploadIntent intent = invocation.getArgument(0);
            ValidatedMedia media = invocation.getArgument(1);
            fixtureAssetIds.add(intent.mediaAssetId());
            String format = media.format();
            String urlPath = intent.mediaKind() == MediaKind.MODEL_3D
                    ? intent.publicId() : intent.publicId() + "." + format;
            String secureUrl = "https://res.cloudinary.com/test-cloud/" + intent.resourceType()
                    + "/upload/v1/" + urlPath;
            return new MediaUploadResult(MediaProvider.CLOUDINARY, "test-cloud",
                    "test-asset-" + intent.mediaAssetId(), intent.publicId(), intent.mediaKind(),
                    intent.resourceType(), intent.deliveryType(), format, media.byteCount(), 1, secureUrl);
        });
    }

    @AfterEach
    void cleanOwnFixtures() {
        for (Integer userId : fixtureUserIds) {
            jdbc.update("DELETE FROM model_images WHERE model_id IN "
                    + "(SELECT model_id FROM model_3d WHERE user_id = ?)", userId);
            jdbc.update("DELETE FROM model_parts WHERE model_id IN "
                    + "(SELECT model_id FROM model_3d WHERE user_id = ?)", userId);
            jdbc.update("DELETE FROM model_3d WHERE user_id = ?", userId);
        }
        fixtureAssetIds.forEach(id -> jdbc.update("DELETE FROM media_asset WHERE id = ?", id));
        for (Integer userId : fixtureUserIds) {
            users.deleteById(userId);
        }
    }

    @Test
    void authenticatedPostPersistsAndAttachesAiModelUsingTrustedMediaIdentity() throws Exception {
        User owner = saveUser("owner");
        User other = saveUser("other");

        String responseBody = mockMvc.perform(upload(requestJson(null), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.sourceType").value("AI_MODEL"))
                .andExpect(jsonPath("$.userId").value(owner.getId()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.modelParts[0].partName").value("shade"))
                .andExpect(jsonPath("$.modelImages.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        JsonNode response = objectMapper.readTree(responseBody);
        int modelId = response.path("id").asInt();
        assertThat(modelId).isPositive();

        var row = jdbc.queryForMap("SELECT source_type, model_status, user_id, file_path_3d, media_asset_id, name, "
                + "slug, category, size_x_mm, size_y_mm, size_z_mm, weight FROM model_3d WHERE model_id = ?", modelId);
        assertThat(row.get("source_type").toString()).isEqualTo("AI_MODEL");
        assertThat(row.get("model_status").toString()).isEqualTo("PENDING");
        assertThat(((Number) row.get("user_id")).intValue()).isEqualTo(owner.getId());
        assertThat(row.get("name")).isEqualTo("Generated chair");
        assertThat(row.get("slug")).isEqualTo("generated-chair");
        assertThat(row.get("category")).isEqualTo("furniture");
        assertThat(((Number) row.get("size_x_mm")).doubleValue()).isEqualTo(120.0);
        assertThat(((Number) row.get("size_y_mm")).doubleValue()).isEqualTo(80.0);
        assertThat(((Number) row.get("size_z_mm")).doubleValue()).isEqualTo(90.0);
        assertThat(((Number) row.get("weight")).doubleValue()).isEqualTo(2.5);
        assertThat(row.get("file_path_3d").toString()).startsWith("product-media/models/")
                .doesNotContain("forged/model.glb");
        assertThat(((Number) row.get("media_asset_id")).longValue()).isIn(fixtureAssetIds);

        List<MapRow> imageRows = jdbc.query("SELECT image_path, media_asset_id, is_primary FROM model_images "
                        + "WHERE model_id = ? ORDER BY image_id", (rs, index) -> new MapRow(
                        rs.getString("image_path"), rs.getLong("media_asset_id"), rs.getBoolean("is_primary")), modelId);
        assertThat(imageRows).hasSize(2);
        assertThat(imageRows).extracting(MapRow::path).allMatch(path -> path.startsWith("product-media/images/"));
        assertThat(imageRows).extracting(MapRow::path).noneMatch("forged/image.png"::equals);
        assertThat(imageRows).extracting(MapRow::primary).containsExactly(true, false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_images WHERE model_id = ? AND is_primary = TRUE",
                Integer.class, modelId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_parts WHERE model_id = ? AND part_name = ? "
                + "AND part_key = ?", Integer.class, modelId, "shade", "main-shade")).isEqualTo(1);

        List<MediaAsset> persistedAssets = assets.findAllById(fixtureAssetIds);
        assertThat(persistedAssets).hasSize(3).extracting(MediaAsset::getState).containsOnly(MediaAssetState.ATTACHED);
        assertThat(persistedAssets).extracting(MediaAsset::getId).containsAll(fixtureAssetIds);
        Long modelAssetId = ((Number) row.get("media_asset_id")).longValue();
        MediaAsset modelAsset = persistedAssets.stream().filter(asset -> asset.getId().equals(modelAssetId))
                .findFirst().orElseThrow();
        assertThat(modelAsset.getMediaKind()).isEqualTo(MediaKind.MODEL_3D);
        assertThat(row.get("file_path_3d")).isEqualTo(modelAsset.getPublicId());
        assertThat(response.path("filePath3d").asText()).isEqualTo(modelAsset.getPublicId());
        assertThat(imageRows).extracting(MapRow::assetId).containsExactlyInAnyOrderElementsOf(
                persistedAssets.stream().filter(asset -> asset.getMediaKind() == MediaKind.IMAGE)
                        .map(MediaAsset::getId).toList());
        for (MapRow image : imageRows) {
            MediaAsset imageAsset = persistedAssets.stream().filter(asset -> asset.getId().equals(image.assetId()))
                    .findFirst().orElseThrow();
            assertThat(image.path()).isEqualTo(imageAsset.getPublicId());
        }
        assertThat(response.path("modelImages").get(0).path("imagePath").asText())
                .isEqualTo(imageRows.get(0).path());
        assertThat(response.path("modelImages").get(1).path("imagePath").asText())
                .isEqualTo(imageRows.get(1).path());

        mockMvc.perform(get("/api/ai-models/{id}", modelId).with(authenticationFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(modelId))
                .andExpect(jsonPath("$.filePath3d").value(row.get("file_path_3d")));
        mockMvc.perform(get("/api/ai-models/{id}", modelId).with(authenticationFor(other)))
                .andExpect(status().isForbidden());

        JsonNode catalog = objectMapper.readTree(mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        boolean appearsInCatalog = false;
        for (JsonNode product : catalog.path("items")) {
            if (product.path("id").asInt() == modelId) appearsInCatalog = true;
        }
        assertThat(appearsInCatalog).isFalse();
        mockMvc.perform(get("/api/products/{id}", modelId)).andExpect(status().isNotFound());

        verify(storage, times(3)).upload(any(MediaUploadIntent.class), any(ValidatedMedia.class));
        verify(storage, never()).delete(any());
    }

    @Test
    void persistenceFailureRollsBackRowsAndRequestsDeferredCleanupWithoutProviderDeletion() throws Exception {
        User owner = saveUser("failure-owner");
        String request = requestJson(999999);

        String response = mockMvc.perform(upload(request, owner))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("The model could not be saved"))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("Requested AI model part color is unavailable")
                .doesNotContain("999999").doesNotContain("Exception");
        assertThat(cleanupObservedReadyAssets).isTrue();
        assertThat(fixtureAssetIds).hasSize(3);
        verify(lifecycle, times(3)).recordUpload(any(MediaUploadIntent.class), any(MediaUploadResult.class));
        assertThat(assets.findAllById(fixtureAssetIds)).hasSize(3)
                .extracting(MediaAsset::getState).containsOnly(MediaAssetState.CLEANUP_PENDING);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_3d WHERE user_id = ?", Integer.class,
                owner.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_images WHERE media_asset_id IN (" + idListSql()
                + ")", Integer.class, fixtureAssetIds.toArray())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_parts WHERE model_id IN "
                + "(SELECT model_id FROM model_3d WHERE user_id = ?)", Integer.class, owner.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_3d WHERE media_asset_id IN (" + idListSql()
                + ")", Integer.class, fixtureAssetIds.toArray())).isZero();

        verify(storage, times(3)).upload(any(MediaUploadIntent.class), any(ValidatedMedia.class));
        verify(storage, never()).delete(any());
    }

    private User saveUser(String label) {
        User user = users.saveAndFlush(User.builder().name("AI HTTP " + label)
                .email("ai-http-" + label + "-" + UUID.randomUUID() + "@example.com")
                .password("encoded").role(Role.USER).status(UserStatus.ACTIVE).build());
        fixtureUserIds.add(user.getId());
        return user;
    }

    private RequestBuilder upload(String request, User owner) throws IOException {
        return multipart("/api/ai-models")
                .file(new MockMultipartFile("data", "", MediaType.APPLICATION_JSON_VALUE,
                        request.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("images", "front.png", "image/png", pngBytes()))
                .file(new MockMultipartFile("images", "side.png", "image/png", pngBytes()))
                .file(new MockMultipartFile("model", "chair.glb", "model/gltf-binary", validGlbBytes()))
                .with(authenticationFor(owner));
    }

    private String requestJson(Integer colorId) throws IOException {
        AIModelRequest request = new AIModelRequest();
        request.setOwnerId(999999);
        request.setName("Generated chair");
        request.setSlug("generated-chair");
        request.setDescription("Saved through the real HTTP flow");
        request.setFilePath3d("forged/model.glb");
        request.setCategory("furniture");
        request.setSizeXMm(120.0);
        request.setSizeYMm(80.0);
        request.setSizeZMm(90.0);
        request.setWeight(2.5);
        request.setImages(List.of(
                com.exe.morphia3d.dto.request.ModelImageRequest.builder()
                        .imagePath("forged/image.png").isPrimary(false).build()));
        ModelPartRequest part = new ModelPartRequest();
        part.setPartName("shade");
        part.setPartKey("main-shade");
        part.setColorId(colorId);
        request.setModelParts(List.of(part));
        return objectMapper.writeValueAsString(request);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor authenticationFor(User user) {
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

    private String idListSql() {
        return String.join(",", fixtureAssetIds.stream().map(id -> "?").toList());
    }

    private record MapRow(String path, long assetId, boolean primary) {
    }
}
