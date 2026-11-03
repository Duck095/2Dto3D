package com.exe.morphia3d.controller;

import com.exe.morphia3d.dto.request.AIModelRequest;
import com.exe.morphia3d.dto.request.DesignerModelRequest;
import com.exe.morphia3d.dto.response.ModelResponse;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.AIModelUploadService;
import com.exe.morphia3d.service.DesignerModelUploadService;
import com.exe.morphia3d.service.Model3dService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class Model3dController {
    private final Model3dService model3dService;
    private final AIModelUploadService aiModelUploadService;
    private final DesignerModelUploadService designerModelUploadService;

    @PostMapping(value = "/designer-models", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ModelResponse> addDesignerModel(
            Authentication authentication,
            @RequestPart("data") DesignerModelRequest request,
            @RequestPart("images") List<MultipartFile> imageFiles,
            @RequestPart("model") MultipartFile modelFile) {
        return ResponseEntity.ok(designerModelUploadService.create(
                currentPrincipal(authentication), request, imageFiles, modelFile));
    }

    @PostMapping("/admin/designer-models/{id}/publish")
    public ResponseEntity<Void> publishDesignerModel(Authentication authentication, @PathVariable Integer id) {
        currentPrincipal(authentication);
        model3dService.publishDesignerModel(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/designer-models")
    public ResponseEntity<List<ModelResponse>> getAllDesignerModels() {
        return ResponseEntity.ok(model3dService.getAllModelsBySourceType(ModelType.DESIGNER_MODEL));
    }

    @GetMapping("/designer-models/{id}")
    public ResponseEntity<ModelResponse> getDesignerModelById(@PathVariable Integer id){
        return ResponseEntity.ok(model3dService.getDesignerModelById(id));
    }

    @PostMapping("/ai-models")
    public ResponseEntity<ModelResponse> generateAIModel(
            Authentication authentication,
            @RequestPart("data") AIModelRequest request,
            @RequestPart("images") List<MultipartFile> imageFiles,
            @RequestPart("model") MultipartFile modelFile) {
        return ResponseEntity.ok(aiModelUploadService.create(
                currentPrincipal(authentication), request, imageFiles, modelFile));
    }

    @GetMapping("/ai-models/{id}")
    public ResponseEntity<ModelResponse> getAIModelById(Authentication authentication, @PathVariable Integer id){
        return ResponseEntity.ok(model3dService.getAIModelById(currentUserId(authentication), id));
    }

    private Integer currentUserId(Authentication authentication) {
        return currentPrincipal(authentication).getId();
    }

    private UserPrincipal currentPrincipal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)
                || principal.getId() == null) {
            throw new org.springframework.security.access.AccessDeniedException("Authenticated user principal is required");
        }
        return principal;
    }
}
