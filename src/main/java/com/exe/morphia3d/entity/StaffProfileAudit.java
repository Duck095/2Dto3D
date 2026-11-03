package com.exe.morphia3d.entity;

import com.exe.morphia3d.enums.StaffAuditAction;
import com.exe.morphia3d.enums.StaffStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "staff_profile_audit")
@NoArgsConstructor
public class StaffProfileAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_profile_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_staff_profile_audit_profile"))
    private StaffProfile staffProfile;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_staff_profile_audit_actor"))
    private User actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 24)
    private StaffAuditAction action;

    @Column(name = "reason", length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 16)
    private StaffStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", length = 16)
    private StaffStatus newStatus;

    @Column(name = "previous_department", length = 100)
    private String previousDepartment;

    @Column(name = "new_department", length = 100)
    private String newDepartment;

    @Column(name = "previous_job_title", length = 120)
    private String previousJobTitle;

    @Column(name = "new_job_title", length = 120)
    private String newJobTitle;

    @Column(name = "previous_joined_at")
    private LocalDate previousJoinedAt;

    @Column(name = "new_joined_at")
    private LocalDate newJoinedAt;

    @CreationTimestamp
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;
}
