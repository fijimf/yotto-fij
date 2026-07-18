package com.yotto.basketball.controller;

import com.yotto.basketball.news.NewsThumbnailService;
import com.yotto.basketball.repository.NewsArticleRepository;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Serves locally cached news thumbnails (docs/NEWS_MODULE.md open question 6:
 * we re-serve a small resized copy instead of hotlinking, so images don't
 * break and reader IPs aren't leaked to third-party CDNs).
 */
@RestController
public class NewsImageController {

    private final NewsArticleRepository articleRepository;
    private final NewsThumbnailService thumbnailService;

    public NewsImageController(NewsArticleRepository articleRepository,
                               NewsThumbnailService thumbnailService) {
        this.articleRepository = articleRepository;
        this.thumbnailService = thumbnailService;
    }

    @GetMapping("/news/img/{articleId}")
    public ResponseEntity<byte[]> thumbnail(@PathVariable Long articleId) {
        return articleRepository.findById(articleId)
                .map(thumbnailService::loadThumbnail)
                .filter(bytes -> bytes != null && bytes.length > 0)
                .map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                        .body(bytes))
                .orElse(ResponseEntity.notFound().build());
    }
}
