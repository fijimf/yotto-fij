package com.yotto.basketball.service;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.entity.TeamPowerRatingSnapshot;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.PowerModelParamSnapshotRepository;
import com.yotto.basketball.repository.TeamPowerRatingSnapshotRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Assembles game predictions from pre-computed Massey and Bradley-Terry snapshots.
 *
 * <p>All lookups use the most recent snapshot dated strictly before the game date,
 * ensuring only information available before tip-off is used.
 */
@Service
@Transactional(readOnly = true)
public class PredictionService {

    static final int DEFAULT_UPCOMING_DAYS = 7;
    static final int MAX_UPCOMING_DAYS     = 30;

    private final GameRepository gameRepository;
    private final TeamPowerRatingSnapshotRepository ratingRepository;
    private final PowerModelParamSnapshotRepository paramRepository;

    public PredictionService(GameRepository gameRepository,
                             TeamPowerRatingSnapshotRepository ratingRepository,
                             PowerModelParamSnapshotRepository paramRepository) {
        this.gameRepository  = gameRepository;
        this.ratingRepository = ratingRepository;
        this.paramRepository  = paramRepository;
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

    // ── Core prediction logic ─────────────────────────────────────────────────

    private PredictionResult buildPrediction(Game game) {
        PredictionResult.TeamSummary homeTeam = toTeamSummary(game.getHomeTeam());
        PredictionResult.TeamSummary awayTeam = toTeamSummary(game.getAwayTeam());

        // Postponed/cancelled games have no meaningful prediction
        if (game.getStatus() == Game.GameStatus.POSTPONED
                || game.getStatus() == Game.GameStatus.CANCELLED) {
            return new PredictionResult(
                    game.getId(), game.getGameDate().toLocalDate(), game.getStatus(),
                    game.getNeutralSite(), homeTeam, awayTeam,
                    null, null, null, null, null, null, null);
        }

        LocalDate cutoff  = game.getGameDate().toLocalDate();
        Long seasonId     = game.getSeason().getId();
        boolean neutral   = Boolean.TRUE.equals(game.getNeutralSite());
        Long homeId       = game.getHomeTeam().getId();
        Long awayId       = game.getAwayTeam().getId();

        PredictionResult.MasseyPrediction        massey      = buildMassey(homeId, awayId, seasonId, cutoff, neutral);
        PredictionResult.MasseyTotalPrediction   masseyTotal = buildMasseyTotal(homeId, awayId, seasonId, cutoff, neutral);
        PredictionResult.BradleyTerryPrediction  bt          = buildBradleyTerry(homeId, awayId, seasonId, cutoff, neutral);

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
                massey, masseyTotal, bt);
    }

    private PredictionResult.MasseyPrediction buildMassey(
            Long homeId, Long awayId, Long seasonId, LocalDate cutoff, boolean neutral) {
        Optional<TeamPowerRatingSnapshot> homeSnap =
                ratingRepository.findLatestBefore(homeId, seasonId, MasseyRatingService.MODEL_TYPE, cutoff);
        Optional<TeamPowerRatingSnapshot> awaySnap =
                ratingRepository.findLatestBefore(awayId, seasonId, MasseyRatingService.MODEL_TYPE, cutoff);
        if (homeSnap.isEmpty() || awaySnap.isEmpty()) return null;

        double hca = 0;
        if (!neutral) {
            hca = paramRepository.findLatestParamBefore(seasonId, MasseyRatingService.MODEL_TYPE, "hca", cutoff)
                    .map(p -> p.getParamValue()).orElse(0.0);
        }

        double spread = homeSnap.get().getRating() - awaySnap.get().getRating() + hca;
        return new PredictionResult.MasseyPrediction(
                spread,
                homeSnap.get().getGamesPlayed(),
                awaySnap.get().getGamesPlayed(),
                earlierDate(homeSnap.get().getSnapshotDate(), awaySnap.get().getSnapshotDate()));
    }

    private PredictionResult.MasseyTotalPrediction buildMasseyTotal(
            Long homeId, Long awayId, Long seasonId, LocalDate cutoff, boolean neutral) {
        Optional<TeamPowerRatingSnapshot> homeSnap =
                ratingRepository.findLatestBefore(homeId, seasonId, MasseyRatingService.MODEL_TYPE_TOTAL, cutoff);
        Optional<TeamPowerRatingSnapshot> awaySnap =
                ratingRepository.findLatestBefore(awayId, seasonId, MasseyRatingService.MODEL_TYPE_TOTAL, cutoff);
        if (homeSnap.isEmpty() || awaySnap.isEmpty()) return null;

        double delta = 0;
        if (!neutral) {
            delta = paramRepository.findLatestParamBefore(seasonId, MasseyRatingService.MODEL_TYPE_TOTAL, "hca_total", cutoff)
                    .map(p -> p.getParamValue()).orElse(0.0);
        }

        double total = homeSnap.get().getRating() + awaySnap.get().getRating() + delta;
        return new PredictionResult.MasseyTotalPrediction(
                total,
                homeSnap.get().getGamesPlayed(),
                awaySnap.get().getGamesPlayed(),
                earlierDate(homeSnap.get().getSnapshotDate(), awaySnap.get().getSnapshotDate()));
    }

    private PredictionResult.BradleyTerryPrediction buildBradleyTerry(
            Long homeId, Long awayId, Long seasonId, LocalDate cutoff, boolean neutral) {
        Optional<TeamPowerRatingSnapshot> homeSnap =
                ratingRepository.findLatestBefore(homeId, seasonId, BradleyTerryRatingService.MODEL_TYPE, cutoff);
        Optional<TeamPowerRatingSnapshot> awaySnap =
                ratingRepository.findLatestBefore(awayId, seasonId, BradleyTerryRatingService.MODEL_TYPE, cutoff);
        if (homeSnap.isEmpty() || awaySnap.isEmpty()) return null;

        double alpha = 0;
        if (!neutral) {
            alpha = paramRepository.findLatestParamBefore(seasonId, BradleyTerryRatingService.MODEL_TYPE, "hca", cutoff)
                    .map(p -> p.getParamValue()).orElse(0.0);
        }

        double logOdds = homeSnap.get().getRating() - awaySnap.get().getRating() + alpha;
        double pHome   = sigmoid(logOdds);
        double pAway   = 1.0 - pHome;
        return new PredictionResult.BradleyTerryPrediction(
                pHome, pAway,
                impliedMoneyline(pHome), impliedMoneyline(pAway),
                homeSnap.get().getGamesPlayed(),
                awaySnap.get().getGamesPlayed(),
                earlierDate(homeSnap.get().getSnapshotDate(), awaySnap.get().getSnapshotDate()));
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static PredictionResult.TeamSummary toTeamSummary(Team t) {
        return new PredictionResult.TeamSummary(t.getId(), t.getName(), t.getAbbreviation(), t.getLogoUrl());
    }

    private static LocalDate earlierDate(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    /**
     * No-vig fair American moneyline.
     * Favorite: −round(p/(1−p)×100). Underdog: +round((1−p)/p×100).
     */
    private static int impliedMoneyline(double p) {
        if (p >= 0.5) return -(int) Math.round(p / (1.0 - p) * 100);
        return  (int) Math.round((1.0 - p) / p * 100);
    }
}
