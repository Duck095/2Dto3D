package com.exe.morphia3d.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "validated_slicing_runs")
public class ValidatedSlicingRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "slicing_run_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private Model3d model;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id", nullable = false)
    private Material material;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "printer_id", nullable = false)
    private Printer printer;

    @Column(name = "configuration_fingerprint", nullable = false, length = 128)
    private String configurationFingerprint;

    @Column(name = "configuration_snapshot", nullable = false, columnDefinition = "TEXT")
    private String configurationSnapshot;

    @Column(name = "consumed_material_grams", nullable = false, precision = 18, scale = 6)
    private BigDecimal consumedMaterialGrams;

    @Column(name = "print_time_seconds", nullable = false)
    private Long printTimeSeconds;

    @Column(name = "source", nullable = false, length = 100)
    private String source;

    @Column(name = "validated_at", nullable = false)
    private LocalDateTime validatedAt;

    @Column(name = "validated", nullable = false)
    private boolean validated;
}
