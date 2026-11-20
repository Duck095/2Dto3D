package com.exe.morphia3d.repository;

import com.exe.morphia3d.domain.payment.PaymentAttemptStatus;
import com.exe.morphia3d.entity.PaymentAttempt;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {
    Optional<PaymentAttempt> findByOwnerIdAndIdempotencyKey(Integer ownerId, String idempotencyKey);

    Optional<PaymentAttempt> findByAttemptIdAndOwnerId(UUID attemptId, Integer ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PaymentAttempt a where a.attemptId = :attemptId")
    Optional<PaymentAttempt> findByAttemptIdForUpdate(@Param("attemptId") UUID attemptId);

    List<PaymentAttempt> findByOrderIdOrderByCreatedAtDescAttemptIdDesc(Integer orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PaymentAttempt a where a.orderId = :orderId and a.status in :statuses order by a.createdAt, a.attemptId")
    List<PaymentAttempt> findOrderAttemptsForUpdate(
            @Param("orderId") Integer orderId,
            @Param("statuses") Collection<PaymentAttemptStatus> statuses);

    boolean existsByOrderIdAndStatusIn(Integer orderId, Collection<PaymentAttemptStatus> statuses);
}
