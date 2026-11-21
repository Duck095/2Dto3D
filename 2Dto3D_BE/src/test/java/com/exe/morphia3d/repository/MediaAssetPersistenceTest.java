package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.ModelImage;
import com.exe.morphia3d.entity.MediaAsset;
import com.exe.morphia3d.enums.MediaAssetState;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.ManagedType;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.exe.morphia3d.test.support.PostgresTestRoleProvisioning;

import java.util.UUID;
import java.util.Set;
import java.util.stream.Collectors;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@ActiveProfiles("test")
@Transactional
@Testcontainers
class MediaAssetPersistenceTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("media_asset_persistence")
            .withUsername("media_asset_persistence")
            .withPassword("media_asset_persistence_test_only");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        migrateCanonicalSchema();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    private static synchronized void migrateCanonicalSchema() {
        String url = POSTGRES.getJdbcUrl();
        String username = POSTGRES.getUsername();
        String password = POSTGRES.getPassword();
        JdbcTemplate bootstrap = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        PostgresTestRoleProvisioning.provisionMigrationRoles(bootstrap, username);
        Flyway.configure().dataSource(url, username, password)
                .locations("classpath:db/migration")
                .schemas("public").defaultSchema("public")
                .target(MigrationVersion.fromVersion("14"))
                .cleanDisabled(true).load().migrate();
        PostgresTestRoleProvisioning.grantSnapshotReferencePrivileges(bootstrap);
        Flyway finalMigrations = Flyway.configure().dataSource(url, username, password)
                .locations("classpath:db/migration")
                .schemas("public").defaultSchema("public")
                .cleanDisabled(true).load();
        finalMigrations.migrate();
        finalMigrations.validate();
    }

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MediaAssetRepository mediaAssetRepository;

    @Test
    void mediaAssetEntityExposesProviderMetadataAndLifecycleFields() {
        Set<String> entityNames = entityManager.getMetamodel().getEntities().stream()
                .map(entity -> entity.getName())
                .collect(Collectors.toSet());

        assertThat(entityNames).contains("MediaAsset");
        ManagedType<?> mediaAsset = entityManager.getMetamodel().managedType(
                entityManager.getMetamodel().getEntities().stream()
                        .filter(entity -> entity.getName().equals("MediaAsset"))
                        .findFirst().orElseThrow().getJavaType());
        Set<String> attributes = mediaAsset.getAttributes().stream()
                .map(attribute -> attribute.getName())
                .collect(Collectors.toSet());

        assertThat(attributes).contains(
                "operationId", "mediaKind", "provider", "cloudName", "providerAssetId",
                "publicId", "resourceType", "deliveryType", "format", "byteCount",
                "providerVersion", "secureUrl", "state", "leaseOwner", "leaseToken",
                "leaseUntil", "retryCount", "lastErrorCode", "createdAt", "updatedAt");
    }

    @Test
    void modelAndImageExposeNullableMediaAssetAssociations() {
        Set<String> modelAttributes = entityManager.getMetamodel().managedType(Model3d.class)
                .getAttributes().stream().map(attribute -> attribute.getName()).collect(Collectors.toSet());
        Set<String> imageAttributes = entityManager.getMetamodel().managedType(ModelImage.class)
                .getAttributes().stream().map(attribute -> attribute.getName()).collect(Collectors.toSet());

        assertThat(modelAttributes).contains("mediaAsset");
        assertThat(imageAttributes).contains("mediaAsset");
    }

    @Test
    void providerMetadataPersistsAndMultipleAssetsCanShareOneOperation() {
        UUID operationId = UUID.randomUUID();

        Long firstId = insertAsset(operationId, "products/images/first", "cloudinary-image-1");
        Long secondId = insertAsset(operationId, "products/models/model.glb", "cloudinary-model-1");

        assertThat(firstId).isNotEqualTo(secondId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_asset WHERE operation_id = ?", Integer.class, operationId))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT provider, cloud_name, provider_asset_id, public_id, resource_type, delivery_type, " +
                        "format, byte_count, secure_url FROM media_asset WHERE id = ?", firstId))
                .containsEntry("PROVIDER", "CLOUDINARY")
                .containsEntry("CLOUD_NAME", "test-cloud")
                .containsEntry("PROVIDER_ASSET_ID", "cloudinary-image-1")
                .containsEntry("PUBLIC_ID", "products/images/first")
                .containsEntry("RESOURCE_TYPE", "image")
                .containsEntry("DELIVERY_TYPE", "upload")
                .containsEntry("FORMAT", "png")
                .containsEntry("BYTE_COUNT", 4L)
                .containsEntry("SECURE_URL", "https://res.cloudinary.com/test-cloud/image/upload/v1/products/images/first.png");
    }

    @Test
    void repositoryRoundTripsProviderMetadataAndFindsAllAssetsForAnOperation() {
        UUID operationId = UUID.randomUUID();
        MediaAsset image = asset(operationId, MediaKind.IMAGE, "products/images/cover", "cover-asset",
                "image", "png", "https://res.cloudinary.com/test-cloud/image/upload/v1/products/images/cover.png");
        MediaAsset model = asset(operationId, MediaKind.MODEL_3D, "products/models/chair.glb", "model-asset",
                "raw", "glb", "https://res.cloudinary.com/test-cloud/raw/upload/v1/products/models/chair.glb");

        mediaAssetRepository.saveAndFlush(image);
        mediaAssetRepository.saveAndFlush(model);
        entityManager.clear();

        var rows = mediaAssetRepository.findAllByOperationIdOrderByCreatedAtAscIdAsc(operationId);

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(MediaAsset::getPublicId)
                .containsExactly("products/images/cover", "products/models/chair.glb");
        assertThat(rows.get(0).getProviderAssetId()).isEqualTo("cover-asset");
        assertThat(rows.get(0).getSecureUrl())
                .isEqualTo("https://res.cloudinary.com/test-cloud/image/upload/v1/products/images/cover.png");
        assertThat(rows.get(1).getFormat()).isEqualTo("glb");
    }

    @Test
    void providerAssetIdentityCannotBeAssignedToTwoMediaRows() {
        UUID operationId = UUID.randomUUID();
        insertAsset(operationId, "products/images/first", "same-provider-asset-id");

        assertThatThrownBy(() -> insertAsset(operationId, "products/images/second", "same-provider-asset-id"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void legacyModelAndImageKeepTheirPublicIdsWithNullMediaReferences() {
        Model3d model = Model3d.builder()
                .sourceType(com.exe.morphia3d.enums.ModelType.DESIGNER_MODEL)
                .filePath3d("legacy/models/chair.glb")
                .build();
        model.getImages().add(ModelImage.builder()
                .model(model)
                .imagePath("legacy/images/chair-cover")
                .isPrimary(true)
                .build());

        entityManager.persist(model);
        entityManager.flush();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT file_path_3d FROM model_3d WHERE model_id = ?", String.class, model.getId()))
                .isEqualTo("legacy/models/chair.glb");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT media_asset_id IS NULL FROM model_3d WHERE model_id = ?", Boolean.class, model.getId()))
                .isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT image_path FROM model_images WHERE model_id = ?", String.class, model.getId()))
                .isEqualTo("legacy/images/chair-cover");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT media_asset_id IS NULL FROM model_images WHERE model_id = ?", Boolean.class, model.getId()))
                .isTrue();
    }

    @Test
    void mediaAssetCannotBeDeletedWhileModelOrImageReferencesIt() {
        UUID operationId = UUID.randomUUID();
        Long modelAssetId = insertAsset(operationId, "products/models/model.glb", "cloudinary-model-2");
        Long imageAssetId = insertAsset(operationId, "products/images/image", "cloudinary-image-2");
        Integer modelId = insertModelWithAsset(modelAssetId);
        jdbcTemplate.update(
                "INSERT INTO model_images (model_id, image_path, is_primary, media_asset_id, uploaded_at) " +
                        "VALUES (?, 'legacy/image', TRUE, ?, CURRENT_TIMESTAMP)", modelId, imageAssetId);

        assertThatThrownBy(() -> deleteAssetExpectingForeignKeyRestriction(modelAssetId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> deleteAssetExpectingForeignKeyRestriction(imageAssetId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Long insertAsset(UUID operationId, String publicId, String providerAssetId) {
        String sql = "INSERT INTO media_asset (operation_id, media_kind, provider, cloud_name, provider_asset_id, " +
                "public_id, resource_type, delivery_type, format, byte_count, secure_url, state, retry_count, " +
                "created_at, updated_at) VALUES (?, 'IMAGE', 'CLOUDINARY', 'test-cloud', ?, ?, 'image', " +
                "'upload', 'png', 4, 'https://res.cloudinary.com/test-cloud/image/upload/v1/" +
                "products/images/first.png', 'PENDING_UPLOAD', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update((PreparedStatementCreator) connection -> {
            var statement = connection.prepareStatement(sql, new String[] {"id"});
            statement.setObject(1, operationId);
            statement.setString(2, providerAssetId);
            statement.setString(3, publicId);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private MediaAsset asset(UUID operationId,
                             MediaKind kind,
                             String publicId,
                             String providerAssetId,
                             String resourceType,
                             String format,
                             String secureUrl) {
        return MediaAsset.builder()
                .operationId(operationId)
                .mediaKind(kind)
                .provider(MediaProvider.CLOUDINARY)
                .cloudName("test-cloud")
                .providerAssetId(providerAssetId)
                .publicId(publicId)
                .resourceType(resourceType)
                .deliveryType("upload")
                .format(format)
                .byteCount(4L)
                .secureUrl(secureUrl)
                .state(MediaAssetState.PENDING_UPLOAD)
                .retryCount(0)
                .build();
    }

    private Integer insertModelWithAsset(Long assetId) {
        String sql = "INSERT INTO model_3d (source_type, model_status, file_path_3d, media_asset_id, created_at) " +
                "VALUES ('DESIGNER_MODEL', 'PENDING', 'legacy/model', ?, CURRENT_TIMESTAMP)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update((PreparedStatementCreator) connection -> {
            var statement = connection.prepareStatement(sql, new String[] {"model_id"});
            statement.setLong(1, assetId);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().intValue();
    }

    private void deleteAssetExpectingForeignKeyRestriction(Long assetId) {
        jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            Savepoint savepoint = connection.setSavepoint();
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM media_asset WHERE id = ?")) {
                statement.setLong(1, assetId);
                statement.executeUpdate();
                throw new AssertionError("Expected the model/image foreign key to restrict media deletion");
            } catch (SQLException failure) {
                connection.rollback(savepoint);
                assertThat(failure.getSQLState()).isEqualTo("23503");
                throw new DataIntegrityViolationException("Expected media asset foreign key restriction", failure);
            } finally {
                connection.releaseSavepoint(savepoint);
            }
        });
    }
}
