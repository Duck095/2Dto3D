package com.exe.morphia3d.entity;

import com.exe.morphia3d.enums.PrinterStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Nationalized;

@Getter
@Setter
@Entity
@Table(name = "printers")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Printer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "printer_id")
    private Integer id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "technology", length = 50)
    private String technology;

    @Column(name = "max_x_mm")
    private Double maxXMm;

    @Column(name = "max_y_mm")
    private Double maxYMm;

    @Column(name = "max_z_mm")
    private Double maxZMm;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    @Builder.Default
    private PrinterStatus status =  PrinterStatus.ACTIVE;

}