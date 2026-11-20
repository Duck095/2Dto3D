package com.exe.morphia3d.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
@Table(name = "ai_generation_sessions")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIGenerationSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "session_id")
    private Integer id;

    // Customer đã request
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // Ảnh 2D gốc customer upload
    @Column(name = "original_image_path", length = 500)
    private String originalImagePath;

    // Kích thước customer mong muốn
    @Column(name = "target_size_x_mm")
    private Double targetSizeXMm;

    @Column(name = "target_size_y_mm")
    private Double targetSizeYMm;

    @Column(name = "target_size_z_mm")
    private Double targetSizeZMm;

    // Prompt hoặc mô tả customer nhập vào chatbox
    @Column(name = "prompt")
    private String prompt;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // Các model AI sinh ra trong session này
    @OneToMany(mappedBy = "generationSession", cascade = CascadeType.ALL)
    @Builder.Default
    private List<Model3d> generatedModels = new ArrayList<>();
}
