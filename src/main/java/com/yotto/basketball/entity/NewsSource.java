package com.yotto.basketball.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "news_sources")
public class NewsSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    private String name;

    @NotBlank
    private String domain;

    @Column(name = "feed_url", unique = true)
    private String feedUrl;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type")
    private SourceType sourceType = SourceType.RSS;

    @NotNull
    @Column(name = "authority_weight")
    private Integer authorityWeight = 50;

    @NotNull
    @Column(name = "dedicated_cbb")
    private Boolean dedicatedCbb = false;

    @NotNull
    private Boolean active = true;

    @Column(name = "auto_disabled_at")
    private LocalDateTime autoDisabledAt;

    @NotNull
    @Column(name = "consecutive_failures")
    private Integer consecutiveFailures = 0;

    @Column(name = "last_polled_at")
    private LocalDateTime lastPolledAt;

    @Column(name = "last_success_at")
    private LocalDateTime lastSuccessAt;

    private String etag;

    @Column(name = "last_modified_header")
    private String lastModifiedHeader;

    private String notes;

    public enum SourceType {
        RSS,
        HTML_INDEX
    }

    public NewsSource() {
    }

    /** Pollable = admin-enabled and not currently auto-disabled by the health check. */
    public boolean isPollable() {
        return Boolean.TRUE.equals(active) && autoDisabledAt == null;
    }

    public void recordSuccess() {
        this.consecutiveFailures = 0;
        this.lastSuccessAt = LocalDateTime.now();
        this.autoDisabledAt = null;
    }

    public void recordFailure(int autoDisableThreshold) {
        this.consecutiveFailures++;
        // Refresh the timestamp when already disabled so a failed weekly retry
        // probe schedules the next probe a full period out.
        if (this.autoDisabledAt != null || this.consecutiveFailures >= autoDisableThreshold) {
            this.autoDisabledAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getFeedUrl() {
        return feedUrl;
    }

    public void setFeedUrl(String feedUrl) {
        this.feedUrl = feedUrl;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(SourceType sourceType) {
        this.sourceType = sourceType;
    }

    public Integer getAuthorityWeight() {
        return authorityWeight;
    }

    public void setAuthorityWeight(Integer authorityWeight) {
        this.authorityWeight = authorityWeight;
    }

    public Boolean getDedicatedCbb() {
        return dedicatedCbb;
    }

    public void setDedicatedCbb(Boolean dedicatedCbb) {
        this.dedicatedCbb = dedicatedCbb;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public LocalDateTime getAutoDisabledAt() {
        return autoDisabledAt;
    }

    public void setAutoDisabledAt(LocalDateTime autoDisabledAt) {
        this.autoDisabledAt = autoDisabledAt;
    }

    public Integer getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public void setConsecutiveFailures(Integer consecutiveFailures) {
        this.consecutiveFailures = consecutiveFailures;
    }

    public LocalDateTime getLastPolledAt() {
        return lastPolledAt;
    }

    public void setLastPolledAt(LocalDateTime lastPolledAt) {
        this.lastPolledAt = lastPolledAt;
    }

    public LocalDateTime getLastSuccessAt() {
        return lastSuccessAt;
    }

    public void setLastSuccessAt(LocalDateTime lastSuccessAt) {
        this.lastSuccessAt = lastSuccessAt;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }

    public String getLastModifiedHeader() {
        return lastModifiedHeader;
    }

    public void setLastModifiedHeader(String lastModifiedHeader) {
        this.lastModifiedHeader = lastModifiedHeader;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NewsSource that = (NewsSource) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "NewsSource{id=" + id + ", name='" + name + "', domain='" + domain + "'}";
    }
}
