package com.exe.morphia3d.dto.response;
import java.time.Instant;
public record TaskSubmissionResponse(Long id, int submissionNumber, long assignmentCycle, String resultDescription,
 String resultUrl, Instant submittedAt, Integer submitterUserId, String submitterName) {}
