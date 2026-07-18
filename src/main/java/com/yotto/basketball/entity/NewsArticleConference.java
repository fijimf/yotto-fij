package com.yotto.basketball.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.util.Objects;

@Entity
@Table(name = "news_article_conferences",
        uniqueConstraints = @UniqueConstraint(columnNames = {"article_id", "conference_id"}))
public class NewsArticleConference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id")
    private NewsArticle article;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conference_id")
    private Conference conference;

    @NotNull
    private Double confidence;

    @Column(name = "matched_via")
    private String matchedVia;

    @NotNull
    private Boolean manual = false;

    public NewsArticleConference() {
    }

    public NewsArticleConference(NewsArticle article, Conference conference, Double confidence,
                                 String matchedVia, boolean manual) {
        this.article = article;
        this.conference = conference;
        this.confidence = confidence;
        this.matchedVia = matchedVia;
        this.manual = manual;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public NewsArticle getArticle() {
        return article;
    }

    public void setArticle(NewsArticle article) {
        this.article = article;
    }

    public Conference getConference() {
        return conference;
    }

    public void setConference(Conference conference) {
        this.conference = conference;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getMatchedVia() {
        return matchedVia;
    }

    public void setMatchedVia(String matchedVia) {
        this.matchedVia = matchedVia;
    }

    public Boolean getManual() {
        return manual;
    }

    public void setManual(Boolean manual) {
        this.manual = manual;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NewsArticleConference that = (NewsArticleConference) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
