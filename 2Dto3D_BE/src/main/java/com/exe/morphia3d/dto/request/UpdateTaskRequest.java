package com.exe.morphia3d.dto.request;
import com.exe.morphia3d.enums.TaskPriority;
import jakarta.validation.constraints.*;
import java.time.Instant;
public record UpdateTaskRequest(@NotNull @PositiveOrZero Long expectedVersion, @Size(min=1,max=180) String title,
 @Size(min=1,max=20000) String description, TaskPriority priority, @Positive Long assigneeStaffProfileId, Instant dueAt) {
 public boolean hasChanges(){ return title!=null||description!=null||priority!=null||assigneeStaffProfileId!=null||dueAt!=null; }
}
