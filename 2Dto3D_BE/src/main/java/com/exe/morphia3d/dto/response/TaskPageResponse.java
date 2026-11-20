package com.exe.morphia3d.dto.response;
import java.util.List;
public record TaskPageResponse<T>(List<T> items,int page,int size,long totalItems,int totalPages) {}
