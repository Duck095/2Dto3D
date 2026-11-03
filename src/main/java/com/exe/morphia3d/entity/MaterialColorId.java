package com.exe.morphia3d.entity;

import jakarta.persistence.Embeddable;
import lombok.*;

import java.io.Serializable;

@Getter
@Setter
@Embeddable
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class MaterialColorId implements Serializable {
    private Integer materialId;
    private Integer colorId;
}