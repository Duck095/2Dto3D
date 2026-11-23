package com.exe.morphia3d.service;

import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.service.ai.ValidatedGlb;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

import static com.exe.morphia3d.service.MediaValidationException.Reason.CONTENT_MISMATCH;
import static com.exe.morphia3d.service.MediaValidationException.Reason.IMAGE_DIMENSIONS_EXCEEDED;
import static com.exe.morphia3d.service.MediaValidationException.Reason.INVALID_GLB;
import static com.exe.morphia3d.service.MediaValidationException.Reason.MALFORMED_IMAGE;
import static com.exe.morphia3d.service.MediaValidationException.Reason.MISSING_FILE;
import static com.exe.morphia3d.service.MediaValidationException.Reason.TOO_LARGE;
import static com.exe.morphia3d.service.MediaValidationException.Reason.TOO_MANY_IMAGES;
import static com.exe.morphia3d.service.MediaValidationException.Reason.UNSUPPORTED_EXTENSION;
import static com.exe.morphia3d.service.MediaValidationException.Reason.UNSUPPORTED_MIME_TYPE;

@Service
@RequiredArgsConstructor
public class MediaValidationService {

    private static final Map<String, ImageType> IMAGE_TYPES = Map.of(
            "jpg", new ImageType("jpeg", "image/jpeg", Signature.JPEG),
            "jpeg", new ImageType("jpeg", "image/jpeg", Signature.JPEG),
            "png", new ImageType("png", "image/png", Signature.PNG),
            "webp", new ImageType("webp", "image/webp", Signature.WEBP));

    private static final int GLB_HEADER_BYTES = 12;
    private static final String GLB_MIME_TYPE = "model/gltf-binary";
    private static final String GENERIC_BINARY_MIME_TYPE = "application/octet-stream";
    private static final String THREE_MF_MIME_TYPE = "model/3mf";
    private static final String THREE_MF_PACKAGE_MIME_TYPE = "application/vnd.ms-package.3dmanufacturing-3dmodel+xml";
    private static final long ZIP_EOCD_SIGNATURE = 0x06054b50L;
    private static final long ZIP_CENTRAL_SIGNATURE = 0x02014b50L;
    private static final long ZIP_LOCAL_SIGNATURE = 0x04034b50L;

    private final MediaUploadProperties properties;

    public List<ValidatedMedia> validateImages(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new MediaValidationException(MISSING_FILE);
        }
        if (files.size() > properties.getMaxImages()) {
            throw new MediaValidationException(TOO_MANY_IMAGES);
        }

        List<ValidatedMedia> validated = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            validated.add(validateImage(file));
        }
        return List.copyOf(validated);
    }

    public ValidatedMedia validateGlb(MultipartFile file) {
        requirePresent(file);
        validateGlbMetadata(file.getOriginalFilename(), file.getContentType(), file.getSize());
        try (InputStream input = file.getInputStream()) {
            validateGlbContent(input, file.getSize());
        } catch (IOException exception) {
            throw new MediaValidationException(INVALID_GLB, exception);
        }

        return new ValidatedMedia(file, MediaKind.MODEL_3D, "glb", file.getSize(), null, null);
    }

    public ValidatedMedia validate3mf(MultipartFile file) {
        requirePresent(file);
        if (file.getSize() > properties.getPrint3mfMaxBytes()) {
            throw new MediaValidationException(TOO_LARGE);
        }
        if (!"3mf".equals(extension(file.getOriginalFilename()))) {
            throw new MediaValidationException(UNSUPPORTED_EXTENSION);
        }
        String mimeType = normalizedMimeType(file.getContentType());
        if (!THREE_MF_MIME_TYPE.equals(mimeType) && !THREE_MF_PACKAGE_MIME_TYPE.equals(mimeType)
                && !GENERIC_BINARY_MIME_TYPE.equals(mimeType)) {
            throw new MediaValidationException(UNSUPPORTED_MIME_TYPE);
        }

        try (InputStream input = file.getInputStream()) {
            byte[] archive = readBounded(input, properties.getPrint3mfMaxBytes());
            if (archive.length != file.getSize()) {
                throw new MediaValidationException(CONTENT_MISMATCH);
            }
            validateThreeMfArchive(archive);
        } catch (MediaValidationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new MediaValidationException(MediaValidationException.Reason.INVALID_3MF, exception);
        }
        return new ValidatedMedia(file, MediaKind.PRINT_3MF, "3mf", file.getSize(), null, null);
    }

    private byte[] readBounded(InputStream input, long maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(maxBytes, 64 * 1024));
        byte[] buffer = new byte[8192];
        int read;
        long total = 0;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new MediaValidationException(TOO_LARGE);
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void validateThreeMfArchive(byte[] archive) throws IOException {
        CentralDirectory directory = inspectCentralDirectory(archive);
        if (directory.entryCount() <= 0 || directory.entryCount() > properties.getPrint3mfMaxEntries()) {
            throw invalid3mf();
        }
        Set<String> names = new HashSet<>();
        Set<String> foldedNames = new HashSet<>();
        boolean hasContentTypes = false;
        boolean hasModel = false;
        long expandedBytes = 0;
        int entries = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > properties.getPrint3mfMaxEntries() || entries > directory.entryCount()) {
                    throw invalid3mf();
                }
                String name = entry.getName();
                validateZipEntryName(name);
                if (!names.add(name) || !foldedNames.add(name.toLowerCase(Locale.ROOT))) {
                    throw invalid3mf();
                }
                hasContentTypes |= "[Content_Types].xml".equals(name);
                hasModel |= "3D/3dmodel.model".equals(name);
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    expandedBytes += read;
                    if (expandedBytes > properties.getPrint3mfMaxExpandedBytes()) {
                        throw new MediaValidationException(TOO_LARGE);
                    }
                }
                zip.closeEntry();
            }
        } catch (ZipException exception) {
            throw new MediaValidationException(MediaValidationException.Reason.INVALID_3MF, exception);
        }
        if (entries != directory.entryCount() || !hasContentTypes || !hasModel) {
            throw invalid3mf();
        }
    }

    private CentralDirectory inspectCentralDirectory(byte[] archive) {
        int searchStart = Math.max(0, archive.length - 65_557);
        int eocd = -1;
        for (int i = archive.length - 22; i >= searchStart; i--) {
            if (littleEndianInt(archive, i) == ZIP_EOCD_SIGNATURE) {
                int commentLength = littleEndianShort(archive, i + 20);
                if (i + 22 + commentLength == archive.length) {
                    eocd = i;
                    break;
                }
            }
        }
        if (eocd < 0 || littleEndianShort(archive, eocd + 4) != 0
                || littleEndianShort(archive, eocd + 6) != 0) {
            throw invalid3mf();
        }
        int entries = littleEndianShort(archive, eocd + 10);
        long centralSize = littleEndianInt(archive, eocd + 12);
        long centralOffset = littleEndianInt(archive, eocd + 16);
        if (entries == 0xffff || centralSize == 0xffffffffL || centralOffset == 0xffffffffL
                || centralOffset + centralSize != eocd) {
            throw invalid3mf();
        }
        int cursor = (int) centralOffset;
        for (int i = 0; i < entries; i++) {
            if (cursor < 0 || cursor + 46 > eocd || littleEndianInt(archive, cursor) != ZIP_CENTRAL_SIGNATURE) {
                throw invalid3mf();
            }
            int flags = littleEndianShort(archive, cursor + 8);
            if ((flags & 0x0041) != 0) {
                throw invalid3mf();
            }
            int nameLength = littleEndianShort(archive, cursor + 28);
            int extraLength = littleEndianShort(archive, cursor + 30);
            int commentLength = littleEndianShort(archive, cursor + 32);
            long localOffset = littleEndianInt(archive, cursor + 42);
            if (localOffset > archive.length - 30L) {
                throw invalid3mf();
            }
            int local = (int) localOffset;
            int localFlags = littleEndianShort(archive, local + 6);
            int localNameLength = littleEndianShort(archive, local + 26);
            int localExtraLength = littleEndianShort(archive, local + 28);
            if (littleEndianInt(archive, local) != ZIP_LOCAL_SIGNATURE || (localFlags & 0x0041) != 0
                    || localNameLength != nameLength || local + 30L + localNameLength + localExtraLength > archive.length
                    || !java.util.Arrays.equals(java.util.Arrays.copyOfRange(archive, cursor + 46, cursor + 46 + nameLength),
                    java.util.Arrays.copyOfRange(archive, local + 30, local + 30 + localNameLength))) {
                throw invalid3mf();
            }
            cursor += 46 + nameLength + extraLength + commentLength;
        }
        if (cursor != eocd) {
            throw invalid3mf();
        }
        return new CentralDirectory(entries);
    }

    private void validateZipEntryName(String name) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.startsWith("\\")
                || name.contains("\\") || name.contains(":")) {
            throw invalid3mf();
        }
        for (String segment : name.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw invalid3mf();
            }
        }
    }

    private MediaValidationException invalid3mf() {
        return new MediaValidationException(MediaValidationException.Reason.INVALID_3MF);
    }

    private int littleEndianShort(byte[] bytes, int offset) {
        if (offset < 0 || offset + 2 > bytes.length) throw invalid3mf();
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private long littleEndianInt(byte[] bytes, int offset) {
        if (offset < 0 || offset + 4 > bytes.length) throw invalid3mf();
        return (bytes[offset] & 0xffL) | ((bytes[offset + 1] & 0xffL) << 8)
                | ((bytes[offset + 2] & 0xffL) << 16) | ((bytes[offset + 3] & 0xffL) << 24);
    }

    private record CentralDirectory(int entryCount) {
    }

    public ValidatedGlb validateGlb(byte[] bytes, String filename, String contentType) {
        if (bytes == null || bytes.length == 0) {
            throw new MediaValidationException(MISSING_FILE);
        }
        validateGlbMetadata(filename, contentType, bytes.length);
        try (InputStream input = new ByteArrayInputStream(bytes)) {
            validateGlbContent(input, bytes.length);
        } catch (IOException exception) {
            throw new MediaValidationException(INVALID_GLB, exception);
        }
        return new ValidatedGlb(bytes);
    }

    private void validateGlbMetadata(String filename, String contentType, long size) {
        if (size > properties.getModelMaxBytes()) {
            throw new MediaValidationException(TOO_LARGE);
        }
        if (!"glb".equals(extension(filename))) {
            throw new MediaValidationException(UNSUPPORTED_EXTENSION);
        }
        String mimeType = normalizedMimeType(contentType);
        if (!GLB_MIME_TYPE.equals(mimeType) && !GENERIC_BINARY_MIME_TYPE.equals(mimeType)) {
            throw new MediaValidationException(UNSUPPORTED_MIME_TYPE);
        }
        if (size < GLB_HEADER_BYTES) {
            throw new MediaValidationException(INVALID_GLB);
        }
    }

    private void validateGlbContent(InputStream input, long expectedSize) throws IOException {
        byte[] header = input.readNBytes(GLB_HEADER_BYTES);
        if (header.length != GLB_HEADER_BYTES
                || header[0] != 'g' || header[1] != 'l' || header[2] != 'T' || header[3] != 'F') {
            throw new MediaValidationException(INVALID_GLB);
        }
        long version = unsignedLittleEndianInt(header, 4);
        long declaredLength = unsignedLittleEndianInt(header, 8);
        if (version != 2) {
            throw new MediaValidationException(INVALID_GLB);
        }

        long actualLength = header.length;
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            actualLength += read;
            if (actualLength > properties.getModelMaxBytes()) {
                throw new MediaValidationException(TOO_LARGE);
            }
        }
        if (declaredLength != actualLength || expectedSize != actualLength) {
            throw new MediaValidationException(INVALID_GLB);
        }
    }

    private ValidatedMedia validateImage(MultipartFile file) {
        requirePresent(file);
        if (file.getSize() > properties.getImageMaxBytes()) {
            throw new MediaValidationException(TOO_LARGE);
        }

        String extension = extension(file.getOriginalFilename());
        ImageType expectedType = IMAGE_TYPES.get(extension);
        if (expectedType == null) {
            throw new MediaValidationException(UNSUPPORTED_EXTENSION);
        }
        if (!expectedType.mimeType().equals(normalizedMimeType(file.getContentType()))) {
            throw new MediaValidationException(UNSUPPORTED_MIME_TYPE);
        }

        try {
            verifySignature(file, expectedType.signature());
            return decodeAndValidateDimensions(file, expectedType);
        } catch (MediaValidationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new MediaValidationException(MALFORMED_IMAGE, exception);
        }
    }

    private void verifySignature(MultipartFile file, Signature expected) throws IOException {
        try (InputStream input = file.getInputStream()) {
            byte[] prefix = input.readNBytes(expected.minimumBytes());
            if (!expected.matches(prefix)) {
                throw new MediaValidationException(CONTENT_MISMATCH);
            }
        }
    }

    private ValidatedMedia decodeAndValidateDimensions(MultipartFile file, ImageType expectedType) throws IOException {
        try (InputStream input = file.getInputStream(); ImageInputStream imageInput = ImageIO.createImageInputStream(input)) {
            if (imageInput == null) {
                throw new MediaValidationException(MALFORMED_IMAGE);
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new MediaValidationException(MALFORMED_IMAGE);
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                String decodedFormat = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!expectedType.format().equals(decodedFormat)
                        && !(expectedType.format().equals("jpeg") && decodedFormat.equals("jpg"))) {
                    throw new MediaValidationException(CONTENT_MISMATCH);
                }

                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0
                        || (long) width * (long) height > properties.getDecodedPixelMax()) {
                    throw new MediaValidationException(IMAGE_DIMENSIONS_EXCEEDED);
                }

                BufferedImage decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                    throw new MediaValidationException(MALFORMED_IMAGE);
                }
                decoded.flush();
                return new ValidatedMedia(file, MediaKind.IMAGE, expectedType.format(), file.getSize(), width, height);
            } finally {
                reader.dispose();
            }
        }
    }

    private void requirePresent(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() <= 0 || file.getOriginalFilename() == null) {
            throw new MediaValidationException(MISSING_FILE);
        }
    }

    private String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int separator = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        int dot = filename.lastIndexOf('.');
        if (dot <= separator || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String normalizedMimeType(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        int parameters = mimeType.indexOf(';');
        return (parameters < 0 ? mimeType : mimeType.substring(0, parameters)).trim().toLowerCase(Locale.ROOT);
    }

    private long unsignedLittleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL)
                | ((bytes[offset + 1] & 0xffL) << 8)
                | ((bytes[offset + 2] & 0xffL) << 16)
                | ((bytes[offset + 3] & 0xffL) << 24);
    }

    private record ImageType(String format, String mimeType, Signature signature) {
    }

    private enum Signature {
        JPEG(3) {
            @Override
            boolean matches(byte[] bytes) {
                return bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                        && (bytes[2] & 0xff) == 0xff;
            }
        },
        PNG(8) {
            @Override
            boolean matches(byte[] bytes) {
                byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
                return bytes.length >= signature.length && java.util.Arrays.equals(signature, bytes);
            }
        },
        WEBP(12) {
            @Override
            boolean matches(byte[] bytes) {
                return bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                        && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
            }
        };

        private final int minimumBytes;

        Signature(int minimumBytes) {
            this.minimumBytes = minimumBytes;
        }

        int minimumBytes() {
            return minimumBytes;
        }

        abstract boolean matches(byte[] bytes);
    }
}
