package com.yotto.basketball.service;

import com.yotto.basketball.config.CacheConfig;
import com.yotto.basketball.entity.Conference;
import com.yotto.basketball.entity.ConferenceMembership;
import com.yotto.basketball.entity.PowerModelParamSnapshot;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.ConferenceMembershipRepository;
import com.yotto.basketball.repository.ConferenceRepository;
import com.yotto.basketball.repository.PowerModelParamSnapshotRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamPowerRatingSnapshotRepository;
import com.yotto.basketball.repository.TeamPowerRatingSnapshotRepository.SeriesRow;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Payloads for the two season-animation charts: the conference "rating river"
 * (bump chart of national rank over the season) and the league-wide adjusted
 * efficiency landscape (AdjO vs AdjD scatter played across the season).
 *
 * <p>Both are built from the daily snapshot tables and change only after a
 * calculation run, so they are cached in-process and evicted with the other
 * page caches ({@link PageCacheEvictionService}). Arrays are aligned to
 * {@code dates}; a {@code null} entry means no snapshot for that team on that date.
 */
@Service
public class ChartDataService {

    /** Models with stored national ranks that the river can draw. */
    public enum RiverModel {
        MASSEY("massey", MasseyRatingService.MODEL_TYPE, "Massey"),
        BRADLEY_TERRY("bradley-terry", BradleyTerryRatingService.MODEL_TYPE, "Bradley-Terry"),
        BRADLEY_TERRY_WEIGHTED("bradley-terry-weighted", BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, "Weighted Bradley-Terry");

        private final String slug, modelType, title;
        RiverModel(String slug, String modelType, String title) { this.slug = slug; this.modelType = modelType; this.title = title; }
        public String getSlug() { return slug; }
        public String getModelType() { return modelType; }
        public String getTitle() { return title; }
        public static Optional<RiverModel> fromSlug(String slug) {
            return Arrays.stream(values()).filter(m -> m.slug.equals(slug)).findFirst();
        }
    }

    public record RiverTeam(long id, String name, String abbr, String logoUrl, String color, String altColor,
                            List<Integer> rank, List<Double> rating) {}
    public record RatingRiver(long conferenceId, String conference, int year, String model, String modelTitle,
                              List<LocalDate> dates, List<RiverTeam> teams) {}

    public record LandscapeTeam(long id, String name, String abbr, String conf, String logoUrl, String color, String altColor,
                                List<Double> off, List<Double> def, List<Double> tempo, List<Integer> gp) {}
    public record EfficiencyLandscape(int year, List<LocalDate> dates, List<LandscapeTeam> teams) {}

    /** Landscape frames: every Nth snapshot date, plus the latest, keep the payload ~7 frames/month. */
    static final int LANDSCAPE_FRAME_STEP = 4;

    private final SeasonRepository seasonRepository;
    private final ConferenceRepository conferenceRepository;
    private final ConferenceMembershipRepository membershipRepository;
    private final TeamPowerRatingSnapshotRepository ratingRepository;
    private final PowerModelParamSnapshotRepository paramRepository;
    private final ConferenceNamingService namingService;

    public ChartDataService(SeasonRepository seasonRepository,
                            ConferenceRepository conferenceRepository,
                            ConferenceMembershipRepository membershipRepository,
                            TeamPowerRatingSnapshotRepository ratingRepository,
                            PowerModelParamSnapshotRepository paramRepository,
                            ConferenceNamingService namingService) {
        this.seasonRepository = seasonRepository;
        this.conferenceRepository = conferenceRepository;
        this.membershipRepository = membershipRepository;
        this.ratingRepository = ratingRepository;
        this.paramRepository = paramRepository;
        this.namingService = namingService;
    }

    // ── Rating river ─────────────────────────────────────────────────────────

    @Cacheable(value = CacheConfig.CHART_RATING_RIVER, key = "#conferenceId + ':' + #year + ':' + #model")
    @Transactional(readOnly = true)
    public RatingRiver buildRatingRiver(long conferenceId, int year, RiverModel model) {
        Conference conference = conferenceRepository.findById(conferenceId)
                .orElseThrow(() -> new EntityNotFoundException("Conference not found: " + conferenceId));
        Season season = requireSeason(year);
        String confName = namingService.resolve(conference, year).name();

        List<Team> members = membershipRepository
                .findByConferenceIdAndSeasonIdWithTeam(conferenceId, season.getId()).stream()
                .map(ConferenceMembership::getTeam).toList();
        List<LocalDate> dates = ratingRepository.findSnapshotDates(season.getId(), model.getModelType());
        if (members.isEmpty() || dates.isEmpty()) {
            return new RatingRiver(conferenceId, confName, year, model.getSlug(), model.getTitle(), List.of(), List.of());
        }
        Map<LocalDate, Integer> dateIdx = index(dates);
        List<Long> ids = members.stream().map(Team::getId).toList();

        Map<Long, Integer[]> ranks = new HashMap<>();
        Map<Long, Double[]> ratings = new HashMap<>();
        for (SeriesRow r : ratingRepository.findSeriesBySeasonModelAndTeams(season.getId(), model.getModelType(), ids)) {
            Integer i = dateIdx.get(r.getSnapshotDate());
            if (i == null) continue;
            ranks.computeIfAbsent(r.getTeamId(), k -> new Integer[dates.size()])[i] = r.getRank();
            ratings.computeIfAbsent(r.getTeamId(), k -> new Double[dates.size()])[i] = r.getRating();
        }

        List<RiverTeam> teams = new ArrayList<>();
        for (Team t : members) {
            Integer[] rk = ranks.getOrDefault(t.getId(), new Integer[dates.size()]);
            Double[] rt = ratings.getOrDefault(t.getId(), new Double[dates.size()]);
            teams.add(new RiverTeam(t.getId(), t.getName(), t.getAbbreviation(), t.getLogoUrl(),
                    hex(t.getColor()), hex(t.getAlternateColor()), Arrays.asList(rk), Arrays.asList(rt)));
        }
        // Best current rank first, so the client can label top-to-bottom without resorting.
        teams.sort(Comparator.comparing((RiverTeam t) -> lastNonNull(t.rank()), Comparator.nullsLast(Integer::compareTo)));
        return new RatingRiver(conferenceId, confName, year, model.getSlug(), model.getTitle(), dates, teams);
    }

    // ── Efficiency landscape ────────────────────────────────────────────────

    @Cacheable(value = CacheConfig.CHART_EFF_LANDSCAPE, key = "#year")
    @Transactional(readOnly = true)
    public EfficiencyLandscape buildEfficiencyLandscape(int year) {
        Season season = requireSeason(year);
        List<LocalDate> all = ratingRepository.findSnapshotDates(season.getId(), "ADJ_OFF");
        if (all.isEmpty()) return new EfficiencyLandscape(year, List.of(), List.of());

        List<LocalDate> frames = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (i % LANDSCAPE_FRAME_STEP == 0 || i == all.size() - 1) frames.add(all.get(i));
        }
        Map<LocalDate, Integer> frameIdx = index(frames);

        // Stored ratings are centered params (see AdjustedEfficiencyRatingService):
        // AdjO = μ + off, AdjD = μ − def (lower is better), Tempo = base + τ.
        Map<LocalDate, Double> mu = paramsByDate(season, "ADJ_OFF", "eff_intercept");
        Map<LocalDate, Double> tempoBase = paramsByDate(season, "ADJ_TEMPO", "tempo_intercept");

        Map<Long, Double[]> off = new HashMap<>(), def = new HashMap<>(), tempo = new HashMap<>();
        Map<Long, Integer[]> gp = new HashMap<>();
        for (SeriesRow r : ratingRepository.findSeriesBySeasonAndModels(season.getId(), List.of("ADJ_OFF", "ADJ_DEF", "ADJ_TEMPO"))) {
            Integer i = frameIdx.get(r.getSnapshotDate());
            if (i == null || r.getRating() == null) continue;
            switch (r.getModelType()) {
                case "ADJ_OFF" -> {
                    off.computeIfAbsent(r.getTeamId(), k -> new Double[frames.size()])[i] =
                            round1(asOf(mu, r.getSnapshotDate()) + r.getRating());
                    gp.computeIfAbsent(r.getTeamId(), k -> new Integer[frames.size()])[i] = r.getGamesPlayed();
                }
                case "ADJ_DEF" -> def.computeIfAbsent(r.getTeamId(), k -> new Double[frames.size()])[i] =
                        round1(asOf(mu, r.getSnapshotDate()) - r.getRating());
                case "ADJ_TEMPO" -> tempo.computeIfAbsent(r.getTeamId(), k -> new Double[frames.size()])[i] =
                        round1(asOf(tempoBase, r.getSnapshotDate()) + r.getRating());
                default -> { }
            }
        }

        Map<Long, String> confByTeam = new HashMap<>();
        Map<Long, Team> teamById = new LinkedHashMap<>();
        for (ConferenceMembership m : membershipRepository.findBySeasonIdWithTeamAndConference(season.getId())) {
            teamById.put(m.getTeam().getId(), m.getTeam());
            confByTeam.put(m.getTeam().getId(), namingService.resolve(m.getConference(), year).abbreviation());
        }

        List<LandscapeTeam> teams = new ArrayList<>();
        for (Map.Entry<Long, Double[]> e : off.entrySet()) {
            Team t = teamById.get(e.getKey());
            if (t == null || !def.containsKey(e.getKey())) continue;
            teams.add(new LandscapeTeam(t.getId(), t.getName(), t.getAbbreviation(), confByTeam.get(t.getId()),
                    t.getLogoUrl(), hex(t.getColor()), hex(t.getAlternateColor()),
                    Arrays.asList(e.getValue()), Arrays.asList(def.get(e.getKey())),
                    Arrays.asList(tempo.getOrDefault(e.getKey(), new Double[frames.size()])),
                    Arrays.asList(gp.getOrDefault(e.getKey(), new Integer[frames.size()]))));
        }
        teams.sort(Comparator.comparing(LandscapeTeam::name));
        return new EfficiencyLandscape(year, frames, teams);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Season requireSeason(int year) {
        return seasonRepository.findByYear(year)
                .orElseThrow(() -> new EntityNotFoundException("Season not found: " + year));
    }

    private Map<LocalDate, Double> paramsByDate(Season season, String modelType, String paramName) {
        Map<LocalDate, Double> out = new LinkedHashMap<>();
        for (PowerModelParamSnapshot p : paramRepository.findBySeasonAndModel(season.getId(), modelType)) {
            if (paramName.equals(p.getParamName()) && p.getParamValue() != null) out.put(p.getSnapshotDate(), p.getParamValue());
        }
        return out;
    }

    /** The param on that date, else the latest earlier one, else 0 (ratings then display centered). */
    private static double asOf(Map<LocalDate, Double> byDate, LocalDate date) {
        Double exact = byDate.get(date);
        if (exact != null) return exact;
        double best = 0.0; LocalDate bestDate = null;
        for (Map.Entry<LocalDate, Double> e : byDate.entrySet()) {
            if (!e.getKey().isAfter(date) && (bestDate == null || e.getKey().isAfter(bestDate))) { bestDate = e.getKey(); best = e.getValue(); }
        }
        return best;
    }

    private static <T> Map<T, Integer> index(List<T> items) {
        Map<T, Integer> idx = new HashMap<>();
        for (int i = 0; i < items.size(); i++) idx.put(items.get(i), i);
        return idx;
    }

    private static Integer lastNonNull(List<Integer> xs) {
        for (int i = xs.size() - 1; i >= 0; i--) if (xs.get(i) != null) return xs.get(i);
        return null;
    }

    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }

    /** ESPN stores colors without the '#'; normalize so the client can use them directly. */
    static String hex(String c) {
        if (c == null || c.isBlank()) return null;
        String s = c.trim();
        return s.startsWith("#") ? s : "#" + s;
    }
}
