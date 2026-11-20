package com.exe.morphia3d.entity;

import com.exe.morphia3d.service.glb.GlbStructure;
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
@Table(name = "product_glb_structure_snapshots")
@Immutable
public class ProductGlbStructureSnapshot implements Persistable<UUID> {
    public static final String CURRENT_PARSER_VERSION = "glb-structure-1";

    @Id
    @Column(name = "structure_snapshot_id", nullable = false, updatable = false)
    private UUID structureSnapshotId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Integer productId;

    @Column(name = "media_asset_id", nullable = false, updatable = false)
    private Long mediaAssetId;

    @Column(name = "snapshot_revision", nullable = false, updatable = false)
    private Long snapshotRevision;

    @Column(name = "parser_version", nullable = false, length = 40, updatable = false)
    private String parserVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "structure_json", nullable = false, columnDefinition = "jsonb", updatable = false)
    private JsonNode structureJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Integer createdBy;

    @Transient
    private boolean newEntity = true;

    protected ProductGlbStructureSnapshot() {}

    public ProductGlbStructureSnapshot(UUID structureSnapshotId, Integer productId, Long mediaAssetId,
                                       Long snapshotRevision, String parserVersion, JsonNode structureJson,
                                       Instant createdAt, Integer createdBy) {
        this.structureSnapshotId = require(structureSnapshotId, "structureSnapshotId");
        this.productId = requirePositive(productId, "productId");
        this.mediaAssetId = requirePositive(mediaAssetId, "mediaAssetId");
        this.snapshotRevision = requirePositive(snapshotRevision, "snapshotRevision");
        this.parserVersion = require(parserVersion, "parserVersion");
        this.structureJson = Admin3dHistoryJson.validateNewStructure(parserVersion, structureJson);
        this.createdAt = require(createdAt, "createdAt");
        this.createdBy = requirePositive(createdBy, "createdBy");
    }

    /** Validates and serializes trusted parser output before media upload intents are created. */
    public static JsonNode validatedStructureDocument(String parserVersion, GlbStructure structure) {
        return Admin3dHistoryJson.validateNewStructure(parserVersion, structure);
    }

    public static JsonNode validatedStructureDocument(String parserVersion, JsonNode structureJson) {
        return Admin3dHistoryJson.validateNewStructure(parserVersion, structureJson);
    }

    @Override
    public UUID getId() {
        return structureSnapshotId;
    }

    public UUID getStructureSnapshotId() { return structureSnapshotId; }
    public Integer getProductId() { return productId; }
    public Long getMediaAssetId() { return mediaAssetId; }
    public Long getSnapshotRevision() { return snapshotRevision; }
    public String getParserVersion() { return parserVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Integer getCreatedBy() { return createdBy; }

    public JsonNode getStructureJson() {
        Admin3dHistoryJson.readStructure(parserVersion, structureJson);
        return structureJson.deepCopy();
    }

    public GlbStructure readStructure() {
        return Admin3dHistoryJson.readStructure(parserVersion, structureJson);
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PrePersist
    private void validateBeforeInsert() {
        structureJson = Admin3dHistoryJson.validateNewStructure(parserVersion, structureJson);
    }

    @PostPersist
    @PostLoad
    private void markExistingAndValidate() {
        newEntity = false;
        Admin3dHistoryJson.readStructure(parserVersion, structureJson);
    }

    @PreUpdate
    private void rejectUpdate() {
        throw new IllegalStateException("Product GLB structure snapshots are immutable");
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
