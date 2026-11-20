package com.exe.morphia3d.dto.response;
import com.exe.morphia3d.enums.TaskHistoryAction;
import com.exe.morphia3d.enums.TaskStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
public record TaskHistoryResponse(Long id, TaskHistoryAction action, Integer actorUserId, String actorName,
 long assignmentCycle, TaskStatus previousStatus, TaskStatus newStatus, Long previousAssigneeStaffProfileId,
 String previousAssigneeEmployeeNumber, String previousAssigneeName, Long newAssigneeStaffProfileId,
 String newAssigneeEmployeeNumber, String newAssigneeName, Long submissionId, String note, JsonNode details, Instant occurredAt) {}
