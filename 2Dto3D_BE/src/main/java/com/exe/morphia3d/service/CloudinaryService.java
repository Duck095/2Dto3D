package com.exe.morphia3d.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Service
public interface CloudinaryService {
    public String uploadImage(MultipartFile file) throws IOException;
    public String uploadModel(MultipartFile file) throws IOException;
}
