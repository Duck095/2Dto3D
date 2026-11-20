package com.exe.morphia3d.service;

import com.exe.morphia3d.dto.request.ColorRequest;
import com.exe.morphia3d.dto.response.ColorResponse;

public interface ColorService {
    void addColor(ColorRequest colorRequest);
    void removeColor(ColorRequest colorRequest);

    ColorResponse getColorByName(String colorName);
}
