package com.yotto.basketball.service;

import com.yotto.basketball.entity.PowerModelParamSnapshot;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.SeasonStatistics;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.entity.TeamPowerRatingSnapshot;
import com.yotto.basketball.entity.TeamSeasonStatSnapshot;
import com.yotto.basketball.repository.PowerModelParamSnapshotRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.SeasonStatisticsRepository;
import com.yotto.basketball.repository.TeamPowerRatingSnapshotRepository;
import com.yotto.basketball.repository.TeamSeasonStatSnapshotRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Assembles the per-model power ranking pages (/rankings/{modelSlug}, spec
 * §6.2): a full ranked table with model-specific columns, plus the model's
 * fitted parameters as a footnote. One template renders every model; columns
 * and formatting are decided here.
 */
@Service
public class PowerRankingPageService {

    /** Rows with fewer games get the low-GP de-emphasis styling. */
    public static final int LOW_GP_THRESHOLD = 5;

    /** The models with public ranking pages, keyed by URL slug. */
    public enum RankingModel {
        RPI("rpi", "RPI",
                "The NCAA's classic Rating Percentage Index: 25% the team's own "
                + "location-adjusted winning percentage (home wins count less, road wins more), "
                + "50% its opponents' winning percentage (OWP), and 25% its opponents' opponents' "
                + "winning percentage (OOWP) — a record-plus-schedule-strength blend that ignores "
                + "margin of victory entirely."),
        MASSEY("massey", "Massey",
                "A least-squares rating fit to score margins: every game says "
                + "\"home rating − away rating + home-court advantage ≈ margin\", and the ratings "
                + "that best satisfy all games at once are the solution. A rating is the expected "
                + "margin against an average team on a neutral floor."),
        BRADLEY_TERRY("bradley-terry", "Bradley-Terry",
                "A win/loss-only strength model: each team gets a strength θ such that the "
                + "probability of beating an opponent is a logistic function of the strength "
                + "difference (plus home advantage). Margins don't matter — only who won — so "
                + "close losses to great teams don't help and blowouts don't pad the rating."),
        BRADLEY_TERRY_WEIGHTED("bradley-terry-weighted", "Bradley-Terry (Weighted)",
                "Bradley-Terry with recency weighting: recent games count more than November "
                + "ones when fitting the strengths, so the rating tracks how a team is playing "
                + "now rather than averaging the whole season equally."),
        ADJUSTED_EFFICIENCY("adjusted-efficiency", "Adjusted Efficiency",
                "Per-possession scoring adjusted for opponent strength: AdjO is points scored "
                + "per 100 possessions against an average defense, AdjD points allowed per 100 "
                + "against an average offense, and Net (AdjO − AdjD) is the margin per 100 "
                + "possessions expected against an average team. Tempo is the adjusted "
                + "possessions per game. Requires box-score data.");

        private final String slug;
        private final String title;
        private final String explainer;

        RankingModel(String slug, String title, String explainer) {
            this.slug = slug;
            this.title = title;
            this.explainer = explainer;
        }

        public static Optional<RankingModel> fromSlug(String slug) {
            for (RankingModel m : values()) {
                if (m.slug.equals(slug)) return Optional.of(m);
            }
            return Optional.empty();
        }

        public String getSlug() { return slug; }
        public String getTitle() { return title; }
        public String getExplainer() { return explainer; }
    }

    public record Row(int rank, long teamId, String teamName, String logoUrl,
                      String confAbbr, String record, int gamesPlayed,
                      List<String> values, boolean lowGp) {}

    public record Param(String label, String value) {}

    public record PageData(RankingModel model, int year, List<Integer> availableSeasons,
                           LocalDate date, LocalDate latestDate,
                           List<String> columns, List<Row> rows, List<Param> params) {

        public boolean hasData() { return !rows.isEmpty(); }
    }

    private final SeasonRepository seasonRepository;
    private final TeamPowerRatingSnapshotRepository ratingRepository;
    private final TeamSeasonStatSnapshotRepository wideSnapshotRepository;
    private final SeasonStatisticsRepository seasonStatisticsRepository;
    private final PowerModelParamSnapshotRepository paramRepository;
    private final ConferenceNamingService namingService;

    public PowerRankingPageService(SeasonRepository seasonRepository,
                                   TeamPowerRatingSnapshotRepository ratingRepository,
                                   TeamSeasonStatSnapshotRepository wideSnapshotRepository,
                                   SeasonStatisticsRepository seasonStatisticsRepository,
                                   PowerModelParamSnapshotRepository paramRepository,
                                   ConferenceNamingService namingService) {
        this.seasonRepository = seasonRepository;
        this.ratingRepository = ratingRepository;
        this.wideSnapshotRepository = wideSnapshotRepository;
        this.seasonStatisticsRepository = seasonStatisticsRepository;
        this.paramRepository = paramRepository;
        this.namingService = namingService;
    }

    @Transactional(readOnly = true)
    public PageData build(int year, RankingModel model, LocalDate requestedDate) {
        Season season = seasonRepository.findByYear(year)
                .orElseThrow(() -> new EntityNotFoundException("Season not found: " + year));

        LocalDate latestDate = latestDateFor(season, model);
        LocalDate date = requestedDate != null ? requestedDate : latestDate;

        TeamContext ctx = date != null ? loadTeamContext(season, date) : TeamContext.empty();

        List<String> columns;
        List<Row> rows;
        if (date == null) {
            columns = List.of();
            rows = List.of();
        } else if (model == RankingModel.RPI) {
            columns = List.of("RPI", "WP", "OWP", "OOWP");
            rows = buildRpiRows(season, date, ctx);
        } else if (model == RankingModel.ADJUSTED_EFFICIENCY) {
            columns = List.of("AdjO", "AdjD", "Net", "Tempo");
            rows = buildAdjEfficiencyRows(season, date, ctx);
        } else {
            columns = List.of("Rating");
            rows = buildSingleRatingRows(season, date, ctx, modelType(model), model);
        }

        List<Integer> years = seasonRepository.findAll().stream()
                .map(Season::getYear)
                .sorted(Comparator.reverseOrder())
                .toList();

        return new PageData(model, year, years, date, latestDate, columns, rows,
                date != null ? buildParams(season, model, date) : List.of());
    }

    private LocalDate latestDateFor(Season season, RankingModel model) {
        if (model == RankingModel.RPI) {
            return wideSnapshotRepository.findLatestSnapshotDate(season.getId()).orElse(null);
        }
        String type = model == RankingModel.ADJUSTED_EFFICIENCY ? "ADJ_OFF" : modelType(model);
        return ratingRepository.findLatestSnapshotDate(season.getId(), type).orElse(null);
    }

    private static String modelType(RankingModel model) {
        return switch (model) {
            case MASSEY -> MasseyRatingService.MODEL_TYPE;
            case BRADLEY_TERRY -> BradleyTerryRatingService.MODEL_TYPE;
            case BRADLEY_TERRY_WEIGHTED -> BradleyTerryRatingService.MODEL_TYPE_WEIGHTED;
            default -> throw new IllegalArgumentException("No single model type for " + model);
        };
    }

    // ── Row builders ──────────────────────────────────────────────────────────

    private List<Row> buildRpiRows(Season season, LocalDate date, TeamContext ctx) {
        List<TeamSeasonStatSnapshot> snaps = wideSnapshotRepository.findBySeasonAndDate(season.getId(), date)
                .stream()
                .filter(s -> s.getRpi() != null)
                .sorted(Comparator.comparingDouble(TeamSeasonStatSnapshot::getRpi).reversed())
                .toList();
        List<Row> rows = new ArrayList<>(snaps.size());
        for (int i = 0; i < snaps.size(); i++) {
            TeamSeasonStatSnapshot s = snaps.get(i);
            rows.add(row(i + 1, s.getTeam(), ctx, s.getGamesPlayed(),
                    record(s.getWins(), s.getLosses()),
                    List.of(fmt(s.getRpi(), 4),
                            pct(s.getRpiWp()),
                            pct(s.getRpiOwp()),
                            pct(s.getRpiOowp()))));
        }
        return rows;
    }

    private List<Row> buildSingleRatingRows(Season season, LocalDate date, TeamContext ctx,
                                            String modelType, RankingModel model) {
        List<TeamPowerRatingSnapshot> snaps =
                ratingRepository.findBySeasonModelAndDate(season.getId(), modelType, date);
        List<Row> rows = new ArrayList<>(snaps.size());
        int rank = 0;
        for (TeamPowerRatingSnapshot s : snaps) {
            rank++;
            String value = model == RankingModel.MASSEY
                    ? signed(s.getRating(), 2)
                    : fmt(s.getRating(), 3);
            rows.add(row(s.getRank() != null ? s.getRank() : rank, s.getTeam(), ctx,
                    s.getGamesPlayed(), ctx.record(s.getTeam().getId()), List.of(value)));
        }
        return rows;
    }

    private List<Row> buildAdjEfficiencyRows(Season season, LocalDate date, TeamContext ctx) {
        Map<Long, TeamPowerRatingSnapshot> off = byTeam(season, "ADJ_OFF", date);
        Map<Long, TeamPowerRatingSnapshot> def = byTeam(season, "ADJ_DEF", date);
        Map<Long, TeamPowerRatingSnapshot> tempo = byTeam(season, "ADJ_TEMPO", date);

        // Stored ratings are centered team params where higher def = stronger defense
        // (it subtracts from opponent efficiency — see AdjustedEfficiencyRatingService).
        // Display as absolute per-100 values: AdjO = μ + off, AdjD = μ − def (lower is
        // better), Tempo = baseline + τ; Net = AdjO − AdjD = off + def.
        LocalDate before = date.plusDays(1);
        double mu = param(season, "ADJ_OFF", "eff_intercept", before).orElse(0.0);
        double tempoBase = param(season, "ADJ_TEMPO", "tempo_intercept", before).orElse(0.0);

        record NetRow(TeamPowerRatingSnapshot o, TeamPowerRatingSnapshot d, TeamPowerRatingSnapshot t, double net) {}
        List<NetRow> nets = new ArrayList<>();
        for (Map.Entry<Long, TeamPowerRatingSnapshot> e : off.entrySet()) {
            TeamPowerRatingSnapshot d = def.get(e.getKey());
            if (d == null || e.getValue().getRating() == null || d.getRating() == null) continue;
            nets.add(new NetRow(e.getValue(), d, tempo.get(e.getKey()),
                    e.getValue().getRating() + d.getRating()));
        }
        nets.sort(Comparator.comparingDouble(NetRow::net).reversed());

        List<Row> rows = new ArrayList<>(nets.size());
        for (int i = 0; i < nets.size(); i++) {
            NetRow n = nets.get(i);
            Team team = n.o().getTeam();
            rows.add(row(i + 1, team, ctx, n.o().getGamesPlayed(), ctx.record(team.getId()),
                    List.of(fmt(mu + n.o().getRating(), 1),
                            fmt(mu - n.d().getRating(), 1),
                            signed(n.net(), 1),
                            n.t() != null && n.t().getRating() != null
                                    ? fmt(tempoBase + n.t().getRating(), 1) : "—")));
        }
        return rows;
    }

    private Map<Long, TeamPowerRatingSnapshot> byTeam(Season season, String modelType, LocalDate date) {
        Map<Long, TeamPowerRatingSnapshot> map = new HashMap<>();
        for (TeamPowerRatingSnapshot s : ratingRepository.findBySeasonModelAndDate(season.getId(), modelType, date)) {
            map.putIfAbsent(s.getTeam().getId(), s);
        }
        return map;
    }

    private Row row(int rank, Team team, TeamContext ctx, int gamesPlayed,
                    String record, List<String> values) {
        return new Row(rank, team.getId(), team.getName(), team.getLogoUrl(),
                ctx.confAbbr(team.getId()), record, gamesPlayed, values,
                gamesPlayed < LOW_GP_THRESHOLD);
    }

    // ── Params footnote ───────────────────────────────────────────────────────

    private List<Param> buildParams(Season season, RankingModel model, LocalDate date) {
        // Params are snapshotted alongside ratings; "before date+1" = on-or-before date
        LocalDate before = date.plusDays(1);
        List<Param> params = new ArrayList<>();
        switch (model) {
            case MASSEY -> param(season, MasseyRatingService.MODEL_TYPE, "hca", before)
                    .ifPresent(v -> params.add(new Param("Home-court advantage", signed(v, 2) + " pts")));
            case BRADLEY_TERRY -> param(season, BradleyTerryRatingService.MODEL_TYPE, "hca", before)
                    .ifPresent(v -> params.add(new Param("Home advantage (log-odds)", signed(v, 3))));
            case BRADLEY_TERRY_WEIGHTED -> param(season, BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, "hca", before)
                    .ifPresent(v -> params.add(new Param("Home advantage (log-odds)", signed(v, 3))));
            case ADJUSTED_EFFICIENCY -> {
                param(season, "ADJ_OFF", "eff_intercept", before)
                        .ifPresent(v -> params.add(new Param("League efficiency baseline", fmt(v, 1) + " pts/100")));
                param(season, "ADJ_OFF", "eff_hca", before)
                        .ifPresent(v -> params.add(new Param("Home-court advantage", signed(v, 2) + " pts/100")));
                param(season, "ADJ_TEMPO", "tempo_intercept", before)
                        .ifPresent(v -> params.add(new Param("League tempo baseline", fmt(v, 1) + " poss/game")));
            }
            case RPI -> { /* no fitted params — RPI is a fixed formula */ }
        }
        return params;
    }

    private Optional<Double> param(Season season, String modelType, String name, LocalDate before) {
        return paramRepository.findLatestParamBefore(season.getId(), modelType, name, before)
                .map(PowerModelParamSnapshot::getParamValue);
    }

    // ── Team context (conference + record) ────────────────────────────────────

    private record TeamContext(Map<Long, String> confAbbrByTeam, Map<Long, String> recordByTeam) {

        static TeamContext empty() {
            return new TeamContext(Map.of(), Map.of());
        }

        String confAbbr(long teamId) {
            return confAbbrByTeam.getOrDefault(teamId, "—");
        }

        String record(long teamId) {
            return recordByTeam.getOrDefault(teamId, "—");
        }
    }

    private TeamContext loadTeamContext(Season season, LocalDate date) {
        Map<Long, String> conf = new HashMap<>();
        ConferenceNamingService.ConferenceNames names = namingService.load();
        for (SeasonStatistics ss : seasonStatisticsRepository.findBySeasonIdWithTeamAndConference(season.getId())) {
            ConferenceNamingService.ConferenceIdentity id = names.identity(ss.getConference(), season.getYear());
            if (id != null) {
                conf.putIfAbsent(ss.getTeam().getId(),
                        id.abbreviation() != null ? id.abbreviation() : id.name());
            }
        }
        Map<Long, String> records = new HashMap<>();
        for (TeamSeasonStatSnapshot s : wideSnapshotRepository.findBySeasonAndDate(season.getId(), date)) {
            records.putIfAbsent(s.getTeam().getId(), record(s.getWins(), s.getLosses()));
        }
        return new TeamContext(conf, records);
    }

    // ── Formatting ────────────────────────────────────────────────────────────

    private static String record(Integer wins, Integer losses) {
        if (wins == null || losses == null) return "—";
        return wins + "–" + losses;
    }

    private static String fmt(Double v, int decimals) {
        return v == null ? "—" : String.format(Locale.US, "%." + decimals + "f", v);
    }

    private static String signed(Double v, int decimals) {
        if (v == null) return "—";
        return String.format(Locale.US, "%+." + decimals + "f", v);
    }

    private static String pct(Double v) {
        return v == null ? "—" : String.format(Locale.US, "%.1f%%", v * 100);
    }
}
