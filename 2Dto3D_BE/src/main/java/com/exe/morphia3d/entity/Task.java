package com.exe.morphia3d.entity;

import com.exe.morphia3d.enums.TaskPriority;
import com.exe.morphia3d.enums.TaskStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.Instant;

@Entity @Table(name = "tasks") @Getter @Setter @NoArgsConstructor
public class Task {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 180) private String title;
    @Column(nullable = false, columnDefinition = "text") private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private TaskPriority priority;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "assignee_staff_profile_id", nullable = false) private StaffProfile assignee;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "created_by_user_id", nullable = false) private User createdBy;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private TaskStatus status = TaskStatus.ASSIGNED;
    @Column(name = "assignment_cycle", nullable = false) private long assignmentCycle = 1;
    @Column(name = "due_at", nullable = false) private Instant dueAt;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(nullable = false) private long version;
}
