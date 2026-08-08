package com.yotto.basketball.service;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.TeamPowerRatingSnapshot;
import com.yotto.basketball.repository.ConferenceRepository;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.PredictionEvaluationRepository;
import com.yotto.basketball.repository.TeamPowerRatingSnapshotRepository;
import com.yotto.basketball.util.EasternDates;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The EPILOGUE "Season Wrapped" superlatives, all computed from existing tables — champion,
 * most improbable win, the model's best call and worst miss, the biggest rating climb, and the
 * conference of the year. Cached per season for the process lifetime: the underlying season is
 * over, so the answers can't change until a rebuild anyway.
 */
@Service
public class SeasonWrapService {

    /** One wrap card. gameId/teamId are optional link targets. */
    public record WrapStat(String label, String headline, String detail, Long gameId, Long teamId) {}

    public record SeasonWrap(int year, List<WrapStat> stats) {}

    private final GameRepository gameRepository;
    private final PredictionEvaluationRepository evaluationRepository;
    private final TeamPowerRatingSnapshotRepository ratingSnapshotRepository;
    private final ConferenceRankingService conferenceRankingService;
    private final ConferenceRepository conferenceRepository;

    private final Map<Long, SeasonWrap> cache = new ConcurrentHashMap<>();

    public SeasonWrapService(GameRepository gameRepository,
                             PredictionEvaluationRepository evaluationRepository,
                             TeamPowerRatingSnapshotRepository ratingSnapshotRepository,
                             ConferenceRankingService conferenceRankingService,
                             ConferenceRepository conferenceRepository) {
        this.gameRepository = gameRepository;
        this.evaluationRepository = evaluationRepository;
        this.ratingSnapshotRepository = ratingSnapshotRepository;
        this.conferenceRankingService = conferenceRankingService;
        this.conferenceRepository = conferenceRepository;
    }

    /** Recompute on next request — for tests (shared-context cache vs reset ids) and admin rebuilds. */
    public void clearCache() {
        cache.clear();
    }

    /**
     * @param spreadModelType evaluation rows for spread-based stats (e.g. {@code ML:slug}, MASSEY)
     * @param probModelType   evaluation rows for win-prob stats (e.g. {@code ML:slug}, BRADLEY_TERRY)
     */
    @Transactional(readOnly = true)
    public SeasonWrap wrap(Season season, String spreadModelType, String probModelType) {
        return cache.computeIfAbsent(season.getId(),
                id -> compute(season, spreadModelType, probModelType));
    }

    private SeasonWrap compute(Season season, String spreadModelType, String probModelType) {
        List<WrapStat> stats = new ArrayList<>();
        championshipGame(season).ifPresent(g -> stats.add(champion(g)));
        improbableWin(season, probModelType).ifPresent(stats::add);
        bestCall(season, spreadModelType).ifPresent(stats::add);
        worstMiss(season, spreadModelType).ifPresent(stats::add);
        biggestClimb(season).ifPresent(stats::add);
        conferenceOfTheYear(season).ifPresent(stats::add);
        return new SeasonWrap(season.getYear(), stats);
    }

    /** The NCAA title game: FINAL, round naming the championship; fallback = last FINAL NCAA game. */
    public Optional<Game> championshipGame(Season season) {
        List<Game> ncaa = gameRepository.findBySeasonIdAndTournamentTypeWithDetails(
                season.getId(), Game.TournamentType.NCAA_TOURNAMENT);
        Optional<Game> byRound = ncaa.stream()
                .filter(g -> g.getStatus() == Game.GameStatus.FINAL)
                .filter(g -> g.getTournamentRound() != null
                        && g.getTournamentRound().toLowerCase(Locale.ROOT).contains("championship"))
                .max(java.util.Comparator.comparing(Game::getGameDate));
        if (byRound.isPresent()) return byRound;
        return ncaa.stream()
                .filter(g -> g.getStatus() == Game.GameStatus.FINAL)
                .max(java.util.Comparator.comparing(Game::getGameDate));
    }

    private WrapStat champion(Game title) {
        boolean homeWon = title.getHomeScore() > title.getAwayScore();
        var winner = homeWon ? title.getHomeTeam() : title.getAwayTeam();
        var loser = homeWon ? title.getAwayTeam() : title.getHomeTeam();
        int ws = Math.max(title.getHomeScore(), title.getAwayScore());
        int ls = Math.min(title.getHomeScore(), title.getAwayScore());
        return new WrapStat("National Champions", winner.getName(),
                "Beat " + loser.getName() + " " + ws + "–" + ls + " in the title game.",
                title.getId(), winner.getId());
    }

    private Optional<WrapStat> improbableWin(Season season, String probModelType) {
        return evaluationRepository.findMostImprobableWin(season.getId(), probModelType)
                .flatMap(row -> gameRepository.findByIdWithDetails(row.getGameId()).map(g -> {
                    boolean homeWon = g.getHomeScore() > g.getAwayScore();
                    var winner = homeWon ? g.getHomeTeam() : g.getAwayTeam();
                    var loser = homeWon ? g.getAwayTeam() : g.getHomeTeam();
                    double pctVal = row.getWinnerProb() * 100;
                    // sub-1% chances deserve their decimal — "0%" undersells the miracle
                    String pct = String.format(Locale.US, pctVal < 1 ? "%.1f%%" : "%.0f%%", pctVal);
                    return new WrapStat("Most Improbable Win",
                            winner.getName() + " over " + loser.getName(),
                            "The model gave " + winner.getName() + " a " + pct + " chance on "
                                    + EasternDates.toEasternDate(g.getGameDate())
                                            .format(java.time.format.DateTimeFormatter.ofPattern("MMMM d", Locale.US))
                                    + ". They won anyway.",
                            g.getId(), winner.getId());
                }));
    }

    private Optional<WrapStat> bestCall(Season season, String spreadModelType) {
        return evaluationRepository.findBestUpsetCall(season.getId(), spreadModelType)
                .flatMap(row -> gameRepository.findByIdWithDetails(row.getGameId()).map(g -> {
                    boolean homeWon = g.getHomeScore() > g.getAwayScore();
                    var winner = homeWon ? g.getHomeTeam() : g.getAwayTeam();
                    // BOOK rows store predicted spread as a home margin; its favorite is the side it backs
                    var bookFavorite = row.getBookSpread() >= 0 ? g.getHomeTeam() : g.getAwayTeam();
                    return new WrapStat("The Model's Best Call",
                            winner.getName() + " (the book disagreed)",
                            "The book had " + bookFavorite.getName() + " by "
                                    + String.format(Locale.US, "%.1f", Math.abs(row.getBookSpread()))
                                    + "; the model took " + winner.getName() + " — and was right.",
                            g.getId(), winner.getId());
                }));
    }

    private Optional<WrapStat> worstMiss(Season season, String spreadModelType) {
        return evaluationRepository.findWorstSpreadMiss(season.getId(), spreadModelType)
                .flatMap(row -> gameRepository.findByIdWithDetails(row.getGameId()).map(g ->
                        new WrapStat("The Model's Worst Miss",
                                g.getAwayTeam().getName() + " at " + g.getHomeTeam().getName(),
                                "Off by " + String.format(Locale.US, "%.1f", Math.abs(row.getSpreadError()))
                                        + " points. We don't want to talk about it.",
                                g.getId(), null)));
    }

    private Optional<WrapStat> biggestClimb(Season season) {
        List<LocalDate> dates = ratingSnapshotRepository.findSnapshotDates(season.getId(), "MASSEY");
        if (dates.size() < 2) return Optional.empty();
        Map<Long, TeamPowerRatingSnapshot> first = byTeam(season, dates.get(0));
        Map<Long, TeamPowerRatingSnapshot> last = byTeam(season, dates.get(dates.size() - 1));
        TeamPowerRatingSnapshot bestLast = null;
        double bestClimb = 0;
        for (TeamPowerRatingSnapshot end : last.values()) {
            TeamPowerRatingSnapshot start = first.get(end.getTeam().getId());
            if (start == null || start.getRating() == null || end.getRating() == null) continue;
            double climb = end.getRating() - start.getRating();
            if (climb > bestClimb) {
                bestClimb = climb;
                bestLast = end;
            }
        }
        if (bestLast == null) return Optional.empty();
        return Optional.of(new WrapStat("Biggest Riser",
                bestLast.getTeam().getName(),
                "+" + String.format(Locale.US, "%.1f", bestClimb)
                        + " Massey rating points from the season's first snapshot to its last.",
                null, bestLast.getTeam().getId()));
    }

    private Map<Long, TeamPowerRatingSnapshot> byTeam(Season season, LocalDate date) {
        Map<Long, TeamPowerRatingSnapshot> m = new java.util.HashMap<>();
        for (TeamPowerRatingSnapshot s : ratingSnapshotRepository
                .findBySeasonModelAndDate(season.getId(), "MASSEY", date)) {
            m.put(s.getTeam().getId(), s);
        }
        return m;
    }

    private Optional<WrapStat> conferenceOfTheYear(Season season) {
        return conferenceRankingService.aggregateBySeason(season).values().stream()
                .filter(a -> a.conferenceRank() != null && a.conferenceRank() == 1)
                .findFirst()
                .flatMap(a -> conferenceRepository.findById(a.conferenceId()).map(c ->
                        new WrapStat("Conference of the Year", c.getName(),
                                a.wins() + "–" + a.losses() + " overall, "
                                        + a.nonConfWins() + "–" + a.nonConfLosses()
                                        + " outside the conference.",
                                null, null)));
    }
}
