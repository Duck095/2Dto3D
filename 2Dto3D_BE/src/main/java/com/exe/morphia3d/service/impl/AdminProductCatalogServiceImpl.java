package com.exe.morphia3d.service.impl;

import com.exe.morphia3d.dto.request.AdminProductUpdateRequest;
import com.exe.morphia3d.dto.response.AdminProductPageResponse;
import com.exe.morphia3d.dto.response.ModelResponse;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.enums.ModelStatus;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.mapper.ModelMapper;
import com.exe.morphia3d.repository.Model3dRepository;
import com.exe.morphia3d.service.AdminProductCatalogService;
import com.exe.morphia3d.service.admin3d.Admin3dRestrictedMutationGateway;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AdminProductCatalogServiceImpl implements AdminProductCatalogService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_QUERY_LENGTH = 100;

    private final Model3dRepository models;
    private final ModelMapper mapper;
    private final Admin3dRestrictedMutationGateway restrictedMutations;
    private final EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public AdminProductPageResponse list(int page, int size, String query, ModelStatus status) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page or size");
        }
        String normalized = query == null ? null : query.trim();
        if (normalized != null && normalized.length() > MAX_QUERY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "q must be at most 100 characters");
        }
        if (normalized != null && normalized.isEmpty()) normalized = null;

        String search = normalized;
        Specification<Model3d> specification = (root, criteria, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("sourceType"), ModelType.DESIGNER_MODEL));
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (search != null) {
                String escaped = search.toLowerCase(Locale.ROOT).replace("\\", "\\\\")
                        .replace("%", "\\%").replace("_", "\\_");
                predicates.add(cb.like(cb.lower(root.get("name")), "%" + escaped + "%", '\\'));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };

        Page<Model3d> result = models.findAll(specification, PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return new AdminProductPageResponse(result.getContent().stream().map(mapper::toResponse).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public ModelResponse get(Integer id) {
        return mapper.toResponse(requireDesignerProduct(id));
    }

    @Override
    @Transactional
    public ModelResponse update(Integer id, AdminProductUpdateRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request is required");
        if (id == null || id < 1) throw notFound();
        String name = null;
        String description = null;
        String category = null;
        if (request.name() != null) {
            name = request.name().trim();
            if (name.isEmpty() || name.length() > 150) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name must be 1 to 150 characters");
            }
        }
        if (request.description() != null) {
            description = request.description().trim();
            if (description.length() > 255) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "description must be at most 255 characters");
            }
            if (description.isEmpty()) description = null;
        }
        if (request.category() != null) {
            category = request.category().trim();
            if (category.length() > 100) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "category must be at most 100 characters");
            }
            if (category.isEmpty()) category = null;
        }
        restrictedMutations.edit(id, request.name() != null, name,
                request.description() != null, description,
                request.category() != null, category);
        entityManager.clear();
        return mapper.toResponse(requireDesignerProduct(id));
    }

    @Override
    @Transactional
    public void archive(Integer id) {
        if (id == null || id < 1) throw notFound();
        restrictedMutations.archive(id);
        entityManager.clear();
    }

    private Model3d requireDesignerProduct(Integer id) {
        if (id == null || id < 1) throw notFound();
        return models.findById(id).filter(model -> model.getSourceType() == ModelType.DESIGNER_MODEL)
                .orElseThrow(this::notFound);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
    }
}
