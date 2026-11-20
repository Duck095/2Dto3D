package com.exe.morphia3d.dto.response;
import com.exe.morphia3d.enums.TaskPriority;
import com.exe.morphia3d.enums.TaskStatus;
import java.time.Instant;
public record EmployeeTaskResponse(Long id,String title,String description,TaskPriority priority,TaskStatus status,
 long assignmentCycle,Instant dueAt,boolean overdue,Instant updatedAt,long version,
 TaskSubmissionResponse latestSubmission,String latestRevisionFeedback,Instant reviewedAt) {}
