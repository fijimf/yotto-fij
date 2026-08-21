package com.yotto.basketball.service;

import com.yotto.basketball.controller.TeamStatDisplay;
import com.yotto.basketball.entity.ConferenceMembership;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.TeamPowerRatingSnapshot;
import com.yotto.basketball.entity.TeamSeasonStatSnapshot;
import com.yotto.basketball.entity.TeamStatSnapshot;
import com.yotto.basketball.repository.ConferenceMembershipRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamPowerRatingSnapshotRepository;
import com.yotto.basketball.repository.TeamSeasonStatSnapshotRepository;
import com.yotto.basketball.repository.TeamStatSnapshotRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The correlation explorer's variable registry and data assembler
 * (/stats/correlation, spec §5.5): the user picks 2–8 variables; this service
 * returns per-team values for each on a snapshot date. Pearson r and the
 * matrix rendering happen client-side (js/scatter-matrix.js).
 *
 * <p>Variables span three snapshot families — long-format catalog stats, the
 * wide snapshot's RPI, and power-rating models. Values are matched on the
 * exact date; a team missing a value for some variable is dropped pairwise by
 * the renderer, not here.
 */
@Service
public class CorrelationDataService {

    public static final int MAX_VARS = 8;
    public static final int MIN_VARS = 2;

    /** The default selection — the eight variables of the original rankings scatter matrix. */
    public static final List<String> DEFAULT_VARS = List.of(
            "wp", "ppg", "opp_ppg", "scoring_margin", "rpi", "massey", "bradley_terry", "bradley_terry_w");

    /** One selectable variable. {@code decimals} drives client-side tick/tooltip formatting. */
    public record Variable(String id, String label, String group, int decimals) {}

    private static final Map<String, Variable> RATING_VARS = buildRatingVars();

    private static Map<String, Variable> buildRatingVars() {
        Map<String, Variable> m = new LinkedHashMap<>();
        m.put("rpi", new Variable("rpi", "RPI", "Ratings", 4));
        m.put("massey", new Variable("massey", "Massey", "Ratings", 2));
        m.put("bradley_terry", new Variable("bradley_terry", "B-T", "Ratings", 3));
        m.put("bradley_terry_w", new Variable("bradley_terry_w", "BTW", "Ratings", 3));
        m.put("adj_off", new Variable("adj_off", "AdjO", "Ratings", 1));
        m.put("adj_def", new Variable("adj_def", "AdjD", "Ratings", 1));
        m.put("adj_tempo", new Variable("adj_tempo", "Tempo", "Ratings", 1));
        return m;
    }

    /** Rating variable id → power model type. */
    private static final Map<String, String> RATING_MODEL_TYPES = Map.of(
            "massey", "MASSEY",
            "bradley_terry", "BRADLEY_TERRY",
            "bradley_terry_w", "BRADLEY_TERRY_W",
            "adj_off", "ADJ_OFF",
            "adj_def", "ADJ_DEF",
            "adj_tempo", "ADJ_TEMPO");

    private final SeasonRepository seasonRepository;
    private final TeamStatSnapshotRepository statSnapshotRepository;
    private final TeamSeasonStatSnapshotRepository wideSnapshotRepository;
    private final TeamPowerRatingSnapshotRepository ratingRepository;
    private final ConferenceMembershipRepository membershipRepository;

    public CorrelationDataService(SeasonRepository seasonRepository,
                                  TeamStatSnapshotRepository statSnapshotRepository,
                                  TeamSeasonStatSnapshotRepository wideSnapshotRepository,
                                  TeamPowerRatingSnapshotRepository ratingRepository,
                                  ConferenceMembershipRepository membershipRepository) {
        this.seasonRepository = seasonRepository;
        this.statSnapshotRepository = statSnapshotRepository;
        this.wideSnapshotRepository = wideSnapshotRepository;
        this.ratingRepository = ratingRepository;
        this.membershipRepository = membershipRepository;
    }

    /** Every selectable variable, grouped for the picker: Ratings first, then catalog categories. */
    public Map<String, List<Variable>> variablesByGroup() {
        Map<String, List<Variable>> groups = new LinkedHashMap<>();
        groups.put("Ratings", List.copyOf(RATING_VARS.values()));
        for (StatCatalog.StatInfo info : StatCatalog.all()) {
            groups.computeIfAbsent(info.category(), k -> new ArrayList<>())
                    .add(catalogVariable(info));
        }
        return groups;
    }

    /** Resolve an id to a Variable (catalog stat or rating), or null if unknown. */
    public Variable resolve(String id) {
        Variable rating = RATING_VARS.get(id);
        if (rating != null) return rating;
        return StatCatalog.contains(id) ? catalogVariable(StatCatalog.require(id)) : null;
    }

    private static Variable catalogVariable(StatCatalog.StatInfo info) {
        TeamStatDisplay display = TeamStatDisplay.forStat(info.name());
        String label = display != null ? display.getLabel() : info.title();
        int decimals = switch (info.format()) {
            case PERCENT, RATE -> 3;
            case RATIO -> 2;
            case RATING, PER_GAME -> 1;
        };
        return new Variable(info.name(), label, info.category(), decimals);
    }

    public record TeamRow(long teamId, String team, String conf, Map<String, Double> values) {}

    public record CorrelationPage(int year,
                                  List<Integer> availableSeasons,
                                  LocalDate date,
                                  LocalDate latestDate,
                                  LocalDate seasonStart,
                                  List<Variable> selected,
                                  List<TeamRow> rows) {}

    @Transactional(readOnly = true)
    public CorrelationPage build(int year, List<String> varIds, LocalDate requestedDate) {
        Season season = seasonRepository.findByYear(year)
                .orElseThrow(() -> new EntityNotFoundException("Season not found: " + year));

        LocalDate latestDate = statSnapshotRepository.findLatestSnapshotDate(season.getId()).orElse(null);
        LocalDate date = requestedDate != null ? requestedDate : latestDate;

        List<Variable> selected = varIds.stream()
                .map(this::resolve)
                .filter(v -> v != null)
                .distinct()
                .limit(MAX_VARS)
                .toList();

        Map<Long, TeamRow> rowsByTeam = new LinkedHashMap<>();
        Map<Long, String> confByTeam = new HashMap<>();
        for (ConferenceMembership cm : membershipRepository.findBySeasonId(season.getId())) {
            confByTeam.put(cm.getTeam().getId(),
                    cm.getConference().getAbbreviation() != null
                            ? cm.getConference().getAbbreviation() : cm.getConference().getName());
        }

        if (date != null) {
            for (Variable v : selected) {
                loadVariable(season, v, date, rowsByTeam, confByTeam);
            }
        }

        List<Integer> years = seasonRepository.findAll().stream()
                .map(Season::getYear)
                .sorted(Comparator.reverseOrder())
                .toList();

        return new CorrelationPage(year, years, date, latestDate, season.getStartDate(),
                selected, List.copyOf(rowsByTeam.values()));
    }

    private void loadVariable(Season season, Variable v, LocalDate date,
                              Map<Long, TeamRow> rowsByTeam, Map<Long, String> confByTeam) {
        if ("rpi".equals(v.id())) {
            for (TeamSeasonStatSnapshot s : wideSnapshotRepository.findBySeasonAndDate(season.getId(), date)) {
                if (s.getRpi() != null) {
                    row(rowsByTeam, confByTeam, s.getTeam().getId(), s.getTeam().getName())
                            .values().put(v.id(), s.getRpi());
                }
            }
        } else if (RATING_MODEL_TYPES.containsKey(v.id())) {
            String modelType = RATING_MODEL_TYPES.get(v.id());
            for (TeamPowerRatingSnapshot s : ratingRepository.findBySeasonModelAndDate(season.getId(), modelType, date)) {
                if (s.getRating() != null) {
                    row(rowsByTeam, confByTeam, s.getTeam().getId(), s.getTeam().getName())
                            .values().put(v.id(), s.getRating());
                }
            }
        } else {
            for (TeamStatSnapshot s : statSnapshotRepository.findBySeasonStatAndDate(season.getId(), v.id(), date)) {
                if (s.getValue() != null) {
                    row(rowsByTeam, confByTeam, s.getTeam().getId(), s.getTeam().getName())
                            .values().put(v.id(), s.getValue());
                }
            }
        }
    }

    private static TeamRow row(Map<Long, TeamRow> rowsByTeam, Map<Long, String> confByTeam,
                               long teamId, String teamName) {
        return rowsByTeam.computeIfAbsent(teamId,
                id -> new TeamRow(id, teamName, confByTeam.getOrDefault(id, "—"), new HashMap<>()));
    }
}
