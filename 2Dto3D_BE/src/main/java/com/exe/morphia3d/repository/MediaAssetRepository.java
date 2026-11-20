package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.MediaAsset;
import com.exe.morphia3d.enums.MediaAssetState;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import java.time.Instant;

@Repository
public interface MediaAssetRepository extends JpaRepository<MediaAsset, Long> {
    List<MediaAsset> findAllByOperationIdOrderByCreatedAtAscIdAsc(UUID operationId);

    boolean existsByOperationId(UUID operationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select asset from MediaAsset asset where asset.operationId = :operationId order by asset.id")
    List<MediaAsset> findAllByOperationIdForUpdate(@Param("operationId") UUID operationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select asset from MediaAsset asset where asset.id = :id")
    java.util.Optional<MediaAsset> findByIdForUpdate(@Param("id") Long id);

    @Query("select asset.id from MediaAsset asset where asset.state in :states "
            + "and asset.leaseUntil is not null and asset.leaseUntil <= :expiredBefore "
            + "order by asset.leaseUntil, asset.id")
    List<Long> findExpiredOperationAssetIds(@Param("states") List<MediaAssetState> states,
                                            @Param("expiredBefore") Instant expiredBefore,
                                            Pageable pageable);

    @Query("select asset.id from MediaAsset asset where asset.state = :state "
            + "and asset.retryCount < :maxAttempts and asset.leaseUntil is not null "
            + "and asset.leaseUntil <= :eligibleAt order by asset.leaseUntil, asset.id")
    List<Long> findEligibleCleanupIds(@Param("state") MediaAssetState state,
                                      @Param("maxAttempts") int maxAttempts,
                                      @Param("eligibleAt") Instant eligibleAt,
                                      Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MediaAsset asset set asset.state = :uploading where asset.id = :id "
            + "and asset.operationId = :operationId and asset.state = :pending "
            + "and asset.leaseToken = :token and asset.leaseOwner = :owner and asset.leaseUntil > :now")
    int claimUploadIfPending(@Param("id") Long id,
                             @Param("operationId") UUID operationId,
                             @Param("pending") MediaAssetState pending,
                             @Param("uploading") MediaAssetState uploading,
                             @Param("token") UUID token,
                             @Param("owner") String owner,
                             @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MediaAsset asset set asset.state = :attached, asset.leaseOwner = null, "
            + "asset.leaseToken = null, asset.leaseUntil = null where asset.id = :id "
            + "and asset.operationId = :operationId and asset.state = :ready "
            + "and asset.leaseToken = :token and asset.leaseOwner = :owner and asset.leaseUntil > :now")
    int attachIfReady(@Param("id") Long id,
                      @Param("operationId") UUID operationId,
                      @Param("ready") MediaAssetState ready,
                      @Param("attached") MediaAssetState attached,
                      @Param("token") UUID token,
                      @Param("owner") String owner,
                      @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MediaAsset asset set asset.state = :pending, asset.leaseOwner = null, "
            + "asset.leaseToken = null, asset.leaseUntil = :notBefore where asset.id = :id "
            + "and asset.operationId = :operationId and asset.state = :fromState "
            + "and asset.leaseToken = :token and asset.leaseOwner = :owner and asset.leaseUntil > :now")
    int requestCleanupIfOwned(@Param("id") Long id,
                              @Param("operationId") UUID operationId,
                              @Param("fromState") MediaAssetState fromState,
                              @Param("pending") MediaAssetState pending,
                              @Param("token") UUID token,
                              @Param("owner") String owner,
                              @Param("now") Instant now,
                              @Param("notBefore") Instant notBefore);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MediaAsset asset set asset.state = :leased, asset.leaseOwner = :owner, "
            + "asset.leaseToken = :token, asset.leaseUntil = :leaseUntil, "
            + "asset.retryCount = asset.retryCount + 1 where asset.id = :id "
            + "and asset.state = :pending and asset.retryCount < :maxAttempts "
            + "and asset.leaseUntil is not null and asset.leaseUntil <= :now")
    int claimCleanupIfDue(@Param("id") Long id,
                          @Param("pending") MediaAssetState pending,
                          @Param("leased") MediaAssetState leased,
                          @Param("owner") String owner,
                          @Param("token") UUID token,
                          @Param("leaseUntil") Instant leaseUntil,
                          @Param("maxAttempts") int maxAttempts,
                          @Param("now") Instant now);
}
