package com.yotto.basketball.controller;

import com.yotto.basketball.entity.Conference;
import com.yotto.basketball.entity.ConferenceMembership;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.SeasonStatistics;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.entity.TeamPowerRatingSnapshot;
import com.yotto.basketball.repository.ConferenceMembershipRepository;
import com.yotto.basketball.repository.ConferenceRepository;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.SeasonStatisticsRepository;
import com.yotto.basketball.repository.TeamPowerRatingSnapshotRepository;
import com.yotto.basketball.service.ConferenceRankingService;
import com.yotto.basketball.service.ConferenceRankingService.ConferenceAggregate;
import com.yotto.basketball.service.MasseyRatingService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class ConferenceWebController {

    private final ConferenceRepository conferenceRepository;
    private final ConferenceMembershipRepository membershipRepository;
    private final SeasonRepository seasonRepository;
    private final SeasonStatisticsRepository seasonStatisticsRepository;
    private final TeamPowerRatingSnapshotRepository ratingRepository;
    private final GameRepository gameRepository;
    private final ConferenceRankingService rankingService;

    public ConferenceWebController(ConferenceRepository conferenceRepository,
                                  ConferenceMembershipRepository membershipRepository,
                                  SeasonRepository seasonRepository,
                                  SeasonStatisticsRepository seasonStatisticsRepository,
                                  TeamPowerRatingSnapshotRepository ratingRepository,
                                  GameRepository gameRepository,
                                  ConferenceRankingService rankingService) {
        this.conferenceRepository = conferenceRepository;
        this.membershipRepository = membershipRepository;
        this.seasonRepository = seasonRepository;
        this.seasonStatisticsRepository = seasonStatisticsRepository;
        this.ratingRepository = ratingRepository;
        this.gameRepository = gameRepository;
        this.rankingService = rankingService;
    }

    // ── Conference index ──

    @GetMapping("/conferences")
    public String conferences(@RequestParam(required = false) Integer year, Model model) {
        Season season = year != null
                ? seasonRepository.findByYear(year).orElse(null)
                : seasonRepository.findTopByOrderByYearDesc().orElse(null);

        List<ConferenceSummary> summaries = List.of();
        if (season != null) {
            Map<Long, ConferenceAggregate> aggregates = rankingService.aggregateBySeason(season);
            Map<Long, Conference> confById = conferenceRepository.findAll().stream()
                    .collect(Collectors.toMap(Conference::getId, c -> c));
            int rankedCount = (int) aggregates.values().stream()
                    .filter(a -> a.conferenceRank() != null).count();

            summaries = aggregates.values().stream()
                    .map(a -> {
                        Conference c = confById.get(a.conferenceId());
                        if (c == null) return null;
                        return new ConferenceSummary(
                                c.getId(), c.getName(), c.getAbbreviation(), c.getLogoUrl(),
                                a.teamCount(), a.avgMasseyRating(), a.conferenceRank(), rankedCount,
                                a.wins(), a.losses(), a.nonConfWins(), a.nonConfLosses());
                    })
                    .filter(s -> s != null)
                    .sorted(Comparator
                            .comparing((ConferenceSummary s) -> s.conferenceRank() == null
                                    ? Integer.MAX_VALUE : s.conferenceRank())
                            .thenComparing(ConferenceSummary::name))
                    .toList();
        }

        model.addAttribute("currentPage", "conferences");
        model.addAttribute("conferences", summaries);
        model.addAttribute("conferenceCount", summaries.size());
        model.addAttribute("seasonYear", season != null ? season.getYear() : null);
        return "pages/conferences";
    }

    // ── Conference detail ──

    @GetMapping("/conferences/{id}")
    public String conferenceDetail(@PathVariable Long id, Model model) {
        Conference conference = conferenceRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Conference not found: " + id));

        List<Season> seasons = membershipRepository.findSeasonsByConferenceId(id);
        Season season = seasons.isEmpty() ? null : seasons.get(0);

        model.addAttribute("currentPage", "conferences");
        model.addAttribute("conference", conference);
        model.addAttribute("conferenceId", id);
        model.addAttribute("seasons", seasons);
        model.addAttribute("currentSeasonYear", season != null ? season.getYear() : null);
        model.addAttribute("detail", season != null ? buildDetail(conference, season) : null);
        return "pages/conference-detail";
    }

    @GetMapping("/conferences/{id}/season/{year}")
    public String conferenceSeason(@PathVariable Long id, @PathVariable Integer year, Model model) {
        Conference conference = conferenceRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Conference not found: " + id));
        Season season = seasonRepository.findByYear(year)
                .orElseThrow(() -> new EntityNotFoundException("Season not found: " + year));

        model.addAttribute("detail", buildDetail(conference, season));
        return "fragments/conference-season :: panel";
    }

    // ── Detail assembly ──

    private ConferenceDetail buildDetail(Conference conference, Season season) {
        Long seasonId = season.getId();

        List<Team> members = membershipRepository
                .findByConferenceIdAndSeasonIdWithTeam(conference.getId(), seasonId).stream()
                .map(ConferenceMembership::getTeam)
                .toList();
        List<Long> memberIds = members.stream().map(Team::getId).toList();

        Map<Long, SeasonStatistics> statsByTeam = seasonStatisticsRepository
                .findBySeasonIdWithTeamAndConference(seasonId).stream()
                .collect(Collectors.toMap(ss -> ss.getTeam().getId(), ss -> ss, (a, b) -> a));

        Map<Long, TeamPowerRatingSnapshot> ratingByTeam = new LinkedHashMap<>();
        LocalDate ratingDate = ratingRepository
                .findLatestSnapshotDate(seasonId, MasseyRatingService.MODEL_TYPE).orElse(null);
        if (ratingDate != null) {
            for (TeamPowerRatingSnapshot s : ratingRepository
                    .findBySeasonModelAndDate(seasonId, MasseyRatingService.MODEL_TYPE, ratingDate)) {
                ratingByTeam.put(s.getTeam().getId(), s);
            }
        }

        // Standings rows, sorted by conferenceStanding (when present) else conf/overall win%.
        List<ConferenceStandingRow> standings = members.stream()
                .map(team -> {
                    SeasonStatistics ss = statsByTeam.get(team.getId());
                    TeamPowerRatingSnapshot rating = ratingByTeam.get(team.getId());
                    return new ConferenceStandingRow(
                            0, team.getId(), team.getName(), team.getLogoUrl(),
                            resolveInt(ss != null ? ss.getCalcConferenceWins() : null, ss != null ? ss.getConferenceWins() : null),
                            resolveInt(ss != null ? ss.getCalcConferenceLosses() : null, ss != null ? ss.getConferenceLosses() : null),
                            resolveInt(ss != null ? ss.getCalcWins() : null, ss != null ? ss.getWins() : null),
                            resolveInt(ss != null ? ss.getCalcLosses() : null, ss != null ? ss.getLosses() : null),
                            resolveInt(ss != null ? ss.getCalcHomeWins() : null, ss != null ? ss.getHomeWins() : null),
                            resolveInt(ss != null ? ss.getCalcHomeLosses() : null, ss != null ? ss.getHomeLosses() : null),
                            resolveInt(ss != null ? ss.getCalcRoadWins() : null, ss != null ? ss.getRoadWins() : null),
                            resolveInt(ss != null ? ss.getCalcRoadLosses() : null, ss != null ? ss.getRoadLosses() : null),
                            ss != null ? (ss.getCalcStreak() != null ? ss.getCalcStreak() : ss.getStreak()) : null,
                            ss != null ? ss.getConferenceStanding() : null,
                            rating != null ? rating.getRating() : null,
                            rating != null ? rating.getRank() : null);
                })
                .sorted(STANDINGS_ORDER)
                .toList();
        // Re-number sequentially for the displayed "#" column.
        List<ConferenceStandingRow> ranked = new ArrayList<>(standings.size());
        for (int i = 0; i < standings.size(); i++) {
            ranked.add(standings.get(i).withStanding(i + 1));
        }

        // Member games for tournament + NCAA sections.
        List<Game> memberGames = memberIds.isEmpty() ? List.of()
                : gameRepository.findBySeasonAndTeamIds(seasonId, memberIds);

        ConferenceTournament tournament = buildTournament(memberGames);
        NcaaTournamentSummary ncaa = buildNcaaSummary(memberGames, memberIds);

        Map<Long, ConferenceAggregate> aggregates = rankingService.aggregateBySeason(season);
        ConferenceAggregate agg = aggregates.get(conference.getId());
        int rankedCount = (int) aggregates.values().stream()
                .filter(a -> a.conferenceRank() != null).count();
        ConferenceSummary summary = agg != null
                ? new ConferenceSummary(conference.getId(), conference.getName(),
                        conference.getAbbreviation(), conference.getLogoUrl(),
                        agg.teamCount(), agg.avgMasseyRating(), agg.conferenceRank(), rankedCount,
                        agg.wins(), agg.losses(), agg.nonConfWins(), agg.nonConfLosses())
                : new ConferenceSummary(conference.getId(), conference.getName(),
                        conference.getAbbreviation(), conference.getLogoUrl(),
                        members.size(), null, null, rankedCount, 0, 0, 0, 0);

        return new ConferenceDetail(season.getYear(), conference, summary, ranked, tournament, ncaa);
    }

    /** Conference-tournament games grouped by round (named) or date, ordered chronologically. */
    private ConferenceTournament buildTournament(List<Game> memberGames) {
        List<Game> games = memberGames.stream()
                .filter(g -> g.getTournamentType() == Game.TournamentType.CONFERENCE_TOURNAMENT)
                .sorted(Comparator.comparing(Game::getGameDate))
                .toList();
        if (games.isEmpty()) return null;

        String name = games.stream()
                .map(Game::getTournamentName).filter(n -> n != null && !n.isBlank())
                .findFirst().orElse("Conference Tournament");

        // Group key: round name when present, else the game date.
        Map<String, List<Game>> grouped = new LinkedHashMap<>();
        for (Game g : games) {
            String round = g.getTournamentRound();
            String key = (round != null && !round.isBlank())
                    ? round
                    : easternDate(g).toString();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(g);
        }

        List<ConferenceTournamentGroup> groups = grouped.entrySet().stream()
                .map(e -> new ConferenceTournamentGroup(
                        labelFor(e.getKey()),
                        e.getValue().stream().map(this::toTournamentRow).toList(),
                        e.getValue().get(0).getGameDate()))
                .sorted(Comparator.comparing(ConferenceTournamentGroup::earliest))
                .toList();

        // Champion = winner of the last FINAL game (the final).
        Game last = null;
        for (Game g : games) {
            if (g.getStatus() == Game.GameStatus.FINAL && winnerSide(g) != null) last = g;
        }
        Long championId = null;
        String championName = null;
        String championLogo = null;
        if (last != null) {
            Team champ = "top".equals(winnerSide(last)) ? last.getHomeTeam() : last.getAwayTeam();
            championId = champ.getId();
            championName = champ.getName();
            championLogo = champ.getLogoUrl();
        }
        return new ConferenceTournament(name, championId, championName, championLogo, groups);
    }

    private NcaaTournamentSummary buildNcaaSummary(List<Game> memberGames, List<Long> memberIds) {
        List<Game> games = memberGames.stream()
                .filter(g -> g.getTournamentType() == Game.TournamentType.NCAA_TOURNAMENT)
                .toList();
        if (games.isEmpty()) return null;

        java.util.Set<Long> memberSet = new java.util.HashSet<>(memberIds);
        Map<Long, NcaaTeamAccumulator> byTeam = new LinkedHashMap<>();
        for (Game g : games) {
            accumulate(byTeam, memberSet, g, true);
            accumulate(byTeam, memberSet, g, false);
        }

        int wins = 0, losses = 0;
        String champion = null;
        List<NcaaTeamLine> lines = new ArrayList<>();
        for (NcaaTeamAccumulator acc : byTeam.values()) {
            wins += acc.wins;
            losses += acc.losses;
            if (acc.champion) champion = acc.teamName;
            lines.add(new NcaaTeamLine(acc.teamId, acc.teamName, acc.logoUrl, acc.seed, acc.region,
                    acc.wins, acc.losses, roundName(acc.furthestRoundIdx), acc.champion));
        }
        lines.sort(Comparator
                .comparingInt((NcaaTeamLine l) -> -furthestIdxOf(l))
                .thenComparing(l -> l.seed() == null ? Integer.MAX_VALUE : l.seed()));

        return new NcaaTournamentSummary(byTeam.size(), wins, losses, champion, lines);
    }

    private void accumulate(Map<Long, NcaaTeamAccumulator> byTeam, java.util.Set<Long> memberSet,
                            Game g, boolean home) {
        Team team = home ? g.getHomeTeam() : g.getAwayTeam();
        if (!memberSet.contains(team.getId())) return;
        NcaaTeamAccumulator acc = byTeam.computeIfAbsent(team.getId(), k -> {
            NcaaTeamAccumulator a = new NcaaTeamAccumulator();
            a.teamId = team.getId();
            a.teamName = team.getName();
            a.logoUrl = team.getLogoUrl();
            a.furthestRoundIdx = -1;
            return a;
        });
        Integer seed = home ? g.getHomeSeed() : g.getAwaySeed();
        if (seed != null && acc.seed == null) acc.seed = seed;
        if (g.getTournamentRegion() != null && acc.region == null) acc.region = g.getTournamentRegion();

        int idx = TournamentRounds.indexOf(g.getTournamentRound());
        if (idx > acc.furthestRoundIdx) acc.furthestRoundIdx = idx;

        if (g.getStatus() == Game.GameStatus.FINAL) {
            Integer winInc = winLoss(g, team.getId());
            if (winInc != null) {
                if (winInc == 1) acc.wins++; else acc.losses++;
                if (winInc == 1 && "National Championship".equals(g.getTournamentRound())) {
                    acc.champion = true;
                }
            }
        }
    }

    // ── Helpers ──

    private TournamentGameRow toTournamentRow(Game g) {
        String side = winnerSide(g);
        return new TournamentGameRow(
                g.getId(), easternDate(g),
                g.getHomeTeam().getId(), g.getHomeTeam().getName(), g.getHomeTeam().getLogoUrl(),
                g.getHomeSeed(), g.getHomeScore(), "top".equals(side),
                g.getAwayTeam().getId(), g.getAwayTeam().getName(), g.getAwayTeam().getLogoUrl(),
                g.getAwaySeed(), g.getAwayScore(), "bottom".equals(side),
                g.getStatus(), Boolean.TRUE.equals(g.getNeutralSite()));
    }

    /** "top" = home won, "bottom" = away won, null = not decided. */
    private static String winnerSide(Game g) {
        if (g.getStatus() != Game.GameStatus.FINAL) return null;
        Integer h = g.getHomeScore(), a = g.getAwayScore();
        if (h == null || a == null || h.equals(a)) return null;
        return h > a ? "top" : "bottom";
    }

    /** 1 = team won, 0 = team lost, null = not decided. */
    private static Integer winLoss(Game g, Long teamId) {
        boolean home = g.getHomeTeam().getId().equals(teamId);
        Integer ts = home ? g.getHomeScore() : g.getAwayScore();
        Integer os = home ? g.getAwayScore() : g.getHomeScore();
        if (ts == null || os == null || ts.equals(os)) return null;
        return ts > os ? 1 : 0;
    }

    private static LocalDate easternDate(Game g) {
        return g.getGameDate().atZone(ZoneOffset.UTC)
                .withZoneSameInstant(ZoneId.of("America/New_York")).toLocalDate();
    }

    private static String labelFor(String key) {
        // Date-keyed groups parse as ISO dates; round-named groups pass through unchanged.
        try {
            LocalDate d = LocalDate.parse(key);
            return d.format(java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.US));
        } catch (Exception e) {
            return key;
        }
    }

    private static String roundName(int idx) {
        return idx >= 0 && idx < TournamentRounds.ORDER.size() ? TournamentRounds.ORDER.get(idx) : null;
    }

    private static int furthestIdxOf(NcaaTeamLine l) {
        return TournamentRounds.indexOf(l.furthestRound());
    }

    private static int resolveInt(Integer calc, Integer scraped) {
        if (calc != null) return calc;
        return scraped != null ? scraped : 0;
    }

    private static double winPct(int w, int l) {
        return (w + l) == 0 ? 0.0 : (double) w / (w + l);
    }

    /** conferenceStanding when present (nulls last), then conf win%, overall win%, name. */
    private static final Comparator<ConferenceStandingRow> STANDINGS_ORDER = Comparator
            .comparing((ConferenceStandingRow r) -> r.scrapedStanding() == null
                    ? Integer.MAX_VALUE : r.scrapedStanding())
            .thenComparing(Comparator.comparingDouble(
                    (ConferenceStandingRow r) -> winPct(r.confWins(), r.confLosses())).reversed())
            .thenComparing(Comparator.comparingDouble(
                    (ConferenceStandingRow r) -> winPct(r.wins(), r.losses())).reversed())
            .thenComparing(ConferenceStandingRow::teamName);

    // ── Records ──

    public record ConferenceSummary(
            Long id, String name, String abbreviation, String logoUrl,
            int teamCount, Double avgPowerRating, Integer conferenceRank, int rankedConferenceCount,
            int wins, int losses, int nonConfWins, int nonConfLosses
    ) {
        public String record() { return wins + "-" + losses; }
        public String nonConfRecord() { return nonConfWins + "-" + nonConfLosses; }
        public String ratingDisplay() {
            return avgPowerRating == null ? "—" : String.format(Locale.US, "%.2f", avgPowerRating);
        }
        public String rankDisplay() {
            return conferenceRank == null ? "—" : "#" + conferenceRank + " of " + rankedConferenceCount;
        }
    }

    public record ConferenceStandingRow(
            int standing, Long teamId, String teamName, String teamLogoUrl,
            int confWins, int confLosses, int wins, int losses,
            int homeWins, int homeLosses, int roadWins, int roadLosses,
            Integer streak, Integer scrapedStanding, Double powerRating, Integer powerRank
    ) {
        ConferenceStandingRow withStanding(int s) {
            return new ConferenceStandingRow(s, teamId, teamName, teamLogoUrl,
                    confWins, confLosses, wins, losses, homeWins, homeLosses, roadWins, roadLosses,
                    streak, scrapedStanding, powerRating, powerRank);
        }
        public String confRecord() { return confWins + "-" + confLosses; }
        public String overallRecord() { return wins + "-" + losses; }
        public String homeRecord() { return homeWins + "-" + homeLosses; }
        public String roadRecord() { return roadWins + "-" + roadLosses; }
        public String streakDisplay() {
            if (streak == null || streak == 0) return "—";
            return (streak > 0 ? "W" : "L") + Math.abs(streak);
        }
        public String ratingDisplay() {
            return powerRating == null ? "—" : String.format(Locale.US, "%.2f", powerRating);
        }
        public String powerRankDisplay() { return powerRank == null ? "—" : "#" + powerRank; }
    }

    public record ConferenceTournament(
            String name, Long championId, String championName, String championLogoUrl,
            List<ConferenceTournamentGroup> groups
    ) {}

    public record ConferenceTournamentGroup(
            String label, List<TournamentGameRow> games, java.time.LocalDateTime earliest
    ) {}

    public record TournamentGameRow(
            Long gameId, LocalDate date,
            Long topTeamId, String topTeamName, String topTeamLogoUrl, Integer topSeed,
            Integer topScore, boolean topWon,
            Long bottomTeamId, String bottomTeamName, String bottomTeamLogoUrl, Integer bottomSeed,
            Integer bottomScore, boolean bottomWon,
            Game.GameStatus status, boolean neutral
    ) {
        public boolean isFinal() { return status == Game.GameStatus.FINAL; }
    }

    public record NcaaTournamentSummary(
            int bidCount, int wins, int losses, String champion, List<NcaaTeamLine> teams
    ) {
        public String record() { return wins + "-" + losses; }
    }

    public record NcaaTeamLine(
            Long teamId, String teamName, String logoUrl, Integer seed, String region,
            int wins, int losses, String furthestRound, boolean champion
    ) {
        public String record() { return wins + "-" + losses; }
        public String seedDisplay() { return seed == null ? "" : "(" + seed + ")"; }
    }

    public record ConferenceDetail(
            int year, Conference conference, ConferenceSummary summary,
            List<ConferenceStandingRow> standings,
            ConferenceTournament tournament, NcaaTournamentSummary ncaa
    ) {}

    private static final class NcaaTeamAccumulator {
        Long teamId;
        String teamName;
        String logoUrl;
        Integer seed;
        String region;
        int wins;
        int losses;
        int furthestRoundIdx;
        boolean champion;
    }
}
