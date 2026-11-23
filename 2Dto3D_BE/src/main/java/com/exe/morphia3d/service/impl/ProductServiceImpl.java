package com.exe.morphia3d.service.impl;

import com.exe.morphia3d.dto.response.PaginatedProductResponse;
import com.exe.morphia3d.dto.response.ProductDetailResponse;
import com.exe.morphia3d.dto.response.ProductImageResponse;
import com.exe.morphia3d.dto.response.ProductPartResponse;
import com.exe.morphia3d.dto.response.ProductSummaryResponse;
import com.exe.morphia3d.dto.response.ProductMeshAppearanceResponse;
import com.exe.morphia3d.dto.response.ProductMeshAppearanceResponse.ProductMeshColorResponse;
import com.exe.morphia3d.entity.Color;
import com.exe.morphia3d.entity.MediaAsset;
import com.exe.morphia3d.entity.Material;
import com.exe.morphia3d.entity.MaterialColor;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.ModelImage;
import com.exe.morphia3d.entity.ProductGlbStructureSnapshot;
import com.exe.morphia3d.entity.ProductMeshConfigurationRevision;
import com.exe.morphia3d.enums.MediaKind;
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
import com.exe.morphia3d.service.glb.GlbStructure;
import com.exe.morphia3d.service.ProductService;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

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
    private final ProductGlbStructureSnapshotRepository structureSnapshots;
    private final ProductMeshConfigurationRevisionRepository meshConfigurations;
    private final MaterialRepository materialRepository;
    private final MaterialColorRepository materialColorRepository;
    private final String imageBaseUrl;

    public ProductServiceImpl(Model3dRepository modelRepository,
                              ModelImageRepository imageRepository,
                              ModelPartRepository partRepository,
                              ModelUrlResolver modelUrlResolver,
                              MediaUrlResolver mediaUrlResolver,
                              ProductGlbStructureSnapshotRepository structureSnapshots,
                              ProductMeshConfigurationRevisionRepository meshConfigurations,
                              MaterialRepository materialRepository,
                              MaterialColorRepository materialColorRepository,
                              @Value("${cloudinary.base-url}") String imageBaseUrl) {
        this.modelRepository = modelRepository;
        this.imageRepository = imageRepository;
        this.partRepository = partRepository;
        this.modelUrlResolver = modelUrlResolver;
        this.mediaUrlResolver = mediaUrlResolver;
        this.structureSnapshots = structureSnapshots;
        this.meshConfigurations = meshConfigurations;
        this.materialRepository = materialRepository;
        this.materialColorRepository = materialColorRepository;
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

        String modelUrl = resolveModelUrl(model);
        List<ProductMeshAppearanceResponse> meshAppearances = resolveMeshAppearances(model, modelUrl);
        return new ProductDetailResponse(model.getId(), model.getName(), model.getSlug(), model.getDescription(),
                model.getCategory(), images, modelUrl, model.getSizeXMm(),
                model.getSizeYMm(), model.getSizeZMm(), parts, meshAppearances);
    }

    private List<ProductMeshAppearanceResponse> resolveMeshAppearances(Model3d product, String modelUrl) {
        MediaAsset asset = product.getMediaAsset();
        if (asset == null || asset.getId() == null || modelUrl == null || modelUrl.isBlank()) return List.of();

        ProductGlbStructureSnapshot snapshot = structureSnapshots
                .findFirstByProductIdAndMediaAssetIdOrderBySnapshotRevisionDesc(product.getId(), asset.getId())
                .orElse(null);
        if (snapshot == null || !snapshot.getProductId().equals(product.getId())
                || !snapshot.getMediaAssetId().equals(asset.getId())) return List.of();

        ProductMeshConfigurationRevision revision = meshConfigurations
                .findFirstByProductIdAndMediaAssetIdAndStructureSnapshotIdOrderByConfigRevisionDesc(
                        product.getId(), asset.getId(), snapshot.getStructureSnapshotId())
                .orElse(null);
        if (revision == null) return List.of();

        GlbStructure structure;
        JsonNode meshes;
        try {
            structure = snapshot.readStructure();
            meshes = revision.getConfigurationJson().path("meshes");
        } catch (RuntimeException invalidHistory) {
            return List.of();
        }
        if (!revision.getProductId().equals(product.getId()) || !revision.getMediaAssetId().equals(asset.getId())
                || !revision.getStructureSnapshotId().equals(snapshot.getStructureSnapshotId())
                || !meshes.isArray() || meshes.size() != structure.primitiveOccurrences().size()) return List.of();

        Material material = materialRepository.findById(revision.getMaterialId()).orElse(null);
        if (material == null || material.getId() == null || material.getName() == null) return List.of();
        Map<Integer, Color> catalogColors = new HashMap<>();
        for (MaterialColor link : materialColorRepository.findAllByMaterial_Id(material.getId())) {
            if (link.getColor() != null && link.getColor().getId() != null) {
                catalogColors.put(link.getColor().getId(), link.getColor());
            }
        }

        Map<String, GlbStructure.PrimitiveOccurrence> occurrences = new HashMap<>();
        structure.primitiveOccurrences().forEach(occurrence -> occurrences.put(
                occurrenceKey(occurrence.nodeIndex(), occurrence.meshIndex(), occurrence.primitiveIndex()), occurrence));
        Set<String> seen = new HashSet<>();
        List<ProductMeshAppearanceResponse> result = new ArrayList<>(meshes.size());
        for (JsonNode mesh : meshes) {
            if (!mesh.isObject() || !mesh.path("nodeIndex").canConvertToInt()
                    || !mesh.path("meshIndex").canConvertToInt() || !mesh.path("primitiveIndex").canConvertToInt()
                    || !mesh.path("customizable").isBoolean() || !mesh.path("defaultColorId").canConvertToInt()
                    || !mesh.path("allowedColorIds").isArray()) return List.of();
            int nodeIndex = mesh.path("nodeIndex").intValue();
            int meshIndex = mesh.path("meshIndex").intValue();
            int primitiveIndex = mesh.path("primitiveIndex").intValue();
            String key = occurrenceKey(nodeIndex, meshIndex, primitiveIndex);
            boolean customizable = mesh.path("customizable").booleanValue();
            int defaultColorId = mesh.path("defaultColorId").intValue();
            JsonNode allowedIds = mesh.path("allowedColorIds");
            if (!occurrences.containsKey(key) || !seen.add(key) || defaultColorId <= 0
                    || (!customizable && !allowedIds.isEmpty()) || (customizable && allowedIds.isEmpty())) return List.of();
            Color defaultColor = catalogColors.get(defaultColorId);
            if (defaultColor == null) return List.of();
            List<ProductMeshColorResponse> allowedColors = new ArrayList<>();
            Set<Integer> allowedSeen = new HashSet<>();
            for (JsonNode allowed : allowedIds) {
                if (!allowed.canConvertToInt() || allowed.intValue() <= 0 || !allowedSeen.add(allowed.intValue())) return List.of();
                Color color = catalogColors.get(allowed.intValue());
                if (color == null) return List.of();
                allowedColors.add(toMeshColor(color));
            }
            if (customizable && !allowedSeen.contains(defaultColorId)) return List.of();
            result.add(new ProductMeshAppearanceResponse(nodeIndex, meshIndex, primitiveIndex, customizable,
                    toMeshColor(defaultColor), List.copyOf(allowedColors), material.getId(), material.getName()));
        }
        if (seen.size() != occurrences.size()) return List.of();
        return List.copyOf(result);
    }

    private ProductMeshColorResponse toMeshColor(Color color) {
        return new ProductMeshColorResponse(color.getId(), color.getName(), color.getHexCode());
    }

    private String occurrenceKey(int nodeIndex, int meshIndex, int primitiveIndex) {
        return nodeIndex + ":" + meshIndex + ":" + primitiveIndex;
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
