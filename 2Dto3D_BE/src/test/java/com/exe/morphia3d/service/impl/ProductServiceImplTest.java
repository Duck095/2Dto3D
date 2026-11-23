package com.exe.morphia3d.service.impl;

import com.exe.morphia3d.dto.response.PaginatedProductResponse;
import com.exe.morphia3d.dto.response.ProductDetailResponse;
import com.exe.morphia3d.entity.MediaAsset;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.ModelImage;
import com.exe.morphia3d.entity.Material;
import com.exe.morphia3d.entity.MaterialColor;
import com.exe.morphia3d.entity.MaterialColorId;
import com.exe.morphia3d.entity.Color;
import com.exe.morphia3d.entity.ProductGlbStructureSnapshot;
import com.exe.morphia3d.entity.ProductMeshConfigurationRevision;
import com.exe.morphia3d.enums.MediaAssetState;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import com.exe.morphia3d.enums.ModelStatus;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.repository.Model3dRepository;
import com.exe.morphia3d.repository.ModelImageRepository;
import com.exe.morphia3d.repository.ModelPartRepository;
import com.exe.morphia3d.repository.MaterialColorRepository;
import com.exe.morphia3d.repository.MaterialRepository;
import com.exe.morphia3d.repository.ProductGlbStructureSnapshotRepository;
import com.exe.morphia3d.repository.ProductMeshConfigurationRevisionRepository;
import com.exe.morphia3d.service.MediaUrlResolver;
import com.exe.morphia3d.utils.ModelUrlResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.exe.morphia3d.service.glb.GlbStructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProductServiceImplTest {
    private Model3dRepository modelRepository;
    private ModelImageRepository imageRepository;
    private ModelPartRepository partRepository;
    private ModelUrlResolver modelUrlResolver;
    private MediaUrlResolver mediaUrlResolver;
    private ProductGlbStructureSnapshotRepository structureSnapshots;
    private ProductMeshConfigurationRevisionRepository meshConfigurations;
    private MaterialRepository materialRepository;
    private MaterialColorRepository materialColorRepository;
    private ProductServiceImpl productService;

    @BeforeEach
    void setUp() {
        modelRepository = mock(Model3dRepository.class);
        imageRepository = mock(ModelImageRepository.class);
        partRepository = mock(ModelPartRepository.class);
        modelUrlResolver = mock(ModelUrlResolver.class);
        mediaUrlResolver = mock(MediaUrlResolver.class);
        structureSnapshots = mock(ProductGlbStructureSnapshotRepository.class);
        meshConfigurations = mock(ProductMeshConfigurationRevisionRepository.class);
        materialRepository = mock(MaterialRepository.class);
        materialColorRepository = mock(MaterialColorRepository.class);
        productService = new ProductServiceImpl(modelRepository, imageRepository, partRepository,
                modelUrlResolver, mediaUrlResolver, structureSnapshots, meshConfigurations,
                materialRepository, materialColorRepository, "https://media.example/image/upload");
    }

    @Test
    void listNormalizesFiltersAndUsesAllowlistedStableDatabaseSort() {
        when(modelRepository.searchPublicCatalog(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        productService.getProducts(2, 24, "  Desk  ", " Furniture ", "name_asc");

        var pageable = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(modelRepository).searchPublicCatalog(eq(com.exe.morphia3d.enums.ModelType.DESIGNER_MODEL),
                eq(com.exe.morphia3d.enums.ModelStatus.ACTIVE), eq("Desk"), eq("Furniture"), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(24);
        assertThat(pageable.getValue().getSort().getOrderFor("name").isAscending()).isTrue();
        assertThat(pageable.getValue().getSort().getOrderFor("id").isAscending()).isTrue();
        verifyNoInteractions(imageRepository, partRepository);
    }

    @Test
    void defaultSortUsesCreationTimeThenId() {
        when(modelRepository.searchPublicCatalog(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        productService.getProducts(0, 12, null, null, "newest");

        var pageable = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(modelRepository).searchPublicCatalog(any(), any(), any(), any(), pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt").isDescending()).isTrue();
        assertThat(pageable.getValue().getSort().getOrderFor("id").isDescending()).isTrue();
    }

    @Test
    void blankFiltersAreNormalizedToNullBeforeTheDatabaseQuery() {
        when(modelRepository.searchPublicCatalog(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        productService.getProducts(0, 12, "   ", "  ", "newest");

        verify(modelRepository).searchPublicCatalog(eq(ModelType.DESIGNER_MODEL), eq(ModelStatus.ACTIVE),
                eq(null), eq(null), any(Pageable.class));
        verifyNoInteractions(imageRepository, partRepository);
    }

    @Test
    void invalidPageSizeOrSortIsBadRequestBeforeDatabaseAccess() {
        ResponseStatusException pageError = assertThrows(ResponseStatusException.class,
                () -> productService.getProducts(-1, 12, null, null, "newest"));
        ResponseStatusException sizeError = assertThrows(ResponseStatusException.class,
                () -> productService.getProducts(0, 101, null, null, "newest"));
        ResponseStatusException sortError = assertThrows(ResponseStatusException.class,
                () -> productService.getProducts(0, 12, null, null, "createdAt,desc"));
        ResponseStatusException queryError = assertThrows(ResponseStatusException.class,
                () -> productService.getProducts(0, 12, "x".repeat(101), null, "newest"));
        ResponseStatusException categoryError = assertThrows(ResponseStatusException.class,
                () -> productService.getProducts(0, 12, null, "x".repeat(101), "newest"));

        assertThat(pageError.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(sizeError.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(sortError.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(queryError.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(categoryError.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(modelRepository, imageRepository, partRepository);
    }

    @Test
    void summaryBatchPrefersPrimaryThenFallsBackToFirstAndLeavesMissingThumbnailNull() {
        Model3d first = model(1, "Chair");
        Model3d second = model(2, "Table");
        Model3d third = model(3, "Lamp");
        List<ModelImage> pageImages = List.of(
                image(20, first, "primary-chair", true),
                image(10, first, "fallback-chair", false),
                image(30, second, "first-table", false),
                image(40, second, "second-table", false));
        when(modelRepository.searchPublicCatalog(eq(ModelType.DESIGNER_MODEL), eq(ModelStatus.ACTIVE),
                eq(null), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(first, second, third), PageRequest.of(0, 10), 3));
        when(imageRepository.findByModel_IdInOrderByIsPrimaryDescIdAsc(List.of(1, 2, 3)))
                .thenReturn(pageImages);

        PaginatedProductResponse response = productService.getProducts(0, 10, null, null, "newest");

        assertThat(response.items()).extracting(item -> item.thumbnailUrl())
                .containsExactly("https://media.example/image/upload/primary-chair",
                        "https://media.example/image/upload/first-table", null);
        verify(imageRepository, times(1)).findByModel_IdInOrderByIsPrimaryDescIdAsc(List.of(1, 2, 3));
    }

    @Test
    void detailMapsEveryImageAndResolvesModelFileOrReturnsNullWhenMissing() {
        Model3d modelWithFile = model(7, "Chair");
        modelWithFile.setFilePath3d("chair-model");
        when(modelRepository.findByIdAndSourceTypeAndStatus(7, ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE))
                .thenReturn(Optional.of(modelWithFile));
        when(imageRepository.findByModel_IdOrderByIsPrimaryDescIdAsc(7)).thenReturn(List.of(
                image(2, modelWithFile, "primary-chair", true),
                image(1, modelWithFile, "secondary-chair", false)));
        when(partRepository.findByModel_IdOrderByIdAsc(7)).thenReturn(List.of());
        when(modelUrlResolver.resolve("chair-model")).thenReturn("https://media.example/raw/chair-model");

        ProductDetailResponse detail = productService.getProduct(7);

        assertThat(detail.images()).hasSize(2);
        assertThat(detail.images().get(0).url()).isEqualTo("https://media.example/image/upload/primary-chair");
        assertThat(detail.images().get(0).primary()).isTrue();
        assertThat(detail.images().get(1).url()).isEqualTo("https://media.example/image/upload/secondary-chair");
        assertThat(detail.model3dUrl()).isEqualTo("https://media.example/raw/chair-model");

        Model3d modelWithoutFile = model(8, "Table");
        when(modelRepository.findByIdAndSourceTypeAndStatus(8, ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE))
                .thenReturn(Optional.of(modelWithoutFile));
        when(imageRepository.findByModel_IdOrderByIsPrimaryDescIdAsc(8)).thenReturn(List.of());
        when(partRepository.findByModel_IdOrderByIdAsc(8)).thenReturn(List.of());

        assertThat(productService.getProduct(8).model3dUrl()).isNull();
    }

    @Test
    void catalogUsesTrustedAssetUrlsForNewImageAndGlbMedia() {
        Model3d model = model(9, "New media chair");
        MediaAsset modelAsset = mediaAsset(900L, MediaKind.MODEL_3D, "glb", "raw");
        MediaAsset imageAsset = mediaAsset(901L, MediaKind.IMAGE, "webp", "image");
        model.setFilePath3d(modelAsset.getPublicId());
        model.setMediaAsset(modelAsset);
        ModelImage image = image(91, model, imageAsset.getPublicId(), true);
        image.setMediaAsset(imageAsset);
        when(modelRepository.findByIdAndSourceTypeAndStatus(9, ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE))
                .thenReturn(Optional.of(model));
        when(imageRepository.findByModel_IdOrderByIsPrimaryDescIdAsc(9)).thenReturn(List.of(image));
        when(partRepository.findByModel_IdOrderByIdAsc(9)).thenReturn(List.of());
        when(mediaUrlResolver.resolve(modelAsset, MediaKind.MODEL_3D))
                .thenReturn("https://res.cloudinary.com/test-cloud/raw/upload/v3/" + modelAsset.getPublicId());
        when(mediaUrlResolver.resolve(imageAsset, MediaKind.IMAGE))
                .thenReturn("https://res.cloudinary.com/test-cloud/image/upload/v4/" + imageAsset.getPublicId() + ".webp");

        ProductDetailResponse response = productService.getProduct(9);

        assertThat(response.model3dUrl()).isEqualTo(
                "https://res.cloudinary.com/test-cloud/raw/upload/v3/" + modelAsset.getPublicId());
        assertThat(response.images()).extracting(imageResponse -> imageResponse.url()).containsExactly(
                "https://res.cloudinary.com/test-cloud/image/upload/v4/" + imageAsset.getPublicId() + ".webp");
        verify(mediaUrlResolver).resolve(modelAsset, MediaKind.MODEL_3D);
        verify(mediaUrlResolver).resolve(imageAsset, MediaKind.IMAGE);
        verifyNoInteractions(modelUrlResolver);
    }

    @Test
    void invalidLinkedMediaDoesNotFallBackToLegacyPaths() {
        Model3d model = model(10, "Invalid media chair");
        model.setFilePath3d("legacy-model-id");
        MediaAsset invalidModelAsset = mediaAsset(1000L, MediaKind.MODEL_3D, "glb", "raw");
        model.setMediaAsset(invalidModelAsset);
        MediaAsset invalidImageAsset = mediaAsset(1001L, MediaKind.IMAGE, "png", "image");
        ModelImage image = image(101, model, "legacy-image-id", true);
        image.setMediaAsset(invalidImageAsset);
        when(modelRepository.findByIdAndSourceTypeAndStatus(10, ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE))
                .thenReturn(Optional.of(model));
        when(imageRepository.findByModel_IdOrderByIsPrimaryDescIdAsc(10)).thenReturn(List.of(image));
        when(partRepository.findByModel_IdOrderByIdAsc(10)).thenReturn(List.of());
        when(mediaUrlResolver.resolve(invalidModelAsset, MediaKind.MODEL_3D)).thenReturn(null);
        when(mediaUrlResolver.resolve(invalidImageAsset, MediaKind.IMAGE)).thenReturn(null);

        ProductDetailResponse response = productService.getProduct(10);

        assertThat(response.model3dUrl()).isNull();
        assertThat(response.images().get(0).url()).isNull();
        verifyNoInteractions(modelUrlResolver);
    }

    @Test
    void publicDetailProjectsOnlyCurrentSnapshotOccurrenceAndMaterialAllowedColors() {
        int productId = 22;
        long assetId = 220L;
        UUID snapshotId = UUID.randomUUID();
        Model3d product = model(productId, "Configured product");
        MediaAsset asset = mediaAsset(assetId, MediaKind.MODEL_3D, "glb", "raw");
        product.setMediaAsset(asset);
        String safeUrl = "https://res.cloudinary.com/test-cloud/raw/upload/v1/" + asset.getPublicId();
        when(modelRepository.findByIdAndSourceTypeAndStatus(productId, ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE))
                .thenReturn(Optional.of(product));
        when(imageRepository.findByModel_IdOrderByIsPrimaryDescIdAsc(productId)).thenReturn(List.of());
        when(partRepository.findByModel_IdOrderByIdAsc(productId)).thenReturn(List.of());
        when(mediaUrlResolver.resolve(asset, MediaKind.MODEL_3D)).thenReturn(safeUrl);

        GlbStructure structure = new GlbStructure(0,
                List.of(new GlbStructure.Scene(0, "Scene", List.of(0))),
                List.of(new GlbStructure.Node(0, "Body", 0, List.of(), List.of(0))),
                List.of(new GlbStructure.Mesh(0, "BodyMesh", List.of(new GlbStructure.Primitive(0, 0, List.of("POSITION"), List.of())))),
                List.of(new GlbStructure.Material(0, "Original", List.of())), List.of(), List.of(),
                List.of(new GlbStructure.PrimitiveOccurrence(0, 0, 0, "Body", 0)));
        ProductGlbStructureSnapshot snapshot = new ProductGlbStructureSnapshot(snapshotId, productId, assetId, 1L,
                ProductGlbStructureSnapshot.CURRENT_PARSER_VERSION,
                ProductGlbStructureSnapshot.validatedStructureDocument(
                        ProductGlbStructureSnapshot.CURRENT_PARSER_VERSION, structure), Instant.now(), 1);
        when(structureSnapshots.findFirstByProductIdAndMediaAssetIdOrderBySnapshotRevisionDesc(productId, assetId))
                .thenReturn(Optional.of(snapshot));

        ObjectMapper mapper = new ObjectMapper();
        var configuration = mapper.createObjectNode().put("schemaVersion", 1);
        var meshes = configuration.putArray("meshes");
        meshes.addObject().put("nodeIndex", 0).put("meshIndex", 0).put("primitiveIndex", 0)
                .put("customizable", true).put("defaultColorId", 31).putArray("allowedColorIds").add(31).add(32);
        ProductMeshConfigurationRevision revision = new ProductMeshConfigurationRevision(UUID.randomUUID(), productId,
                assetId, snapshotId, 1L, 7, configuration, Instant.now(), 1);
        when(meshConfigurations.findFirstByProductIdAndMediaAssetIdAndStructureSnapshotIdOrderByConfigRevisionDesc(
                productId, assetId, snapshotId)).thenReturn(Optional.of(revision));

        Material material = Material.builder().id(7).name("PLA blue").build();
        Color defaultColor = Color.builder().id(31).name("Ocean blue").hexCode("#123456").build();
        Color allowedColor = Color.builder().id(32).name("Sky blue").hexCode("#ABCDEF").build();
        when(materialRepository.findById(7)).thenReturn(Optional.of(material));
        when(materialColorRepository.findAllByMaterial_Id(7)).thenReturn(List.of(
                MaterialColor.builder().id(new MaterialColorId(7, 31)).material(material).color(defaultColor).quantity(0d).build(),
                MaterialColor.builder().id(new MaterialColorId(7, 32)).material(material).color(allowedColor).quantity(0d).build()));

        var response = productService.getProduct(productId);

        assertThat(response.model3dUrl()).isEqualTo(safeUrl);
        assertThat(response.modelMeshAppearances()).containsExactly(new com.exe.morphia3d.dto.response.ProductMeshAppearanceResponse(
                0, 0, 0, true,
                new com.exe.morphia3d.dto.response.ProductMeshAppearanceResponse.ProductMeshColorResponse(31, "Ocean blue", "#123456"),
                List.of(new com.exe.morphia3d.dto.response.ProductMeshAppearanceResponse.ProductMeshColorResponse(31, "Ocean blue", "#123456"),
                        new com.exe.morphia3d.dto.response.ProductMeshAppearanceResponse.ProductMeshColorResponse(32, "Sky blue", "#ABCDEF")),
                7, "PLA blue"));
    }

    @Test
    void invalidLinkedThumbnailReturnsNullInsteadOfLegacyUrl() {
        Model3d model = model(11, "Invalid thumbnail");
        MediaAsset invalidAsset = mediaAsset(1100L, MediaKind.IMAGE, "png", "image");
        ModelImage image = image(111, model, "legacy-image-id", true);
        image.setMediaAsset(invalidAsset);
        when(modelRepository.searchPublicCatalog(eq(ModelType.DESIGNER_MODEL), eq(ModelStatus.ACTIVE),
                eq(null), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(model), PageRequest.of(0, 10), 1));
        when(imageRepository.findByModel_IdInOrderByIsPrimaryDescIdAsc(List.of(11))).thenReturn(List.of(image));
        when(mediaUrlResolver.resolve(invalidAsset, MediaKind.IMAGE)).thenReturn(null);

        assertThat(productService.getProducts(0, 10, null, null, "newest").items().get(0).thumbnailUrl()).isNull();
    }

    private MediaAsset mediaAsset(Long id, MediaKind kind, String format, String resourceType) {
        String folder = kind == MediaKind.IMAGE ? "images" : "models";
        String suffix = kind == MediaKind.MODEL_3D ? ".glb" : "";
        return MediaAsset.builder().id(id).operationId(java.util.UUID.randomUUID()).mediaKind(kind)
                .provider(MediaProvider.CLOUDINARY).cloudName("test-cloud").providerAssetId("asset-" + id)
                .publicId("product-media/" + folder + "/" + java.util.UUID.randomUUID() + "/" + id + suffix)
                .resourceType(resourceType).deliveryType("upload").format(format).byteCount(10L)
                .providerVersion(1L).secureUrl("https://res.cloudinary.com/test-cloud")
                .state(MediaAssetState.ATTACHED).build();
    }

    private Model3d model(Integer id, String name) {
        return Model3d.builder().id(id).name(name).slug(name.toLowerCase())
                .sourceType(ModelType.DESIGNER_MODEL).status(ModelStatus.ACTIVE).build();
    }

    private ModelImage image(Integer id, Model3d model, String path, boolean primary) {
        return ModelImage.builder().id(id).model(model).imagePath(path).isPrimary(primary).build();
    }
}
