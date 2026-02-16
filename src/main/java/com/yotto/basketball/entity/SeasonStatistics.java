package com.yotto.basketball.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.util.Objects;

@Entity
@Table(name = "season_statistics",
       uniqueConstraints = @UniqueConstraint(columnNames = {"team_id", "season_id"}))
public class SeasonStatistics {

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
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conference_id")
    private Conference conference;

    private Integer wins;
    private Integer losses;
    private Integer conferenceWins;
    private Integer conferenceLosses;
    private Integer homeWins;
    private Integer homeLosses;
    private Integer roadWins;
    private Integer roadLosses;
    private Integer pointsFor;
    private Integer pointsAgainst;
    private Integer streak;
    private Integer conferenceStanding;

    public SeasonStatistics() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Team getTeam() {
        return team;
    }

    public void setTeam(Team team) {
        this.team = team;
    }

    public Season getSeason() {
        return season;
    }

    public void setSeason(Season season) {
        this.season = season;
    }

    public Conference getConference() {
        return conference;
    }

    public void setConference(Conference conference) {
        this.conference = conference;
    }

    public Integer getWins() {
        return wins;
    }

    public void setWins(Integer wins) {
        this.wins = wins;
    }

    public Integer getLosses() {
        return losses;
    }

    public void setLosses(Integer losses) {
        this.losses = losses;
    }

    public Integer getConferenceWins() {
        return conferenceWins;
    }

    public void setConferenceWins(Integer conferenceWins) {
        this.conferenceWins = conferenceWins;
    }

    public Integer getConferenceLosses() {
        return conferenceLosses;
    }

    public void setConferenceLosses(Integer conferenceLosses) {
        this.conferenceLosses = conferenceLosses;
    }

    public Integer getHomeWins() {
        return homeWins;
    }

    public void setHomeWins(Integer homeWins) {
        this.homeWins = homeWins;
    }

    public Integer getHomeLosses() {
        return homeLosses;
    }

    public void setHomeLosses(Integer homeLosses) {
        this.homeLosses = homeLosses;
    }

    public Integer getRoadWins() {
        return roadWins;
    }

    public void setRoadWins(Integer roadWins) {
        this.roadWins = roadWins;
    }

    public Integer getRoadLosses() {
        return roadLosses;
    }

    public void setRoadLosses(Integer roadLosses) {
        this.roadLosses = roadLosses;
    }

    public Integer getPointsFor() {
        return pointsFor;
    }

    public void setPointsFor(Integer pointsFor) {
        this.pointsFor = pointsFor;
    }

    public Integer getPointsAgainst() {
        return pointsAgainst;
    }

    public void setPointsAgainst(Integer pointsAgainst) {
        this.pointsAgainst = pointsAgainst;
    }

    public Integer getStreak() {
        return streak;
    }

    public void setStreak(Integer streak) {
        this.streak = streak;
    }

    public Integer getConferenceStanding() {
        return conferenceStanding;
    }

    public void setConferenceStanding(Integer conferenceStanding) {
        this.conferenceStanding = conferenceStanding;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SeasonStatistics that = (SeasonStatistics) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "SeasonStatistics{" +
                "id=" + id +
                ", wins=" + wins +
                ", losses=" + losses +
                '}';
    }
}
