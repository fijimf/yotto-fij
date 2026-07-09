package com.yotto.basketball.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yotto.basketball.controller.dto.ChartDataDto;
import com.yotto.basketball.controller.dto.LastMeetingDto;
import com.yotto.basketball.controller.dto.SeasonGameMarkerDto;
import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.*;
import com.yotto.basketball.service.ConferenceNamingService;
import com.yotto.basketball.service.PredictionResult;
import com.yotto.basketball.service.PredictionService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
public class GameDetailController {

    private final GameRepository gameRepository;
    private final PredictionService predictionService;
    private final SeasonStatisticsRepository seasonStatsRepository;
    private final TeamPowerRatingSnapshotRepository powerRatingRepository;
    private final TeamSeasonStatSnapshotRepository statSnapshotRepository;
    private final TeamStatSnapshotRepository derivedStatRepository;
    private final ObjectMapper objectMapper;
    private final TournamentBadgeFormatter tournamentBadgeFormatter;
    private final ConferenceNamingService namingService;

    public GameDetailController(GameRepository gameRepository,
                                PredictionService predictionService,
                                SeasonStatisticsRepository seasonStatsRepository,
                                TeamPowerRatingSnapshotRepository powerRatingRepository,
                                TeamSeasonStatSnapshotRepository statSnapshotRepository,
                                TeamStatSnapshotRepository derivedStatRepository,
                                ObjectMapper objectMapper,
                                TournamentBadgeFormatter tournamentBadgeFormatter,
                                ConferenceNamingService namingService) {
        this.gameRepository = gameRepository;
        this.predictionService = predictionService;
        this.seasonStatsRepository = seasonStatsRepository;
        this.powerRatingRepository = powerRatingRepository;
        this.statSnapshotRepository = statSnapshotRepository;
        this.derivedStatRepository = derivedStatRepository;
        this.objectMapper = objectMapper;
        this.tournamentBadgeFormatter = tournamentBadgeFormatter;
        this.namingService = namingService;
    }

    @GetMapping("/games/{id}")
    public String gameDetail(@PathVariable Long id, Model model) {
        Game game = gameRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new EntityNotFoundException("Game not found: " + id));

        Team home = game.getHomeTeam();
        Team away = game.getAwayTeam();
        BettingOdds odds = game.getBettingOdds();
        Season season = game.getSeason();
        Long seasonId = season.getId();
        LocalDate gameLocalDate = game.getGameDate().toLocalDate();
        LocalDateTime gameDateTime = game.getGameDate();

        // ── Basic game attributes ───────────────────────────────────────────────
        model.addAttribute("gameId", game.getId());
        model.addAttribute("homeTeamId", home.getId());
        model.addAttribute("homeTeamName", home.getName());
        model.addAttribute("homeTeamAbbr", home.getAbbreviation());
        model.addAttribute("homeTeamMascot", home.getMascot());
        model.addAttribute("homeTeamLogoUrl", home.getLogoUrl());
        model.addAttribute("homeTeamColor", home.getColor());
        model.addAttribute("homeTeamSlug", home.getSlug());
        model.addAttribute("awayTeamId", away.getId());
        model.addAttribute("awayTeamName", away.getName());
        model.addAttribute("awayTeamAbbr", away.getAbbreviation());
        model.addAttribute("awayTeamMascot", away.getMascot());
        model.addAttribute("awayTeamLogoUrl", away.getLogoUrl());
        model.addAttribute("awayTeamColor", away.getColor());
        model.addAttribute("awayTeamSlug", away.getSlug());
        model.addAttribute("gameDate", game.getGameDate());
        model.addAttribute("venue", game.getVenue());
        model.addAttribute("neutralSite", Boolean.TRUE.equals(game.getNeutralSite()));
        model.addAttribute("conferenceGame", Boolean.TRUE.equals(game.getConferenceGame()));
        model.addAttribute("status", game.getStatus());
        model.addAttribute("homeScore", game.getHomeScore());
        model.addAttribute("awayScore", game.getAwayScore());
        model.addAttribute("periods", game.getPeriods());
        model.addAttribute("odds", odds);
        model.addAttribute("homeSeed", game.getHomeSeed());
        model.addAttribute("awaySeed", game.getAwaySeed());
        model.addAttribute("tournamentBadge", tournamentBadgeFormatter.format(game));
        model.addAttribute("tournamentName", game.getTournamentName());
        model.addAttribute("tournamentRound", game.getTournamentRound());
        model.addAttribute("tournamentRegion", game.getTournamentRegion());

        // ── Predictions ─────────────────────────────────────────────────────────
        PredictionResult prediction = predictionService.predict(id);
        model.addAttribute("prediction", prediction);

        // ── Season statistics (includes conference for header) ───────────────────
        SeasonStatistics homeStats = seasonStatsRepository
                .findByTeamAndSeasonWithConference(home.getId(), seasonId).orElse(null);
        SeasonStatistics awayStats = seasonStatsRepository
                .findByTeamAndSeasonWithConference(away.getId(), seasonId).orElse(null);

        String conferenceName = null;
        if (Boolean.TRUE.equals(game.getConferenceGame()) && homeStats != null) {
            conferenceName = namingService.resolve(homeStats.getConference(), season.getYear()).name();
        }
        model.addAttribute("conferenceName", conferenceName);
        model.addAttribute("homeStats", homeStats);
        model.addAttribute("awayStats", awayStats);

        // ── Last 5 meetings ─────────────────────────────────────────────────────
        List<Game> h2hGames = gameRepository.findAllH2HGames(home.getId(), away.getId(), id);
        List<LastMeetingDto> lastMeetings = h2hGames.stream()
                .limit(5)
                .map(this::toLastMeeting)
                .toList();
        model.addAttribute("lastMeetings", lastMeetings);

        // ── Neutral-site record this season ─────────────────────────────────────
        List<Game> homeNeutral = gameRepository.findNeutralSiteFinalGames(home.getId(), seasonId);
        List<Game> awayNeutral = gameRepository.findNeutralSiteFinalGames(away.getId(), seasonId);
        model.addAttribute("homeNeutralWins", countWins(homeNeutral, home.getId()));
        model.addAttribute("homeNeutralLosses", countLosses(homeNeutral, home.getId()));
        model.addAttribute("awayNeutralWins", countWins(awayNeutral, away.getId()));
        model.addAttribute("awayNeutralLosses", countLosses(awayNeutral, away.getId()));

        // ── Last-5-games record ─────────────────────────────────────────────────
        List<Game> homeLast5 = gameRepository.findRecentFinalGamesForTeam(
                home.getId(), gameDateTime, PageRequest.of(0, 5));
        List<Game> awayLast5 = gameRepository.findRecentFinalGamesForTeam(
                away.getId(), gameDateTime, PageRequest.of(0, 5));
        model.addAttribute("homeLast5Wins", countWins(homeLast5, home.getId()));
        model.addAttribute("homeLast5Losses", countLosses(homeLast5, home.getId()));
        model.addAttribute("awayLast5Wins", countWins(awayLast5, away.getId()));
        model.addAttribute("awayLast5Losses", countLosses(awayLast5, away.getId()));

        // ── Power ratings ────────────────────────────────────────────────────────
        TeamPowerRatingSnapshot homeMassey = powerRatingRepository
                .findLatestBefore(home.getId(), seasonId, "MASSEY", gameLocalDate).orElse(null);
        TeamPowerRatingSnapshot awayMassey = powerRatingRepository
                .findLatestBefore(away.getId(), seasonId, "MASSEY", gameLocalDate).orElse(null);
        TeamPowerRatingSnapshot homeBT = powerRatingRepository
                .findLatestBefore(home.getId(), seasonId, "BRADLEY_TERRY", gameLocalDate).orElse(null);
        TeamPowerRatingSnapshot awayBT = powerRatingRepository
                .findLatestBefore(away.getId(), seasonId, "BRADLEY_TERRY", gameLocalDate).orElse(null);
        TeamPowerRatingSnapshot homeBTW = powerRatingRepository
                .findLatestBefore(home.getId(), seasonId, "BRADLEY_TERRY_W", gameLocalDate).orElse(null);
        TeamPowerRatingSnapshot awayBTW = powerRatingRepository
                .findLatestBefore(away.getId(), seasonId, "BRADLEY_TERRY_W", gameLocalDate).orElse(null);
        model.addAttribute("homeMassey", homeMassey);
        model.addAttribute("awayMassey", awayMassey);
        model.addAttribute("homeBT", homeBT);
        model.addAttribute("awayBT", awayBT);
        model.addAttribute("homeBTW", homeBTW);
        model.addAttribute("awayBTW", awayBTW);

        // ── Stat snapshots (for RPI, ellipse, marginals) ─────────────────────────
        TeamSeasonStatSnapshot homeSnap = statSnapshotRepository
                .findLatestBefore(home.getId(), seasonId, gameLocalDate).orElse(null);
        TeamSeasonStatSnapshot awaySnap = statSnapshotRepository
                .findLatestBefore(away.getId(), seasonId, gameLocalDate).orElse(null);
        model.addAttribute("homeSnap", homeSnap);
        model.addAttribute("awaySnap", awaySnap);

        // ── Derived box-score stats (shooting, rebounding, four factors) ─────────
        model.addAttribute("homeDerived", derivedStatsByName(home.getId(), seasonId, gameLocalDate));
        model.addAttribute("awayDerived", derivedStatsByName(away.getId(), seasonId, gameLocalDate));

        // RPI rank: find all snaps on the same date, sort by RPI desc, determine rank
        Integer homeRpiRank = null;
        Integer awayRpiRank = null;
        if (homeSnap != null && homeSnap.getRpi() != null) {
            List<TeamSeasonStatSnapshot> allSnaps = statSnapshotRepository
                    .findBySeasonAndDate(seasonId, homeSnap.getSnapshotDate());
            homeRpiRank = 1 + (int) allSnaps.stream()
                    .filter(s -> s.getRpi() != null && s.getRpi() > homeSnap.getRpi())
                    .count();
        }
        if (awaySnap != null && awaySnap.getRpi() != null) {
            List<TeamSeasonStatSnapshot> allSnaps = statSnapshotRepository
                    .findBySeasonAndDate(seasonId, awaySnap.getSnapshotDate());
            awayRpiRank = 1 + (int) allSnaps.stream()
                    .filter(s -> s.getRpi() != null && s.getRpi() > awaySnap.getRpi())
                    .count();
        }
        model.addAttribute("homeRpiRank", homeRpiRank);
        model.addAttribute("awayRpiRank", awayRpiRank);

        // ── Chart data ──────────────────────────────────────────────────────────
        List<Game> homeSeasonGames = gameRepository.findSeasonFinalGamesForTeamBefore(
                home.getId(), seasonId, gameDateTime);
        List<Game> awaySeasonGames = gameRepository.findSeasonFinalGamesForTeamBefore(
                away.getId(), seasonId, gameDateTime);

        int[] homeForArr = homeSeasonGames.stream()
                .mapToInt(g -> g.getHomeTeam().getId().equals(home.getId()) ? g.getHomeScore() : g.getAwayScore())
                .toArray();
        int[] homeAgainstArr = homeSeasonGames.stream()
                .mapToInt(g -> g.getHomeTeam().getId().equals(home.getId()) ? g.getAwayScore() : g.getHomeScore())
                .toArray();
        int[] awayForArr = awaySeasonGames.stream()
                .mapToInt(g -> g.getHomeTeam().getId().equals(away.getId()) ? g.getHomeScore() : g.getAwayScore())
                .toArray();
        int[] awayAgainstArr = awaySeasonGames.stream()
                .mapToInt(g -> g.getHomeTeam().getId().equals(away.getId()) ? g.getAwayScore() : g.getHomeScore())
                .toArray();

        int[] homeForIqr = computeIqr(homeForArr);
        int[] homeAgainstIqr = computeIqr(homeAgainstArr);
        int[] awayForIqr = computeIqr(awayForArr);
        int[] awayAgainstIqr = computeIqr(awayAgainstArr);

        double homeAvgFor = avg(homeStats != null ? homeStats.getCalcPointsFor() : null,
                                homeStats != null ? totalGames(homeStats) : 0);
        double homeAvgAgainst = avg(homeStats != null ? homeStats.getCalcPointsAgainst() : null,
                                    homeStats != null ? totalGames(homeStats) : 0);
        double awayAvgFor = avg(awayStats != null ? awayStats.getCalcPointsFor() : null,
                                awayStats != null ? totalGames(awayStats) : 0);
        double awayAvgAgainst = avg(awayStats != null ? awayStats.getCalcPointsAgainst() : null,
                                    awayStats != null ? totalGames(awayStats) : 0);

        ChartDataDto chartData = new ChartDataDto(
                home.getAbbreviation(), away.getAbbreviation(),
                home.getColor(), away.getColor(),
                home.getLogoUrl(), away.getLogoUrl(),
                home.getName(), away.getName(),
                game.getHomeScore(), game.getAwayScore(),
                odds != null ? (odds.getSpread() != null ? odds.getSpread().doubleValue() : null) : null,
                odds != null ? (odds.getOverUnder() != null ? odds.getOverUnder().doubleValue() : null) : null,
                homeAvgFor, homeAvgAgainst, awayAvgFor, awayAvgAgainst,
                homeForIqr != null ? homeForIqr[0] : null,
                homeForIqr != null ? homeForIqr[1] : null,
                homeAgainstIqr != null ? homeAgainstIqr[0] : null,
                homeAgainstIqr != null ? homeAgainstIqr[1] : null,
                awayForIqr != null ? awayForIqr[0] : null,
                awayForIqr != null ? awayForIqr[1] : null,
                awayAgainstIqr != null ? awayAgainstIqr[0] : null,
                awayAgainstIqr != null ? awayAgainstIqr[1] : null,
                homeSnap != null ? homeSnap.getMeanPtsFor() : null,
                homeSnap != null ? homeSnap.getStddevPtsFor() : null,
                homeSnap != null ? homeSnap.getMeanPtsAgainst() : null,
                homeSnap != null ? homeSnap.getStddevPtsAgainst() : null,
                homeSnap != null ? homeSnap.getCorrelationPts() : null,
                awaySnap != null ? awaySnap.getMeanPtsFor() : null,
                awaySnap != null ? awaySnap.getStddevPtsFor() : null,
                awaySnap != null ? awaySnap.getMeanPtsAgainst() : null,
                awaySnap != null ? awaySnap.getStddevPtsAgainst() : null,
                awaySnap != null ? awaySnap.getCorrelationPts() : null,
                toMarkers(homeSeasonGames, home.getId()),
                toMarkers(awaySeasonGames, away.getId())
        );

        try {
            model.addAttribute("chartDataJson", objectMapper.writeValueAsString(chartData));
        } catch (JsonProcessingException e) {
            model.addAttribute("chartDataJson", "null");
        }

        model.addAttribute("currentPage", "games");
        return "pages/game-detail";
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /** Latest pre-game derived stats for a team, keyed by stat name for template lookup. */
    private Map<String, TeamStatSnapshot> derivedStatsByName(Long teamId, Long seasonId, LocalDate beforeDate) {
        Map<String, TeamStatSnapshot> byName = new HashMap<>();
        for (TeamStatSnapshot s : derivedStatRepository.findLatestBefore(teamId, seasonId, beforeDate)) {
            byName.put(s.getStatName(), s);
        }
        return byName;
    }

    private boolean isWinner(Game g, Long teamId) {
        if (g.getHomeScore() == null || g.getAwayScore() == null) return false;
        if (teamId.equals(g.getHomeTeam().getId())) return g.getHomeScore() > g.getAwayScore();
        return g.getAwayScore() > g.getHomeScore();
    }

    private int countWins(List<Game> games, Long teamId) {
        return (int) games.stream().filter(g -> isWinner(g, teamId)).count();
    }

    private int countLosses(List<Game> games, Long teamId) {
        return (int) games.stream()
                .filter(g -> g.getHomeScore() != null && g.getAwayScore() != null)
                .filter(g -> !isWinner(g, teamId))
                .count();
    }

    private LastMeetingDto toLastMeeting(Game g) {
        boolean homeWon = g.getHomeScore() > g.getAwayScore();
        return new LastMeetingDto(
                g.getId(),
                g.getGameDate().toLocalDate(),
                homeWon ? g.getHomeTeam().getAbbreviation() : g.getAwayTeam().getAbbreviation(),
                homeWon ? g.getHomeScore() : g.getAwayScore(),
                homeWon ? g.getAwayScore() : g.getHomeScore(),
                homeWon ? g.getAwayTeam().getAbbreviation() : g.getHomeTeam().getAbbreviation()
        );
    }

    private List<SeasonGameMarkerDto> toMarkers(List<Game> games, Long teamId) {
        return games.stream().map(g -> {
            boolean isHome = g.getHomeTeam().getId().equals(teamId);
            int teamScore = isHome ? g.getHomeScore() : g.getAwayScore();
            int oppScore = isHome ? g.getAwayScore() : g.getHomeScore();
            String oppAbbr = isHome ? g.getAwayTeam().getAbbreviation() : g.getHomeTeam().getAbbreviation();
            boolean win = teamScore > oppScore;
            return new SeasonGameMarkerDto(g.getId(), g.getGameDate().toLocalDate().toString(),
                    teamScore, oppScore, oppAbbr, win);
        }).toList();
    }

    /** Returns [Q1, Q3] for the given array, or null if fewer than 4 values. */
    private int[] computeIqr(int[] values) {
        if (values.length < 4) return null;
        int[] sorted = Arrays.copyOf(values, values.length);
        Arrays.sort(sorted);
        int q1 = sorted[sorted.length / 4];
        int q3 = sorted[sorted.length * 3 / 4];
        return new int[]{q1, q3};
    }

    private int totalGames(SeasonStatistics s) {
        int w = s.getCalcWins() != null ? s.getCalcWins() : 0;
        int l = s.getCalcLosses() != null ? s.getCalcLosses() : 0;
        return w + l;
    }

    private double avg(Integer total, int games) {
        if (total == null || games == 0) return 0.0;
        return (double) total / games;
    }
}
