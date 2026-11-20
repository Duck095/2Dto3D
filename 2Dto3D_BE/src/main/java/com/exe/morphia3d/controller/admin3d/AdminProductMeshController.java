package com.exe.morphia3d.controller.admin3d;

import com.exe.morphia3d.dto.admin3d.mesh.MeshConfigurationRequest;
import com.exe.morphia3d.dto.admin3d.mesh.MeshConfigurationResponse;
import com.exe.morphia3d.dto.admin3d.mesh.MeshStructureResponse;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.admin3d.mesh.AdminProductMeshService;
import com.exe.morphia3d.service.admin3d.mesh.MeshConfigurationRequestReader;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/products")
public class AdminProductMeshController {
    private final AdminProductMeshService service;
    private final MeshConfigurationRequestReader requestReader;

    public AdminProductMeshController(AdminProductMeshService service,
                                      MeshConfigurationRequestReader requestReader) {
        this.service = service;
        this.requestReader = requestReader;
    }

    @GetMapping("/{productId}/mesh-structure")
    public MeshStructureResponse getStructure(@PathVariable Integer productId) {
        requireValidProductId(productId);
        return service.getStructure(productId);
    }

    @GetMapping("/{productId}/mesh-configurations")
    public MeshConfigurationResponse getConfiguration(@PathVariable Integer productId) {
        requireValidProductId(productId);
        return service.getConfiguration(productId);
    }

    @PutMapping(path = "/{productId}/mesh-configurations", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MeshConfigurationResponse putConfiguration(@PathVariable Integer productId,
                                                       @AuthenticationPrincipal UserPrincipal principal,
                                                       HttpServletRequest servletRequest) {
        requireValidProductId(productId);
        if (principal == null || principal.getId() == null) {
            throw new MeshConfigurationRequestReader.RequestFailure(401, "AUTHENTICATION_REQUIRED");
        }
        try (MeshConfigurationRequestReader.Payload payload = requestReader.read(servletRequest)) {
            return service.putConfiguration(productId, principal.getId(), payload.request());
        }
    }

    private static void requireValidProductId(Integer productId) {
        if (productId == null || productId <= 0) {
            throw new MeshConfigurationRequestReader.RequestFailure(400, "REQUEST_INVALID");
        }
    }
}
