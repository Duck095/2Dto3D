package com.exe.morphia3d.controller.admin3d;

import com.exe.morphia3d.service.admin3d.mesh.MeshConfigurationException;
import com.exe.morphia3d.service.admin3d.mesh.MeshConfigurationRequestReader;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AdminProductMeshController.class)
public class AdminProductMeshExceptionHandler {
    @ExceptionHandler(MeshConfigurationRequestReader.RequestFailure.class)
    public ResponseEntity<MeshApiErrorResponse> requestFailure(MeshConfigurationRequestReader.RequestFailure exception) {
        return response(HttpStatus.valueOf(exception.status()), exception.code());
    }

    @ExceptionHandler(MeshConfigurationException.class)
    public ResponseEntity<MeshApiErrorResponse> domainFailure(MeshConfigurationException exception) {
        if (exception instanceof MeshConfigurationException.PersistenceFailure) {
            return response(HttpStatus.SERVICE_UNAVAILABLE, "PERSISTENCE_UNAVAILABLE");
        }
        if (exception instanceof MeshConfigurationException.MaterialNotFound) {
            return response(HttpStatus.UNPROCESSABLE_ENTITY, "MATERIAL_NOT_FOUND");
        }
        if (exception instanceof MeshConfigurationException.NotFound) {
            return response(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
        }
        if (exception instanceof MeshConfigurationException.Invalid) {
            String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (message.contains("color") || message.contains("compatible")) {
                return response(HttpStatus.UNPROCESSABLE_ENTITY, "MATERIAL_COLOR_INCOMPATIBLE");
            }
            return response(HttpStatus.UNPROCESSABLE_ENTITY, "CONFIGURATION_INVALID");
        }
        if (exception instanceof MeshConfigurationException.Conflict) {
            return response(HttpStatus.CONFLICT, conflictCode(exception.getMessage()));
        }
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<MeshApiErrorResponse> persistenceFailure(DataAccessException exception) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "PERSISTENCE_UNAVAILABLE");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<MeshApiErrorResponse> methodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return response(HttpStatus.METHOD_NOT_ALLOWED, "REQUEST_INVALID");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<MeshApiErrorResponse> unsupportedMediaType(HttpMediaTypeNotSupportedException exception) {
        return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "REQUEST_INVALID");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<MeshApiErrorResponse> invalidPathValue(MethodArgumentTypeMismatchException exception) {
        return response(HttpStatus.BAD_REQUEST, "REQUEST_INVALID");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<MeshApiErrorResponse> unexpected(Exception exception) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
    }

    private static String conflictCode(String message) {
        String value = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("snapshot is required")) return "GLB_STRUCTURE_SNAPSHOT_REQUIRED";
        if (value.contains("snapshot") && (value.contains("corrupt") || value.contains("unsupported")
                || value.contains("invalid"))) return "GLB_STRUCTURE_UNAVAILABLE";
        if (value.contains("snapshot") && value.contains("stale")) return "GLB_STRUCTURE_STALE";
        if (value.contains("media") && value.contains("stale")) return "MEDIA_ASSET_NOT_CURRENT";
        if (value.contains("configuration") && value.contains("corrupt")) return "CONFIGURATION_HISTORY_UNAVAILABLE";
        if (value.contains("revision") && (value.contains("stale") || value.contains("conflict"))) {
            return "CONFIGURATION_REVISION_CONFLICT";
        }
        if (value.contains("exhausted")) return "CONFIGURATION_REVISION_EXHAUSTED";
        if (value.contains("material") && value.contains("unavailable")) return "MATERIAL_UNAVAILABLE";
        if (value.contains("media") || value.contains("glb")) return "GLB_NOT_READY";
        return "CONFIGURATION_CONFLICT";
    }

    private static ResponseEntity<MeshApiErrorResponse> response(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new MeshApiErrorResponse(code, safeMessage(code)));
    }

    private static String safeMessage(String code) {
        return switch (code) {
            case "REQUEST_INVALID" -> "The request is invalid.";
            case "PRODUCT_NOT_FOUND" -> "The product was not found.";
            case "GLB_STRUCTURE_SNAPSHOT_REQUIRED" -> "A verified GLB structure snapshot is required.";
            case "GLB_STRUCTURE_UNAVAILABLE" -> "The GLB structure snapshot is unavailable.";
            case "CONFIGURATION_HISTORY_UNAVAILABLE" -> "The latest configuration history is unavailable.";
            case "GLB_STRUCTURE_STALE" -> "The GLB structure snapshot is stale.";
            case "CONFIGURATION_REVISION_CONFLICT" -> "The configuration revision is stale.";
            case "CONFIGURATION_REVISION_EXHAUSTED" -> "The configuration revision limit was reached.";
            case "GLB_NOT_READY" -> "The current GLB media is not ready.";
            case "MEDIA_ASSET_NOT_CURRENT" -> "The selected media asset is no longer current.";
            case "MATERIAL_UNAVAILABLE" -> "The selected material is unavailable.";
            case "MATERIAL_NOT_FOUND" -> "The selected material was not found.";
            case "MATERIAL_COLOR_INCOMPATIBLE" -> "The selected material and color are incompatible.";
            case "CONFIGURATION_INVALID" -> "The mesh configuration is invalid.";
            case "REQUEST_TOO_LARGE" -> "The request body exceeds the permitted size.";
            case "PERSISTENCE_UNAVAILABLE" -> "The persistence service is temporarily unavailable.";
            case "AUTHENTICATION_REQUIRED" -> "Authentication is required.";
            case "ADMIN_REQUIRED" -> "Administrator access is required.";
            case "INTERNAL_ERROR" -> "The request could not be completed.";
            default -> "The request could not be completed.";
        };
    }
}

