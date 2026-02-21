package com.yotto.basketball.controller;

import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.ConferenceMembershipRepository;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamRepository;
import com.yotto.basketball.service.RecordCalculationService;
import com.yotto.basketball.service.RecordCalculationService.TeamRecord;
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
    private final ConferenceMembershipRepository membershipRepository;
    private final GameRepository gameRepository;
    private final RecordCalculationService recordCalculationService;

    public TeamWebController(TeamRepository teamRepository,
                             SeasonRepository seasonRepository,
                             ConferenceMembershipRepository membershipRepository,
                             GameRepository gameRepository,
                             RecordCalculationService recordCalculationService) {
        this.teamRepository = teamRepository;
        this.seasonRepository = seasonRepository;
        this.membershipRepository = membershipRepository;
        this.gameRepository = gameRepository;
        this.recordCalculationService = recordCalculationService;
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

            Map<Long, TeamRecord> records = recordCalculationService.calculateRecords(season.getId());

            List<ConferenceMembership> memberships = membershipRepository.findBySeasonId(season.getId());
            Map<Long, Conference> conferenceByTeamId = new HashMap<>();
            for (ConferenceMembership cm : memberships) {
                conferenceByTeamId.put(cm.getTeam().getId(), cm.getConference());
            }

            List<Team> allTeams = teamRepository.findAll();

            teamSummaries = allTeams.stream()
                    .map(team -> {
                        TeamRecord rec = records.get(team.getId());
                        Conference conf = conferenceByTeamId.get(team.getId());
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
                                rec != null ? rec.getWins() : null,
                                rec != null ? rec.getLosses() : null,
                                rec != null ? rec.getConferenceWins() : null,
                                rec != null ? rec.getConferenceLosses() : null
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

        // Conference memberships (most recent first)
        List<ConferenceMembership> memberships = membershipRepository.findByTeamIdOrderBySeasonDesc(id);
        Conference currentConference = memberships.isEmpty() ? null : memberships.get(0).getConference();

        // Seasons this team has games in
        List<Season> seasons = gameRepository.findSeasonsByTeamId(id);
        Season currentSeason = seasons.isEmpty() ? null : seasons.get(0);

        // Build schedule per season
        Map<Integer, SeasonSchedule> schedulesBySeason = new LinkedHashMap<>();
        for (Season season : seasons) {
            List<Game> games = gameRepository.findByTeamAndSeasonWithDetails(id, season.getId());
            Map<Long, TeamRecord> records = recordCalculationService.calculateRecords(season.getId());
            TeamRecord rec = records.get(id);

            // Find conference for this season
            Conference seasonConf = null;
            for (ConferenceMembership cm : memberships) {
                if (cm.getSeason().getId().equals(season.getId())) {
                    seasonConf = cm.getConference();
                    break;
                }
            }

            List<GameRow> gameRows = games.stream()
                    .map(g -> toGameRow(g, id))
                    .toList();

            schedulesBySeason.put(season.getYear(), new SeasonSchedule(
                    season.getYear(),
                    seasonConf != null ? seasonConf.getName() : null,
                    rec != null ? rec.getWins() : 0,
                    rec != null ? rec.getLosses() : 0,
                    rec != null ? rec.getConferenceWins() : 0,
                    rec != null ? rec.getConferenceLosses() : 0,
                    gameRows
            ));
        }

        model.addAttribute("currentPage", "teams");
        model.addAttribute("team", team);
        model.addAttribute("currentConference", currentConference);
        model.addAttribute("schedulesBySeason", schedulesBySeason);
        model.addAttribute("currentSeasonYear", currentSeason != null ? currentSeason.getYear() : null);

        return "pages/team-detail";
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
