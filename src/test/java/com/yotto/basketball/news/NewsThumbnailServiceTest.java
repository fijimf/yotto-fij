package com.yotto.basketball.news;

import com.yotto.basketball.config.NewsProperties;
import com.yotto.basketball.entity.NewsArticle;
import com.yotto.basketball.repository.NewsArticleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NewsThumbnailServiceTest {

    @TempDir
    Path tempDir;

    private final NewsHttpClient httpClient = Mockito.mock(NewsHttpClient.class);
    private final NewsArticleRepository articleRepository = Mockito.mock(NewsArticleRepository.class);
    private NewsProperties properties;
    private NewsThumbnailService service;

    @BeforeEach
    void setUp() {
        properties = new NewsProperties();
        properties.getImages().setThumbnailDir(tempDir.toString());
        properties.getImages().setThumbnailWidth(100);
        service = new NewsThumbnailService(properties, httpClient, articleRepository);
    }

    private static byte[] pngBytes(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static NewsArticle article(Long id, String imageUrl) {
        NewsArticle a = new NewsArticle();
        a.setId(id);
        a.setImageUrl(imageUrl);
        return a;
    }

    @Test
    void downloadsResizesAndStores() throws IOException {
        when(httpClient.fetchBytes(anyString(), anyInt())).thenReturn(
                new NewsHttpClient.FetchResult(200, "https://cdn.example.com/big.png",
                        "image/png", pngBytes(800, 400), null, null));
        NewsArticle article = article(42L, "https://cdn.example.com/big.png");

        service.storeThumbnail(article);

        assertEquals("42.jpg", article.getThumbnailPath());
        verify(articleRepository).save(article);
        Path stored = tempDir.resolve("42.jpg");
        assertTrue(Files.exists(stored));
        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(Files.readAllBytes(stored)));
        assertEquals(100, thumb.getWidth());
        assertEquals(50, thumb.getHeight());

        assertNotNull(service.loadThumbnail(article));
    }

    @Test
    void smallSourceNotUpscaled() throws IOException {
        when(httpClient.fetchBytes(anyString(), anyInt())).thenReturn(
                new NewsHttpClient.FetchResult(200, "https://cdn.example.com/small.png",
                        "image/png", pngBytes(60, 60), null, null));
        NewsArticle article = article(43L, "https://cdn.example.com/small.png");

        service.storeThumbnail(article);

        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(service.loadThumbnail(article)));
        assertEquals(60, thumb.getWidth());
    }

    @Test
    void failedDownloadLeavesArticleUntouched() {
        when(httpClient.fetchBytes(anyString(), anyInt())).thenReturn(
                new NewsHttpClient.FetchResult(0, "https://cdn.example.com/gone.png", null, null, null, null));
        NewsArticle article = article(44L, "https://cdn.example.com/gone.png");

        service.storeThumbnail(article);

        assertNull(article.getThumbnailPath());
        verify(articleRepository, never()).save(article);
    }

    @Test
    void nonImageContentTypeRejected() {
        when(httpClient.fetchBytes(anyString(), anyInt())).thenReturn(
                new NewsHttpClient.FetchResult(200, "https://cdn.example.com/page.html",
                        "text/html", "<html></html>".getBytes(), null, null));
        NewsArticle article = article(45L, "https://cdn.example.com/page.html");

        service.storeThumbnail(article);

        assertNull(article.getThumbnailPath());
    }

    @Test
    void corruptImageBytesHandledQuietly() {
        when(httpClient.fetchBytes(anyString(), anyInt())).thenReturn(
                new NewsHttpClient.FetchResult(200, "https://cdn.example.com/broken.png",
                        "image/png", new byte[]{1, 2, 3, 4}, null, null));
        NewsArticle article = article(46L, "https://cdn.example.com/broken.png");

        service.storeThumbnail(article);

        assertNull(article.getThumbnailPath());
    }

    @Test
    void disabledImagesSkipDownloadEntirely() {
        properties.getImages().setEnabled(false);
        NewsArticle article = article(47L, "https://cdn.example.com/x.png");

        service.storeThumbnail(article);

        verify(httpClient, never()).fetchBytes(anyString(), anyInt());
    }

    @Test
    void articleWithoutImageUrlSkipped() {
        service.storeThumbnail(article(48L, null));
        verify(httpClient, never()).fetchBytes(anyString(), anyInt());
    }

    @Test
    void loadReturnsNullWhenFileMissing() {
        NewsArticle article = article(49L, "https://cdn.example.com/x.png");
        article.setThumbnailPath("49.jpg");
        assertNull(service.loadThumbnail(article));
    }
}
