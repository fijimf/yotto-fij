package com.yotto.basketball.service;

import com.yotto.basketball.entity.BettingOdds;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.entity.TeamPowerRatingSnapshot;
import com.yotto.basketball.entity.TeamStatSnapshot;
import com.yotto.basketball.entity.TeamSeasonStatSnapshot;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.PowerModelParamSnapshotRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamPowerRatingSnapshotRepository;
import com.yotto.basketball.repository.TeamRepository;
import com.yotto.basketball.repository.TeamSeasonStatSnapshotRepository;
import com.yotto.basketball.repository.TeamStatSnapshotRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final TeamStatSnapshotRepository teamStatSnapshotRepository;
    private final TeamSeasonStatSnapshotRepository teamSeasonStatSnapshotRepository;
    private final MlPredictionService mlPredictionService;
    private final MlModelRegistryService mlModelRegistryService;
    /** Margin stddev σ for Φ(spread/σ) win probabilities — see {@link WinProbability}. */
    private final double marginSigma;

    public PredictionService(GameRepository gameRepository,
                             TeamRepository teamRepository,
                             SeasonRepository seasonRepository,
                             TeamPowerRatingSnapshotRepository ratingRepository,
                             PowerModelParamSnapshotRepository paramRepository,
                             TeamStatSnapshotRepository teamStatSnapshotRepository,
                             TeamSeasonStatSnapshotRepository teamSeasonStatSnapshotRepository,
                             MlPredictionService mlPredictionService,
                             MlModelRegistryService mlModelRegistryService,
                             @org.springframework.beans.factory.annotation.Value("${app.prediction.margin-sigma:11.0}") double marginSigma) {
        this.gameRepository       = gameRepository;
        this.teamRepository       = teamRepository;
        this.seasonRepository     = seasonRepository;
        this.ratingRepository     = ratingRepository;
        this.paramRepository      = paramRepository;
        this.teamStatSnapshotRepository = teamStatSnapshotRepository;
        this.teamSeasonStatSnapshotRepository = teamSeasonStatSnapshotRepository;
        this.mlPredictionService  = mlPredictionService;
        this.mlModelRegistryService = mlModelRegistryService;
        this.marginSigma          = marginSigma;
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

        GameRatings ratings = fetchGameRatings(homeTeamId, awayTeamId, season.getId(), gameDate, neutralSite, null);

        PredictionResult.MasseyPrediction       massey      = toMassey(ratings);
        PredictionResult.MasseyTotalPrediction  masseyTotal = toMasseyTotal(ratings);
        PredictionResult.BradleyTerryPrediction bt          = toBradleyTerry(ratings);
        PredictionResult.BradleyTerryPrediction btWeighted  = toBradleyTerryWeighted(ratings);
        PredictionResult.AdjEfficiencyPrediction adjEff     = toAdjEfficiency(ratings);

        MlPredictions mlPredictions = MlPredictions.none();
        if (ratings.hasAll()) {
            mlPredictions = computeMlPredictions(homeTeamId, awayTeamId, gameDate.atStartOfDay(),
                    season, neutralSite, false, ratings, null);
        }

        return new PredictionResult(
                null, gameDate, null, neutralSite,
                toTeamSummary(home), toTeamSummary(away),
                null, null, null, null,
                massey, masseyTotal, bt, btWeighted, adjEff,
                mlPredictions.defaultPrediction(), mlPredictions.active(),
                null, null);
    }

    // ── Core prediction logic ─────────────────────────────────────────────────

    /** Package-private for {@link PredictionEvaluationService}, which evaluates pre-loaded games in bulk. */
    PredictionResult buildPrediction(Game game) {
        return buildInternal(game).result();
    }

    /**
     * Builds the season-scoped snapshot cache for bulk evaluation. One instance per
     * evaluation run; pass it to {@link #buildInternal(Game, SeasonPredictionCache)}.
     */
    SeasonPredictionCache buildSeasonCache(Season season, List<Game> finalGamesAscending) {
        Long priorSeasonId = seasonRepository.findByYear(season.getYear() - 1)
                .map(Season::getId).orElse(null);
        return new SeasonPredictionCache(season.getId(), priorSeasonId, finalGamesAscending,
                ratingRepository, paramRepository, teamStatSnapshotRepository,
                teamSeasonStatSnapshotRepository);
    }

    InternalPrediction buildInternal(Game game) {
        return buildInternal(game, null);
    }

    /**
     * Full prediction plus every evaluable ML model's output (including CANDIDATE
     * shadow models, which are never exposed in {@link PredictionResult}).
     * Package-private for {@link PredictionEvaluationService}. {@code cache} (nullable)
     * swaps the per-game repository lookups for season-bulk in-memory ones with
     * identical semantics — live predictions pass null.
     */
    InternalPrediction buildInternal(Game game, SeasonPredictionCache cache) {
        PredictionResult.TeamSummary homeTeam = toTeamSummary(game.getHomeTeam());
        PredictionResult.TeamSummary awayTeam = toTeamSummary(game.getAwayTeam());

        // Book lines (pre-load within transaction to avoid LazyInitializationException in callers)
        BettingOdds bo = game.getBettingOdds();
        java.math.BigDecimal bookSpread    = bo != null ? bo.getSpread()    : null;
        java.math.BigDecimal bookOverUnder = bo != null ? bo.getOverUnder() : null;

        // Postponed/cancelled games have no meaningful prediction
        if (game.getStatus() == Game.GameStatus.POSTPONED
                || game.getStatus() == Game.GameStatus.CANCELLED) {
            return new InternalPrediction(new PredictionResult(
                    game.getId(), game.getGameDate().toLocalDate(), game.getStatus(),
                    game.getNeutralSite(), homeTeam, awayTeam,
                    null, null, null, null, null, null, null, null, null, null, Map.of(),
                    bookSpread, bookOverUnder), Map.of());
        }

        LocalDate cutoff = game.getGameDate().toLocalDate();
        Long seasonId    = game.getSeason().getId();
        boolean neutral  = Boolean.TRUE.equals(game.getNeutralSite());
        Long homeId      = game.getHomeTeam().getId();
        Long awayId      = game.getAwayTeam().getId();

        // Fetch all snapshots in one pass — used by both Phase 1 and Phase 2
        GameRatings ratings = fetchGameRatings(homeId, awayId, seasonId, cutoff, neutral, cache);

        PredictionResult.MasseyPrediction        massey          = toMassey(ratings);
        PredictionResult.MasseyTotalPrediction   masseyTotal     = toMasseyTotal(ratings);
        PredictionResult.BradleyTerryPrediction  bt              = toBradleyTerry(ratings);
        PredictionResult.BradleyTerryPrediction  btWeighted      = toBradleyTerryWeighted(ratings);
        PredictionResult.AdjEfficiencyPrediction adjEff          = toAdjEfficiency(ratings);

        // ML models — every evaluable bundle is scored once; only ACTIVE ones are public
        MlPredictions mlPredictions = MlPredictions.none();
        if (ratings.hasAll()) {
            mlPredictions = computeMlPredictions(
                    game.getHomeTeam().getId(), game.getAwayTeam().getId(),
                    game.getGameDate(), game.getSeason(),
                    neutral, Boolean.TRUE.equals(game.getConferenceGame()), ratings, cache);
        }

        Integer actualHomeScore = null, actualAwayScore = null, actualMargin = null, actualTotal = null;
        if (game.getStatus() == Game.GameStatus.FINAL
                && game.getHomeScore() != null && game.getAwayScore() != null) {
            actualHomeScore = game.getHomeScore();
            actualAwayScore = game.getAwayScore();
            actualMargin    = game.getHomeScore() - game.getAwayScore();
            actualTotal     = game.getHomeScore() + game.getAwayScore();
        }

        PredictionResult result = new PredictionResult(
                game.getId(), game.getGameDate().toLocalDate(), game.getStatus(),
                game.getNeutralSite(), homeTeam, awayTeam,
                actualHomeScore, actualAwayScore, actualMargin, actualTotal,
                massey, masseyTotal, bt, btWeighted, adjEff,
                mlPredictions.defaultPrediction(), mlPredictions.active(),
                bookSpread, bookOverUnder);
        return new InternalPrediction(result, mlPredictions.all());
    }

    // ── Snapshot fetch ────────────────────────────────────────────────────────

    /**
     * Fetches all team snapshots and params in a single logical pass — from the
     * repositories, or from the season cache when one is supplied (identical
     * semantics). HCA params are only fetched when the game is not at a neutral site
     * and both team snapshots are available (avoids unnecessary queries).
     */
    private GameRatings fetchGameRatings(Long homeId, Long awayId, Long seasonId,
                                          LocalDate cutoff, boolean neutral,
                                          SeasonPredictionCache cache) {
        var masseyHome = latestRating(cache, homeId, seasonId, MasseyRatingService.MODEL_TYPE, cutoff);
        var masseyAway = latestRating(cache, awayId, seasonId, MasseyRatingService.MODEL_TYPE, cutoff);
        double masseyHca = 0;
        if (!neutral && masseyHome != null && masseyAway != null) {
            masseyHca = orZero(latestParam(cache, seasonId, MasseyRatingService.MODEL_TYPE, "hca", cutoff));
        }

        var masseyTotalHome = latestRating(cache, homeId, seasonId, MasseyRatingService.MODEL_TYPE_TOTALS, cutoff);
        var masseyTotalAway = latestRating(cache, awayId, seasonId, MasseyRatingService.MODEL_TYPE_TOTALS, cutoff);
        double masseyTotalIntercept = 0, masseyTotalDelta = 0;
        if (masseyTotalHome != null && masseyTotalAway != null) {
            masseyTotalIntercept = orZero(latestParam(cache, seasonId, MasseyRatingService.MODEL_TYPE_TOTALS, "intercept", cutoff));
            if (!neutral) {
                masseyTotalDelta = orZero(latestParam(cache, seasonId, MasseyRatingService.MODEL_TYPE_TOTALS, "hca_total", cutoff));
            }
        }

        var btHome = latestRating(cache, homeId, seasonId, BradleyTerryRatingService.MODEL_TYPE, cutoff);
        var btAway = latestRating(cache, awayId, seasonId, BradleyTerryRatingService.MODEL_TYPE, cutoff);
        double btAlpha = 0;
        if (!neutral && btHome != null && btAway != null) {
            btAlpha = orZero(latestParam(cache, seasonId, BradleyTerryRatingService.MODEL_TYPE, "hca", cutoff));
        }

        var btWeightedHome = latestRating(cache, homeId, seasonId, BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, cutoff);
        var btWeightedAway = latestRating(cache, awayId, seasonId, BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, cutoff);
        double btWeightedAlpha = 0;
        if (!neutral && btWeightedHome != null && btWeightedAway != null) {
            btWeightedAlpha = orZero(latestParam(cache, seasonId, BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, "hca", cutoff));
        }

        // Adjusted efficiency + tempo (ADJ_EFF model, also feeds eff-v4 ML features).
        // Intercept params are required (null ⇒ no prediction); HCA is zero on neutral floors.
        var adjOffHome   = latestRating(cache, homeId, seasonId, AdjustedEfficiencyRatingService.MODEL_TYPE_OFF, cutoff);
        var adjOffAway   = latestRating(cache, awayId, seasonId, AdjustedEfficiencyRatingService.MODEL_TYPE_OFF, cutoff);
        var adjDefHome   = latestRating(cache, homeId, seasonId, AdjustedEfficiencyRatingService.MODEL_TYPE_DEF, cutoff);
        var adjDefAway   = latestRating(cache, awayId, seasonId, AdjustedEfficiencyRatingService.MODEL_TYPE_DEF, cutoff);
        var adjTempoHome = latestRating(cache, homeId, seasonId, AdjustedEfficiencyRatingService.MODEL_TYPE_TEMPO, cutoff);
        var adjTempoAway = latestRating(cache, awayId, seasonId, AdjustedEfficiencyRatingService.MODEL_TYPE_TEMPO, cutoff);
        Double effIntercept = null, tempoIntercept = null;
        double effHca = 0;
        if (adjOffHome != null && adjOffAway != null && adjDefHome != null && adjDefAway != null
                && adjTempoHome != null && adjTempoAway != null) {
            effIntercept = latestParam(cache, seasonId, AdjustedEfficiencyRatingService.MODEL_TYPE_OFF, "eff_intercept", cutoff);
            tempoIntercept = latestParam(cache, seasonId, AdjustedEfficiencyRatingService.MODEL_TYPE_TEMPO, "tempo_intercept", cutoff);
            if (!neutral) {
                effHca = orZero(latestParam(cache, seasonId, AdjustedEfficiencyRatingService.MODEL_TYPE_OFF, "eff_hca", cutoff));
            }
        }

        return new GameRatings(
                masseyHome, masseyAway, masseyHca,
                masseyTotalHome, masseyTotalAway, masseyTotalIntercept, masseyTotalDelta,
                btHome, btAway, btAlpha,
                btWeightedHome, btWeightedAway, btWeightedAlpha,
                adjOffHome, adjOffAway, adjDefHome, adjDefAway,
                adjTempoHome, adjTempoAway, effIntercept, effHca, tempoIntercept);
    }

    /** Latest-strictly-before rating snapshot via the cache when present, else the repository. */
    private TeamPowerRatingSnapshot latestRating(SeasonPredictionCache cache, Long teamId, Long seasonId,
                                                 String modelType, LocalDate cutoff) {
        if (cache != null) return cache.latestRatingBefore(teamId, modelType, cutoff);
        return ratingRepository.findLatestBefore(teamId, seasonId, modelType, cutoff).orElse(null);
    }

    /** Latest-strictly-before param value via the cache when present, else the repository. */
    private Double latestParam(SeasonPredictionCache cache, Long seasonId, String modelType,
                               String paramName, LocalDate cutoff) {
        if (cache != null) return cache.latestParamBefore(modelType, paramName, cutoff);
        return paramRepository.findLatestParamBefore(seasonId, modelType, paramName, cutoff)
                .map(p -> p.getParamValue()).orElse(null);
    }

    private static double orZero(Double value) {
        return value != null ? value : 0.0;
    }

    // ── Phase 1 sub-block builders ────────────────────────────────────────────

    private PredictionResult.MasseyPrediction toMassey(GameRatings r) {
        if (!r.hasMassey()) return null;
        double spread = r.masseyHome().getRating() - r.masseyAway().getRating() + r.masseyHca();
        return new PredictionResult.MasseyPrediction(
                spread,
                WinProbability.fromMargin(spread, marginSigma),
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

    /**
     * ADJ_EFF prediction (spec W3-3): expected possessions {@code ν + τ_h + τ_a},
     * per-100 expected scores {@code eh = μ + off_h − def_a + η} and
     * {@code ea = μ + off_a − def_h − η} (η pre-zeroed for neutral sites), scaled
     * to points by {@code poss/100}; win probability Φ(spread/σ).
     */
    private PredictionResult.AdjEfficiencyPrediction toAdjEfficiency(GameRatings r) {
        if (!r.hasAdj()) return null;
        double poss = r.tempoIntercept() + r.adjTempoHome().getRating() + r.adjTempoAway().getRating();
        double eh = r.effIntercept() + r.adjOffHome().getRating() - r.adjDefAway().getRating() + r.effHca();
        double ea = r.effIntercept() + r.adjOffAway().getRating() - r.adjDefHome().getRating() - r.effHca();
        double spread = (eh - ea) * poss / 100.0;
        double total  = (eh + ea) * poss / 100.0;
        return new PredictionResult.AdjEfficiencyPrediction(
                spread, total,
                WinProbability.fromMargin(spread, marginSigma),
                r.adjOffHome().getGamesPlayed(), r.adjOffAway().getGamesPlayed(),
                earlierDate(r.adjOffHome().getSnapshotDate(), r.adjOffAway().getSnapshotDate()));
    }

    // ── ML scoring (Phase 3: per-bundle vectors from one shared context) ──────

    /**
     * Builds the context once, scores every evaluable bundle (ACTIVE + CANDIDATE),
     * and splits the results into public and shadow views.
     */
    private MlPredictions computeMlPredictions(Long homeId, Long awayId,
                                               LocalDateTime gameDatetime, Season season,
                                               boolean neutralSite, boolean conferenceGame,
                                               GameRatings r, SeasonPredictionCache cache) {
        MlModelRegistryService.ServingPlan plan = mlModelRegistryService.plan();
        if (!plan.hasServableModels()) {
            return MlPredictions.none();
        }

        PredictionContext context = buildContext(homeId, awayId, gameDatetime, season,
                neutralSite, conferenceGame, r, plan, cache);

        Map<String, PredictionResult.MlPrediction> all = new LinkedHashMap<>();
        for (String slug : plan.evaluableVersions().keySet()) {
            PredictionResult.MlPrediction prediction = mlPredictionService.predict(slug, context);
            if (prediction != null) {
                all.put(slug, prediction);
            }
        }
        Map<String, PredictionResult.MlPrediction> active = new LinkedHashMap<>();
        for (String slug : plan.activeVersions().keySet()) {
            PredictionResult.MlPrediction prediction = all.get(slug);
            if (prediction != null) {
                active.put(slug, prediction);
            }
        }
        PredictionResult.MlPrediction defaultPrediction =
                plan.defaultSlug() != null ? active.get(plan.defaultSlug()) : null;
        return new MlPredictions(defaultPrediction, Map.copyOf(active), Map.copyOf(all));
    }

    private PredictionContext buildContext(Long homeId, Long awayId,
                                           LocalDateTime gameDatetime, Season season,
                                           boolean neutralSite, boolean conferenceGame,
                                           GameRatings r, MlModelRegistryService.ServingPlan plan,
                                           SeasonPredictionCache cache) {
        Long seasonId = season.getId();
        // 10 most recent (newest first) feed both the 5- and 10-game windows
        List<Game> homeRecent = cache != null
                ? cache.recentFinalGames(homeId, gameDatetime, 10)
                : gameRepository.findRecentFinalGamesForTeam(homeId, seasonId, gameDatetime, PageRequest.of(0, 10));
        List<Game> awayRecent = cache != null
                ? cache.recentFinalGames(awayId, gameDatetime, 10)
                : gameRepository.findRecentFinalGamesForTeam(awayId, seasonId, gameDatetime, PageRequest.of(0, 10));
        List<Game> homeLast5 = homeRecent.subList(0, Math.min(5, homeRecent.size()));
        List<Game> awayLast5 = awayRecent.subList(0, Math.min(5, awayRecent.size()));

        RollingStats homeStats   = computeRolling(homeId, homeLast5);
        RollingStats awayStats   = computeRolling(awayId, awayLast5);
        RollingStats homeStats10 = computeRolling(homeId, homeRecent);
        RollingStats awayStats10 = computeRolling(awayId, awayRecent);

        Integer homeDaysRest = daysRest(homeId, homeLast5, gameDatetime);
        Integer awayDaysRest = daysRest(awayId, awayLast5, gameDatetime);

        int seasonWeek = (int) (ChronoUnit.DAYS.between(
                season.getStartDate(), gameDatetime.toLocalDate()) / 7) + 1;

        Map<String, Double> homeBox = Map.of();
        Map<String, Double> awayBox = Map.of();
        Double homeRpi = null, awayRpi = null;
        Double homeStddevMargin = null, awayStddevMargin = null;
        Double homeRpiOwp = null, awayRpiOwp = null;
        if (plan.needsExtendedStats()) {
            LocalDate cutoff = gameDatetime.toLocalDate();
            if (cache != null) {
                homeBox = cache.latestBoxStatsBefore(homeId, cutoff);
                awayBox = cache.latestBoxStatsBefore(awayId, cutoff);
                SeasonPredictionCache.SeasonStats homeSeason = cache.latestSeasonStatsBefore(homeId, cutoff);
                SeasonPredictionCache.SeasonStats awaySeason = cache.latestSeasonStatsBefore(awayId, cutoff);
                if (homeSeason != null) {
                    homeRpi = homeSeason.rpi();
                    homeStddevMargin = homeSeason.stddevMargin();
                    homeRpiOwp = homeSeason.rpiOwp();
                }
                if (awaySeason != null) {
                    awayRpi = awaySeason.rpi();
                    awayStddevMargin = awaySeason.stddevMargin();
                    awayRpiOwp = awaySeason.rpiOwp();
                }
            } else {
                homeBox = toStatMap(teamStatSnapshotRepository.findLatestBefore(homeId, seasonId, cutoff));
                awayBox = toStatMap(teamStatSnapshotRepository.findLatestBefore(awayId, seasonId, cutoff));
                TeamSeasonStatSnapshot homeSeason = teamSeasonStatSnapshotRepository
                        .findLatestBefore(homeId, seasonId, cutoff).orElse(null);
                TeamSeasonStatSnapshot awaySeason = teamSeasonStatSnapshotRepository
                        .findLatestBefore(awayId, seasonId, cutoff).orElse(null);
                if (homeSeason != null) {
                    homeRpi = homeSeason.getRpi();
                    homeStddevMargin = homeSeason.getStddevMargin();
                    homeRpiOwp = homeSeason.getRpiOwp();
                }
                if (awaySeason != null) {
                    awayRpi = awaySeason.getRpi();
                    awayStddevMargin = awaySeason.getStddevMargin();
                    awayRpiOwp = awaySeason.getRpiOwp();
                }
            }
        }

        // Preseason priors: previous season's FINAL ratings, both-or-neither per side
        Double homePrevBeta = null, awayPrevBeta = null, homePrevTheta = null, awayPrevTheta = null;
        if (plan.needsPriorRatings()) {
            double[] homePrev, awayPrev;
            if (cache != null) {
                homePrev = cache.priorRatings(homeId);
                awayPrev = cache.priorRatings(awayId);
            } else {
                Long priorSeasonId = seasonRepository.findByYear(season.getYear() - 1)
                        .map(Season::getId).orElse(null);
                homePrev = priorSeasonId != null ? priorRatings(homeId, priorSeasonId) : null;
                awayPrev = priorSeasonId != null ? priorRatings(awayId, priorSeasonId) : null;
            }
            if (homePrev != null) { homePrevBeta = homePrev[0]; homePrevTheta = homePrev[1]; }
            if (awayPrev != null) { awayPrevBeta = awayPrev[0]; awayPrevTheta = awayPrev[1]; }
        }

        Double homeMasseyResid = null, awayMasseyResid = null;
        if (plan.needsResidualForm()) {
            homeMasseyResid = masseyResidual(homeId, seasonId, homeLast5, cache);
            awayMasseyResid = masseyResidual(awayId, seasonId, awayLast5, cache);
        }

        // Already fetched (same model types, same latest-before-game-date cutoff) in
        // fetchGameRatings — reuse rather than re-query.
        Double homeAdjOff = null, awayAdjOff = null, homeAdjDef = null, awayAdjDef = null;
        if (plan.needsAdjEfficiency()) {
            homeAdjOff = r.adjOffHome() != null ? r.adjOffHome().getRating() : null;
            homeAdjDef = r.adjDefHome() != null ? r.adjDefHome().getRating() : null;
            awayAdjOff = r.adjOffAway() != null ? r.adjOffAway().getRating() : null;
            awayAdjDef = r.adjDefAway() != null ? r.adjDefAway().getRating() : null;
        }

        return new PredictionContext(
                r.masseyHome().getRating(), r.masseyAway().getRating(),
                r.masseyTotalHome().getRating(), r.masseyTotalAway().getRating(),
                r.btHome().getRating(), r.btAway().getRating(), r.btAlpha(),
                r.btWeightedHome().getRating(), r.btWeightedAway().getRating(), r.btWeightedAlpha(),
                homeStats.winPct(), homeStats.avgMargin(), homeStats.avgTotal(), homeStats.marginStddev(),
                awayStats.winPct(), awayStats.avgMargin(), awayStats.avgTotal(), awayStats.marginStddev(),
                r.masseyHome().getGamesPlayed(), r.masseyAway().getGamesPlayed(),
                homeDaysRest, awayDaysRest, seasonWeek,
                neutralSite, conferenceGame,
                homeBox, awayBox, homeRpi, awayRpi,
                homeStddevMargin, awayStddevMargin, homeRpiOwp, awayRpiOwp,
                homeStats10.winPct(), awayStats10.winPct(),
                homeStats10.avgMargin(), awayStats10.avgMargin(),
                homePrevBeta, awayPrevBeta, homePrevTheta, awayPrevTheta,
                homeMasseyResid, awayMasseyResid,
                homeAdjOff, awayAdjOff, homeAdjDef, awayAdjDef,
                r.masseyHca());
    }

    /** [β, θ] from the previous season's final snapshots, or null unless BOTH exist. */
    private double[] priorRatings(Long teamId, Long priorSeasonId) {
        var beta = ratingRepository.findLatest(teamId, priorSeasonId, MasseyRatingService.MODEL_TYPE)
                .orElse(null);
        var theta = ratingRepository.findLatest(teamId, priorSeasonId, BradleyTerryRatingService.MODEL_TYPE)
                .orElse(null);
        if (beta == null || theta == null) return null;
        return new double[]{beta.getRating(), theta.getRating()};
    }

    /**
     * Hot/cold vs rating: mean over the team's recent games of (actual margin from the
     * team's perspective − Massey-predicted margin), each prediction using the ratings
     * and HCA as of that PAST game's date. Past games lacking a prior snapshot for
     * either participant are skipped; null when no usable game exists. Mirrors the
     * trainer's massey_residual_l5 exactly.
     */
    private Double masseyResidual(Long teamId, Long seasonId, List<Game> recentGames,
                                  SeasonPredictionCache cache) {
        double sum = 0;
        int n = 0;
        for (Game g : recentGames) {
            LocalDate date = g.getGameDate().toLocalDate();
            var snapHome = latestRating(cache, g.getHomeTeam().getId(), seasonId, MasseyRatingService.MODEL_TYPE, date);
            var snapAway = latestRating(cache, g.getAwayTeam().getId(), seasonId, MasseyRatingService.MODEL_TYPE, date);
            if (snapHome == null || snapAway == null) {
                continue;
            }
            double hca = Boolean.TRUE.equals(g.getNeutralSite()) ? 0.0
                    : orZero(latestParam(cache, seasonId, MasseyRatingService.MODEL_TYPE, "hca", date));
            double residHome = (g.getHomeScore() - g.getAwayScore())
                    - (snapHome.getRating() - snapAway.getRating() + hca);
            sum += g.getHomeTeam().getId().equals(teamId) ? residHome : -residHome;
            n++;
        }
        return n == 0 ? null : sum / n;
    }

    private static Map<String, Double> toStatMap(List<TeamStatSnapshot> snapshots) {
        Map<String, Double> byName = new LinkedHashMap<>();
        for (TeamStatSnapshot s : snapshots) {
            byName.put(s.getStatName(), s.getValue());
        }
        return byName;
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
            TeamPowerRatingSnapshot btWeightedHome, TeamPowerRatingSnapshot btWeightedAway, double btWeightedAlpha,
            TeamPowerRatingSnapshot adjOffHome, TeamPowerRatingSnapshot adjOffAway,
            TeamPowerRatingSnapshot adjDefHome, TeamPowerRatingSnapshot adjDefAway,
            TeamPowerRatingSnapshot adjTempoHome, TeamPowerRatingSnapshot adjTempoAway,
            Double effIntercept, double effHca, Double tempoIntercept
    ) {
        boolean hasMassey()      { return masseyHome != null && masseyAway != null; }
        boolean hasMasseyTotal() { return masseyTotalHome != null && masseyTotalAway != null; }
        boolean hasBt()          { return btHome != null && btAway != null; }
        boolean hasBtWeighted()  { return btWeightedHome != null && btWeightedAway != null; }
        // No imputation: every input must exist before the game date (spec W3-4)
        boolean hasAdj()         { return adjOffHome != null && adjOffAway != null
                && adjDefHome != null && adjDefAway != null
                && adjTempoHome != null && adjTempoAway != null
                && effIntercept != null && tempoIntercept != null; }
        // The ML models are trained only on games where all four rating models have
        // snapshots — the feature vector must never be built with imputed ratings.
        boolean hasAll()         { return hasMassey() && hasMasseyTotal() && hasBt() && hasBtWeighted(); }
    }

    /** Rolling aggregate stats for a team over their last N games. Nullable when window is empty. */
    private record RollingStats(Double winPct, Double avgMargin, Double avgTotal, Double marginStddev) {}

    /**
     * ML outputs for one game: the default model, the public ACTIVE map, and the full
     * evaluable map (ACTIVE + CANDIDATE shadow models — evaluation only, never public).
     */
    private record MlPredictions(PredictionResult.MlPrediction defaultPrediction,
                                 Map<String, PredictionResult.MlPrediction> active,
                                 Map<String, PredictionResult.MlPrediction> all) {
        static MlPredictions none() {
            return new MlPredictions(null, Map.of(), Map.of());
        }
    }

    /** A public result plus the shadow-model predictions used only by evaluation. */
    record InternalPrediction(PredictionResult result,
                              Map<String, PredictionResult.MlPrediction> allMlPredictions) {}

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
