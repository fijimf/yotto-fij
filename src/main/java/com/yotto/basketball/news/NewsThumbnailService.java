package com.yotto.basketball.news;

import com.yotto.basketball.config.NewsProperties;
import com.yotto.basketball.entity.NewsArticle;
import com.yotto.basketball.repository.NewsArticleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Downloads an article's og:image and stores a small local JPEG thumbnail
 * (open question 6: "cache tiny images"). Serving our own copy avoids
 * hotlink breakage and stops leaking reader IPs to third-party CDNs.
 * Failures are silent — an article without a thumbnail renders text-only.
 */
@Service
public class NewsThumbnailService {

    private static final Logger log = LoggerFactory.getLogger(NewsThumbnailService.class);

    private final NewsProperties properties;
    private final NewsHttpClient httpClient;
    private final NewsArticleRepository articleRepository;

    public NewsThumbnailService(NewsProperties properties, NewsHttpClient httpClient,
                                NewsArticleRepository articleRepository) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.articleRepository = articleRepository;
    }

    /** Downloads, resizes, and stores the thumbnail; updates the article row on success. */
    public void storeThumbnail(NewsArticle article) {
        if (!properties.getImages().isEnabled() || article.getImageUrl() == null) {
            return;
        }
        try {
            byte[] jpeg = downloadAndResize(article.getImageUrl());
            if (jpeg == null) {
                return;
            }
            Path dir = Path.of(properties.getImages().getThumbnailDir());
            Files.createDirectories(dir);
            String filename = article.getId() + ".jpg";
            Files.write(dir.resolve(filename), jpeg);
            article.setThumbnailPath(filename);
            articleRepository.save(article);
        } catch (Exception e) {
            log.debug("Thumbnail failed for article {} ({}): {}",
                    article.getId(), article.getImageUrl(), e.toString());
        }
    }

    /** Resolves an article's stored thumbnail to bytes, or null when absent. */
    public byte[] loadThumbnail(NewsArticle article) {
        if (article.getThumbnailPath() == null) {
            return null;
        }
        Path file = Path.of(properties.getImages().getThumbnailDir()).resolve(article.getThumbnailPath());
        try {
            return Files.exists(file) ? Files.readAllBytes(file) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private byte[] downloadAndResize(String imageUrl) throws IOException {
        NewsHttpClient.FetchResult result = httpClient.fetchBytes(
                imageUrl, properties.getImages().getMaxDownloadBytes());
        if (!result.isSuccess()) {
            return null;
        }
        if (result.contentType() != null && !result.contentType().startsWith("image/")) {
            return null;
        }
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(result.body()));
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
            return null;
        }
        int targetWidth = Math.min(properties.getImages().getThumbnailWidth(), source.getWidth());
        int targetHeight = (int) Math.round((double) source.getHeight() * targetWidth / source.getWidth());
        if (targetHeight <= 0) {
            return null;
        }

        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            g.dispose();
        }
        var out = new java.io.ByteArrayOutputStream();
        ImageIO.write(scaled, "jpg", out);
        return out.toByteArray();
    }
}
