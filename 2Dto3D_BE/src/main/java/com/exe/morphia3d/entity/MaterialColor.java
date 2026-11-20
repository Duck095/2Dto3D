package com.exe.morphia3d.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "material_colors")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MaterialColor {

    @EmbeddedId
    private MaterialColorId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("materialId")
    @JoinColumn(name = "material_id")
    private Material material;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("colorId")
    @JoinColumn(name = "color_id")
    private Color color;

    @Column(name = "quantity", nullable = false)
    @ColumnDefault("0")
    private Double quantity;
}