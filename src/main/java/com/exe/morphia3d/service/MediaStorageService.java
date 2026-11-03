package com.exe.morphia3d.service;

public interface MediaStorageService {

    MediaUploadResult upload(MediaUploadIntent intent, ValidatedMedia media);

    DeleteResult delete(MediaAssetIdentity identity);
}
