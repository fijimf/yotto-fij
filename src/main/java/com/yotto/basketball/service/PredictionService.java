package com.yotto.basketball.service;

import com.yotto.basketball.entity.BettingOdds;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.entity.TeamPowerRatingSnapshot;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.PowerModelParamSnapshotRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamPowerRatingSnapshotRepository;
import com.yotto.basketball.repository.TeamRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Assembles game predictions from pre-computed Massey and Bradley-Terry snapshots
 * and, when enabled, from the ONNX ML models via {@link MlPredictionService}.
 *
 * <p>All snapshot lookups use the most recent snapshot dated strictly before the game
 * date, ensuring only information available before tip-off is used.
 */
@Service
@Transactional(readOnly = true)
public class PredictionService {

    static final int DEFAULT_UPCOMING_DAYS = 7;
    static final int MAX_UPCOMING_DAYS     = 30;

    private final GameRepository gameRepository;
    private final TeamRepository teamRepository;
    private final SeasonRepository seasonRepository;
    private final TeamPowerRatingSnapshotRepository ratingRepository;
    private final PowerModelParamSnapshotRepository paramRepository;
    private final MlPredictionService mlPredictionService;

    public PredictionService(GameRepository gameRepository,
                             TeamRepository teamRepository,
                             SeasonRepository seasonRepository,
                             TeamPowerRatingSnapshotRepository ratingRepository,
                             PowerModelParamSnapshotRepository paramRepository,
                             MlPredictionService mlPredictionService) {
        this.gameRepository       = gameRepository;
        this.teamRepository       = teamRepository;
        this.seasonRepository     = seasonRepository;
        this.ratingRepository     = ratingRepository;
        this.paramRepository      = paramRepository;
        this.mlPredictionService  = mlPredictionService;
    }

    /** Returns a prediction for a single game by ID. */
    public PredictionResult predict(Long gameId) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new EntityNotFoundException("Game not found: " + gameId));
        return buildPrediction(game);
    }

    /**
     * Returns predictions for all SCHEDULED games in the next {@code days} calendar days.
     * IN_PROGRESS games are excluded. {@code days} is clamped to [1, {@value #MAX_UPCOMING_DAYS}].
     */
    public List<PredictionResult> getUpcoming(int days) {
        int clamped = Math.min(Math.max(days, 1), MAX_UPCOMING_DAYS);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime end = now.plusDays(clamped);
        return gameRepository.findScheduledBetween(now, end)
                .stream()
                .map(this::buildPrediction)
                .collect(Collectors.toList());
    }

    /**
     * Returns a prediction for a hypothetical matchup between two teams on a given date.
     * Uses the most recent rating snapshots strictly before {@code gameDate}.
     * Season is resolved from the date; falls back to the most recent season if unmatched.
     */
    public PredictionResult predictMatchup(Long homeTeamId, Long awayTeamId,
                                           LocalDate gameDate, boolean neutralSite) {
        if (homeTeamId.equals(awayTeamId)) {
            throw new IllegalArgumentException("Home and away teams must be different.");
        }
        Team home = teamRepository.findById(homeTeamId)
                .orElseThrow(() -> new EntityNotFoundException("Team not found: " + homeTeamId));
        Team away = teamRepository.findById(awayTeamId)
                .orElseThrow(() -> new EntityNotFoundException("Team not found: " + awayTeamId));
        Season season = seasonRepository.findByDate(gameDate)
                .orElseGet(() -> seasonRepository.findTopByOrderByYearDesc()
                        .orElseThrow(() -> new IllegalStateException("No seasons configured")));

        GameRatings ratings = fetchGameRatings(homeTeamId, awayTeamId, season.getId(), gameDate, neutralSite);

        PredictionResult.MasseyPrediction       massey      = toMassey(ratings);
        PredictionResult.MasseyTotalPrediction  masseyTotal = toMasseyTotal(ratings);
        PredictionResult.BradleyTerryPrediction bt          = toBradleyTerry(ratings);
        PredictionResult.BradleyTerryPrediction btWeighted  = toBradleyTerryWeighted(ratings);

        PredictionResult.MlPrediction ml = null;
        if (mlPredictionService.isEnabled() && ratings.hasAll()) {
            LocalDateTime gameDatetime = gameDate.atStartOfDay();
            MlFeatureVector features = buildMlFeatureVector(
                    homeTeamId, awayTeamId, gameDatetime, season.getStartDate(),
                    neutralSite, false, ratings);
            ml = mlPredictionService.predict(features);
        }

        return new PredictionResult(
                null, gameDate, null, neutralSite,
                toTeamSummary(home), toTeamSummary(away),
                null, null, null, null,
                massey, masseyTotal, bt, btWeighted, ml,
                null, null);
    }

    // ── Core prediction logic ─────────────────────────────────────────────────

    private PredictionResult buildPrediction(Game game) {
        PredictionResult.TeamSummary homeTeam = toTeamSummary(game.getHomeTeam());
        PredictionResult.TeamSummary awayTeam = toTeamSummary(game.getAwayTeam());

        // Book lines (pre-load within transaction to avoid LazyInitializationException in callers)
        BettingOdds bo = game.getBettingOdds();
        java.math.BigDecimal bookSpread    = bo != null ? bo.getSpread()    : null;
        java.math.BigDecimal bookOverUnder = bo != null ? bo.getOverUnder() : null;

        // Postponed/cancelled games have no meaningful prediction
        if (game.getStatus() == Game.GameStatus.POSTPONED
                || game.getStatus() == Game.GameStatus.CANCELLED) {
            return new PredictionResult(
                    game.getId(), game.getGameDate().toLocalDate(), game.getStatus(),
                    game.getNeutralSite(), homeTeam, awayTeam,
                    null, null, null, null, null, null, null, null, null,
                    bookSpread, bookOverUnder);
        }

        LocalDate cutoff = game.getGameDate().toLocalDate();
        Long seasonId    = game.getSeason().getId();
        boolean neutral  = Boolean.TRUE.equals(game.getNeutralSite());
        Long homeId      = game.getHomeTeam().getId();
        Long awayId      = game.getAwayTeam().getId();

        // Fetch all snapshots in one pass — used by both Phase 1 and Phase 2
        GameRatings ratings = fetchGameRatings(homeId, awayId, seasonId, cutoff, neutral);

        PredictionResult.MasseyPrediction        massey          = toMassey(ratings);
        PredictionResult.MasseyTotalPrediction   masseyTotal     = toMasseyTotal(ratings);
        PredictionResult.BradleyTerryPrediction  bt              = toBradleyTerry(ratings);
        PredictionResult.BradleyTerryPrediction  btWeighted      = toBradleyTerryWeighted(ratings);

        // ML enhancement (Phase 2) — null if ML is disabled or features incomplete
        PredictionResult.MlPrediction ml = null;
        if (mlPredictionService.isEnabled() && ratings.hasAll()) {
            MlFeatureVector features = buildMlFeatureVector(game, ratings);
            ml = mlPredictionService.predict(features);
        }

        Integer actualHomeScore = null, actualAwayScore = null, actualMargin = null, actualTotal = null;
        if (game.getStatus() == Game.GameStatus.FINAL
                && game.getHomeScore() != null && game.getAwayScore() != null) {
            actualHomeScore = game.getHomeScore();
            actualAwayScore = game.getAwayScore();
            actualMargin    = game.getHomeScore() - game.getAwayScore();
            actualTotal     = game.getHomeScore() + game.getAwayScore();
        }

        return new PredictionResult(
                game.getId(), game.getGameDate().toLocalDate(), game.getStatus(),
                game.getNeutralSite(), homeTeam, awayTeam,
                actualHomeScore, actualAwayScore, actualMargin, actualTotal,
                massey, masseyTotal, bt, btWeighted, ml,
                bookSpread, bookOverUnder);
    }

    // ── Snapshot fetch ────────────────────────────────────────────────────────

    /**
     * Fetches all six team snapshots and three HCA params in a single logical pass.
     * HCA params are only fetched when the game is not at a neutral site and both
     * team snapshots are available (avoids unnecessary queries).
     */
    private GameRatings fetchGameRatings(Long homeId, Long awayId, Long seasonId,
                                          LocalDate cutoff, boolean neutral) {
        var masseyHome = ratingRepository.findLatestBefore(homeId, seasonId, MasseyRatingService.MODEL_TYPE, cutoff).orElse(null);
        var masseyAway = ratingRepository.findLatestBefore(awayId, seasonId, MasseyRatingService.MODEL_TYPE, cutoff).orElse(null);
        double masseyHca = 0;
        if (!neutral && masseyHome != null && masseyAway != null) {
            masseyHca = paramRepository.findLatestParamBefore(seasonId, MasseyRatingService.MODEL_TYPE, "hca", cutoff)
                    .map(p -> p.getParamValue()).orElse(0.0);
        }

        var masseyTotalHome = ratingRepository.findLatestBefore(homeId, seasonId, MasseyRatingService.MODEL_TYPE_TOTALS, cutoff).orElse(null);
        var masseyTotalAway = ratingRepository.findLatestBefore(awayId, seasonId, MasseyRatingService.MODEL_TYPE_TOTALS, cutoff).orElse(null);
        double masseyTotalIntercept = 0, masseyTotalDelta = 0;
        if (masseyTotalHome != null && masseyTotalAway != null) {
            masseyTotalIntercept = paramRepository.findLatestParamBefore(seasonId, MasseyRatingService.MODEL_TYPE_TOTALS, "intercept", cutoff)
                    .map(p -> p.getParamValue()).orElse(0.0);
            if (!neutral) {
                masseyTotalDelta = paramRepository.findLatestParamBefore(seasonId, MasseyRatingService.MODEL_TYPE_TOTALS, "hca_total", cutoff)
                        .map(p -> p.getParamValue()).orElse(0.0);
            }
        }

        var btHome = ratingRepository.findLatestBefore(homeId, seasonId, BradleyTerryRatingService.MODEL_TYPE, cutoff).orElse(null);
        var btAway = ratingRepository.findLatestBefore(awayId, seasonId, BradleyTerryRatingService.MODEL_TYPE, cutoff).orElse(null);
        double btAlpha = 0;
        if (!neutral && btHome != null && btAway != null) {
            btAlpha = paramRepository.findLatestParamBefore(seasonId, BradleyTerryRatingService.MODEL_TYPE, "hca", cutoff)
                    .map(p -> p.getParamValue()).orElse(0.0);
        }

        var btWeightedHome = ratingRepository.findLatestBefore(homeId, seasonId, BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, cutoff).orElse(null);
        var btWeightedAway = ratingRepository.findLatestBefore(awayId, seasonId, BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, cutoff).orElse(null);
        double btWeightedAlpha = 0;
        if (!neutral && btWeightedHome != null && btWeightedAway != null) {
            btWeightedAlpha = paramRepository.findLatestParamBefore(seasonId, BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, "hca", cutoff)
                    .map(p -> p.getParamValue()).orElse(0.0);
        }

        return new GameRatings(
                masseyHome, masseyAway, masseyHca,
                masseyTotalHome, masseyTotalAway, masseyTotalIntercept, masseyTotalDelta,
                btHome, btAway, btAlpha,
                btWeightedHome, btWeightedAway, btWeightedAlpha);
    }

    // ── Phase 1 sub-block builders ────────────────────────────────────────────

    private static PredictionResult.MasseyPrediction toMassey(GameRatings r) {
        if (!r.hasMassey()) return null;
        double spread = r.masseyHome().getRating() - r.masseyAway().getRating() + r.masseyHca();
        return new PredictionResult.MasseyPrediction(
                spread,
                r.masseyHome().getGamesPlayed(), r.masseyAway().getGamesPlayed(),
                earlierDate(r.masseyHome().getSnapshotDate(), r.masseyAway().getSnapshotDate()));
    }

    private static PredictionResult.MasseyTotalPrediction toMasseyTotal(GameRatings r) {
        if (!r.hasMasseyTotal()) return null;
        double total = r.masseyTotalHome().getRating() + r.masseyTotalAway().getRating()
                + r.masseyTotalIntercept() + r.masseyTotalDelta();
        return new PredictionResult.MasseyTotalPrediction(
                total,
                r.masseyTotalHome().getGamesPlayed(), r.masseyTotalAway().getGamesPlayed(),
                earlierDate(r.masseyTotalHome().getSnapshotDate(), r.masseyTotalAway().getSnapshotDate()));
    }

    private static PredictionResult.BradleyTerryPrediction toBradleyTerry(GameRatings r) {
        if (!r.hasBt()) return null;
        double logOdds = r.btHome().getRating() - r.btAway().getRating() + r.btAlpha();
        double pHome   = sigmoid(logOdds);
        double pAway   = 1.0 - pHome;
        return new PredictionResult.BradleyTerryPrediction(
                pHome, pAway,
                impliedMoneyline(pHome), impliedMoneyline(pAway),
                r.btHome().getGamesPlayed(), r.btAway().getGamesPlayed(),
                earlierDate(r.btHome().getSnapshotDate(), r.btAway().getSnapshotDate()));
    }

    private static PredictionResult.BradleyTerryPrediction toBradleyTerryWeighted(GameRatings r) {
        if (!r.hasBtWeighted()) return null;
        double logOdds = r.btWeightedHome().getRating() - r.btWeightedAway().getRating() + r.btWeightedAlpha();
        double pHome   = sigmoid(logOdds);
        double pAway   = 1.0 - pHome;
        return new PredictionResult.BradleyTerryPrediction(
                pHome, pAway,
                impliedMoneyline(pHome), impliedMoneyline(pAway),
                r.btWeightedHome().getGamesPlayed(), r.btWeightedAway().getGamesPlayed(),
                earlierDate(r.btWeightedHome().getSnapshotDate(), r.btWeightedAway().getSnapshotDate()));
    }

    // ── Phase 2 ML feature builder ────────────────────────────────────────────

    private MlFeatureVector buildMlFeatureVector(Game game, GameRatings r) {
        return buildMlFeatureVector(
                game.getHomeTeam().getId(), game.getAwayTeam().getId(),
                game.getGameDate(), game.getSeason().getStartDate(),
                Boolean.TRUE.equals(game.getNeutralSite()),
                Boolean.TRUE.equals(game.getConferenceGame()), r);
    }

    private MlFeatureVector buildMlFeatureVector(Long homeId, Long awayId,
                                                  LocalDateTime gameDatetime, LocalDate seasonStartDate,
                                                  boolean neutralSite, boolean conferenceGame,
                                                  GameRatings r) {
        List<Game> homeRecent = gameRepository.findRecentFinalGamesForTeam(homeId, gameDatetime, PageRequest.of(0, 5));
        List<Game> awayRecent = gameRepository.findRecentFinalGamesForTeam(awayId, gameDatetime, PageRequest.of(0, 5));

        RollingStats homeStats = computeRolling(homeId, homeRecent);
        RollingStats awayStats = computeRolling(awayId, awayRecent);

        Integer homeDaysRest = daysRest(homeId, homeRecent, gameDatetime);
        Integer awayDaysRest = daysRest(awayId, awayRecent, gameDatetime);

        int seasonWeek = (int) (ChronoUnit.DAYS.between(
                seasonStartDate, gameDatetime.toLocalDate()) / 7) + 1;

        double betaHome   = r.masseyHome().getRating();
        double betaAway   = r.masseyAway().getRating();
        double gammaHome  = r.masseyTotalHome().getRating();
        double gammaAway  = r.masseyTotalAway().getRating();
        double thetaHome  = r.btHome().getRating();
        double thetaAway  = r.btAway().getRating();
        double thetaWHome = r.btWeightedHome() != null ? r.btWeightedHome().getRating() : 0.0;
        double thetaWAway = r.btWeightedAway() != null ? r.btWeightedAway().getRating() : 0.0;

        return new MlFeatureVector(
                betaHome, betaAway, betaHome - betaAway,
                gammaHome, gammaAway, gammaHome + gammaAway,
                thetaHome, thetaAway, thetaHome - thetaAway + r.btAlpha(),
                thetaWHome, thetaWAway, thetaWHome - thetaWAway + r.btWeightedAlpha(),
                homeStats.winPct(),   homeStats.avgMargin(),   homeStats.avgTotal(),   homeStats.marginStddev(),
                awayStats.winPct(),   awayStats.avgMargin(),   awayStats.avgTotal(),   awayStats.marginStddev(),
                r.masseyHome().getGamesPlayed(), r.masseyAway().getGamesPlayed(),
                homeDaysRest, awayDaysRest, seasonWeek,
                neutralSite, conferenceGame);
    }

    /**
     * Computes rolling stats from a team's most recent games (up to 5).
     * Returns null-valued stats when the list is empty (cold start).
     */
    private static RollingStats computeRolling(Long teamId, List<Game> games) {
        if (games.isEmpty()) {
            return new RollingStats(null, null, null, null);
        }
        int wins = 0;
        double sumMargin = 0, sumTotal = 0;
        double[] margins = new double[games.size()];
        for (int i = 0; i < games.size(); i++) {
            Game g = games.get(i);
            int homeScore = g.getHomeScore();
            int awayScore = g.getAwayScore();
            int margin = g.getHomeTeam().getId().equals(teamId)
                    ? (homeScore - awayScore) : (awayScore - homeScore);
            int total  = homeScore + awayScore;
            if (margin > 0) wins++;
            sumMargin  += margin;
            sumTotal   += total;
            margins[i]  = margin;
        }
        int n = games.size();
        double avgMargin = sumMargin / n;
        double avgTotal  = sumTotal  / n;
        double stddev    = 0;
        if (n > 1) {
            double sumSq = 0;
            for (double m : margins) sumSq += (m - avgMargin) * (m - avgMargin);
            stddev = Math.sqrt(sumSq / (n - 1));
        }
        return new RollingStats((double) wins / n, avgMargin, avgTotal, stddev);
    }

    /** Returns days since the team's most recent game before the game date, or null if none. */
    private static Integer daysRest(Long teamId, List<Game> recentGames, LocalDateTime gameDatetime) {
        if (recentGames.isEmpty()) return null;
        LocalDate lastGameDate = recentGames.get(0).getGameDate().toLocalDate();
        return (int) ChronoUnit.DAYS.between(lastGameDate, gameDatetime.toLocalDate());
    }

    // ── Private types ─────────────────────────────────────────────────────────

    /** Carries all pre-fetched snapshot values for one game's prediction. */
    private record GameRatings(
            TeamPowerRatingSnapshot masseyHome, TeamPowerRatingSnapshot masseyAway, double masseyHca,
            TeamPowerRatingSnapshot masseyTotalHome, TeamPowerRatingSnapshot masseyTotalAway,
            double masseyTotalIntercept, double masseyTotalDelta,
            TeamPowerRatingSnapshot btHome, TeamPowerRatingSnapshot btAway, double btAlpha,
            TeamPowerRatingSnapshot btWeightedHome, TeamPowerRatingSnapshot btWeightedAway, double btWeightedAlpha
    ) {
        boolean hasMassey()      { return masseyHome != null && masseyAway != null; }
        boolean hasMasseyTotal() { return masseyTotalHome != null && masseyTotalAway != null; }
        boolean hasBt()          { return btHome != null && btAway != null; }
        boolean hasBtWeighted()  { return btWeightedHome != null && btWeightedAway != null; }
        boolean hasAll()         { return hasMassey() && hasMasseyTotal() && hasBt(); }
    }

    /** Rolling aggregate stats for a team over their last N games. Nullable when window is empty. */
    private record RollingStats(Double winPct, Double avgMargin, Double avgTotal, Double marginStddev) {}

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static PredictionResult.TeamSummary toTeamSummary(Team t) {
        return new PredictionResult.TeamSummary(t.getId(), t.getName(), t.getAbbreviation(), t.getLogoUrl(), t.getColor());
    }

    private static LocalDate earlierDate(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    private static int impliedMoneyline(double p) {
        if (p >= 0.5) return -(int) Math.round(p / (1.0 - p) * 100);
        return (int) Math.round((1.0 - p) / p * 100);
    }
}
