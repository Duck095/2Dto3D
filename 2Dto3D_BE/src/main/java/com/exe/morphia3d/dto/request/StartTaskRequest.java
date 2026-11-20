package com.exe.morphia3d.dto.request;
import jakarta.validation.constraints.*;
public record StartTaskRequest(@NotNull @PositiveOrZero Long expectedVersion) {}
