package com.exe.morphia3d.service.impl;

import com.exe.morphia3d.dto.request.ColorRequest;
import com.exe.morphia3d.dto.response.ColorResponse;
import com.exe.morphia3d.entity.Color;
import com.exe.morphia3d.mapper.ColorMapper;
import com.exe.morphia3d.repository.ColorRepository;
import com.exe.morphia3d.service.ColorService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ColorServiceImpl implements ColorService {
    private final ColorRepository colorRepository;
    private final ColorMapper colorMapper;

    @Override
    @Transactional
    public void addColor(ColorRequest colorRequest) {
        if (colorRequest == null || colorRequest.getName() == null || colorRequest.getName().isBlank()
                || colorRequest.getName().trim().length() > 50
                || (colorRequest.getHexCode() != null
                && !colorRequest.getHexCode().matches("#[0-9a-fA-F]{6}"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid color fields");
        }
        String name = colorRequest.getName().trim();
        if (colorRepository.findByName(name) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Color already exists");
        }
        Color color = Color.builder()
                .name(name)
                .hexCode(colorRequest.getHexCode())
                .build();
        try {
            colorRepository.saveAndFlush(color);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Color already exists");
        }
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
