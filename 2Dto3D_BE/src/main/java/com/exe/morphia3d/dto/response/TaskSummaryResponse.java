package com.exe.morphia3d.dto.response;
import com.exe.morphia3d.enums.TaskPriority;
import com.exe.morphia3d.enums.TaskStatus;
import java.time.Instant;
public record TaskSummaryResponse(Long id,String title,TaskPriority priority,Assignee assignee,TaskStatus status,
 long assignmentCycle,Instant dueAt,boolean overdue,Instant updatedAt,long version) {
 public record Assignee(Long id,String employeeNumber,String displayName) {}
}
