package com.exe.morphia3d.entity;

import com.exe.morphia3d.enums.ModelStatus;
import com.exe.morphia3d.enums.ModelType;
import jakarta.persistence.*;
import jakarta.persistence.CascadeType;
import lombok.*;
import org.hibernate.annotations.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
@Table(name = "model_3d",
        uniqueConstraints = @UniqueConstraint(name = "uq_model_3d_media_asset", columnNames = "media_asset_id"))
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Model3d {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "model_id")
    private Integer id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private ModelType sourceType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "name", length = 150)
    private String name;

    @Column(name = "slug")
    private String slug;

    @Column(name = "description")
    private String description;

    @Column(name = "file_path_3d", length = 500)
    private String filePath3d;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "media_asset_id",
            foreignKey = @ForeignKey(name = "fk_model_3d_media_asset"))
    private MediaAsset mediaAsset;

    @Column(name = "size_x_mm")
    private Double sizeXMm;

    @Column(name = "size_y_mm")
    private Double sizeYMm;

    @Column(name = "size_z_mm")
    private Double sizeZMm;

    @Column(name = "weight")
    private Double weight;

    @OneToMany(mappedBy = "model", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ModelImage> images = new ArrayList<>();

    @Column(name = "category", length = 100)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "model_status", nullable = false)
    @Builder.Default
    private ModelStatus status = ModelStatus.PENDING;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "model", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ModelPart> parts = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private AIGenerationSession generationSession;

    // Thứ tự phương án trong session: 1, 2, 3...
    @Column(name = "variant_index")
    private Integer variantIndex;

    public ModelImage getPrimaryImage() {
        return images.stream()
                .filter(ModelImage::getIsPrimary)
                .findFirst()
                .orElse(images.isEmpty() ? null : images.get(0)); // fallback ảnh đầu tiên
    }
}
