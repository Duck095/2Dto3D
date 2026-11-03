package com.exe.morphia3d.controller;

import com.exe.morphia3d.dto.request.ColorQuantityRequest;
import com.exe.morphia3d.dto.request.MaterialRequest;
import com.exe.morphia3d.dto.response.MaterialResponse;
import com.exe.morphia3d.service.MaterialService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/materials")
public class MaterialController {
    private final MaterialService materialService;

    @GetMapping("/")
    public ResponseEntity<List<MaterialResponse>> getAllMaterials() {
        return ResponseEntity.ok(materialService.getAllMaterials());
    }

    @PostMapping("/create")
    public ResponseEntity<Void> addMaterial(@RequestBody MaterialRequest materialRequest) {
        materialService.addMaterial(materialRequest);
        return ResponseEntity.ok().build();
    }

    /** Retires the material by marking it unavailable; stock and historical rows remain intact. */
    @DeleteMapping("/{id}/remove")
    public ResponseEntity<Void> deleteMaterial(@PathVariable Integer id) {
        materialService.removeMaterial(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/colors/{id}")
    public ResponseEntity<Void> addMaterialColor(
            @PathVariable Integer id,
            @RequestBody ColorQuantityRequest request) {
        materialService.addMaterialColor(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/{id}/remove-color/{colorId}")
    public ResponseEntity<Void> deleteMaterialColor(
            @PathVariable Integer id,
            @PathVariable Integer colorId) {
        materialService.removeMaterialColor(id, colorId);
        return ResponseEntity.noContent().build();
    }
}
