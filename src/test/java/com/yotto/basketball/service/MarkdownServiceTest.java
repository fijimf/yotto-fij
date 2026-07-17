package com.yotto.basketball.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownServiceTest {

    private final MarkdownService service = new MarkdownService();

    @Test
    void rendersBasicMarkdown() {
        String html = service.toSafeHtml("**bold** and _italic_\n\n- one\n- two");
        assertThat(html).contains("<strong>bold</strong>");
        assertThat(html).contains("<em>italic</em>");
        assertThat(html).contains("<li>one</li>");
    }

    @Test
    void stripsScriptTags() {
        String html = service.toSafeHtml("Hello <script>alert('x')</script> world");
        assertThat(html).doesNotContain("<script");
        assertThat(html).doesNotContain("alert('x')");
        assertThat(html).contains("Hello");
    }

    @Test
    void stripsEventHandlerAttributes() {
        String html = service.toSafeHtml("<a href=\"https://x.com\" onclick=\"evil()\">link</a>");
        assertThat(html).doesNotContain("onclick");
        assertThat(html).contains("href=\"https://x.com\"");
    }

    @Test
    void stripsJavascriptProtocolLinks() {
        String html = service.toSafeHtml("[click](javascript:alert(1))");
        assertThat(html).doesNotContain("javascript:");
    }

    @Test
    void emptyInputReturnsEmptyString() {
        assertThat(service.toSafeHtml(null)).isEmpty();
        assertThat(service.toSafeHtml("   ")).isEmpty();
    }
}
