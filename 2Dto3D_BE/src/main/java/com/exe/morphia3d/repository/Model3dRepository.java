package com.exe.morphia3d.repository;

import com.exe.morphia3d.dto.response.ModelResponse;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.enums.ModelStatus;
import com.exe.morphia3d.enums.ModelType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface Model3dRepository extends JpaRepository<Model3d, Integer>, JpaSpecificationExecutor<Model3d> {
    Model3d getModel3dById(Integer id);

    List<Model3d> findBySourceType(ModelType sourceType);

    List<Model3d> findBySourceTypeAndStatusOrderByCreatedAtDescIdDesc(ModelType sourceType, ModelStatus status);

    @EntityGraph(attributePaths = "mediaAsset")
    Optional<Model3d> findByIdAndSourceTypeAndStatus(Integer id, ModelType sourceType, ModelStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select model from Model3d model where model.id = :id")
    Optional<Model3d> findByIdForPublication(@Param("id") Integer id);

    boolean existsByMediaAsset_Id(Long mediaAssetId);

    default Page<Model3d> searchPublicCatalog(ModelType sourceType,
                                               ModelStatus status,
                                               String query,
                                               String category,
                                               Pageable pageable) {
        Specification<Model3d> specification = (root, criteriaQuery, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(criteriaBuilder.equal(root.get("sourceType"), sourceType));
            predicates.add(criteriaBuilder.equal(root.get("status"), status));

            if (query != null && !query.isBlank()) {
                String pattern = "%" + escapeLikeWildcards(query).toLowerCase(Locale.ROOT) + "%";
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("name")), pattern, '\\'));
            }
            if (category != null && !category.isBlank()) {
                predicates.add(criteriaBuilder.equal(
                        criteriaBuilder.lower(root.get("category")), category.toLowerCase(Locale.ROOT)));
            }
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
        return findAll(specification, pageable);
    }

    private static String escapeLikeWildcards(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
