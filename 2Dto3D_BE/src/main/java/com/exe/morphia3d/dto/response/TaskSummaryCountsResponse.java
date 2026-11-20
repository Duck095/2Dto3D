package com.exe.morphia3d.dto.response;
public record TaskSummaryCountsResponse(long total,long assigned,long inProgress,long submitted,long revisionRequested,
 long approved,long overdue,long pendingAcceptance) {}
