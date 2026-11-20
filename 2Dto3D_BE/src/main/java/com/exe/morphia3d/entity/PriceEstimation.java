package com.exe.morphia3d.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "price_estimations")
public class PriceEstimation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "estimate_id")
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private Model3d model;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id", nullable = false)
    private Material material;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "printer_id", nullable = false)
    private Printer printer;

    @Column(name = "material_cost")
    private Double materialCost;

    @Column(name = "print_time_hours")
    private Double printTimeHours;

    @Column(name = "labor_cost")
    private Double laborCost;

    @Column(name = "total_price")
    private Double totalPrice;

    @Column(name = "material_cost_vnd", precision = 24, scale = 10)
    private BigDecimal materialCostVnd;

    @Column(name = "machine_cost_vnd", precision = 24, scale = 10)
    private BigDecimal machineCostVnd;

    @Column(name = "electricity_cost_vnd", precision = 24, scale = 10)
    private BigDecimal electricityCostVnd;

    @Column(name = "production_cost_vnd", precision = 24, scale = 10)
    private BigDecimal productionCostVnd;

    @Column(name = "failure_adjusted_cost_vnd", precision = 24, scale = 10)
    private BigDecimal failureAdjustedCostVnd;

    @Column(name = "total_cost_vnd", precision = 24, scale = 10)
    private BigDecimal totalCostVnd;

    @Column(name = "total_price_vnd")
    private Long totalPriceVnd;

    @Column(name = "slicing_data_source", length = 100)
    private String slicingDataSource;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "slicing_run_id")
    private ValidatedSlicingRun slicingRun;

    @Column(name = "policy_version", length = 40)
    private String policyVersion;

    @Column(name = "pricing_policy_snapshot", columnDefinition = "TEXT")
    private String pricingPolicySnapshot;

    @CreationTimestamp
    @Column(name = "estimated_at")
    private LocalDateTime estimatedAt;

}
