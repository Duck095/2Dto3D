package com.exe.morphia3d.config;

import com.cloudinary.Cloudinary;
import com.exe.morphia3d.service.MediaStorageProperties;
import com.exe.morphia3d.service.MediaStorageService;
import com.exe.morphia3d.service.impl.CloudinaryMediaStorageService;
import com.exe.morphia3d.service.impl.QaDisabledMediaStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
public class MediaStorageConfiguration {
    @Bean
    @Profile("!test")
    public SmartInitializingSingleton mediaStorageBeanInvariant(ApplicationContext context,
                                                                 QaMediaActivation activation) {
        return () -> {
            MediaStorageService[] storage = context.getBeansOfType(MediaStorageService.class)
                    .values().toArray(MediaStorageService[]::new);
            Cloudinary[] clients = context.getBeansOfType(Cloudinary.class).values().toArray(Cloudinary[]::new);
            boolean cloudinaryExpected = activation.mode() != QaMediaActivation.Mode.QA_DISABLED;
            boolean storageTypeValid = storage.length == 1 && switch (activation.mode()) {
                case NON_QA, QA_LIVE -> storage[0] instanceof CloudinaryMediaStorageService;
                case QA_DISABLED -> storage[0] instanceof QaDisabledMediaStorageService;
            };
            if (!storageTypeValid || clients.length != (cloudinaryExpected ? 1 : 0)) {
                throw new IllegalStateException("Media provider bean selection is inconsistent with the active mode");
            }
        };
    }

    @Bean
    @Conditional(QaMediaModeCondition.NonQa.class)
    public MediaStorageService nonQaCloudinaryMediaStorageService(Cloudinary cloudinary,
                                                                  MediaStorageProperties properties,
                                                                  @Value("${media.storage.cloudinary-folder-policy:default}") String folderPolicy) {
        CloudinaryMediaStorageService.FolderPolicy policy = switch (folderPolicy) {
            case "default" -> CloudinaryMediaStorageService.FolderPolicy.DEFAULT;
            case "local-product-media" -> CloudinaryMediaStorageService.FolderPolicy.LOCAL_PRODUCT_MEDIA;
            default -> throw new IllegalStateException("Unsupported Cloudinary folder policy");
        };
        return new CloudinaryMediaStorageService(cloudinary, properties, policy);
    }

    @Bean
    @Conditional(QaMediaModeCondition.QaLive.class)
    public MediaStorageService qaCloudinaryMediaStorageService(Cloudinary cloudinary,
                                                               MediaStorageProperties properties,
                                                               QaMediaActivation activation) {
        if (activation.mode() != QaMediaActivation.Mode.QA_LIVE) {
            throw new IllegalStateException("QA dynamic media folders require validated QA media-live mode");
        }
        return new CloudinaryMediaStorageService(cloudinary, properties,
                CloudinaryMediaStorageService.FolderPolicy.QA_DYNAMIC_PRODUCT_MEDIA);
    }

    @Bean
    @Conditional(QaMediaModeCondition.QaDisabled.class)
    public MediaStorageService qaDisabledMediaStorageService() {
        return new QaDisabledMediaStorageService();
    }
}
