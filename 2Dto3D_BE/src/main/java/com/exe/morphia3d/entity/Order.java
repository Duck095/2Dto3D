package com.exe.morphia3d.entity;

import com.exe.morphia3d.enums.OrderStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.hibernate.annotations.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "orders")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_id")
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "printer_id", nullable = false)
    private Printer printer;

    @ColumnDefault("0")
    @Column(name = "total_amount", nullable = false)
    private Double totalAmount;

    @Column(name = "total_amount_vnd")
    private Long totalAmountVnd;

    @Column(name = "shipping_amount_vnd")
    private Long shippingAmountVnd;

    @Column(name = "shipping_confirmation_reference", length = 255)
    private String shippingConfirmationReference;

    @Column(name = "payable_amount_vnd")
    private Long payableAmountVnd;

    @Column(name = "payment_total_frozen_at")
    private Instant paymentTotalFrozenAt;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private OrderStatus status = OrderStatus.PENDING;

    @CreationTimestamp
    @Column(name = "order_date")
    private LocalDateTime orderDate;

    @Column(name = "completed_date")
    private LocalDateTime completedDate;

}
