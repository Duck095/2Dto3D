package com.exe.morphia3d.entity;

import com.exe.morphia3d.enums.MediaAssetState;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "media_asset",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_media_asset_provider_public_identity",
                        columnNames = {"provider", "cloud_name", "resource_type", "delivery_type", "public_id"}),
                @UniqueConstraint(name = "uq_media_asset_provider_asset_identity",
                        columnNames = {"provider", "cloud_name", "provider_asset_id"})
        },
        indexes = {
                @Index(name = "idx_media_asset_operation_id", columnList = "operation_id"),
                @Index(name = "idx_media_asset_state_created_at", columnList = "state, created_at")
        })
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "operation_id", nullable = false, updatable = false)
    private UUID operationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_kind", nullable = false, length = 16)
    private MediaKind mediaKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private MediaProvider provider;

    @Column(name = "cloud_name", nullable = false, length = 255)
    private String cloudName;

    @Column(name = "provider_asset_id", length = 255)
    private String providerAssetId;

    @Column(name = "public_id", nullable = false, length = 512)
    private String publicId;

    @Column(name = "resource_type", nullable = false, length = 16)
    private String resourceType;

    @Column(name = "delivery_type", nullable = false, length = 32)
    private String deliveryType;

    @Column(name = "format", nullable = false, length = 32)
    private String format;

    @Column(name = "byte_count", nullable = false)
    private Long byteCount;

    @Column(name = "provider_version")
    private Long providerVersion;

    @Column(name = "secure_url", length = 2048)
    private String secureUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    @Builder.Default
    private MediaAssetState state = MediaAssetState.PENDING_UPLOAD;

    @Column(name = "lease_owner", length = 128)
    private String leaseOwner;

    @Column(name = "lease_token")
    private UUID leaseToken;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "last_error_code", length = 128)
    private String lastErrorCode;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
