package com.exe.morphia3d.dto.response;
import java.time.Instant;
import java.util.List;
import com.exe.morphia3d.enums.TaskPriority;
import com.exe.morphia3d.enums.TaskStatus;
public record TaskResponse(Long id,String title,TaskPriority priority,TaskSummaryResponse.Assignee assignee,TaskStatus status,
 long assignmentCycle,Instant dueAt,boolean overdue,Instant updatedAt,long version,String description,Integer createdByUserId,String createdByName,
 TaskSubmissionResponse latestSubmission,String latestRevisionFeedback,Integer reviewerUserId,Instant reviewedAt,
 Instant createdAt,List<TaskSubmissionResponse> submissions,List<TaskHistoryResponse> history) {}
