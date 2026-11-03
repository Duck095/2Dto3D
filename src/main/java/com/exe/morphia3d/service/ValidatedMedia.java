package com.exe.morphia3d.service;

import com.exe.morphia3d.enums.MediaKind;
import org.springframework.web.multipart.MultipartFile;

public record ValidatedMedia(
        MultipartFile file,
        MediaKind mediaKind,
        String format,
        long byteCount,
        Integer width,
        Integer height) {
}
