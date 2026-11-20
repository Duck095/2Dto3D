package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.PaymentCallbackEvent;
import com.exe.morphia3d.enums.PaymentProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentCallbackEventRepository extends JpaRepository<PaymentCallbackEvent, Long> {
    boolean existsByProviderAndProviderEventKey(PaymentProvider provider, String providerEventKey);
    Optional<PaymentCallbackEvent> findByProviderAndProviderEventKey(PaymentProvider provider, String providerEventKey);
}
