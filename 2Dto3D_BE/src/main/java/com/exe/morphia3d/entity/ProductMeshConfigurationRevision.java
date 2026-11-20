package com.exe.morphia3d.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product_mesh_configuration_revisions")
@Immutable
public class ProductMeshConfigurationRevision implements Persistable<UUID> {
    public static final int CONFIGURATION_JSON_SCHEMA_VERSION = 1;

    @Id
    @Column(name = "configuration_revision_id", nullable = false, updatable = false)
    private UUID configurationRevisionId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Integer productId;

    @Column(name = "media_asset_id", nullable = false, updatable = false)
    private Long mediaAssetId;

    @Column(name = "structure_snapshot_id", nullable = false, updatable = false)
    private UUID structureSnapshotId;

    @Column(name = "config_revision", nullable = false, updatable = false)
    private Long configRevision;

    @Column(name = "material_id", nullable = false, updatable = false)
    private Integer materialId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "configuration_json", nullable = false, columnDefinition = "jsonb", updatable = false)
    private JsonNode configurationJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Integer createdBy;

    @Transient
    private boolean newEntity = true;

    protected ProductMeshConfigurationRevision() {}

    public ProductMeshConfigurationRevision(UUID configurationRevisionId, Integer productId, Long mediaAssetId,
                                            UUID structureSnapshotId, Long configRevision, Integer materialId,
                                            JsonNode configurationJson, Instant createdAt, Integer createdBy) {
        this.configurationRevisionId = require(configurationRevisionId, "configurationRevisionId");
        this.productId = requirePositive(productId, "productId");
        this.mediaAssetId = requirePositive(mediaAssetId, "mediaAssetId");
        this.structureSnapshotId = require(structureSnapshotId, "structureSnapshotId");
        this.configRevision = requirePositive(configRevision, "configRevision");
        this.materialId = requirePositive(materialId, "materialId");
        this.configurationJson = Admin3dHistoryJson.validateNewConfiguration(configurationJson);
        this.createdAt = require(createdAt, "createdAt");
        this.createdBy = requirePositive(createdBy, "createdBy");
    }

    @Override
    public UUID getId() { return configurationRevisionId; }

    public UUID getConfigurationRevisionId() { return configurationRevisionId; }
    public Integer getProductId() { return productId; }
    public Long getMediaAssetId() { return mediaAssetId; }
    public UUID getStructureSnapshotId() { return structureSnapshotId; }
    public Long getConfigRevision() { return configRevision; }
    public Integer getMaterialId() { return materialId; }
    public Instant getCreatedAt() { return createdAt; }
    public Integer getCreatedBy() { return createdBy; }

    public JsonNode getConfigurationJson() {
        Admin3dHistoryJson.readConfiguration(configurationJson);
        return configurationJson.deepCopy();
    }

    @Override
    public boolean isNew() { return newEntity; }

    @PrePersist
    private void validateBeforeInsert() {
        configurationJson = Admin3dHistoryJson.validateNewConfiguration(configurationJson);
    }

    @PostPersist
    @PostLoad
    private void markExistingAndValidate() {
        newEntity = false;
        Admin3dHistoryJson.readConfiguration(configurationJson);
    }

    @PreUpdate
    private void rejectUpdate() {
        throw new IllegalStateException("Product mesh configuration revisions are immutable");
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static Integer requirePositive(Integer value, String name) {
        if (value == null || value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static Long requirePositive(Long value, String name) {
        if (value == null || value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
