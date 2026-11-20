package com.exe.morphia3d.dto.request;
import jakarta.validation.constraints.*;
public record SubmitTaskRequest(@NotNull @PositiveOrZero Long expectedVersion, @Size(max=10000) String resultDescription,
 @Size(max=2048) String resultUrl) {}
