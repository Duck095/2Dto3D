package com.exe.morphia3d.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import com.exe.morphia3d.entity.MediaAsset;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.ProductGlbStructureSnapshot;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.repository.MediaAssetRepository;
import com.exe.morphia3d.repository.Model3dRepository;
import com.exe.morphia3d.repository.ProductGlbStructureSnapshotRepository;
import com.exe.morphia3d.repository.UserRepository;
import com.exe.morphia3d.service.glb.GlbStructure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Supplemental row-lock smoke test; migration-backed PostgreSQL certification lives in PostgresV1V2SchemaIntegrationTest. */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.flyway.enabled=false",
        "media.lifecycle.reconciliation-interval-millis=3600000"
})
@Import(MediaAssetLifecyclePostgresTest.PostgresContainerConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MediaAssetLifecyclePostgresTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresContainerConfiguration {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> mediaLifecyclePostgresContainer() {
            return new PostgreSQLContainer<>(DockerImageName.parse(
                    "postgres@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("media_lifecycle")
                    .withUsername("test")
                    .withPassword("test");
        }
    }

    @Autowired
    private MediaAssetLifecycleService lifecycle;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MediaAssetRepository assets;

    @Autowired
    private Model3dRepository models;

    @Autowired
    private UserRepository users;

    @Autowired
    private ProductGlbStructureSnapshotRepository snapshots;

    @Test
    void postgresRowLockAllowsOnlyOneConcurrentCleanupLease() throws Exception {
        UUID operationId = UUID.randomUUID();
        MediaAssetOperationLease operation = lifecycle.createUploadIntents(operationId,
                List.of(new MediaAssetIntentSpec(MediaKind.IMAGE, "png", 3L)));
        long id = operation.mediaAssetIds().get(0);
        lifecycle.requestCleanup(operation, List.of(id));
        Instant expiredAt = Instant.now().minusSeconds(600);
        jdbc.update("UPDATE media_asset SET lease_until = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(expiredAt), Timestamp.from(expiredAt), id);

        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<MediaAssetCleanupLease> claim = () -> lifecycle.claimCleanup(id, UUID.randomUUID().toString());
            var first = pool.submit(claim);
            var second = pool.submit(claim);

            assertThat(Stream.of(first.get(), second.get()).filter(java.util.Objects::nonNull).count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void historicalSnapshotProtectsReplacedGlbFromCleanup() {
        UUID oldOperationId = UUID.randomUUID();
        MediaAssetOperationLease oldOperation = lifecycle.createUploadIntents(oldOperationId,
                List.of(new MediaAssetIntentSpec(MediaKind.MODEL_3D, "glb", 12L)));
        long oldMediaId = oldOperation.mediaAssetIds().get(0);
        MediaUploadIntent oldUpload = lifecycle.claimUpload(oldOperation, oldMediaId);
        lifecycle.recordUpload(oldUpload, uploadResult(oldUpload));

        MediaAssetOperationLease currentOperation = lifecycle.createUploadIntents(UUID.randomUUID(),
                List.of(new MediaAssetIntentSpec(MediaKind.MODEL_3D, "glb", 12L)));
        long currentMediaId = currentOperation.mediaAssetIds().get(0);
        MediaUploadIntent currentUpload = lifecycle.claimUpload(currentOperation, currentMediaId);
        lifecycle.recordUpload(currentUpload, uploadResult(currentUpload));

        User owner = users.saveAndFlush(User.builder().name("History owner")
                .email("history-owner-" + UUID.randomUUID() + "@example.invalid")
                .password("test-only").build());
        MediaAsset currentAsset = assets.findById(currentMediaId).orElseThrow();
        Model3d product = models.saveAndFlush(Model3d.builder().sourceType(ModelType.DESIGNER_MODEL)
                .user(owner).name("Historical GLB product").filePath3d(currentUpload.publicId())
                .mediaAsset(currentAsset).build());

        snapshots.save(new ProductGlbStructureSnapshot(UUID.randomUUID(), product.getId(), oldMediaId, 1L,
                ProductGlbStructureSnapshot.CURRENT_PARSER_VERSION, structureJson(), Instant.now(), owner.getId()));
        assertThat(snapshots.existsByMediaAssetId(oldMediaId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_mesh_configuration_revisions WHERE media_asset_id=?",
                Integer.class, oldMediaId)).isZero();

        // Simulate a legacy cleanup candidate after product media replacement; this is fixture setup,
        // not a direct-SQL reference writer. The snapshot above is created through the history repository.
        Instant cleanupDueAt = Instant.now().minusSeconds(600);
        jdbc.update("UPDATE media_asset SET state='CLEANUP_PENDING',lease_owner=NULL,lease_token=NULL,"
                        + "lease_until=?,updated_at=? WHERE id=?",
                Timestamp.from(cleanupDueAt), Timestamp.from(cleanupDueAt), oldMediaId);

        assertThat(assets.findById(oldMediaId).orElseThrow().getState()).isEqualTo(com.exe.morphia3d.enums.MediaAssetState.CLEANUP_PENDING);
        assertThat(models.findById(product.getId()).orElseThrow().getMediaAsset().getId()).isEqualTo(currentMediaId);
        assertThat(lifecycle.claimCleanup(oldMediaId, "history-worker")).isNull();
    }

    private MediaUploadResult uploadResult(MediaUploadIntent intent) {
        return new MediaUploadResult(MediaProvider.CLOUDINARY, "test-cloud", "provider-asset-" + intent.mediaAssetId(),
                intent.publicId(), MediaKind.MODEL_3D, "raw", "upload", "glb", 12L, 1L,
                "https://res.cloudinary.com/test-cloud/raw/upload/v1/" + intent.publicId());
    }

    private static JsonNode structureJson() {
        GlbStructure structure = new GlbStructure(-1, List.of(),
                List.of(new GlbStructure.Node(0, "Node", 0, List.of(), List.of())),
                List.of(new GlbStructure.Mesh(0, "Mesh", List.of(
                        new GlbStructure.Primitive(0, null, List.of("POSITION"), List.of())))),
                List.of(), List.of(), List.of(),
                List.of(new GlbStructure.PrimitiveOccurrence(0, 0, 0, "Node", null)));
        return new ObjectMapper().valueToTree(structure);
    }
}
