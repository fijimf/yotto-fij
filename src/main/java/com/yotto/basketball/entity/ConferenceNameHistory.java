package com.yotto.basketball.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Objects;

/**
 * A superseded branding of a conference (name/abbreviation/logo) and the last
 * {@code Season.year} (inclusive) it applied to. The {@link Conference} row
 * itself always carries the current branding; rows here preserve how the
 * conference was known in earlier seasons (e.g. WAC before the 2026-27 UAC
 * rebrand).
 */
@Entity
@Table(name = "conference_name_history",
       uniqueConstraints = @UniqueConstraint(columnNames = {"conference_id", "last_season_year"}))
public class ConferenceNameHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conference_id")
    private Conference conference;

    @NotBlank
    private String name;

    private String abbreviation;

    @Column(name = "logo_url")
    private String logoUrl;

    @NotNull
    @Column(name = "last_season_year")
    private Integer lastSeasonYear;

    public ConferenceNameHistory() {
    }

    public ConferenceNameHistory(Conference conference, String name, String abbreviation,
                                 String logoUrl, Integer lastSeasonYear) {
        this.conference = conference;
        this.name = name;
        this.abbreviation = abbreviation;
        this.logoUrl = logoUrl;
        this.lastSeasonYear = lastSeasonYear;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Conference getConference() {
        return conference;
    }

    public void setConference(Conference conference) {
        this.conference = conference;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAbbreviation() {
        return abbreviation;
    }

    public void setAbbreviation(String abbreviation) {
        this.abbreviation = abbreviation;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public void setLogoUrl(String logoUrl) {
        this.logoUrl = logoUrl;
    }

    public Integer getLastSeasonYear() {
        return lastSeasonYear;
    }

    public void setLastSeasonYear(Integer lastSeasonYear) {
        this.lastSeasonYear = lastSeasonYear;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConferenceNameHistory that = (ConferenceNameHistory) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ConferenceNameHistory{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", abbreviation='" + abbreviation + '\'' +
                ", lastSeasonYear=" + lastSeasonYear +
                '}';
    }
}
