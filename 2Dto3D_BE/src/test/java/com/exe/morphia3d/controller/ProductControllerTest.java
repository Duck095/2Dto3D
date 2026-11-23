package com.exe.morphia3d.controller;

import com.exe.morphia3d.dto.response.PaginatedProductResponse;
import com.exe.morphia3d.dto.response.ProductDetailResponse;
import com.exe.morphia3d.dto.response.ProductSummaryResponse;
import com.exe.morphia3d.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProductControllerTest {
    private ProductService productService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        productService = mock(ProductService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ProductController(productService))
                .setControllerAdvice(new ProductApiExceptionHandler())
                .build();
    }

    @Test
    void listMapsQueryParametersAndReturnsPaginatedDtoContract() throws Exception {
        when(productService.getProducts(1, 8, "chair", "Furniture", "name_asc"))
                .thenReturn(new PaginatedProductResponse(
                        List.of(new ProductSummaryResponse(3, "Chair", "chair", "Short description", "Furniture", null)),
                        1, 8, 9, 2));

        mockMvc.perform(get("/api/products").param("page", "1").param("size", "8")
                        .param("q", "chair").param("category", "Furniture").param("sort", "name_asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(3))
                .andExpect(jsonPath("$.items[0].thumbnailUrl").doesNotExist())
                .andExpect(jsonPath("$.items[0].sourceType").doesNotExist())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(8))
                .andExpect(jsonPath("$.totalElements").value(9))
                .andExpect(jsonPath("$.totalPages").value(2));

        verify(productService).getProducts(1, 8, "chair", "Furniture", "name_asc");
    }

    @Test
    void detailMapsIdAndReturnsDedicatedDto() throws Exception {
        when(productService.getProduct(3)).thenReturn(
                new ProductDetailResponse(3, "Chair", "chair", "Full description", "Furniture",
                        List.of(), null, 10.0, 20.0, 30.0, List.of(), List.of()));

        mockMvc.perform(get("/api/products/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.model3dUrl").doesNotExist())
                .andExpect(jsonPath("$.images").isArray())
                .andExpect(jsonPath("$.modelMeshAppearances").isArray())
                .andExpect(jsonPath("$.user").doesNotExist());

        verify(productService).getProduct(3);
    }

    @Test
    void invalidTypedQueryParameterReturnsConsistentBadRequestBody() throws Exception {
        mockMvc.perform(get("/api/products").param("page", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("page")));
    }

    @Test
    void queryValidationErrorsHaveConsistentBodiesAndNoStackTrace() throws Exception {
        when(productService.getProducts(anyInt(), anyInt(), any(), any(), anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid product query"));

        assertBadRequest("page", "-1");
        assertBadRequest("size", "0");
        assertBadRequest("size", "101");
        assertBadRequest("size", "not-a-number");
        assertBadRequest("sort", "createdAt,desc");
        assertBadRequest("q", "x".repeat(101));
        assertBadRequest("category", "x".repeat(101));
    }

    @Test
    void invalidOrMissingProductDetailUsesConsistentNotFoundBody() throws Exception {
        when(productService.getProduct(0))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
        when(productService.getProduct(999999))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));

        assertNotFound("0");
        assertNotFound("999999");
        mockMvc.perform(get("/api/products/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    private void assertBadRequest(String name, String value) throws Exception {
        mockMvc.perform(get("/api/products").param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    private void assertNotFound(String id) throws Exception {
        mockMvc.perform(get("/api/products/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Product not found"))
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }
}
