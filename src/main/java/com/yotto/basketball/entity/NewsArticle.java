package com.yotto.basketball.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * A discovered news article. Only link/title/snippet/image metadata is stored;
 * article body text is never persisted (see docs/NEWS_MODULE.md §1).
 */
@Entity
@Table(name = "news_articles")
public class NewsArticle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(name = "url_canonical", unique = true)
    private String urlCanonical;

    @NotBlank
    @Column(name = "url_original")
    private String urlOriginal;

    /** Publisher, attributed by final domain; null when the domain has no sources row. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_id")
    private NewsSource source;

    /** The feed that surfaced the link (differs from source for aggregator feeds). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "discovered_via_source_id")
    private NewsSource discoveredVia;

    @NotBlank
    private String title;

    private String subtitle;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "thumbnail_path")
    private String thumbnailPath;

    @NotNull
    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @NotNull
    @Column(name = "fetched_at")
    private LocalDateTime fetchedAt;

    /** Null when the body was too short to hash reliably. */
    private Long simhash;

    @NotNull
    @Column(name = "body_token_count")
    private Integer bodyTokenCount = 0;

    /** Non-null marks this row a suppressed duplicate of the cluster representative. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "duplicate_of_article_id")
    private NewsArticle duplicateOf;

    @NotNull
    @Column(name = "static_score")
    private Double staticScore = 30.0;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "tag_status")
    private TagStatus tagStatus = TagStatus.UNTAGGED;

    @NotNull
    private Boolean hidden = false;

    public enum TagStatus {
        TAGGED,
        UNTAGGED,
        MANUAL
    }

    public NewsArticle() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUrlCanonical() {
        return urlCanonical;
    }

    public void setUrlCanonical(String urlCanonical) {
        this.urlCanonical = urlCanonical;
    }

    public String getUrlOriginal() {
        return urlOriginal;
    }

    public void setUrlOriginal(String urlOriginal) {
        this.urlOriginal = urlOriginal;
    }

    public NewsSource getSource() {
        return source;
    }

    public void setSource(NewsSource source) {
        this.source = source;
    }

    public NewsSource getDiscoveredVia() {
        return discoveredVia;
    }

    public void setDiscoveredVia(NewsSource discoveredVia) {
        this.discoveredVia = discoveredVia;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getThumbnailPath() {
        return thumbnailPath;
    }

    public void setThumbnailPath(String thumbnailPath) {
        this.thumbnailPath = thumbnailPath;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }

    public LocalDateTime getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(LocalDateTime fetchedAt) {
        this.fetchedAt = fetchedAt;
    }

    public Long getSimhash() {
        return simhash;
    }

    public void setSimhash(Long simhash) {
        this.simhash = simhash;
    }

    public Integer getBodyTokenCount() {
        return bodyTokenCount;
    }

    public void setBodyTokenCount(Integer bodyTokenCount) {
        this.bodyTokenCount = bodyTokenCount;
    }

    public NewsArticle getDuplicateOf() {
        return duplicateOf;
    }

    public void setDuplicateOf(NewsArticle duplicateOf) {
        this.duplicateOf = duplicateOf;
    }

    public Double getStaticScore() {
        return staticScore;
    }

    public void setStaticScore(Double staticScore) {
        this.staticScore = staticScore;
    }

    public TagStatus getTagStatus() {
        return tagStatus;
    }

    public void setTagStatus(TagStatus tagStatus) {
        this.tagStatus = tagStatus;
    }

    public Boolean getHidden() {
        return hidden;
    }

    public void setHidden(Boolean hidden) {
        this.hidden = hidden;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NewsArticle that = (NewsArticle) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "NewsArticle{id=" + id + ", title='" + title + "', url='" + urlCanonical + "'}";
    }
}
