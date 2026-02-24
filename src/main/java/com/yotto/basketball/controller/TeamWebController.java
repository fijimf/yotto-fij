package com.yotto.basketball.controller;

import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.SeasonStatisticsRepository;
import com.yotto.basketball.repository.TeamRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Controller
public class TeamWebController {

    private final TeamRepository teamRepository;
    private final SeasonRepository seasonRepository;
    private final GameRepository gameRepository;
    private final SeasonStatisticsRepository seasonStatisticsRepository;

    public TeamWebController(TeamRepository teamRepository,
                             SeasonRepository seasonRepository,
                             GameRepository gameRepository,
                             SeasonStatisticsRepository seasonStatisticsRepository) {
        this.teamRepository = teamRepository;
        this.seasonRepository = seasonRepository;
        this.gameRepository = gameRepository;
        this.seasonStatisticsRepository = seasonStatisticsRepository;
    }

    // ── Teams listing ──

    @GetMapping("/teams")
    public String teams(Model model) {
        Optional<Season> latestSeason = seasonRepository.findTopByOrderByYearDesc();

        List<TeamSummary> teamSummaries;
        Integer seasonYear = null;

        if (latestSeason.isPresent()) {
            Season season = latestSeason.get();
            seasonYear = season.getYear();

            List<SeasonStatistics> statsForSeason =
                    seasonStatisticsRepository.findBySeasonIdWithTeamAndConference(season.getId());
            Map<Long, SeasonStatistics> statsByTeamId = statsForSeason.stream()
                    .collect(Collectors.toMap(ss -> ss.getTeam().getId(), ss -> ss));

            List<Team> allTeams = teamRepository.findAll();

            teamSummaries = allTeams.stream()
                    .map(team -> {
                        SeasonStatistics ss = statsByTeamId.get(team.getId());
                        Conference conf = ss != null ? ss.getConference() : null;
                        return new TeamSummary(
                                team.getId(),
                                team.getName(),
                                team.getNickname(),
                                team.getMascot(),
                                team.getLogoUrl(),
                                team.getColor(),
                                conf != null ? conf.getName() : null,
                                conf != null ? conf.getAbbreviation() : null,
                                conf != null ? conf.getLogoUrl() : null,
                                resolveInt(ss != null ? ss.getCalcWins() : null, ss != null ? ss.getWins() : null),
                                resolveInt(ss != null ? ss.getCalcLosses() : null, ss != null ? ss.getLosses() : null),
                                resolveInt(ss != null ? ss.getCalcConferenceWins() : null, ss != null ? ss.getConferenceWins() : null),
                                resolveInt(ss != null ? ss.getCalcConferenceLosses() : null, ss != null ? ss.getConferenceLosses() : null)
                        );
                    })
                    .sorted(Comparator.comparing(
                            (TeamSummary ts) -> ts.conferenceName() != null ? ts.conferenceName() : "zzz")
                            .thenComparing(ts -> ts.name() != null ? ts.name() : ""))
                    .toList();
        } else {
            List<Team> allTeams = teamRepository.findAll();
            teamSummaries = allTeams.stream()
                    .map(team -> new TeamSummary(
                            team.getId(), team.getName(), team.getNickname(),
                            team.getMascot(), team.getLogoUrl(), team.getColor(),
                            null, null, null, null, null, null, null))
                    .sorted(Comparator.comparing(ts -> ts.name() != null ? ts.name() : ""))
                    .toList();
        }

        Map<ConferenceInfo, List<TeamSummary>> confGroups = teamSummaries.stream()
                .collect(Collectors.groupingBy(
                        ts -> new ConferenceInfo(
                                ts.conferenceName() != null ? ts.conferenceName() : "Independent",
                                ts.conferenceLogoUrl()),
                        () -> new TreeMap<>(Comparator.comparing(ConferenceInfo::name)),
                        Collectors.toList()));

        model.addAttribute("currentPage", "teams");
        model.addAttribute("conferenceGroups", confGroups);
        model.addAttribute("teamCount", teamSummaries.size());
        model.addAttribute("seasonYear", seasonYear);

        return "pages/teams";
    }

    // ── Team detail ──

    @GetMapping("/teams/{id}")
    public String teamDetail(@PathVariable Long id, Model model) {
        Team team = teamRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Team not found: " + id));

        // Seasons this team has games in
        List<Season> seasons = gameRepository.findSeasonsByTeamId(id);
        Season currentSeason = seasons.isEmpty() ? null : seasons.get(0);

        // All stats rows for this team across all seasons (single query)
        List<SeasonStatistics> teamStats = seasonStatisticsRepository.findByTeamIdWithSeasonAndConference(id);
        Map<Integer, SeasonStatistics> statsByYear = teamStats.stream()
                .collect(Collectors.toMap(ss -> ss.getSeason().getYear(), ss -> ss));

        // Current conference from most recent stats row
        Conference currentConference = teamStats.isEmpty() ? null : teamStats.get(0).getConference();

        // Only load current season (lazy-load historical seasons via HTMX)
        SeasonSchedule currentSeasonSchedule = null;
        if (currentSeason != null) {
            SeasonStatistics ss = statsByYear.get(currentSeason.getYear());
            List<Game> games = gameRepository.findByTeamAndSeasonWithDetails(id, currentSeason.getId());
            int wins = resolveInt(ss != null ? ss.getCalcWins() : null, ss != null ? ss.getWins() : null, 0);
            int losses = resolveInt(ss != null ? ss.getCalcLosses() : null, ss != null ? ss.getLosses() : null, 0);
            int confWins = resolveInt(ss != null ? ss.getCalcConferenceWins() : null, ss != null ? ss.getConferenceWins() : null, 0);
            int confLosses = resolveInt(ss != null ? ss.getCalcConferenceLosses() : null, ss != null ? ss.getConferenceLosses() : null, 0);
            Conference seasonConf = ss != null ? ss.getConference() : null;
            currentSeasonSchedule = new SeasonSchedule(
                    currentSeason.getYear(),
                    seasonConf != null ? seasonConf.getName() : null,
                    wins, losses, confWins, confLosses,
                    games.stream().map(g -> toGameRow(g, id)).toList()
            );
        }

        model.addAttribute("currentPage", "teams");
        model.addAttribute("team", team);
        model.addAttribute("teamId", id);
        model.addAttribute("currentConference", currentConference);
        model.addAttribute("seasons", seasons);
        model.addAttribute("schedule", currentSeasonSchedule);
        model.addAttribute("currentSeasonYear", currentSeason != null ? currentSeason.getYear() : null);

        return "pages/team-detail";
    }

    @GetMapping("/teams/{id}/season/{year}")
    public String teamSeasonSchedule(@PathVariable Long id,
                                     @PathVariable Integer year,
                                     Model model) {
        Season season = seasonRepository.findByYear(year)
                .orElseThrow(() -> new EntityNotFoundException("Season not found: " + year));

        SeasonStatistics ss = seasonStatisticsRepository.findByTeamIdAndSeasonId(id, season.getId()).orElse(null);
        List<Game> games = gameRepository.findByTeamAndSeasonWithDetails(id, season.getId());

        int wins = resolveInt(ss != null ? ss.getCalcWins() : null, ss != null ? ss.getWins() : null, 0);
        int losses = resolveInt(ss != null ? ss.getCalcLosses() : null, ss != null ? ss.getLosses() : null, 0);
        int confWins = resolveInt(ss != null ? ss.getCalcConferenceWins() : null, ss != null ? ss.getConferenceWins() : null, 0);
        int confLosses = resolveInt(ss != null ? ss.getCalcConferenceLosses() : null, ss != null ? ss.getConferenceLosses() : null, 0);
        Conference seasonConf = ss != null ? ss.getConference() : null;

        model.addAttribute("schedule", new SeasonSchedule(
                year,
                seasonConf != null ? seasonConf.getName() : null,
                wins, losses, confWins, confLosses,
                games.stream().map(g -> toGameRow(g, id)).toList()
        ));

        return "fragments/team-season :: season-panel";
    }

    private static Integer resolveInt(Integer calc, Integer scraped) {
        if (calc != null) return calc;
        return scraped;
    }

    private static int resolveInt(Integer calc, Integer scraped, int defaultValue) {
        if (calc != null) return calc;
        if (scraped != null) return scraped;
        return defaultValue;
    }

    private GameRow toGameRow(Game game, Long teamId) {
        boolean isHome = game.getHomeTeam().getId().equals(teamId);
        Team opponent = isHome ? game.getAwayTeam() : game.getHomeTeam();

        Integer teamScore = isHome ? game.getHomeScore() : game.getAwayScore();
        Integer oppScore = isHome ? game.getAwayScore() : game.getHomeScore();

        String result = null;
        if (game.getStatus() == Game.GameStatus.FINAL && teamScore != null && oppScore != null) {
            result = teamScore > oppScore ? "W" : teamScore < oppScore ? "L" : "T";
        }

        String location;
        if (Boolean.TRUE.equals(game.getNeutralSite())) {
            location = "N";
        } else {
            location = isHome ? "vs" : "@";
        }

        BigDecimal spread = null;
        BigDecimal overUnder = null;
        if (game.getBettingOdds() != null) {
            BettingOdds odds = game.getBettingOdds();
            spread = odds.getSpread();
            // Spread is from home perspective; flip if team is away
            if (!isHome && spread != null) {
                spread = spread.negate();
            }
            overUnder = odds.getOverUnder();
        }

        LocalDateTime easternTime = game.getGameDate()
                .atZone(ZoneOffset.UTC)
                .withZoneSameInstant(ZoneId.of("America/New_York"))
                .toLocalDateTime();

        return new GameRow(
                game.getId(),
                easternTime,
                opponent.getId(),
                opponent.getName(),
                opponent.getLogoUrl(),
                opponent.getAbbreviation(),
                location,
                game.getStatus(),
                teamScore,
                oppScore,
                result,
                game.getVenue(),
                Boolean.TRUE.equals(game.getConferenceGame()),
                spread,
                overUnder
        );
    }

    // ── Records ──

    public record ConferenceInfo(String name, String logoUrl) {}

    public record TeamSummary(
            Long id, String name, String nickname, String mascot,
            String logoUrl, String color,
            String conferenceName, String conferenceAbbreviation, String conferenceLogoUrl,
            Integer wins, Integer losses, Integer conferenceWins, Integer conferenceLosses
    ) {
        public String record() {
            if (wins == null || losses == null) return "";
            return wins + "-" + losses;
        }

        public String conferenceRecord() {
            if (conferenceWins == null || conferenceLosses == null) return "";
            return conferenceWins + "-" + conferenceLosses;
        }
    }

    public record SeasonSchedule(
            int year, String conferenceName,
            int wins, int losses, int conferenceWins, int conferenceLosses,
            List<GameRow> games
    ) {
        public String record() { return wins + "-" + losses; }
        public String conferenceRecord() { return conferenceWins + "-" + conferenceLosses; }
    }

    public record GameRow(
            Long gameId,
            LocalDateTime gameDate,
            Long opponentId,
            String opponentName,
            String opponentLogoUrl,
            String opponentAbbreviation,
            String location,
            Game.GameStatus status,
            Integer teamScore,
            Integer opponentScore,
            String result,
            String venue,
            boolean conferenceGame,
            BigDecimal spread,
            BigDecimal overUnder
    ) {
        public String scoreDisplay() {
            if (teamScore == null || opponentScore == null) return "";
            return teamScore + "-" + opponentScore;
        }
    }
}
