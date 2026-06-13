package com.yotto.basketball.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Long-format derived stat snapshot: one row per team × season × date × stat name.
 * Stats are defined by registry entries in code (see BoxScoreStatCalculator), so
 * adding a stat does not require a schema migration.
 */
@Entity
@Table(name = "team_stat_snapshots",
       uniqueConstraints = @UniqueConstraint(columnNames = {"team_id", "season_id", "snapshot_date", "stat_name"}))
public class TeamStatSnapshot {

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

    @NotNull
    @Column(name = "stat_name")
    private String statName;

    @NotNull
    private Double value;

    private int gamesPlayed;

    private Integer rank;

    private Double zscore;

    private Double confZscore;

    public TeamStatSnapshot() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Team getTeam() { return team; }
    public void setTeam(Team team) { this.team = team; }

    public Season getSeason() { return season; }
    public void setSeason(Season season) { this.season = season; }

    public LocalDate getSnapshotDate() { return snapshotDate; }
    public void setSnapshotDate(LocalDate snapshotDate) { this.snapshotDate = snapshotDate; }

    public String getStatName() { return statName; }
    public void setStatName(String statName) { this.statName = statName; }

    public Double getValue() { return value; }
    public void setValue(Double value) { this.value = value; }

    public int getGamesPlayed() { return gamesPlayed; }
    public void setGamesPlayed(int gamesPlayed) { this.gamesPlayed = gamesPlayed; }

    public Integer getRank() { return rank; }
    public void setRank(Integer rank) { this.rank = rank; }

    public Double getZscore() { return zscore; }
    public void setZscore(Double zscore) { this.zscore = zscore; }

    public Double getConfZscore() { return confZscore; }
    public void setConfZscore(Double confZscore) { this.confZscore = confZscore; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TeamStatSnapshot that = (TeamStatSnapshot) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }
}
