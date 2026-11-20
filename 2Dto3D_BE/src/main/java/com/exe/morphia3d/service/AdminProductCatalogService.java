package com.exe.morphia3d.service;

import com.exe.morphia3d.dto.request.AdminProductUpdateRequest;
import com.exe.morphia3d.dto.response.AdminProductPageResponse;
import com.exe.morphia3d.dto.response.ModelResponse;
import com.exe.morphia3d.enums.ModelStatus;

public interface AdminProductCatalogService {
    AdminProductPageResponse list(int page, int size, String query, ModelStatus status);
    ModelResponse get(Integer id);
    ModelResponse update(Integer id, AdminProductUpdateRequest request);
    void archive(Integer id);
}
