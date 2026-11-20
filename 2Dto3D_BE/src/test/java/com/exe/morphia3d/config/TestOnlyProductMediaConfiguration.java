package com.exe.morphia3d.config;

import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import com.exe.morphia3d.service.DeleteResult;
import com.exe.morphia3d.service.MediaAssetIdentity;
import com.exe.morphia3d.service.MediaStorageException;
import com.exe.morphia3d.service.MediaStorageService;
import com.exe.morphia3d.service.MediaUploadIntent;
import com.exe.morphia3d.service.MediaUploadResult;
import com.exe.morphia3d.service.ValidatedMedia;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Filesystem-only media provider for disposable browser acceptance runs. */
@TestConfiguration(proxyBeanMethods = false)
public class TestOnlyProductMediaConfiguration {
    @Bean(name = "TEST_ONLY_productMediaStorage")
    @Primary
    MediaStorageService productMediaStorage(
            @Value("${test.product-media.root}") String root,
            @Value("${cloudinary.cloud-name}") String cloudName) {
        return new TestOnlyProductMediaStorage(Path.of(root), cloudName);
    }

    static final class TestOnlyProductMediaStorage implements MediaStorageService {
        private final Path root;
        private final String cloudName;

        TestOnlyProductMediaStorage(Path root, String cloudName) {
            this.root = root.toAbsolutePath().normalize();
            this.cloudName = cloudName;
        }

        @Override
        public MediaUploadResult upload(MediaUploadIntent intent, ValidatedMedia media) {
            if (media.file().getOriginalFilename() != null
                    && media.file().getOriginalFilename().startsWith("e2e-fail-upload")) {
                throw new MediaStorageException(MediaStorageException.Reason.PROVIDER_UNAVAILABLE);
            }

            Path target = target(intent.publicId());
            try {
                Files.createDirectories(target.getParent());
                try (var input = media.file().getInputStream()) {
                    Files.copy(input, target);
                }
            } catch (IOException failure) {
                throw new MediaStorageException(MediaStorageException.Reason.PROVIDER_UNAVAILABLE);
            }

            long version = 1_900_000_000L;
            String suffix = media.mediaKind() == MediaKind.IMAGE ? "." + media.format() : "";
            String secureUrl = "https://res.cloudinary.com/" + cloudName + "/"
                    + intent.resourceType() + "/upload/v" + version + "/" + intent.publicId() + suffix;
            return new MediaUploadResult(MediaProvider.CLOUDINARY, cloudName, UUID.randomUUID().toString(),
                    intent.publicId(), intent.mediaKind(), intent.resourceType(), intent.deliveryType(),
                    media.format(), media.byteCount(), version, secureUrl);
        }

        @Override
        public DeleteResult delete(MediaAssetIdentity identity) {
            Path target = target(identity.publicId());
            try {
                return Files.deleteIfExists(target) ? DeleteResult.DELETED : DeleteResult.NOT_FOUND;
            } catch (IOException failure) {
                throw new MediaStorageException(MediaStorageException.Reason.PROVIDER_UNAVAILABLE);
            }
        }

        private Path target(String publicId) {
            Path target = root.resolve(publicId).normalize();
            if (!target.startsWith(root)) {
                throw new MediaStorageException(MediaStorageException.Reason.UNSUPPORTED_IDENTITY);
            }
            return target;
        }
    }
}
