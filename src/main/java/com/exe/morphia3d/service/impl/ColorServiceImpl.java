package com.exe.morphia3d.service.impl;

import com.exe.morphia3d.dto.request.ColorRequest;
import com.exe.morphia3d.dto.response.ColorResponse;
import com.exe.morphia3d.entity.Color;
import com.exe.morphia3d.mapper.ColorMapper;
import com.exe.morphia3d.repository.ColorRepository;
import com.exe.morphia3d.service.ColorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ColorServiceImpl implements ColorService {
    private final ColorRepository colorRepository;
    private final ColorMapper colorMapper;

    @Override
    public void addColor(ColorRequest colorRequest) {
        Color color = Color.builder()
                .name(colorRequest.getName())
                .hexCode(colorRequest.getHexCode())
                .build();
        colorRepository.save(color);
    }

    @Override
    public void removeColor(ColorRequest colorRequest) {
        Color color = colorRepository.findByName(colorRequest.getName());
        colorRepository.delete(color);
    }

    @Override
    public ColorResponse getColorByName(String colorName) {
        Color color = colorRepository.findByName(colorName);

        if (color == null) {
            throw new RuntimeException("Color not found");
        }

        return colorMapper.toResponse(color);
    }
}
