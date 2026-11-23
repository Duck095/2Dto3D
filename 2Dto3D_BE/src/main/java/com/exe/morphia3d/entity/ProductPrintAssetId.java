package com.exe.morphia3d.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ProductPrintAssetId implements Serializable {

    @Column(name = "product_id")
    private Integer productId;

    @Column(name = "media_asset_id")
    private Long mediaAssetId;
}
