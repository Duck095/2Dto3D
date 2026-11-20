package com.exe.morphia3d.controller;

import com.exe.morphia3d.dto.request.ColorRequest;
import com.exe.morphia3d.dto.response.ColorResponse;
import com.exe.morphia3d.service.ColorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/colors")
public class ColorController {
    private final ColorService colorService;

    @PostMapping("/create")
    public ResponseEntity<Void> createColor(@RequestBody ColorRequest colorRequest) {
        colorService.addColor(colorRequest);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{colorName}")
    public ResponseEntity<ColorResponse> getColor(@PathVariable String colorName) {
        ColorResponse colorResponse = colorService.getColorByName(colorName);
        return ResponseEntity.ok(colorResponse);
    }
}
