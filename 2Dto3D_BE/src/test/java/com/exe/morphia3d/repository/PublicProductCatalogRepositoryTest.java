package com.exe.morphia3d.repository;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.enums.ModelStatus;
import com.exe.morphia3d.enums.ModelType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ContextConfiguration(classes = Morphia3dApplication.class)
@ActiveProfiles("test")
class PublicProductCatalogRepositoryTest {
    @Autowired private Model3dRepository repository;

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    @Test
    void publicQueryFiltersStatusTypeSearchCategoryAndPagesInDatabase() {
        save("Birch Chair", "Furniture", ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE);
        save("Birch Table", "Furniture", ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE);
        save("Birch Chair Draft", "Furniture", ModelType.DESIGNER_MODEL, ModelStatus.PENDING);
        save("Birch Chair Hidden", "Furniture", ModelType.DESIGNER_MODEL, ModelStatus.INACTIVE);
        save("Birch Chair Personal", "Furniture", ModelType.AI_MODEL, ModelStatus.ACTIVE);
        save("Birch Chair Decor", "Decor", ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE);

        Page<Model3d> firstPage = search(null, "furniture", 0, 1, Sort.by("id").ascending());
        Page<Model3d> secondPage = search(null, "furniture", 1, 1, Sort.by("id").ascending());

        assertThat(firstPage.getContent()).hasSize(1);
        assertThat(secondPage.getContent()).hasSize(1);
        assertThat(firstPage.getContent()).doesNotContainAnyElementsOf(secondPage.getContent());
        assertThat(firstPage.getTotalElements()).isEqualTo(2);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);

        Page<Model3d> matchingQueryAndCategory = search("bIrCh cHaIr", "fUrNiTuRe", 0, 10,
                Sort.by("id").ascending());
        assertThat(matchingQueryAndCategory.getContent()).extracting(Model3d::getName)
                .containsExactly("Birch Chair");
        assertThat(matchingQueryAndCategory.getTotalElements()).isEqualTo(1);
        assertThat(search("not present", "Furniture", 0, 10, Sort.unsorted()).getTotalElements()).isZero();
        assertThat(search(null, null, 0, 10, Sort.unsorted()).getTotalElements()).isEqualTo(3);
    }

    @Test
    void searchTreatsPercentUnderscoreAndEscapeCharacterAsLiterals() {
        save("Model 50% Chair", "Decor", ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE);
        save("Model 50X Chair", "Decor", ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE);
        save("Oak_Table", "Decor", ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE);
        save("OakXTable", "Decor", ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE);
        save("Folder\\_Name", "Decor", ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE);
        save("Folder\\XName", "Decor", ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE);

        assertThat(search("50%", null, 0, 10, Sort.unsorted()).getContent())
                .extracting(Model3d::getName).containsExactly("Model 50% Chair");
        assertThat(search("Oak_Table", null, 0, 10, Sort.unsorted()).getContent())
                .extracting(Model3d::getName).containsExactly("Oak_Table");
        assertThat(search("Folder\\_Name", null, 0, 10, Sort.unsorted()).getContent())
                .extracting(Model3d::getName).containsExactly("Folder\\_Name");
    }

    @Test
    void identicalNamesUseIdAsStableTieBreakerAcrossPages() {
        save("Same Name", "Furniture", ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE);
        save("Same Name", "Furniture", ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE);
        save("Same Name", "Furniture", ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE);

        Page<Model3d> first = search(null, null, 0, 2,
                Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id")));
        Page<Model3d> second = search(null, null, 1, 2,
                Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id")));

        assertThat(first.getContent()).hasSize(2);
        assertThat(second.getContent()).hasSize(1);
        assertThat(first.getContent()).doesNotContainAnyElementsOf(second.getContent());
        assertThat(first.getContent()).extracting(Model3d::getId).isSorted();
        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
    }

    @Test
    void nullFiltersLeaveCatalogQueryUnfiltered() {
        save("Desk", "Furniture", ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE);
        save("Lamp", "Lighting", ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE);

        assertThat(search(null, null, 0, 10, Sort.unsorted()).getTotalElements()).isEqualTo(2);
    }

    private void save(String name, String category, ModelType type, ModelStatus status) {
        repository.save(Model3d.builder().name(name).slug(name.toLowerCase().replace(' ', '-'))
                .category(category).sourceType(type).status(status).build());
    }

    private Page<Model3d> search(String query, String category, int page, int size, Sort sort) {
        return repository.searchPublicCatalog(ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE, query, category,
                PageRequest.of(page, size, sort));
    }
}
