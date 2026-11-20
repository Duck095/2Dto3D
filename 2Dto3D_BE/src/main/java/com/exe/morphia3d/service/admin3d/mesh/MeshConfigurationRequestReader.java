package com.exe.morphia3d.service.admin3d.mesh;

import com.exe.morphia3d.dto.admin3d.mesh.MeshConfigurationRequest;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.concurrent.Semaphore;

/** Strict, bounded JSON boundary for the Admin mesh PUT only. */
@Component
public class MeshConfigurationRequestReader {
    public static final long MAX_BODY_BYTES = 33_554_432L;
    private static final long MAX_TOKENS = 2_000_000L;
    private static final int MAX_DEPTH = 16;
    private static final long MAX_TOTAL_ALLOWED_COLORS = 100_000L;
    private static final int MAX_OCCURRENCES = 100_000;
    // Caps aggregate request bodies at 64 MiB and bounds concurrent DTO/validator heap use.
    private static final int MAX_IN_FLIGHT_REQUESTS = 2;

    private final Semaphore admission = new Semaphore(MAX_IN_FLIGHT_REQUESTS, true);
    private final ObjectMapper mapper;

    public MeshConfigurationRequestReader() {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(MAX_DEPTH).maxStringLength(1024).maxNumberLength(32).build())
                .build();
        this.mapper = new ObjectMapper(factory)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.mapper.coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
        this.mapper.coercionConfigFor(LogicalType.Boolean)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
    }

    public Payload read(HttpServletRequest request) {
        if (!supportedEncoding(request.getHeader("Content-Encoding"))) {
            throw new RequestFailure(400, "REQUEST_INVALID");
        }
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > MAX_BODY_BYTES) throw new RequestFailure(413, "REQUEST_TOO_LARGE");
        if (declaredLength == 0) throw new RequestFailure(400, "REQUEST_INVALID");
        if (!admission.tryAcquire()) throw new RequestFailure(503, "PERSISTENCE_UNAVAILABLE");

        Path spool = null;
        boolean transferred = false;
        try {
            Path directory = Paths.get(System.getProperty("java.io.tmpdir", ".")).toAbsolutePath().normalize();
            try {
                Files.createDirectories(directory);
                FileStore store = Files.getFileStore(directory);
                if (store.getUsableSpace() < MAX_BODY_BYTES) throw new RequestFailure(503, "PERSISTENCE_UNAVAILABLE");
                spool = Files.createTempFile(directory, "admin-mesh-request-", ".json");
            } catch (RequestFailure failure) {
                throw failure;
            } catch (IOException | RuntimeException failure) {
                throw new RequestFailure(503, "PERSISTENCE_UNAVAILABLE", failure);
            }
            restrictPermissions(spool);
            long size = spool(request, spool);
            if (size == 0) throw new RequestFailure(400, "REQUEST_INVALID");
            preflightJson(spool);
            MeshConfigurationRequest body;
            try (InputStream input = Files.newInputStream(spool)) {
                try {
                    body = mapper.readValue(input, MeshConfigurationRequest.class);
                } catch (JsonProcessingException exception) {
                    throw new RequestFailure(400, "REQUEST_INVALID", exception);
                }
            }
            validateEnvelope(body);
            Payload payload = new Payload(body, spool, admission);
            transferred = true;
            return payload;
        } catch (RequestFailure failure) {
            cleanupOnFailure(spool, failure);
            throw failure;
        } catch (IOException failure) {
            RequestFailure mapped = new RequestFailure(503, "PERSISTENCE_UNAVAILABLE", failure);
            cleanupOnFailure(spool, mapped);
            throw mapped;
        } catch (RuntimeException failure) {
            RequestFailure mapped = new RequestFailure(400, "REQUEST_INVALID", failure);
            cleanupOnFailure(spool, mapped);
            throw mapped;
        } catch (Error failure) {
            cleanupOnFailure(spool, failure);
            throw failure;
        } finally {
            if (!transferred) admission.release();
        }
    }

    private long spool(HttpServletRequest request, Path target) throws IOException {
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = request.getInputStream(); OutputStream output = Files.newOutputStream(target)) {
            while (true) {
                int read = input.read(buffer);
                if (read < 0) break;
                if (read == 0) {
                    int one = input.read();
                    if (one < 0) break;
                    if (total == MAX_BODY_BYTES) throw new RequestFailure(413, "REQUEST_TOO_LARGE");
                    output.write(one);
                    total++;
                    continue;
                }
                if (read > MAX_BODY_BYTES - total) throw new RequestFailure(413, "REQUEST_TOO_LARGE");
                output.write(buffer, 0, read);
                total += read;
            }
        }
        if (request.getContentLengthLong() >= 0 && total != request.getContentLengthLong()) {
            throw new RequestFailure(400, "REQUEST_INVALID");
        }
        return total;
    }

    private void preflightJson(Path file) throws IOException {
        long tokens = 0;
        try (InputStream input = Files.newInputStream(file); JsonParser parser = mapper.getFactory().createParser(input)) {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (++tokens > MAX_TOKENS) throw new RequestFailure(400, "REQUEST_INVALID");
            }
            if (tokens == 0) throw new RequestFailure(400, "REQUEST_INVALID");
        } catch (JsonProcessingException exception) {
            throw new RequestFailure(400, "REQUEST_INVALID", exception);
        }
    }

    private static void validateEnvelope(MeshConfigurationRequest request) {
        if (request == null || request.mediaAssetId() == null || request.mediaAssetId() <= 0
                || request.structureSnapshotId() == null || request.expectedRevision() == null
                || request.expectedRevision() < 0 || request.materialId() == null || request.materialId() <= 0
                || request.meshes() == null
                || request.meshes().isEmpty() || request.meshes().size() > MAX_OCCURRENCES) {
            throw new RequestFailure(400, "REQUEST_INVALID");
        }
        long colors = 0;
        for (MeshConfigurationRequest.Mesh mesh : request.meshes()) {
            if (mesh == null || mesh.nodeIndex() == null || mesh.meshIndex() == null || mesh.primitiveIndex() == null
                    || mesh.customizable() == null || mesh.defaultColorId() == null || mesh.allowedColorIds() == null) {
                throw new RequestFailure(400, "REQUEST_INVALID");
            }
            colors += mesh.allowedColorIds().size();
            if (colors > MAX_TOTAL_ALLOWED_COLORS) throw new RequestFailure(400, "REQUEST_INVALID");
            if (mesh.allowedColorIds().stream().anyMatch(java.util.Objects::isNull)) {
                throw new RequestFailure(400, "REQUEST_INVALID");
            }
        }
    }

    private static boolean supportedEncoding(String encoding) {
        return encoding == null || encoding.isBlank() || "identity".equalsIgnoreCase(encoding.trim());
    }

    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows ACLs inherit from the private system temp directory.
        }
    }

    private static void cleanupOnFailure(Path spool, Throwable failure) {
        if (spool == null) return;
        try {
            Files.deleteIfExists(spool);
        } catch (IOException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    public static final class Payload implements AutoCloseable {
        private final MeshConfigurationRequest request;
        private final Path spool;
        private final Semaphore admission;
        private boolean closed;

        private Payload(MeshConfigurationRequest request, Path spool, Semaphore admission) {
            this.request = request;
            this.spool = spool;
            this.admission = admission;
        }

        public MeshConfigurationRequest request() { return request; }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try {
                Files.deleteIfExists(spool);
            } catch (IOException exception) {
                throw new IllegalStateException("Could not remove temporary Admin mesh request", exception);
            } finally {
                admission.release();
            }
        }
    }

    public static final class RequestFailure extends RuntimeException {
        private final int status;
        private final String code;

        public RequestFailure(int status, String code) { super(code); this.status = status; this.code = code; }
        public RequestFailure(int status, String code, Throwable cause) { super(code, cause); this.status = status; this.code = code; }
        public int status() { return status; }
        public String code() { return code; }
    }
}
