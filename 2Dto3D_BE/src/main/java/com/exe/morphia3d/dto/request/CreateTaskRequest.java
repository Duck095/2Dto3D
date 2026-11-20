package com.exe.morphia3d.dto.request;
import com.exe.morphia3d.enums.TaskPriority;
import jakarta.validation.constraints.*;
import java.time.Instant;
public record CreateTaskRequest(@NotBlank @Size(max=180) String title, @NotBlank @Size(max=20000) String description,
 @NotNull TaskPriority priority, @NotNull @Positive Long assigneeStaffProfileId, @NotNull Instant dueAt) {}
