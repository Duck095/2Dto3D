package com.exe.morphia3d.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Bounded private disk spool shared by trusted parsing and provider upload. */
final class SnapshotGlbSpool implements AutoCloseable {
    private final Path path;
    private final FileBackedMultipartFile file;

    private SnapshotGlbSpool(Path path, FileBackedMultipartFile file) {
        this.path = path;
        this.file = file;
    }

    static SnapshotGlbSpool create(MultipartFile source, Path directory, long maxBytes) {
        if (source == null || source.isEmpty() || source.getSize() <= 0) {
            throw new MediaValidationException(MediaValidationException.Reason.MISSING_FILE);
        }
        if (maxBytes < 1 || source.getSize() > maxBytes) {
            throw new MediaValidationException(MediaValidationException.Reason.TOO_LARGE);
        }
        if (directory == null || !Files.isDirectory(directory) || !Files.isWritable(directory)) {
            throw new SnapshotPreparationException("Private GLB temporary storage is unavailable", null);
        }

        Path spoolPath = null;
        try {
            FileStore fileStore = Files.getFileStore(directory);
            if (fileStore.getUsableSpace() < source.getSize()) {
                throw new SnapshotPreparationException("Insufficient temporary storage for GLB spool", null);
            }
            spoolPath = Files.createTempFile(directory, "2dto3d-glb-", ".spool");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream input = source.getInputStream(); OutputStream output = Files.newOutputStream(spoolPath)) {
                while (true) {
                    int read = input.read(buffer);
                    if (read == -1) break;
                    if (read == 0) {
                        int singleByte = input.read();
                        if (singleByte == -1) break;
                        buffer[0] = (byte) singleByte;
                        read = 1;
                    }
                    final long next;
                    try {
                        next = Math.addExact(total, read);
                    } catch (ArithmeticException overflow) {
                        throw new MediaValidationException(MediaValidationException.Reason.TOO_LARGE, overflow);
                    }
                    if (next > maxBytes) {
                        throw new MediaValidationException(MediaValidationException.Reason.TOO_LARGE);
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                    total = next;
                }
            }
            if (total != source.getSize()) {
                throw new MediaValidationException(MediaValidationException.Reason.INVALID_GLB);
            }
            FileBackedMultipartFile file = new FileBackedMultipartFile(source.getName(), source.getOriginalFilename(),
                    source.getContentType(), spoolPath, total, HexFormat.of().formatHex(digest.digest()));
            return new SnapshotGlbSpool(spoolPath, file);
        } catch (RuntimeException failure) {
            deleteAfterFailure(spoolPath, failure);
            throw failure;
        } catch (IOException | NoSuchAlgorithmException failure) {
            SnapshotPreparationException wrapped = new SnapshotPreparationException(
                    "Could not create trusted GLB spool", failure);
            deleteAfterFailure(spoolPath, wrapped);
            throw wrapped;
        }
    }

    FileBackedMultipartFile file() {
        return file;
    }

    @Override
    public void close() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException failure) {
            throw new SnapshotPreparationException("Could not remove trusted GLB temporary file", failure);
        }
    }

    private static void deleteAfterFailure(Path path, RuntimeException original) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    static final class FileBackedMultipartFile implements MultipartFile {
        private final String name;
        private final String originalFilename;
        private final String contentType;
        private final Path path;
        private final long size;
        private final String sha256Hex;

        private FileBackedMultipartFile(String name, String originalFilename, String contentType,
                                        Path path, long size, String sha256Hex) {
            this.name = name;
            this.originalFilename = originalFilename;
            this.contentType = contentType;
            this.path = path;
            this.size = size;
            this.sha256Hex = sha256Hex;
        }

        String sha256Hex() {
            return sha256Hex;
        }

        @Override public String getName() { return name; }
        @Override public String getOriginalFilename() { return originalFilename; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return size == 0; }
        @Override public long getSize() { return size; }

        @Override
        public byte[] getBytes() throws IOException {
            throw new IOException("Snapshot GLB spool only supports streaming reads");
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return Files.newInputStream(path);
        }

        @Override
        public void transferTo(File destination) throws IOException {
            try (InputStream input = getInputStream()) {
                Files.copy(input, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
