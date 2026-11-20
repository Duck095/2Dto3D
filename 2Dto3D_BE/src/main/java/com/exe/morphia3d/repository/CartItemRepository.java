package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.CartItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

@Repository
public interface CartItemRepository extends JpaRepository<CartItem, Integer> {
    List<CartItem> findAllByCartId(Integer cartId);

    List<CartItem> findAllByCartIdOrderByIdAsc(Integer cartId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from CartItem item where item.cart.id = :cartId order by item.id")
    List<CartItem> findAllByCartIdForUpdate(@Param("cartId") Integer cartId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from CartItem item where item.id = :itemId")
    Optional<CartItem> findByIdForUpdate(@Param("itemId") Integer itemId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from CartItem item where item.cart.id = :cartId "
            + "and item.model.id = :modelId and item.estimate.id = :estimateId")
    Optional<CartItem> findBySelectionForUpdate(
            @Param("cartId") Integer cartId,
            @Param("modelId") Integer modelId,
            @Param("estimateId") Integer estimateId);
}
