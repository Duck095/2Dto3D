package com.exe.morphia3d.service.impl;

import com.exe.morphia3d.dto.response.PaginatedProductResponse;
import com.exe.morphia3d.dto.response.ProductDetailResponse;
import com.exe.morphia3d.entity.MediaAsset;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.ModelImage;
import com.exe.morphia3d.enums.MediaAssetState;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import com.exe.morphia3d.enums.ModelStatus;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.repository.Model3dRepository;
import com.exe.morphia3d.repository.ModelImageRepository;
import com.exe.morphia3d.repository.ModelPartRepository;
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
    private ProductServiceImpl productService;

    @BeforeEach
    void setUp() {
        modelRepository = mock(Model3dRepository.class);
        imageRepository = mock(ModelImageRepository.class);
        partRepository = mock(ModelPartRepository.class);
        modelUrlResolver = mock(ModelUrlResolver.class);
        mediaUrlResolver = mock(MediaUrlResolver.class);
        productService = new ProductServiceImpl(modelRepository, imageRepository, partRepository,
                modelUrlResolver, mediaUrlResolver, "https://media.example/image/upload");
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
