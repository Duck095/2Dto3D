package com.exe.morphia3d.service.impl;

import com.exe.morphia3d.service.DeleteResult;
import com.exe.morphia3d.service.MediaAssetIdentity;
import com.exe.morphia3d.service.MediaStorageException;
import com.exe.morphia3d.service.MediaStorageService;
import com.exe.morphia3d.service.MediaUploadIntent;
import com.exe.morphia3d.service.MediaUploadResult;
import com.exe.morphia3d.service.ValidatedMedia;

public class QaDisabledMediaStorageService implements MediaStorageService {
    @Override
    public MediaUploadResult upload(MediaUploadIntent intent, ValidatedMedia media) {
        throw unavailable();
    }

    @Override
    public DeleteResult delete(MediaAssetIdentity identity) {
        throw unavailable();
    }

    private MediaStorageException unavailable() {
        return new MediaStorageException(MediaStorageException.Reason.PROVIDER_UNAVAILABLE);
    }
}
