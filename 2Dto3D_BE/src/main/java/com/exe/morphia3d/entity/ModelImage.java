package com.exe.morphia3d.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "model_images",
        uniqueConstraints = @UniqueConstraint(name = "uq_model_images_media_asset", columnNames = "media_asset_id"))
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "image_id")
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private Model3d model;

    @Column(name = "image_path", nullable = false, length = 500)
    private String imagePath;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "media_asset_id",
            foreignKey = @ForeignKey(name = "fk_model_images_media_asset"))
    private MediaAsset mediaAsset;

    // Đánh dấu ảnh đại diện hiển thị trong danh sách
    @Column(name = "is_primary", nullable = false)
    @Builder.Default
    private Boolean isPrimary = false;

    @CreationTimestamp
    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;
}
