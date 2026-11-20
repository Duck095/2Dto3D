package com.exe.morphia3d.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "model_parts")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelPart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "part_id")
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private Model3d model;

    @Column(name = "part_name", nullable = false, length = 100)
    private String partName;

    @Column(name = "part_key", nullable = false, length = 100)
    private String partKey;

    // Chỉ lưu màu customer chọn cho part này
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "color_id")
    private Color color;
}
