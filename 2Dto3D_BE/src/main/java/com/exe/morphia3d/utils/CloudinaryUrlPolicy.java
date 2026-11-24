package com.exe.morphia3d.utils;

import java.net.URI;

/** Guards product responses from URLs emitted by the former local placeholder configuration. */
public final class CloudinaryUrlPolicy {

    private CloudinaryUrlPolicy() {
    }

    public static boolean isLegacyPlaceholder(String value) {
        if (value == null) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            String path = uri.getPath();
            return "localhost.invalid".equalsIgnoreCase(uri.getHost())
                    && path != null && path.regionMatches(true, 0, "/cloudinary", 0, "/cloudinary".length());
        } catch (IllegalArgumentException invalidUrl) {
            return false;
        }
    }
}
