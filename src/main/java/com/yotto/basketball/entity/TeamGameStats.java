package com.yotto.basketball.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "team_game_stats",
        uniqueConstraints = @UniqueConstraint(name = "uk_team_game_stats_game_team",
                columnNames = {"game_id", "team_id"}))
public class TeamGameStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @NotNull
    @Column(name = "home_away", nullable = false, length = 4)
    private String homeAway;

    @Column(name = "fg_made")
    private Integer fgMade;

    @Column(name = "fg_attempted")
    private Integer fgAttempted;

    @Column(name = "fg3_made")
    private Integer fg3Made;

    @Column(name = "fg3_attempted")
    private Integer fg3Attempted;

    @Column(name = "ft_made")
    private Integer ftMade;

    @Column(name = "ft_attempted")
    private Integer ftAttempted;

    @Column(name = "offensive_reb")
    private Integer offensiveReb;

    @Column(name = "defensive_reb")
    private Integer defensiveReb;

    @Column(name = "total_reb")
    private Integer totalReb;

    private Integer assists;
    private Integer steals;
    private Integer blocks;
    private Integer turnovers;
    private Integer fouls;

    @Column(name = "technical_fouls")
    private Integer technicalFouls;

    @Column(name = "flagrant_fouls")
    private Integer flagrantFouls;

    @Column(name = "largest_lead")
    private Integer largestLead;

    @Column(name = "points_in_paint")
    private Integer pointsInPaint;

    @Column(name = "fast_break_pts")
    private Integer fastBreakPts;

    @Column(name = "turnover_pts")
    private Integer turnoverPts;

    @NotNull
    @Column(name = "scrape_date", nullable = false)
    private LocalDateTime scrapeDate;

    public TeamGameStats() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Game getGame() { return game; }
    public void setGame(Game game) { this.game = game; }

    public Team getTeam() { return team; }
    public void setTeam(Team team) { this.team = team; }

    public String getHomeAway() { return homeAway; }
    public void setHomeAway(String homeAway) { this.homeAway = homeAway; }

    public Integer getFgMade() { return fgMade; }
    public void setFgMade(Integer fgMade) { this.fgMade = fgMade; }

    public Integer getFgAttempted() { return fgAttempted; }
    public void setFgAttempted(Integer fgAttempted) { this.fgAttempted = fgAttempted; }

    public Integer getFg3Made() { return fg3Made; }
    public void setFg3Made(Integer fg3Made) { this.fg3Made = fg3Made; }

    public Integer getFg3Attempted() { return fg3Attempted; }
    public void setFg3Attempted(Integer fg3Attempted) { this.fg3Attempted = fg3Attempted; }

    public Integer getFtMade() { return ftMade; }
    public void setFtMade(Integer ftMade) { this.ftMade = ftMade; }

    public Integer getFtAttempted() { return ftAttempted; }
    public void setFtAttempted(Integer ftAttempted) { this.ftAttempted = ftAttempted; }

    public Integer getOffensiveReb() { return offensiveReb; }
    public void setOffensiveReb(Integer offensiveReb) { this.offensiveReb = offensiveReb; }

    public Integer getDefensiveReb() { return defensiveReb; }
    public void setDefensiveReb(Integer defensiveReb) { this.defensiveReb = defensiveReb; }

    public Integer getTotalReb() { return totalReb; }
    public void setTotalReb(Integer totalReb) { this.totalReb = totalReb; }

    public Integer getAssists() { return assists; }
    public void setAssists(Integer assists) { this.assists = assists; }

    public Integer getSteals() { return steals; }
    public void setSteals(Integer steals) { this.steals = steals; }

    public Integer getBlocks() { return blocks; }
    public void setBlocks(Integer blocks) { this.blocks = blocks; }

    public Integer getTurnovers() { return turnovers; }
    public void setTurnovers(Integer turnovers) { this.turnovers = turnovers; }

    public Integer getFouls() { return fouls; }
    public void setFouls(Integer fouls) { this.fouls = fouls; }

    public Integer getTechnicalFouls() { return technicalFouls; }
    public void setTechnicalFouls(Integer technicalFouls) { this.technicalFouls = technicalFouls; }

    public Integer getFlagrantFouls() { return flagrantFouls; }
    public void setFlagrantFouls(Integer flagrantFouls) { this.flagrantFouls = flagrantFouls; }

    public Integer getLargestLead() { return largestLead; }
    public void setLargestLead(Integer largestLead) { this.largestLead = largestLead; }

    public Integer getPointsInPaint() { return pointsInPaint; }
    public void setPointsInPaint(Integer pointsInPaint) { this.pointsInPaint = pointsInPaint; }

    public Integer getFastBreakPts() { return fastBreakPts; }
    public void setFastBreakPts(Integer fastBreakPts) { this.fastBreakPts = fastBreakPts; }

    public Integer getTurnoverPts() { return turnoverPts; }
    public void setTurnoverPts(Integer turnoverPts) { this.turnoverPts = turnoverPts; }

    public LocalDateTime getScrapeDate() { return scrapeDate; }
    public void setScrapeDate(LocalDateTime scrapeDate) { this.scrapeDate = scrapeDate; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TeamGameStats that = (TeamGameStats) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "TeamGameStats{id=" + id + ", homeAway='" + homeAway + "'}";
    }
}
