package com.yotto.basketball.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.Objects;

@Entity
@Table(name = "season_population_stats")
public class SeasonPopulationStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "season_id")
    private Season season;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conference_id")
    private Conference conference;  // null = league-wide

    @NotNull
    private LocalDate statDate;

    @NotNull
    private String statName;

    private Double popMean;
    private Double popStddev;
    private Double popMin;
    private Double popMax;
    private int teamCount;

    public SeasonPopulationStat() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Season getSeason() { return season; }
    public void setSeason(Season season) { this.season = season; }

    public Conference getConference() { return conference; }
    public void setConference(Conference conference) { this.conference = conference; }

    public LocalDate getStatDate() { return statDate; }
    public void setStatDate(LocalDate statDate) { this.statDate = statDate; }

    public String getStatName() { return statName; }
    public void setStatName(String statName) { this.statName = statName; }

    public Double getPopMean() { return popMean; }
    public void setPopMean(Double popMean) { this.popMean = popMean; }

    public Double getPopStddev() { return popStddev; }
    public void setPopStddev(Double popStddev) { this.popStddev = popStddev; }

    public Double getPopMin() { return popMin; }
    public void setPopMin(Double popMin) { this.popMin = popMin; }

    public Double getPopMax() { return popMax; }
    public void setPopMax(Double popMax) { this.popMax = popMax; }

    public int getTeamCount() { return teamCount; }
    public void setTeamCount(int teamCount) { this.teamCount = teamCount; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SeasonPopulationStat that = (SeasonPopulationStat) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }
}
