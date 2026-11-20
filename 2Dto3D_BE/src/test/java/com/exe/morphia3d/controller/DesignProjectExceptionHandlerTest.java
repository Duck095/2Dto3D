package com.exe.morphia3d.controller;

import com.exe.morphia3d.service.MediaValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesignProjectExceptionHandlerTest {
    private final DesignProjectExceptionHandler handler = new DesignProjectExceptionHandler();

    @Test
    void mapsEveryMediaValidationReasonToStableStatusAndSanitizedErrorCode() {
        Map<MediaValidationException.Reason, Expected> expected = new EnumMap<>(MediaValidationException.Reason.class);
        expected.put(MediaValidationException.Reason.CONTENT_MISMATCH,
                new Expected(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "DESIGN_MEDIA_CONTENT_MISMATCH"));
        expected.put(MediaValidationException.Reason.UNSUPPORTED_EXTENSION,
                new Expected(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "DESIGN_MEDIA_UNSUPPORTED_EXTENSION"));
        expected.put(MediaValidationException.Reason.UNSUPPORTED_MIME_TYPE,
                new Expected(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "DESIGN_MEDIA_UNSUPPORTED_MIME_TYPE"));
        expected.put(MediaValidationException.Reason.INVALID_GLB,
                new Expected(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "DESIGN_MEDIA_INVALID_GLB"));
        expected.put(MediaValidationException.Reason.TOO_LARGE,
                new Expected(HttpStatus.PAYLOAD_TOO_LARGE, "DESIGN_MEDIA_TOO_LARGE"));
        expected.put(MediaValidationException.Reason.TOO_MANY_IMAGES,
                new Expected(HttpStatus.PAYLOAD_TOO_LARGE, "DESIGN_MEDIA_TOO_MANY_IMAGES"));
        expected.put(MediaValidationException.Reason.IMAGE_DIMENSIONS_EXCEEDED,
                new Expected(HttpStatus.PAYLOAD_TOO_LARGE, "DESIGN_MEDIA_IMAGE_DIMENSIONS_EXCEEDED"));
        expected.put(MediaValidationException.Reason.MISSING_FILE,
                new Expected(HttpStatus.BAD_REQUEST, "DESIGN_MEDIA_MISSING_FILE"));
        expected.put(MediaValidationException.Reason.MALFORMED_IMAGE,
                new Expected(HttpStatus.BAD_REQUEST, "DESIGN_MEDIA_MALFORMED_IMAGE"));
        expected.put(MediaValidationException.Reason.MALFORMED_REQUEST,
                new Expected(HttpStatus.BAD_REQUEST, "DESIGN_MEDIA_MALFORMED_REQUEST"));

        assertThat(expected.keySet()).containsExactlyInAnyOrder(MediaValidationException.Reason.values());
        expected.forEach((reason, want) -> {
            var response = handler.mediaValidation(new MediaValidationException(reason,
                    new IllegalStateException("private path D:\\sensitive\\upload.tmp")));

            assertThat(response.getStatusCode()).isEqualTo(want.status());
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo(want.code());
            assertThat(response.getBody().message()).doesNotContain("private path", "sensitive", "upload.tmp", "IllegalStateException");
        });
    }

    @Test
    void handlesMultipartSizeFailureOnlyForDesignProjectCreate() {
        var failure = new MaxUploadSizeExceededException(128L);
        var createRequest = new MockHttpServletRequest("POST", "/api/ai-studio/projects");
        var response = new DesignProjectMultipartExceptionHandler().uploadTooLarge(failure, createRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("DESIGN_UPLOAD_TOO_LARGE");
        assertThat(response.getBody().message()).doesNotContain("128", "MaxUploadSizeExceededException");

        var otherRequest = new MockHttpServletRequest("POST", "/api/admin/products");
        assertThatThrownBy(() -> new DesignProjectMultipartExceptionHandler().uploadTooLarge(failure, otherRequest))
                .isSameAs(failure);
    }

    private record Expected(HttpStatus status, String code) { }
}
