package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.StaffProfile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.domain.Specification;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StaffProfileRepository extends JpaRepository<StaffProfile, Long>, JpaSpecificationExecutor<StaffProfile> {
    Optional<StaffProfile> findByUser_Id(Integer userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StaffProfile s where s.id=:id")
    Optional<StaffProfile> findByIdForUpdate(@Param("id") Long id);

    boolean existsByUser_Id(Integer userId);

    boolean existsByEmployeeNumberIgnoreCase(String employeeNumber);

    @Override
    @EntityGraph(attributePaths = {"user", "createdBy", "updatedBy"})
    Page<StaffProfile> findAll(Specification<StaffProfile> specification, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"user", "createdBy", "updatedBy"})
    Optional<StaffProfile> findById(Long id);
}
