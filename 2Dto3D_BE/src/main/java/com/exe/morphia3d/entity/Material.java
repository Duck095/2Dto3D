package com.exe.morphia3d.entity;

import com.exe.morphia3d.enums.MaterialStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Nationalized;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

@Getter
@Setter
@Entity
@Table(name = "materials")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Material {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "material_id")
    private Integer id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "type", length = 50)
    private String type;

    @OneToMany(mappedBy = "material", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private Set<MaterialColor> materialColors = new HashSet<>();

    @Column(name = "price_per_gram", nullable = false)
    private Double pricePerGram;

    @ColumnDefault("0")
    @Column(name = "stock_quantity", nullable = false)
    private Double stockQuantity;

    @ColumnDefault("'g'")
    @Column(name = "unit", nullable = false, length = 20)
    private String unit;

    @Enumerated(EnumType.STRING)
    @Column(name = "is_available")
    @Builder.Default
    private MaterialStatus isAvailable = MaterialStatus.AVAILABLE;

}