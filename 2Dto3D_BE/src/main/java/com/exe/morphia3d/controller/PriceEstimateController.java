package com.exe.morphia3d.controller;

import com.exe.morphia3d.dto.request.CreatePriceEstimateRequest;
import com.exe.morphia3d.dto.response.PriceEstimateResponse;
import com.exe.morphia3d.service.pricing.PriceEstimateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.exe.morphia3d.dto.response.PriceEstimateOptionsResponse;

@RestController
@RequestMapping("/api/price-estimates")
@RequiredArgsConstructor
public class PriceEstimateController {
    private final PriceEstimateService priceEstimateService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PriceEstimateResponse create(@Valid @RequestBody CreatePriceEstimateRequest request) {
        return priceEstimateService.create(request);
    }

    @GetMapping("/options")
    public PriceEstimateOptionsResponse options(@RequestParam Integer modelId) {
        return priceEstimateService.options(modelId);
    }
}

