package com.yotto.basketball.controller;

import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.SeasonPopulationStatRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.SeasonStatisticsRepository;
import com.yotto.basketball.repository.TeamRepository;
import com.yotto.basketball.repository.TeamSeasonStatSnapshotRepository;
import com.yotto.basketball.repository.TeamStatSnapshotRepository;
import com.yotto.basketball.service.BoxScoreStatCalculator;
import com.yotto.basketball.service.DailyStatCalculator;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Controller
public class TeamWebController {

    private final TeamRepository teamRepository;
    private final SeasonRepository seasonRepository;
    private final GameRepository gameRepository;
    private final SeasonStatisticsRepository seasonStatisticsRepository;
    private final TournamentBadgeFormatter tournamentBadgeFormatter;
    private final TeamStatSnapshotRepository teamStatSnapshotRepository;
    private final TeamSeasonStatSnapshotRepository teamSeasonStatSnapshotRepository;
    private final SeasonPopulationStatRepository popStatRepository;

    public TeamWebController(TeamRepository teamRepository,
                             SeasonRepository seasonRepository,
                             GameRepository gameRepository,
                             SeasonStatisticsRepository seasonStatisticsRepository,
                             TournamentBadgeFormatter tournamentBadgeFormatter,
                             TeamStatSnapshotRepository teamStatSnapshotRepository,
                             TeamSeasonStatSnapshotRepository teamSeasonStatSnapshotRepository,
                             SeasonPopulationStatRepository popStatRepository) {
        this.teamRepository = teamRepository;
        this.seasonRepository = seasonRepository;
        this.gameRepository = gameRepository;
        this.seasonStatisticsRepository = seasonStatisticsRepository;
        this.tournamentBadgeFormatter = tournamentBadgeFormatter;
        this.teamStatSnapshotRepository = teamStatSnapshotRepository;
        this.teamSeasonStatSnapshotRepository = teamSeasonStatSnapshotRepository;
        this.popStatRepository = popStatRepository;
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
                    games.stream().map(g -> toGameRow(g, id)).toList(),
                    computeRegularSeasonRecord(games, id),
                    computeTournamentRecords(games, id)
            );
        }

        model.addAttribute("currentPage", "teams");
        model.addAttribute("team", team);
        model.addAttribute("teamId", id);
        model.addAttribute("currentConference", currentConference);
        model.addAttribute("seasons", seasons);
        model.addAttribute("schedule", currentSeasonSchedule);
        model.addAttribute("currentSeasonYear", currentSeason != null ? currentSeason.getYear() : null);
        model.addAttribute("statPanel", currentSeason != null ? buildStatPanel(id, currentSeason) : null);

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
                games.stream().map(g -> toGameRow(g, id)).toList(),
                computeRegularSeasonRecord(games, id),
                computeTournamentRecords(games, id)
        ));

        return "fragments/team-season :: season-panel";
    }

    @GetMapping("/teams/{id}/season/{year}/stats-panel")
    public String teamSeasonStatPanel(@PathVariable Long id,
                                      @PathVariable Integer year,
                                      Model model) {
        Season season = seasonRepository.findByYear(year)
                .orElseThrow(() -> new EntityNotFoundException("Season not found: " + year));
        model.addAttribute("statPanel", buildStatPanel(id, season));
        return "fragments/team-stat-panel :: panel";
    }

    // ── Team Profile stat panel ──

    /**
     * Builds the box-score-derived stat panel for a team's latest snapshot in a
     * season: every calculated stat grouped by {@link TeamStatDisplay.Category},
     * each with its value, national rank, and a direction-aware percentile.
     * Returns {@code null} when the season has no time-series data yet, or the
     * team has no snapshot on the latest date.
     */
    private TeamStatPanel buildStatPanel(Long teamId, Season season) {
        LocalDate date = teamStatSnapshotRepository.findLatestSnapshotDate(season.getId()).orElse(null);
        if (date == null) return null;

        List<TeamStatSnapshot> rows =
                teamStatSnapshotRepository.findByTeamSeasonAndDate(teamId, season.getId(), date);
        if (rows.isEmpty()) return null;

        // Field size per stat comes free from the league-wide population rows (teamCount).
        Map<String, Integer> fieldSizeByStat = popStatRepository
                .findLeagueWideBySeasonAndDate(season.getId(), date).stream()
                .collect(Collectors.toMap(SeasonPopulationStat::getStatName,
                        SeasonPopulationStat::getTeamCount, (a, b) -> a));

        Map<String, Boolean> directionByStat = BoxScoreStatCalculator.statMetas().stream()
                .collect(Collectors.toMap(DailyStatCalculator.StatMeta::name,
                        DailyStatCalculator.StatMeta::higherIsBetter));

        Map<TeamStatDisplay.Category, List<StatRow>> byCategory =
                new EnumMap<>(TeamStatDisplay.Category.class);
        for (TeamStatSnapshot s : rows) {
            TeamStatDisplay disp = TeamStatDisplay.forStat(s.getStatName());
            TeamStatDisplay.Category cat = disp != null ? disp.getCategory() : TeamStatDisplay.Category.OTHER;
            String label = disp != null ? disp.getLabel() : s.getStatName();
            String formatted = disp != null ? disp.format(s.getValue())
                    : String.format(Locale.US, "%.2f", s.getValue());
            String formatName = (disp != null ? disp.getFormat() : TeamStatDisplay.Format.RAW).name();

            Integer fieldSize = fieldSizeByStat.get(s.getStatName());
            Integer percentile = percentile(s.getRank(), fieldSize);
            boolean higherIsBetter = directionByStat.getOrDefault(s.getStatName(), true);

            byCategory.computeIfAbsent(cat, k -> new ArrayList<>())
                    .add(new StatRow(s.getStatName(), label, formatted, formatName,
                            s.getRank(), fieldSize, percentile,
                            s.getZscore(), s.getConfZscore(), higherIsBetter));
        }

        List<StatGroup> groups = new ArrayList<>();
        // Scoring leads the panel; it is derived from season-level point distributions
        // (TeamSeasonStatSnapshot), not the box-score stats above.
        StatGroup scoring = buildScoringGroup(teamId, season);
        if (scoring != null) groups.add(scoring);

        // Explicit display order; pairs left↔right in the two-column desktop grid.
        for (TeamStatDisplay.Category cat : PANEL_GROUP_ORDER) {
            List<StatRow> catRows = byCategory.get(cat);
            if (catRows == null || catRows.isEmpty()) continue;
            catRows.sort(Comparator.comparingInt(r -> catalogOrder(r.statName())));
            groups.add(new StatGroup(cat.getHeader(), catRows));
        }

        return new TeamStatPanel(season.getYear(), date, rows.get(0).getGamesPlayed(), groups);
    }

    /** Box-score group display order (Scoring is prepended separately). OTHER trails so a new uncatalogued stat still surfaces. */
    private static final List<TeamStatDisplay.Category> PANEL_GROUP_ORDER = List.of(
            TeamStatDisplay.Category.SHOOTING,
            TeamStatDisplay.Category.REBOUNDING,
            TeamStatDisplay.Category.PLAYMAKING,
            TeamStatDisplay.Category.FOUR_FACTORS_OFF,
            TeamStatDisplay.Category.FOUR_FACTORS_DEF,
            TeamStatDisplay.Category.EFFICIENCY,
            TeamStatDisplay.Category.DEFENSE,
            TeamStatDisplay.Category.OTHER);

    /** One scoring metric: its label, direction, value accessor, and (optional) stored league z-score. */
    private record ScoringMetric(String key, String label, boolean higherIsBetter,
                                 Function<TeamSeasonStatSnapshot, Double> value,
                                 Function<TeamSeasonStatSnapshot, Double> zscore) {}

    private static final List<ScoringMetric> SCORING_METRICS = List.of(
            new ScoringMetric("mean_pts_for", "Mean PPG", true,
                    TeamSeasonStatSnapshot::getMeanPtsFor, TeamSeasonStatSnapshot::getZscoreMeanPtsFor),
            new ScoringMetric("mean_pts_against", "Mean OPPG", false,
                    TeamSeasonStatSnapshot::getMeanPtsAgainst, TeamSeasonStatSnapshot::getZscoreMeanPtsAgainst),
            new ScoringMetric("stddev_pts_for", "Std Dev PPG", false,
                    TeamSeasonStatSnapshot::getStddevPtsFor, s -> null),
            new ScoringMetric("stddev_pts_against", "Std Dev OPPG", false,
                    TeamSeasonStatSnapshot::getStddevPtsAgainst, s -> null));

    /**
     * Builds the Scoring group from a team's latest {@link TeamSeasonStatSnapshot}.
     * Ranks are computed in-memory against every team's snapshot on the same date
     * (the snapshot table stores no rank), so the rows match the rank+bar look of
     * the box-score groups. Std-dev is treated lower-is-better (more consistent).
     * Returns {@code null} when the season has no point-distribution data yet.
     */
    private StatGroup buildScoringGroup(Long teamId, Season season) {
        LocalDate date = teamSeasonStatSnapshotRepository.findLatestSnapshotDate(season.getId()).orElse(null);
        if (date == null) return null;

        List<TeamSeasonStatSnapshot> all =
                teamSeasonStatSnapshotRepository.findBySeasonAndDate(season.getId(), date);
        TeamSeasonStatSnapshot mine = all.stream()
                .filter(s -> s.getTeam().getId().equals(teamId)).findFirst().orElse(null);
        if (mine == null) return null;

        List<StatRow> rows = new ArrayList<>();
        for (ScoringMetric m : SCORING_METRICS) {
            Double value = m.value().apply(mine);
            Integer rank = null, fieldSize = null, percentile = null;
            if (value != null) {
                List<Double> peers = all.stream().map(m.value()).filter(Objects::nonNull).toList();
                if (!peers.isEmpty()) {
                    fieldSize = peers.size();
                    long ahead = peers.stream()
                            .filter(v -> m.higherIsBetter() ? v > value : v < value).count();
                    rank = (int) ahead + 1;
                    percentile = percentile(rank, fieldSize);
                }
            }
            String formatted = value != null ? String.format(Locale.US, "%.1f", value) : "—";
            rows.add(new StatRow(m.key(), m.label(), formatted,
                    TeamStatDisplay.Format.DECIMAL_1.name(),
                    rank, fieldSize, percentile, m.zscore().apply(mine), null, m.higherIsBetter()));
        }
        return new StatGroup("Scoring", rows);
    }

    /** Direction-aware percentile from rank + field size; null when the field is too small. */
    private static Integer percentile(Integer rank, Integer fieldSize) {
        if (rank == null || fieldSize == null || fieldSize <= 1) return null;
        double p = 100.0 * (fieldSize - rank) / (fieldSize - 1);
        return (int) Math.round(Math.max(0, Math.min(100, p)));
    }

    /** Catalog declaration order; uncatalogued stats sort last. */
    private static int catalogOrder(String statName) {
        TeamStatDisplay disp = TeamStatDisplay.forStat(statName);
        return disp != null ? disp.ordinal() : Integer.MAX_VALUE;
    }

    private RegularSeasonRecord computeRegularSeasonRecord(List<Game> games, Long teamId) {
        int w = 0, l = 0;
        for (Game g : games) {
            if (g.getStatus() != Game.GameStatus.FINAL) continue;
            Game.TournamentType t = g.getTournamentType();
            if (t != null && t != Game.TournamentType.IN_SEASON_TOURNAMENT) continue;
            Integer result = winLossInc(g, teamId);
            if (result == null) continue;
            if (result == 1) w++; else l++;
        }
        return new RegularSeasonRecord(w, l);
    }

    private List<TournamentRecord> computeTournamentRecords(List<Game> games, Long teamId) {
        // Preserve a stable display order: Conf Tournament, NCAA, NIT, CBI, Crown, Other.
        List<Game.TournamentType> order = List.of(
                Game.TournamentType.CONFERENCE_TOURNAMENT,
                Game.TournamentType.NCAA_TOURNAMENT,
                Game.TournamentType.NIT,
                Game.TournamentType.CBI,
                Game.TournamentType.CROWN,
                Game.TournamentType.OTHER_POSTSEASON);

        List<TournamentRecord> out = new ArrayList<>();
        for (Game.TournamentType type : order) {
            int w = 0, l = 0, best = -1;
            String bestRound = null;
            String name = null;
            for (Game g : games) {
                if (g.getTournamentType() != type) continue;
                if (name == null) name = g.getTournamentName();
                if (g.getStatus() != Game.GameStatus.FINAL) continue;
                int idx = TournamentRounds.indexOf(g.getTournamentRound());
                if (idx > best) {
                    best = idx;
                    bestRound = g.getTournamentRound();
                }
                Integer result = winLossInc(g, teamId);
                if (result == null) continue;
                if (result == 1) w++; else l++;
            }
            if (name != null) {
                out.add(new TournamentRecord(type, name, w, l, bestRound));
            }
        }
        return out;
    }

    /** 1 = team won, 0 = team lost, null = game cannot be scored (tie / missing scores). */
    private static Integer winLossInc(Game g, Long teamId) {
        boolean isHome = g.getHomeTeam().getId().equals(teamId);
        Integer teamScore = isHome ? g.getHomeScore() : g.getAwayScore();
        Integer oppScore = isHome ? g.getAwayScore() : g.getHomeScore();
        if (teamScore == null || oppScore == null) return null;
        if (teamScore > oppScore) return 1;
        if (teamScore < oppScore) return 0;
        return null;
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
        Integer teamSeed = isHome ? game.getHomeSeed() : game.getAwaySeed();
        Integer oppSeed = isHome ? game.getAwaySeed() : game.getHomeSeed();

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

        TournamentBadgeFormatter.Badge badge = tournamentBadgeFormatter.format(game);

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
                overUnder,
                game.getPeriods(),
                teamSeed,
                oppSeed,
                badge
        );
    }

    // ── Records ──

    /** The full Team Profile panel: groups of stats for one team's latest snapshot. */
    public record TeamStatPanel(int year, LocalDate asOfDate, int gamesPlayed, List<StatGroup> groups) {}

    public record StatGroup(String header, List<StatRow> rows) {}

    /**
     * One stat for the panel. {@code rank}/{@code percentile} are direction-aware
     * (rank 1 = best regardless of whether high or low is good). {@code formatName}
     * is emitted to the client so the trajectory chart can format raw values.
     */
    public record StatRow(
            String statName,
            String label,
            String formattedValue,
            String formatName,
            Integer rank,
            Integer fieldSize,
            Integer percentile,
            Double zscore,
            Double confZscore,
            boolean higherIsBetter
    ) {
        public String rankDisplay() {
            return (rank == null || fieldSize == null) ? "—" : "#" + rank + " of " + fieldSize;
        }

        /** True when this stat has a dedicated stat-detail page (the box-score catalog stats). */
        public boolean linkable() {
            return com.yotto.basketball.service.StatCatalog.contains(statName);
        }

        /**
         * Full fill class for the percentile bar. Computed here rather than in the
         * template because the BEM {@code __} would collide with Thymeleaf's
         * {@code __…__} preprocessing when two appear in one expression.
         */
        public String barFillClass() {
            if (percentile == null) return "stat-bar__fill";
            return percentile >= 50 ? "stat-bar__fill stat-bar__fill--good"
                                    : "stat-bar__fill stat-bar__fill--bad";
        }
    }

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
            List<GameRow> games,
            RegularSeasonRecord regularSeason,
            List<TournamentRecord> tournamentRecords
    ) {
        public String record() { return wins + "-" + losses; }
        public String conferenceRecord() { return conferenceWins + "-" + conferenceLosses; }
    }

    /**
     * Wins/losses computed strictly from FINAL games where tournament_type is either null
     * or IN_SEASON_TOURNAMENT (in-season exempts count toward the regular season per UI spec).
     */
    public record RegularSeasonRecord(int wins, int losses) {
        public String record() { return wins + "-" + losses; }
        public boolean isEmpty() { return wins == 0 && losses == 0; }
    }

    /**
     * Wins/losses + furthest round reached for one tournament category.
     * `name` is the canonical display name (e.g. "ACC Tournament", "NCAA Tournament", "NIT").
     */
    public record TournamentRecord(
            Game.TournamentType type, String name,
            int wins, int losses, String furthestRound
    ) {
        public String record() { return wins + "-" + losses; }
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
            BigDecimal overUnder,
            Integer periods,
            Integer teamSeed,
            Integer opponentSeed,
            TournamentBadgeFormatter.Badge tournamentBadge
    ) {
        public String scoreDisplay() {
            if (teamScore == null || opponentScore == null) return "";
            return teamScore + "-" + opponentScore;
        }

        public String overtimeLabel() {
            if (periods == null || periods <= 2) return "";
            int ots = periods - 2;
            return ots == 1 ? "OT" : ots + "OT";
        }
    }
}
