package com.exe.morphia3d.repository;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.ModelImage;
import com.exe.morphia3d.entity.ModelPart;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.ModelType;
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
class ModelAggregatePersistenceTest {

    @Autowired private UserRepository userRepository;
    @Autowired private Model3dRepository modelRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void savingModelAggregateCascadesImagesAndParts() {
        User owner = userRepository.save(User.builder()
                .name("Owner")
                .email("aggregate@example.com")
                .password("encoded")
                .build());
        Model3d model = Model3d.builder()
                .sourceType(ModelType.DESIGNER_MODEL)
                .user(owner)
                .name("Aggregate")
                .slug("aggregate")
                .build();
        model.getImages().add(ModelImage.builder()
                .model(model)
                .imagePath("preview")
                .isPrimary(true)
                .build());
        model.getParts().add(ModelPart.builder()
                .model(model)
                .partName("Body")
                .partKey("body")
                .build());

        Integer modelId = modelRepository.saveAndFlush(model).getId();
        entityManager.clear();

        Model3d reloaded = modelRepository.findById(modelId).orElseThrow();
        assertThat(reloaded.getImages()).hasSize(1);
        assertThat(reloaded.getParts()).hasSize(1);
        assertThat(reloaded.getSlug()).isEqualTo("aggregate");
    }
}
