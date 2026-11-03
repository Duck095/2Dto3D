package com.exe.morphia3d.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.HashSet;
import java.util.Set;

@Getter
@Setter
@Entity
@Table(name = "colors")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Color {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "color_id")
    private Integer id;

    @Column(name = "name", nullable = false, length = 50, unique = true)
    private String name;

    @Column(name = "hex_code", length = 7)
    private String hexCode; // VD: "#FF5733"

    @OneToMany(mappedBy = "color")
    @Builder.Default
    private Set<MaterialColor> materialColors = new HashSet<>();
}