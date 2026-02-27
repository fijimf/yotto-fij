package com.yotto.basketball.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.Objects;

@Entity
@Table(name = "team_season_stat_snapshots",
       uniqueConstraints = @UniqueConstraint(columnNames = {"team_id", "season_id", "snapshot_date"}))
public class TeamSeasonStatSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "season_id")
    private Season season;

    @NotNull
    private LocalDate snapshotDate;

    private int gamesPlayed;
    private int wins;
    private int losses;

    private Double winPct;
    private Double meanPtsFor;
    private Double stddevPtsFor;
    private Double meanPtsAgainst;
    private Double stddevPtsAgainst;
    private Double correlationPts;
    private Double meanMargin;
    private Double stddevMargin;

    private Integer rollingWins;
    private Integer rollingLosses;
    private Double rollingMeanPtsFor;
    private Double rollingMeanPtsAgainst;

    // League-wide z-scores
    private Double zscoreWinPct;
    private Double zscoreMeanPtsFor;
    private Double zscoreMeanPtsAgainst;
    private Double zscoreMeanMargin;
    private Double zscoreCorrelationPts;

    // Conference z-scores
    private Double confZscoreWinPct;
    private Double confZscoreMeanPtsFor;
    private Double confZscoreMeanPtsAgainst;
    private Double confZscoreMeanMargin;

    public TeamSeasonStatSnapshot() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Team getTeam() { return team; }
    public void setTeam(Team team) { this.team = team; }

    public Season getSeason() { return season; }
    public void setSeason(Season season) { this.season = season; }

    public LocalDate getSnapshotDate() { return snapshotDate; }
    public void setSnapshotDate(LocalDate snapshotDate) { this.snapshotDate = snapshotDate; }

    public int getGamesPlayed() { return gamesPlayed; }
    public void setGamesPlayed(int gamesPlayed) { this.gamesPlayed = gamesPlayed; }

    public int getWins() { return wins; }
    public void setWins(int wins) { this.wins = wins; }

    public int getLosses() { return losses; }
    public void setLosses(int losses) { this.losses = losses; }

    public Double getWinPct() { return winPct; }
    public void setWinPct(Double winPct) { this.winPct = winPct; }

    public Double getMeanPtsFor() { return meanPtsFor; }
    public void setMeanPtsFor(Double meanPtsFor) { this.meanPtsFor = meanPtsFor; }

    public Double getStddevPtsFor() { return stddevPtsFor; }
    public void setStddevPtsFor(Double stddevPtsFor) { this.stddevPtsFor = stddevPtsFor; }

    public Double getMeanPtsAgainst() { return meanPtsAgainst; }
    public void setMeanPtsAgainst(Double meanPtsAgainst) { this.meanPtsAgainst = meanPtsAgainst; }

    public Double getStddevPtsAgainst() { return stddevPtsAgainst; }
    public void setStddevPtsAgainst(Double stddevPtsAgainst) { this.stddevPtsAgainst = stddevPtsAgainst; }

    public Double getCorrelationPts() { return correlationPts; }
    public void setCorrelationPts(Double correlationPts) { this.correlationPts = correlationPts; }

    public Double getMeanMargin() { return meanMargin; }
    public void setMeanMargin(Double meanMargin) { this.meanMargin = meanMargin; }

    public Double getStddevMargin() { return stddevMargin; }
    public void setStddevMargin(Double stddevMargin) { this.stddevMargin = stddevMargin; }

    public Integer getRollingWins() { return rollingWins; }
    public void setRollingWins(Integer rollingWins) { this.rollingWins = rollingWins; }

    public Integer getRollingLosses() { return rollingLosses; }
    public void setRollingLosses(Integer rollingLosses) { this.rollingLosses = rollingLosses; }

    public Double getRollingMeanPtsFor() { return rollingMeanPtsFor; }
    public void setRollingMeanPtsFor(Double rollingMeanPtsFor) { this.rollingMeanPtsFor = rollingMeanPtsFor; }

    public Double getRollingMeanPtsAgainst() { return rollingMeanPtsAgainst; }
    public void setRollingMeanPtsAgainst(Double rollingMeanPtsAgainst) { this.rollingMeanPtsAgainst = rollingMeanPtsAgainst; }

    public Double getZscoreWinPct() { return zscoreWinPct; }
    public void setZscoreWinPct(Double zscoreWinPct) { this.zscoreWinPct = zscoreWinPct; }

    public Double getZscoreMeanPtsFor() { return zscoreMeanPtsFor; }
    public void setZscoreMeanPtsFor(Double zscoreMeanPtsFor) { this.zscoreMeanPtsFor = zscoreMeanPtsFor; }

    public Double getZscoreMeanPtsAgainst() { return zscoreMeanPtsAgainst; }
    public void setZscoreMeanPtsAgainst(Double zscoreMeanPtsAgainst) { this.zscoreMeanPtsAgainst = zscoreMeanPtsAgainst; }

    public Double getZscoreMeanMargin() { return zscoreMeanMargin; }
    public void setZscoreMeanMargin(Double zscoreMeanMargin) { this.zscoreMeanMargin = zscoreMeanMargin; }

    public Double getZscoreCorrelationPts() { return zscoreCorrelationPts; }
    public void setZscoreCorrelationPts(Double zscoreCorrelationPts) { this.zscoreCorrelationPts = zscoreCorrelationPts; }

    public Double getConfZscoreWinPct() { return confZscoreWinPct; }
    public void setConfZscoreWinPct(Double confZscoreWinPct) { this.confZscoreWinPct = confZscoreWinPct; }

    public Double getConfZscoreMeanPtsFor() { return confZscoreMeanPtsFor; }
    public void setConfZscoreMeanPtsFor(Double confZscoreMeanPtsFor) { this.confZscoreMeanPtsFor = confZscoreMeanPtsFor; }

    public Double getConfZscoreMeanPtsAgainst() { return confZscoreMeanPtsAgainst; }
    public void setConfZscoreMeanPtsAgainst(Double confZscoreMeanPtsAgainst) { this.confZscoreMeanPtsAgainst = confZscoreMeanPtsAgainst; }

    public Double getConfZscoreMeanMargin() { return confZscoreMeanMargin; }
    public void setConfZscoreMeanMargin(Double confZscoreMeanMargin) { this.confZscoreMeanMargin = confZscoreMeanMargin; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TeamSeasonStatSnapshot that = (TeamSeasonStatSnapshot) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }
}
