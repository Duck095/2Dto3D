package com.exe.morphia3d.entity;

import com.exe.morphia3d.enums.TaskHistoryAction;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity @Table(name = "task_history") @Getter @Setter @NoArgsConstructor
public class TaskHistory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "task_id", nullable = false) private Task task;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "actor_user_id", nullable = false) private User actor;
    @Column(name = "assignment_cycle", nullable = false) private long assignmentCycle;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private TaskHistoryAction action;
    @Enumerated(EnumType.STRING) @Column(name = "previous_status", length = 24) private com.exe.morphia3d.enums.TaskStatus previousStatus;
    @Enumerated(EnumType.STRING) @Column(name = "new_status", length = 24) private com.exe.morphia3d.enums.TaskStatus newStatus;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "previous_assignee_staff_profile_id") private StaffProfile previousAssignee;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "new_assignee_staff_profile_id") private StaffProfile newAssignee;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "submission_id") private TaskSubmission submission;
    @Column(length = 2000) private String note;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false) private JsonNode details;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt = Instant.now();
}
