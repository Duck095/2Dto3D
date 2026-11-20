package com.exe.morphia3d.service.impl;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.entity.MediaAsset;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.ModelImage;
import com.exe.morphia3d.enums.MediaAssetState;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import com.exe.morphia3d.enums.ModelStatus;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.repository.MediaAssetRepository;
import com.exe.morphia3d.repository.Model3dRepository;
import com.exe.morphia3d.repository.ModelImageRepository;
import com.exe.morphia3d.service.ProductService;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = Morphia3dApplication.class,
        properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@Transactional
class ProductCatalogMediaBatchIntegrationTest {

    @Autowired private ProductService products;
    @Autowired private Model3dRepository models;
    @Autowired private ModelImageRepository images;
    @Autowired private MediaAssetRepository assets;
    @Autowired private jakarta.persistence.EntityManagerFactory entityManagerFactory;
    @PersistenceContext private EntityManager entityManager;

    @Test
    void productListLoadsThumbnailAndMediaMetadataInBoundedBatchQueries() {
        for (int index = 0; index < 6; index++) {
            UUID operationId = UUID.randomUUID();
            MediaAsset modelAsset = attachedAsset(operationId, MediaKind.MODEL_3D, "glb", "raw");
            MediaAsset primaryImageAsset = attachedAsset(operationId, MediaKind.IMAGE, "webp", "image");
            MediaAsset secondaryImageAsset = attachedAsset(operationId, MediaKind.IMAGE, "png", "image");
            Model3d model = models.saveAndFlush(Model3d.builder().name("Catalog model " + index)
                    .slug("catalog-model-" + index).sourceType(ModelType.DESIGNER_MODEL)
                    .status(ModelStatus.ACTIVE).filePath3d(modelAsset.getPublicId()).mediaAsset(modelAsset).build());
            images.saveAndFlush(ModelImage.builder().model(model).imagePath(primaryImageAsset.getPublicId())
                    .mediaAsset(primaryImageAsset).isPrimary(true).build());
            images.saveAndFlush(ModelImage.builder().model(model).imagePath(secondaryImageAsset.getPublicId())
                    .mediaAsset(secondaryImageAsset).isPrimary(false).build());
        }

        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        Statistics statistics = sessionFactory.getStatistics();
        assertThat(statistics.isStatisticsEnabled()).isTrue();
        entityManager.flush();
        entityManager.clear();
        statistics.clear();

        var response = products.getProducts(0, 20, null, null, "newest");

        assertThat(response.items()).hasSize(6);
        assertThat(response.items()).allSatisfy(item ->
                assertThat(item.thumbnailUrl()).contains("/image/upload/v1/").endsWith(".webp"));
        long listQueryCount = statistics.getPrepareStatementCount();
        assertThat(listQueryCount)
                .as("cold product page plus set-wise image/media metadata query")
                .isEqualTo(2);

        entityManager.flush();
        entityManager.clear();
        statistics.clear();

        var detail = products.getProduct(response.items().get(0).id());

        assertThat(detail.images()).hasSize(2);
        assertThat(detail.images()).extracting(image -> image.url())
                .allSatisfy(url -> assertThat(url).startsWith("https://res.cloudinary.com/test-cloud/image/upload/v1/"));
        assertThat(detail.model3dUrl()).startsWith("https://res.cloudinary.com/test-cloud/raw/upload/v1/");
        long detailQueryCount = statistics.getPrepareStatementCount();
        assertThat(detailQueryCount)
                .as("cold product row, model media, image batch, and parts queries")
                .isEqualTo(3);
    }

    private MediaAsset attachedAsset(UUID operationId, MediaKind kind, String format, String resourceType) {
        String folder = kind == MediaKind.IMAGE ? "images" : "models";
        String suffix = kind == MediaKind.MODEL_3D ? ".glb" : "";
        MediaAsset asset = assets.saveAndFlush(MediaAsset.builder().operationId(operationId).mediaKind(kind)
                .provider(MediaProvider.CLOUDINARY).cloudName("test-cloud").providerAssetId("asset-" + UUID.randomUUID())
                .publicId("pending/" + UUID.randomUUID()).resourceType(resourceType).deliveryType("upload")
                .format(format).byteCount(10L).providerVersion(1L).state(MediaAssetState.ATTACHED).build());
        asset.setPublicId("product-media/" + folder + "/" + operationId + "/" + asset.getId() + suffix);
        String urlId = asset.getPublicId() + (kind == MediaKind.IMAGE ? "." + format : "");
        asset.setSecureUrl("https://res.cloudinary.com/test-cloud/" + resourceType + "/upload/v1/" + urlId);
        return assets.saveAndFlush(asset);
    }
}
