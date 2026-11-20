package com.exe.morphia3d.feature;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class QaMediaUploadFilterTest {
    private final AtomicInteger controllerCalls = new AtomicInteger();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new UploadRoutes(controllerCalls))
            .addFilters(new QaMediaUploadFilter())
            .build();

    @Test
    void qaUploadsFailBeforeProductOrDesignerUploadOrchestration() throws Exception {
        mvc.perform(multipart("/api/admin/products")
                        .file(new MockMultipartFile("data", "{}".getBytes())))
                .andExpect(result -> {
                    assertThat(result.getResponse().getStatus()).isEqualTo(503);
                    assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
                    assertThat(result.getResponse().getContentAsString())
                            .isEqualTo("{\"code\":\"FEATURE_DISABLED\",\"featureFamily\":\"MEDIA\"}");
                });
        mvc.perform(multipart("/api/designer-models")
                        .file(new MockMultipartFile("data", "{}".getBytes())))
                .andExpect(status().isServiceUnavailable());
        assertThat(controllerCalls).hasValue(0);
    }

    @Test
    void nonUploadAdminProductActionsRemainAvailableInQa() throws Exception {
        mvc.perform(post("/api/admin/products/1/publish")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        assertThat(controllerCalls).hasValue(1);
    }

    @Test
    void liveModeAllowsOnlyExactAdminProductMultipartAndBlocksOtherMultipartRoutes() throws Exception {
        MockMvc liveMvc = MockMvcBuilders.standaloneSetup(new UploadRoutes(controllerCalls))
                .addFilters(new QaMediaUploadFilter(true)).build();
        liveMvc.perform(multipart("/api/admin/products").file(new MockMultipartFile("data", "{}".getBytes())))
                .andExpect(status().isOk());
        liveMvc.perform(multipart("/api/admin/products/4/assets")
                        .file(new MockMultipartFile("data", "{}".getBytes())))
                .andExpect(status().isServiceUnavailable());
        liveMvc.perform(multipart("/api/designer-models")
                        .file(new MockMultipartFile("data", "{}".getBytes())))
                .andExpect(status().isServiceUnavailable());
        liveMvc.perform(multipart("/api/future-upload")
                        .file(new MockMultipartFile("data", "{}".getBytes())))
                .andExpect(status().isServiceUnavailable());
        assertThat(controllerCalls).hasValue(1);
    }

    @RestController
    static class UploadRoutes {
        private final AtomicInteger calls;

        UploadRoutes(AtomicInteger calls) { this.calls = calls; }

        @PostMapping("/api/admin/products")
        String productUpload() { calls.incrementAndGet(); return "created"; }

        @PostMapping("/api/designer-models")
        String designerUpload() { calls.incrementAndGet(); return "created"; }

        @PostMapping("/api/admin/products/{id}/publish")
        String publish() { calls.incrementAndGet(); return "published"; }
    }
}
