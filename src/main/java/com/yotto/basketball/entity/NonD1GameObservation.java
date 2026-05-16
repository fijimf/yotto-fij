package com.yotto.basketball.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "non_d1_game_observations")
public class NonD1GameObservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(name = "espn_game_id", unique = true)
    private String espnGameId;

    @NotNull
    @Column(name = "season_year")
    private Integer seasonYear;

    @NotNull
    @Column(name = "scrape_date")
    private LocalDate scrapeDate;

    @Column(name = "game_date_utc")
    private LocalDateTime gameDateUtc;

    @NotBlank
    @Column(name = "home_espn_id")
    private String homeEspnId;

    @NotBlank
    @Column(name = "away_espn_id")
    private String awayEspnId;

    @NotBlank
    @Column(name = "unknown_team_espn_ids", columnDefinition = "TEXT")
    private String unknownTeamEspnIds;

    @NotNull
    @Column(name = "first_seen_at")
    private LocalDateTime firstSeenAt;

    @NotNull
    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    public NonD1GameObservation() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEspnGameId() {
        return espnGameId;
    }

    public void setEspnGameId(String espnGameId) {
        this.espnGameId = espnGameId;
    }

    public Integer getSeasonYear() {
        return seasonYear;
    }

    public void setSeasonYear(Integer seasonYear) {
        this.seasonYear = seasonYear;
    }

    public LocalDate getScrapeDate() {
        return scrapeDate;
    }

    public void setScrapeDate(LocalDate scrapeDate) {
        this.scrapeDate = scrapeDate;
    }

    public LocalDateTime getGameDateUtc() {
        return gameDateUtc;
    }

    public void setGameDateUtc(LocalDateTime gameDateUtc) {
        this.gameDateUtc = gameDateUtc;
    }

    public String getHomeEspnId() {
        return homeEspnId;
    }

    public void setHomeEspnId(String homeEspnId) {
        this.homeEspnId = homeEspnId;
    }

    public String getAwayEspnId() {
        return awayEspnId;
    }

    public void setAwayEspnId(String awayEspnId) {
        this.awayEspnId = awayEspnId;
    }

    public String getUnknownTeamEspnIds() {
        return unknownTeamEspnIds;
    }

    public void setUnknownTeamEspnIds(String unknownTeamEspnIds) {
        this.unknownTeamEspnIds = unknownTeamEspnIds;
    }

    public LocalDateTime getFirstSeenAt() {
        return firstSeenAt;
    }

    public void setFirstSeenAt(LocalDateTime firstSeenAt) {
        this.firstSeenAt = firstSeenAt;
    }

    public LocalDateTime getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(LocalDateTime lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NonD1GameObservation that = (NonD1GameObservation) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
