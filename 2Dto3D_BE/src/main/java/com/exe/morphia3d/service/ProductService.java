package com.exe.morphia3d.service;

import com.exe.morphia3d.dto.response.PaginatedProductResponse;
import com.exe.morphia3d.dto.response.ProductDetailResponse;

public interface ProductService {
    PaginatedProductResponse getProducts(int page, int size, String query, String category, String sort);
    ProductDetailResponse getProduct(Integer id);
}
