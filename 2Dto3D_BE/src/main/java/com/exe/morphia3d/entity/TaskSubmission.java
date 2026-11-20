package com.exe.morphia3d.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;

@Entity @Table(name = "task_submissions", uniqueConstraints = @UniqueConstraint(name = "uq_task_submissions_number", columnNames = {"task_id", "submission_number"})) @Getter @Setter @NoArgsConstructor
public class TaskSubmission {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "task_id", nullable = false) private Task task;
    @Column(name = "submission_number", nullable = false) private int submissionNumber;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "submitted_by_user_id", nullable = false) private User submittedBy;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "submitted_by_staff_profile_id", nullable = false) private StaffProfile submittedByStaffProfile;
    @Column(name = "assignment_cycle", nullable = false) private long assignmentCycle;
    @Column(name = "result_description", columnDefinition = "text") private String resultDescription;
    @Column(name = "result_url", length = 2048) private String resultUrl;
    @CreationTimestamp @Column(name = "submitted_at", nullable = false, updatable = false) private Instant submittedAt;
}
