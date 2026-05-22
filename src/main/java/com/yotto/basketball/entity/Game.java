package com.yotto.basketball.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "games")
public class Game {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "espn_id", unique = true)
    private String espnId;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "home_team_id")
    private Team homeTeam;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "away_team_id")
    private Team awayTeam;

    @NotNull
    private LocalDateTime gameDate;

    private String venue;

    private Integer homeScore;

    private Integer awayScore;

    @Enumerated(EnumType.STRING)
    private GameStatus status;

    private Boolean neutralSite;

    private Boolean conferenceGame;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "season_id")
    private Season season;

    @OneToOne(mappedBy = "game", cascade = CascadeType.ALL)
    private BettingOdds bettingOdds;

    private Integer periods;

    private LocalDate scrapeDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "tournament_type")
    private TournamentType tournamentType;

    @Column(name = "tournament_name")
    private String tournamentName;

    @Column(name = "tournament_round")
    private String tournamentRound;

    @Column(name = "tournament_region")
    private String tournamentRegion;

    @Column(name = "espn_season_type")
    private Integer espnSeasonType;

    @Column(name = "espn_note_raw")
    private String espnNoteRaw;

    public enum GameStatus {
        SCHEDULED,
        IN_PROGRESS,
        FINAL,
        POSTPONED,
        CANCELLED
    }

    public enum TournamentType {
        NCAA_TOURNAMENT,
        NIT,
        CBI,
        CROWN,
        CONFERENCE_TOURNAMENT,
        IN_SEASON_TOURNAMENT,
        OTHER_POSTSEASON
    }

    public Game() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEspnId() {
        return espnId;
    }

    public void setEspnId(String espnId) {
        this.espnId = espnId;
    }

    public Team getHomeTeam() {
        return homeTeam;
    }

    public void setHomeTeam(Team homeTeam) {
        this.homeTeam = homeTeam;
    }

    public Team getAwayTeam() {
        return awayTeam;
    }

    public void setAwayTeam(Team awayTeam) {
        this.awayTeam = awayTeam;
    }

    public LocalDateTime getGameDate() {
        return gameDate;
    }

    public void setGameDate(LocalDateTime gameDate) {
        this.gameDate = gameDate;
    }

    public String getVenue() {
        return venue;
    }

    public void setVenue(String venue) {
        this.venue = venue;
    }

    public Integer getHomeScore() {
        return homeScore;
    }

    public void setHomeScore(Integer homeScore) {
        this.homeScore = homeScore;
    }

    public Integer getAwayScore() {
        return awayScore;
    }

    public void setAwayScore(Integer awayScore) {
        this.awayScore = awayScore;
    }

    public GameStatus getStatus() {
        return status;
    }

    public void setStatus(GameStatus status) {
        this.status = status;
    }

    public Boolean getNeutralSite() {
        return neutralSite;
    }

    public void setNeutralSite(Boolean neutralSite) {
        this.neutralSite = neutralSite;
    }

    public Boolean getConferenceGame() {
        return conferenceGame;
    }

    public void setConferenceGame(Boolean conferenceGame) {
        this.conferenceGame = conferenceGame;
    }

    public Season getSeason() {
        return season;
    }

    public void setSeason(Season season) {
        this.season = season;
    }

    public BettingOdds getBettingOdds() {
        return bettingOdds;
    }

    public void setBettingOdds(BettingOdds bettingOdds) {
        this.bettingOdds = bettingOdds;
    }

    public Integer getPeriods() {
        return periods;
    }

    public void setPeriods(Integer periods) {
        this.periods = periods;
    }

    public LocalDate getScrapeDate() {
        return scrapeDate;
    }

    public void setScrapeDate(LocalDate scrapeDate) {
        this.scrapeDate = scrapeDate;
    }

    public TournamentType getTournamentType() {
        return tournamentType;
    }

    public void setTournamentType(TournamentType tournamentType) {
        this.tournamentType = tournamentType;
    }

    public String getTournamentName() {
        return tournamentName;
    }

    public void setTournamentName(String tournamentName) {
        this.tournamentName = tournamentName;
    }

    public String getTournamentRound() {
        return tournamentRound;
    }

    public void setTournamentRound(String tournamentRound) {
        this.tournamentRound = tournamentRound;
    }

    public String getTournamentRegion() {
        return tournamentRegion;
    }

    public void setTournamentRegion(String tournamentRegion) {
        this.tournamentRegion = tournamentRegion;
    }

    public Integer getEspnSeasonType() {
        return espnSeasonType;
    }

    public void setEspnSeasonType(Integer espnSeasonType) {
        this.espnSeasonType = espnSeasonType;
    }

    public String getEspnNoteRaw() {
        return espnNoteRaw;
    }

    public void setEspnNoteRaw(String espnNoteRaw) {
        this.espnNoteRaw = espnNoteRaw;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Game game = (Game) o;
        return Objects.equals(id, game.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Game{" +
                "id=" + id +
                ", espnId='" + espnId + '\'' +
                ", gameDate=" + gameDate +
                ", venue='" + venue + '\'' +
                ", homeScore=" + homeScore +
                ", awayScore=" + awayScore +
                ", status=" + status +
                '}';
    }
}
