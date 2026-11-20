package com.exe.morphia3d.dto.request;
import jakarta.validation.constraints.*;
public record ReviewTaskRequest(@NotNull @PositiveOrZero Long expectedVersion, @NotBlank String decision, @Size(max=2000) String feedback) {}
