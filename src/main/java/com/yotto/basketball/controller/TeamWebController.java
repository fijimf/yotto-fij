package com.yotto.basketball.controller;

import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.SeasonStatisticsRepository;
import com.yotto.basketball.repository.TeamRepository;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.service.ConferenceNamingService;
import com.yotto.basketball.service.ConferenceNamingService.ConferenceIdentity;
import com.yotto.basketball.service.ConferenceNamingService.ConferenceNames;
import com.yotto.basketball.service.TeamPageDataService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Teams listing and team profile pages. The expensive per-season builders
 * (schedule + stat panel) live in {@link TeamPageDataService}, where they are
 * cached per (team, season).
 */
@Controller
public class TeamWebController {

    private final TeamRepository teamRepository;
    private final SeasonRepository seasonRepository;
    private final GameRepository gameRepository;
    private final SeasonStatisticsRepository seasonStatisticsRepository;
    private final ConferenceNamingService namingService;
    private final TeamPageDataService teamPageDataService;

    private final com.yotto.basketball.news.NewsQueryService newsQueryService;
    private final com.yotto.basketball.service.FavoriteTeamService favoriteTeamService;

    public TeamWebController(TeamRepository teamRepository,
                             SeasonRepository seasonRepository,
                             GameRepository gameRepository,
                             SeasonStatisticsRepository seasonStatisticsRepository,
                             ConferenceNamingService namingService,
                             TeamPageDataService teamPageDataService,
                             com.yotto.basketball.news.NewsQueryService newsQueryService,
                             com.yotto.basketball.service.FavoriteTeamService favoriteTeamService) {
        this.teamRepository = teamRepository;
        this.seasonRepository = seasonRepository;
        this.gameRepository = gameRepository;
        this.seasonStatisticsRepository = seasonStatisticsRepository;
        this.namingService = namingService;
        this.teamPageDataService = teamPageDataService;
        this.newsQueryService = newsQueryService;
        this.favoriteTeamService = favoriteTeamService;
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
            ConferenceNames names = namingService.load();
            int year = season.getYear();

            teamSummaries = allTeams.stream()
                    .map(team -> {
                        SeasonStatistics ss = statsByTeamId.get(team.getId());
                        Conference conf = ss != null ? ss.getConference() : null;
                        ConferenceIdentity identity = conf != null ? names.identity(conf, year) : null;
                        return new TeamSummary(
                                team.getId(),
                                team.getName(),
                                team.getNickname(),
                                team.getMascot(),
                                team.getLogoUrl(),
                                team.getColor(),
                                identity != null ? identity.name() : null,
                                identity != null ? identity.abbreviation() : null,
                                identity != null ? identity.logoUrl() : null,
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
        model.addAttribute("currentSection", "teams");
        model.addAttribute("conferenceGroups", confGroups);
        model.addAttribute("teamCount", teamSummaries.size());
        model.addAttribute("seasonYear", seasonYear);

        return "pages/teams";
    }

    // ── Team detail ──

    @GetMapping("/teams/{id}")
    public String teamDetail(@PathVariable Long id, Model model,
                             @org.springframework.security.core.annotation.AuthenticationPrincipal
                             com.yotto.basketball.security.AppUserDetails principal) {
        model.addAttribute("isFollowing",
                principal != null && favoriteTeamService.isFavorite(principal.getId(), id));
        Team team = teamRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Team not found: " + id));

        // Seasons this team has games in
        List<Season> seasons = gameRepository.findSeasonsByTeamId(id);
        Season currentSeason = seasons.isEmpty() ? null : seasons.get(0);

        // All stats rows for this team across all seasons (single query)
        List<SeasonStatistics> teamStats = seasonStatisticsRepository.findByTeamIdWithSeasonAndConference(id);

        // Current conference from most recent stats row, named per that row's season
        ConferenceNames names = namingService.load();
        Conference currentConference = teamStats.isEmpty() ? null : teamStats.get(0).getConference();
        String currentConferenceName = currentConference != null
                ? names.name(currentConference, teamStats.get(0).getSeason().getYear())
                : null;

        model.addAttribute("currentPage", "teams");
        model.addAttribute("currentSection", "teams");
        model.addAttribute("team", team);
        model.addAttribute("teamId", id);
        model.addAttribute("currentConference", currentConference);
        model.addAttribute("currentConferenceName", currentConferenceName);
        model.addAttribute("seasons", seasons);
        // Only load current season (lazy-load historical seasons via HTMX)
        model.addAttribute("schedule", currentSeason != null
                ? teamPageDataService.buildSeasonSchedule(id, currentSeason.getYear()) : null);
        model.addAttribute("currentSeasonYear", currentSeason != null ? currentSeason.getYear() : null);
        model.addAttribute("statPanel", currentSeason != null
                ? teamPageDataService.buildStatPanel(id, currentSeason.getYear()) : null);
        model.addAttribute("newsCards", newsQueryService.teamNews(id, 5));
        model.addAttribute("newsMoreLink", "/news?teamId=" + id);

        return "pages/team-detail";
    }

    @GetMapping("/teams/{id}/season/{year}")
    public String teamSeasonSchedule(@PathVariable Long id,
                                     @PathVariable Integer year,
                                     Model model) {
        model.addAttribute("schedule", teamPageDataService.buildSeasonSchedule(id, year));
        return "fragments/team-season :: season-panel";
    }

    @GetMapping("/teams/{id}/season/{year}/stats-panel")
    public String teamSeasonStatPanel(@PathVariable Long id,
                                      @PathVariable Integer year,
                                      Model model) {
        model.addAttribute("statPanel", teamPageDataService.buildStatPanel(id, year));
        return "fragments/team-stat-panel :: panel";
    }

    private static Integer resolveInt(Integer calc, Integer scraped) {
        if (calc != null) return calc;
        return scraped;
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
}
