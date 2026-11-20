package com.exe.morphia3d.controller;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.entity.MediaAsset;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.ModelImage;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.MediaAssetState;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import com.exe.morphia3d.enums.ModelStatus;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.UserStatus;
import com.exe.morphia3d.repository.MediaAssetRepository;
import com.exe.morphia3d.repository.Model3dRepository;
import com.exe.morphia3d.repository.ModelImageRepository;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.MediaAssetReconciler;
import com.exe.morphia3d.service.MediaStorageService;
import com.exe.morphia3d.test.support.PostgresTestRoleProvisioning;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = Morphia3dApplication.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = false)
class DesignerModelPublicationIntegrationTest {

    private static final String PUBLISH_PATH = "/api/admin/designer-models/{id}/publish";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("designer_publication_integration_test")
            .withUsername("designer_publication_test")
            .withPassword("designer_publication_test_only");

    @DynamicPropertySource
    static void configureDisposablePostgres(DynamicPropertyRegistry registry) {
        if (!POSTGRES.isRunning()) POSTGRES.start();
        JdbcTemplate bootstrap = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        PostgresTestRoleProvisioning.provisionMigrationRoles(bootstrap, POSTGRES.getUsername());
        Flyway throughV14 = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion("14")).load();
        throughV14.migrate();
        PostgresTestRoleProvisioning.grantSnapshotReferencePrivileges(bootstrap);
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired private WebApplicationContext context;
    @Autowired private Model3dRepository models;
    @Autowired private ModelImageRepository images;
    @Autowired private MediaAssetRepository assets;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private MediaStorageService storage;
    @MockitoBean private MediaAssetReconciler reconciler;

    private MockMvc mockMvc;
    private final List<Integer> modelIds = new ArrayList<>();
    private final List<Integer> imageIds = new ArrayList<>();
    private final List<Long> assetIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc.update("INSERT INTO public.admin3d_publication_policy(singleton, cloud_name) VALUES (TRUE, 'test-cloud') " +
                "ON CONFLICT (singleton) DO UPDATE SET cloud_name = EXCLUDED.cloud_name");
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void removeOnlyThisTestFixtures() {
        images.deleteAllById(imageIds);
        models.deleteAllById(modelIds);
        assets.deleteAllById(assetIds);
        imageIds.clear();
        modelIds.clear();
        assetIds.clear();
    }

    @Test
    void adminPublishesReadyPendingProductAndCatalogExposesItAfterCommit() throws Exception {
        Fixture fixture = pendingFixture();

        mockMvc.perform(get("/api/products/{id}", fixture.model().getId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/products").param("q", fixture.model().getName()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());

        mockMvc.perform(publish(fixture.model().getId(), admin(901)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/products/{id}", fixture.model().getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fixture.model().getId()));
        mockMvc.perform(get("/api/products").param("q", fixture.model().getName()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(fixture.model().getId()));

        assertThat(models.findById(fixture.model().getId()).orElseThrow().getStatus())
                .isEqualTo(ModelStatus.ACTIVE);
        assertAttached(fixture);
        verifyNoInteractions(storage);
    }

    @Test
    void repeatedAdminPublishIsIdempotentAndLeavesAttachedMediaUnchanged() throws Exception {
        Fixture fixture = pendingFixture();

        mockMvc.perform(publish(fixture.model().getId(), admin(902))).andExpect(status().isNoContent());
        mockMvc.perform(publish(fixture.model().getId(), admin(902))).andExpect(status().isNoContent());

        assertThat(models.findById(fixture.model().getId()).orElseThrow().getStatus())
                .isEqualTo(ModelStatus.ACTIVE);
        assertAttached(fixture);
        verifyNoInteractions(storage);
    }

    @Test
    void onlyAdminWithApplicationPrincipalCanPublishAndRejectionsHaveNoSideEffects() throws Exception {
        Fixture fixture = pendingFixture();
        var invalidPrincipal = UsernamePasswordAuthenticationToken.authenticated("not-an-application-principal",
                null, AuthorityUtils.createAuthorityList("ROLE_ADMIN"));

        mockMvc.perform(post(PUBLISH_PATH, fixture.model().getId())).andExpect(status().isUnauthorized());
        mockMvc.perform(publish(fixture.model().getId(), user("ordinary-user").roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(publish(fixture.model().getId(), designer(903))).andExpect(status().isForbidden());
        mockMvc.perform(publish(fixture.model().getId(), authentication(invalidPrincipal)))
                .andExpect(status().isForbidden());

        assertThat(models.findById(fixture.model().getId()).orElseThrow().getStatus())
                .isEqualTo(ModelStatus.PENDING);
        assertAttached(fixture);
        verifyNoInteractions(storage);
    }

    @Test
    void missingAndAiModelsReturnNotFound() throws Exception {
        Model3d aiModel = models.saveAndFlush(Model3d.builder().name("Private AI model")
                .sourceType(ModelType.AI_MODEL).status(ModelStatus.PENDING).build());
        modelIds.add(aiModel.getId());

        mockMvc.perform(publish(2_000_000_000, admin(904))).andExpect(status().isNotFound());
        mockMvc.perform(publish(aiModel.getId(), admin(904))).andExpect(status().isNotFound());
        assertThat(models.findById(aiModel.getId()).orElseThrow().getStatus()).isEqualTo(ModelStatus.PENDING);
    }

    @Test
    void inactiveModelReturnsConflictWithoutChangingStatus() throws Exception {
        Fixture fixture = pendingFixture();
        fixture.model().setStatus(ModelStatus.INACTIVE);
        models.saveAndFlush(fixture.model());

        mockMvc.perform(publish(fixture.model().getId(), admin(905))).andExpect(status().isConflict());

        assertThat(models.findById(fixture.model().getId()).orElseThrow().getStatus())
                .isEqualTo(ModelStatus.INACTIVE);
        assertAttached(fixture);
    }

    @Test
    void missingModelNamePathMediaOrImagesBlocksPublication() throws Exception {
        Fixture blankName = pendingFixture();
        blankName.model().setName("   ");
        models.saveAndFlush(blankName.model());
        assertConflictAndPending(blankName);

        Fixture blankPath = pendingFixture();
        blankPath.model().setFilePath3d(" ");
        models.saveAndFlush(blankPath.model());
        assertConflictAndPending(blankPath);

        Fixture missingModelAsset = pendingFixture();
        missingModelAsset.model().setMediaAsset(null);
        models.saveAndFlush(missingModelAsset.model());
        assertConflictAndPending(missingModelAsset);

        Fixture missingImage = pendingFixture();
        images.deleteById(missingImage.image().getId());
        assertConflictAndPending(missingImage);

        Fixture missingImageAsset = pendingFixture();
        missingImageAsset.image().setMediaAsset(null);
        images.saveAndFlush(missingImageAsset.image());
        assertConflictAndPending(missingImageAsset);
    }

    @Test
    void requiresExactlyOnePersistedPrimaryImage() throws Exception {
        Fixture noPrimary = pendingFixture();
        noPrimary.image().setIsPrimary(false);
        images.saveAndFlush(noPrimary.image());
        assertConflictAndPending(noPrimary);

        Fixture multiplePrimary = pendingFixture();
        ModelImage second = saveImage(multiplePrimary.model(), multiplePrimary.modelAsset().getOperationId(), true);
        multiplePrimary.extraImages().add(second);
        assertConflictAndPending(multiplePrimary);
    }

    @Test
    void requiresAttachedTrustedAssetsMatchingPathsAndOperation() throws Exception {
        Fixture modelReady = pendingFixture();
        modelReady.modelAsset().setState(MediaAssetState.READY);
        assets.saveAndFlush(modelReady.modelAsset());
        assertConflictAndPending(modelReady);

        Fixture imageCleanupPending = pendingFixture();
        imageCleanupPending.imageAsset().setState(MediaAssetState.CLEANUP_PENDING);
        assets.saveAndFlush(imageCleanupPending.imageAsset());
        assertConflictAndPending(imageCleanupPending);

        Fixture invalidProviderIdentity = pendingFixture();
        invalidProviderIdentity.modelAsset().setProviderAssetId(null);
        assets.saveAndFlush(invalidProviderIdentity.modelAsset());
        assertConflictAndPending(invalidProviderIdentity);

        Fixture invalidDeliveryUrl = pendingFixture();
        invalidDeliveryUrl.imageAsset().setSecureUrl("https://untrusted.example/not-a-cloudinary-asset");
        assets.saveAndFlush(invalidDeliveryUrl.imageAsset());
        assertConflictAndPending(invalidDeliveryUrl);

        Fixture mismatchedModelPath = pendingFixture();
        mismatchedModelPath.model().setFilePath3d("another/model.glb");
        models.saveAndFlush(mismatchedModelPath.model());
        assertConflictAndPending(mismatchedModelPath);

        Fixture mismatchedImagePath = pendingFixture();
        mismatchedImagePath.image().setImagePath("another/image.png");
        images.saveAndFlush(mismatchedImagePath.image());
        assertConflictAndPending(mismatchedImagePath);

        Fixture mixedOperations = pendingFixture();
        UUID differentOperation = UUID.randomUUID();
        rekeyImageAsset(mixedOperations.imageAsset(), differentOperation);
        mixedOperations.image().setImagePath(mixedOperations.imageAsset().getPublicId());
        assets.saveAndFlush(mixedOperations.imageAsset());
        images.saveAndFlush(mixedOperations.image());
        assertConflictAndPending(mixedOperations);
    }

    @Test
    void concurrentAdminPublishRequestsBothCompleteWithOneActiveFinalState() throws Exception {
        Fixture fixture = pendingFixture();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = executor.submit(() -> concurrentPublish(fixture.model().getId(), 906, ready, start));
            Future<Integer> second = executor.submit(() -> concurrentPublish(fixture.model().getId(), 907, ready, start));
            assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(204);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(204);
            assertThat(models.findById(fixture.model().getId()).orElseThrow().getStatus())
                    .isEqualTo(ModelStatus.ACTIVE);
            assertAttached(fixture);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    private int concurrentPublish(Integer modelId, int adminId, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        if (!start.await(3, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting to start concurrent publication");
        }
        return mockMvc.perform(publish(modelId, admin(adminId))).andReturn().getResponse().getStatus();
    }

    private void assertConflictAndPending(Fixture fixture) throws Exception {
        List<MediaAssetState> modelAndImageStates = List.of(
                fixture.modelAsset().getState(), fixture.imageAsset().getState());
        mockMvc.perform(publish(fixture.model().getId(), admin(908))).andExpect(status().isConflict());
        assertThat(models.findById(fixture.model().getId()).orElseThrow().getStatus()).isEqualTo(ModelStatus.PENDING);
        assertThat(List.of(assets.findById(fixture.modelAsset().getId()).orElseThrow().getState(),
                assets.findById(fixture.imageAsset().getId()).orElseThrow().getState()))
                .containsExactlyElementsOf(modelAndImageStates);
    }

    private Fixture pendingFixture() {
        UUID operationId = UUID.randomUUID();
        MediaAsset modelAsset = saveAsset(operationId, MediaKind.MODEL_3D, "glb", "raw");
        MediaAsset imageAsset = saveAsset(operationId, MediaKind.IMAGE, "png", "image");
        String name = "Publication chair " + UUID.randomUUID();
        Model3d model = models.saveAndFlush(Model3d.builder().sourceType(ModelType.DESIGNER_MODEL)
                .status(ModelStatus.PENDING).name(name).slug("publication-" + UUID.randomUUID())
                .filePath3d(modelAsset.getPublicId()).mediaAsset(modelAsset).build());
        modelIds.add(model.getId());
        ModelImage image = saveImage(model, operationId, imageAsset, true);
        return new Fixture(model, modelAsset, image, imageAsset, new ArrayList<>());
    }

    private MediaAsset saveAsset(UUID operationId, MediaKind kind, String format, String resourceType) {
        String folder = kind == MediaKind.IMAGE ? "images" : "models";
        MediaAsset asset = assets.saveAndFlush(MediaAsset.builder().operationId(operationId).mediaKind(kind)
                .provider(MediaProvider.CLOUDINARY).cloudName("test-cloud")
                .providerAssetId("provider-asset-" + UUID.randomUUID())
                .publicId("pending/" + UUID.randomUUID()).resourceType(resourceType).deliveryType("upload")
                .format(format).byteCount(12L).providerVersion(1L).state(MediaAssetState.ATTACHED).build());
        asset.setPublicId("product-media/" + folder + "/" + operationId + "/" + asset.getId()
                + (kind == MediaKind.MODEL_3D ? ".glb" : ""));
        asset.setSecureUrl("https://res.cloudinary.com/test-cloud/" + resourceType + "/upload/v1/"
                + asset.getPublicId() + (kind == MediaKind.IMAGE ? "." + format : ""));
        asset = assets.saveAndFlush(asset);
        assetIds.add(asset.getId());
        return asset;
    }

    private ModelImage saveImage(Model3d model, UUID operationId, boolean primary) {
        MediaAsset imageAsset = saveAsset(operationId, MediaKind.IMAGE, "png", "image");
        return saveImage(model, operationId, imageAsset, primary);
    }

    private ModelImage saveImage(Model3d model, UUID operationId, MediaAsset imageAsset, boolean primary) {
        ModelImage image = images.saveAndFlush(ModelImage.builder().model(model).imagePath(imageAsset.getPublicId())
                .mediaAsset(imageAsset).isPrimary(primary).build());
        imageIds.add(image.getId());
        return image;
    }

    private void rekeyImageAsset(MediaAsset imageAsset, UUID operationId) {
        imageAsset.setOperationId(operationId);
        imageAsset.setPublicId("product-media/images/" + operationId + "/" + imageAsset.getId() + ".png");
        imageAsset.setSecureUrl("https://res.cloudinary.com/test-cloud/image/upload/v1/"
                + imageAsset.getPublicId());
    }

    private void assertAttached(Fixture fixture) {
        assertThat(assets.findById(fixture.modelAsset().getId()).orElseThrow().getState())
                .isEqualTo(MediaAssetState.ATTACHED);
        assertThat(assets.findById(fixture.imageAsset().getId()).orElseThrow().getState())
                .isEqualTo(MediaAssetState.ATTACHED);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder publish(
            Integer modelId, RequestPostProcessor processor) {
        return post(PUBLISH_PATH, modelId).with(processor).contentType(MediaType.APPLICATION_JSON);
    }

    private RequestPostProcessor admin(int id) {
        return principal(Role.ADMIN, id);
    }

    private RequestPostProcessor designer(int id) {
        return principal(Role.DESIGNER, id);
    }

    private RequestPostProcessor principal(Role role, int id) {
        User user = User.builder().id(id).role(role).status(UserStatus.ACTIVE).name("Reviewer " + id)
                .email("reviewer-" + id + "@example.test").password("encoded").build();
        UserPrincipal principal = new UserPrincipal(user);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private record Fixture(Model3d model, MediaAsset modelAsset, ModelImage image, MediaAsset imageAsset,
                           List<ModelImage> extraImages) {
    }
}
