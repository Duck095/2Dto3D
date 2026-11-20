package com.exe.morphia3d.repository;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.entity.Color;
import com.exe.morphia3d.entity.Material;
import com.exe.morphia3d.entity.MaterialColor;
import com.exe.morphia3d.entity.MaterialColorId;
import com.exe.morphia3d.entity.MaterialStockLog;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.PriceEstimation;
import com.exe.morphia3d.entity.Printer;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.MaterialStatus;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.service.impl.MaterialServiceImpl;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ContextConfiguration(classes = Morphia3dApplication.class)
@ActiveProfiles("test")
class MaterialRetirementJpaIntegrationTest {

    @Autowired
    private MaterialRepository materialRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void retirementPersistsUnavailableAndKeepsStockHistoryColorsAndReferences() {
        User stockLogAuthor = User.builder()
                .name("Inventory operator")
                .email("retirement-jpa@example.com")
                .password("encoded")
                .build();
        entityManager.persist(stockLogAuthor);

        Color color = Color.builder().name("Retirement test blue").hexCode("#123456").build();
        entityManager.persist(color);

        Material material = Material.builder()
                .name("Retirement test PLA")
                .pricePerGram(0.5)
                .stockQuantity(250.0)
                .unit("g")
                .isAvailable(MaterialStatus.AVAILABLE)
                .build();
        MaterialColor materialColor = MaterialColor.builder()
                .id(new MaterialColorId())
                .material(material)
                .color(color)
                .quantity(125.0)
                .build();
        material.getMaterialColors().add(materialColor);
        entityManager.persist(material);

        MaterialStockLog stockLog = new MaterialStockLog();
        stockLog.setMaterial(material);
        stockLog.setCreatedBy(stockLogAuthor);
        stockLog.setChangeType("RECEIVED");
        stockLog.setQuantity(250.0);
        stockLog.setNote("Initial stock");
        entityManager.persist(stockLog);

        Model3d model = Model3d.builder().sourceType(ModelType.DESIGNER_MODEL).name("Historical model").build();
        entityManager.persist(model);
        Printer printer = Printer.builder().name("Historical printer").build();
        entityManager.persist(printer);
        PriceEstimation priceEstimation = new PriceEstimation();
        priceEstimation.setModel(model);
        priceEstimation.setMaterial(material);
        priceEstimation.setPrinter(printer);
        priceEstimation.setTotalPrice(42.0);
        entityManager.persist(priceEstimation);

        entityManager.flush();
        Integer materialId = material.getId();
        Integer stockLogId = stockLog.getId();
        Integer colorId = color.getId();
        Integer estimateId = priceEstimation.getId();
        entityManager.clear();

        new MaterialServiceImpl(materialRepository, null, null, null).removeMaterial(materialId);
        entityManager.flush();
        entityManager.clear();

        Material retired = materialRepository.findById(materialId).orElseThrow();
        assertThat(retired.getIsAvailable()).isEqualTo(MaterialStatus.UNAVAILABLE);
        assertThat(retired.getStockQuantity()).isEqualTo(250.0);

        MaterialStockLog retainedLog = entityManager.find(MaterialStockLog.class, stockLogId);
        assertThat(retainedLog).isNotNull();
        assertThat(retainedLog.getMaterial().getId()).isEqualTo(materialId);
        assertThat(retainedLog.getCreatedBy().getId()).isEqualTo(stockLogAuthor.getId());
        assertThat(retainedLog.getChangeType()).isEqualTo("RECEIVED");
        assertThat(retainedLog.getQuantity()).isEqualTo(250.0);
        assertThat(retainedLog.getNote()).isEqualTo("Initial stock");

        MaterialColor retainedColor = entityManager.createQuery(
                        "select mc from MaterialColor mc where mc.material.id = :materialId", MaterialColor.class)
                .setParameter("materialId", materialId)
                .getSingleResult();
        assertThat(retainedColor.getId().getMaterialId()).isEqualTo(materialId);
        assertThat(retainedColor.getId().getColorId()).isEqualTo(colorId);
        assertThat(retainedColor.getQuantity()).isEqualTo(125.0);

        PriceEstimation retainedEstimate = entityManager.find(PriceEstimation.class, estimateId);
        assertThat(retainedEstimate).isNotNull();
        assertThat(retainedEstimate.getMaterial().getId()).isEqualTo(materialId);
        assertThat(retainedEstimate.getTotalPrice()).isEqualTo(42.0);
    }
}
