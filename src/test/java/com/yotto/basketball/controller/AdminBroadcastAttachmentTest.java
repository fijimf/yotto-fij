package com.yotto.basketball.controller;

import com.yotto.basketball.service.BroadcastAttachment;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Direct coverage of the controller's attachment reading/validation (type + total-size
 * limits), which MockMvc can't exercise because the production MultipartFilter re-parses
 * the request body a mock request doesn't carry.
 */
class AdminBroadcastAttachmentTest {

    // Deps are unused by readAttachments, so nulls are fine here.
    private final AdminBroadcastController controller = new AdminBroadcastController(null, null, null);

    @Test
    void readsAllowedTypesIntoMemory() throws Exception {
        MultipartFile[] files = {
                new MockMultipartFile("attachments", "logo.png", "image/png", new byte[]{1, 2, 3}),
                new MockMultipartFile("attachments", "doc.pdf", "application/pdf", new byte[]{4, 5})
        };

        List<BroadcastAttachment> result = controller.readAttachments(files);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).filename()).isEqualTo("logo.png");
        assertThat(result.get(0).contentType()).isEqualTo("image/png");
        assertThat(result.get(0).data()).containsExactly(1, 2, 3);
        assertThat(result.get(1).filename()).isEqualTo("doc.pdf");
    }

    @Test
    void rejectsDisallowedType() {
        MultipartFile[] files = {
                new MockMultipartFile("attachments", "notes.txt", "text/plain", "hi".getBytes())
        };

        assertThatThrownBy(() -> controller.readAttachments(files))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported type");
    }

    @Test
    void rejectsWhenTotalExceedsTenMegabytes() {
        byte[] big = new byte[6 * 1024 * 1024]; // 6MB each, 12MB total
        MultipartFile[] files = {
                new MockMultipartFile("attachments", "a.pdf", "application/pdf", big),
                new MockMultipartFile("attachments", "b.pdf", "application/pdf", big)
        };

        assertThatThrownBy(() -> controller.readAttachments(files))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10MB");
    }

    @Test
    void skipsEmptyAndNullEntries() throws Exception {
        MultipartFile[] files = {
                null,
                new MockMultipartFile("attachments", "", "application/pdf", new byte[0])
        };

        assertThat(controller.readAttachments(files)).isEmpty();
    }

    @Test
    void nullArrayReturnsEmpty() throws Exception {
        assertThat(controller.readAttachments(null)).isEmpty();
    }
}
