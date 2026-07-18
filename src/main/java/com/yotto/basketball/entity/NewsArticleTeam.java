package com.yotto.basketball.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.util.Objects;

@Entity
@Table(name = "news_article_teams",
        uniqueConstraints = @UniqueConstraint(columnNames = {"article_id", "team_id"}))
public class NewsArticleTeam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id")
    private NewsArticle article;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @NotNull
    private Double confidence;

    @Column(name = "matched_via")
    private String matchedVia;

    @NotNull
    private Boolean manual = false;

    public NewsArticleTeam() {
    }

    public NewsArticleTeam(NewsArticle article, Team team, Double confidence, String matchedVia, boolean manual) {
        this.article = article;
        this.team = team;
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

    public Team getTeam() {
        return team;
    }

    public void setTeam(Team team) {
        this.team = team;
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
        NewsArticleTeam that = (NewsArticleTeam) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
