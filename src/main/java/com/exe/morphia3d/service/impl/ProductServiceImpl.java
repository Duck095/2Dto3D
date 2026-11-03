package com.exe.morphia3d.service.impl;

import com.exe.morphia3d.dto.response.PaginatedProductResponse;
import com.exe.morphia3d.dto.response.ProductDetailResponse;
import com.exe.morphia3d.dto.response.ProductImageResponse;
import com.exe.morphia3d.dto.response.ProductPartResponse;
import com.exe.morphia3d.dto.response.ProductSummaryResponse;
import com.exe.morphia3d.entity.MediaAsset;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.ModelImage;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.ModelStatus;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.repository.Model3dRepository;
import com.exe.morphia3d.repository.ModelImageRepository;
import com.exe.morphia3d.repository.ModelPartRepository;
import com.exe.morphia3d.service.MediaUrlResolver;
import com.exe.morphia3d.service.ProductService;
import com.exe.morphia3d.utils.ModelUrlResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ProductServiceImpl implements ProductService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_FILTER_LENGTH = 100;
    private static final int SUMMARY_DESCRIPTION_LENGTH = 240;

    private final Model3dRepository modelRepository;
    private final ModelImageRepository imageRepository;
    private final ModelPartRepository partRepository;
    private final ModelUrlResolver modelUrlResolver;
    private final MediaUrlResolver mediaUrlResolver;
    private final String imageBaseUrl;

    public ProductServiceImpl(Model3dRepository modelRepository,
                              ModelImageRepository imageRepository,
                              ModelPartRepository partRepository,
                              ModelUrlResolver modelUrlResolver,
                              MediaUrlResolver mediaUrlResolver,
                              @Value("${cloudinary.base-url}") String imageBaseUrl) {
        this.modelRepository = modelRepository;
        this.imageRepository = imageRepository;
        this.partRepository = partRepository;
        this.modelUrlResolver = modelUrlResolver;
        this.mediaUrlResolver = mediaUrlResolver;
        this.imageBaseUrl = imageBaseUrl;
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedProductResponse getProducts(int page, int size, String query, String category, String sort) {
        validatePageAndSize(page, size);
        String normalizedQuery = normalizeFilter(query, "q");
        String normalizedCategory = normalizeFilter(category, "category");
        PageRequest pageable = PageRequest.of(page, size, parseSort(sort));
        Page<Model3d> results = modelRepository.searchPublicCatalog(
                ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE, normalizedQuery, normalizedCategory, pageable);

        List<Integer> ids = results.getContent().stream().map(Model3d::getId).toList();
        Map<Integer, ModelImage> thumbnails = new HashMap<>();
        if (!ids.isEmpty()) {
            for (ModelImage image : imageRepository.findByModel_IdInOrderByIsPrimaryDescIdAsc(ids)) {
                thumbnails.putIfAbsent(image.getModel().getId(), image);
            }
        }
        List<ProductSummaryResponse> items = results.getContent().stream()
                .map(model -> toSummary(model, thumbnails.get(model.getId())))
                .toList();
        return new PaginatedProductResponse(items, results.getNumber(), results.getSize(),
                results.getTotalElements(), results.getTotalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public ProductDetailResponse getProduct(Integer id) {
        if (id == null || id < 1) throw notFound();
        Model3d model = modelRepository.findByIdAndSourceTypeAndStatus(
                        id, ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE)
                .orElseThrow(this::notFound);
        List<ProductImageResponse> images = imageRepository.findByModel_IdOrderByIsPrimaryDescIdAsc(id).stream()
                .map(this::toImageResponse)
                .toList();
        List<ProductPartResponse> parts = partRepository.findByModel_IdOrderByIdAsc(id).stream()
                .map(part -> new ProductPartResponse(part.getPartName(), part.getPartKey()))
                .toList();

        return new ProductDetailResponse(model.getId(), model.getName(), model.getSlug(), model.getDescription(),
                model.getCategory(), images, resolveModelUrl(model), model.getSizeXMm(),
                model.getSizeYMm(), model.getSizeZMm(), parts);
    }

    private ProductSummaryResponse toSummary(Model3d model, ModelImage thumbnail) {
        String description = model.getDescription();
        if (description != null && description.length() > SUMMARY_DESCRIPTION_LENGTH) {
            description = description.substring(0, SUMMARY_DESCRIPTION_LENGTH);
        }
        return new ProductSummaryResponse(model.getId(), model.getName(), model.getSlug(), description,
                model.getCategory(), thumbnail == null ? null : resolveImageUrl(thumbnail));
    }

    private ProductImageResponse toImageResponse(ModelImage image) {
        return new ProductImageResponse(image.getId(), resolveImageUrl(image),
                Boolean.TRUE.equals(image.getIsPrimary()));
    }

    private String resolveImageUrl(ModelImage image) {
        MediaAsset asset = image.getMediaAsset();
        if (asset != null) {
            return mediaUrlResolver.resolve(asset, MediaKind.IMAGE);
        }
        String path = image.getImagePath();
        if (path == null || path.isBlank()) return null;
        if (path.startsWith("https://") || path.startsWith("http://")) return path;
        return imageBaseUrl.replaceAll("/+$", "") + "/" + path.replaceAll("^/+", "");
    }

    private String resolveModelUrl(Model3d model) {
        MediaAsset asset = model.getMediaAsset();
        if (asset != null) {
            return mediaUrlResolver.resolve(asset, MediaKind.MODEL_3D);
        }
        String path = model.getFilePath3d();
        if (path == null || path.isBlank()) return null;
        String resolved = modelUrlResolver.resolve(path);
        return resolved == null || resolved.isBlank() ? null : resolved;
    }

    private void validatePageAndSize(int page, int size) {
        if (page < 0) throw badRequest("page must be zero or greater");
        if (size < 1 || size > MAX_PAGE_SIZE) throw badRequest("size must be between 1 and " + MAX_PAGE_SIZE);
    }

    private String normalizeFilter(String value, String parameter) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > MAX_FILTER_LENGTH) {
            throw badRequest(parameter + " must be at most " + MAX_FILTER_LENGTH + " characters");
        }
        return normalized;
    }

    private Sort parseSort(String sort) {
        if (sort == null || sort.isBlank() || "newest".equalsIgnoreCase(sort.trim())) {
            return Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        }
        return switch (sort.trim().toLowerCase(Locale.ROOT)) {
            case "name_asc" -> Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"));
            case "name_desc" -> Sort.by(Sort.Order.desc("name"), Sort.Order.desc("id"));
            default -> throw badRequest("sort must be one of: newest, name_asc, name_desc");
        };
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
