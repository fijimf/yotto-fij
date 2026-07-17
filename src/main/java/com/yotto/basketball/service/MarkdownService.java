package com.yotto.basketball.service;

import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Service;

/**
 * Renders admin-authored Markdown to HTML for broadcast emails, then sanitizes the
 * result so a malicious or malformed message can never inject script/event handlers
 * into a recipient's inbox. CommonMark passes raw inline HTML through by default, so
 * the jsoup safelist pass is the real security boundary — not decoration.
 */
@Service
public class MarkdownService {

    private final Parser parser = Parser.builder().build();
    private final HtmlRenderer renderer = HtmlRenderer.builder().build();

    // Relaxed formatting tags (headings, lists, links, emphasis, images, tables...)
    // minus anything that can execute: no <script>, no on* handlers, no style/class.
    private final Safelist safelist = Safelist.relaxed()
            .addAttributes("a", "target", "rel")
            .addProtocols("a", "href", "http", "https", "mailto");

    /** Render Markdown to sanitized HTML. Never returns null (empty input -> ""). */
    public String toSafeHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        Node document = parser.parse(markdown);
        String rawHtml = renderer.render(document);
        return Jsoup.clean(rawHtml, safelist);
    }
}
