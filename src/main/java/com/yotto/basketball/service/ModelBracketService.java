package com.yotto.basketball.service;

import com.yotto.basketball.controller.TournamentRounds;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.PredictionEvaluation;
import com.yotto.basketball.repository.PredictionEvaluationRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.service.PublicModelService.PublicModel;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A model's NCAA bracket overlay (/models/{slug}/bracket/{year}, spec §7.4):
 * the season's bracket with each decided game marked right/wrong from the
 * model's leakage-free pre-game evaluation row, plus record-by-round, log
 * loss, and upset accounting. Matchup-by-matchup only — full pre-tournament
 * simulation is future work (OQ-4).
 */
@Service
public class ModelBracketService {

    private final PublicModelService publicModelService;
    private final BracketService bracketService;
    private final PredictionEvaluationRepository evaluationRepository;
    private final SeasonRepository seasonRepository;

    public ModelBracketService(PublicModelService publicModelService,
                               BracketService bracketService,
                               PredictionEvaluationRepository evaluationRepository,
                               SeasonRepository seasonRepository) {
        this.publicModelService = publicModelService;
        this.bracketService = bracketService;
        this.evaluationRepository = evaluationRepository;
        this.seasonRepository = seasonRepository;
    }

    /** One game's model verdict; correct is null while the game is undecided. */
    public record Verdict(String label, Boolean correct) {}

    public record RoundRecord(String round, int correct, int total) {}

    public record BracketOverlayPage(PublicModel model,
                                     int year,
                                     List<Integer> years,
                                     BracketView bracket,
                                     Map<Long, Verdict> overlay,
                                     List<RoundRecord> byRound,
                                     int correct, int decided,
                                     Double logLoss,
                                     int upsetsCalled, int upsetsMissed) {

        public boolean hasPredictions() { return !overlay.isEmpty(); }
    }

    /** Seasons with NCAA tournament games, newest first (year-tab source). */
    @Transactional(readOnly = true)
    public List<Integer> bracketYears() {
        return bracketService.bracketYears();
    }

    @Transactional(readOnly = true)
    public BracketOverlayPage build(String slug, int year) {
        PublicModel model = publicModelService.find(slug)
                .orElseThrow(() -> new EntityNotFoundException("Unknown model: " + slug));

        BracketView bracket = bracketService.buildBracket(year).orElse(null);
        Long seasonId = seasonRepository.findByYear(year)
                .orElseThrow(() -> new EntityNotFoundException("Season not found: " + year))
                .getId();

        List<PredictionEvaluation> evals = evaluationRepository.findByModelSeasonAndTournamentType(
                model.modelType(), seasonId, Game.TournamentType.NCAA_TOURNAMENT);

        Map<Long, Verdict> overlay = new HashMap<>();
        Map<String, int[]> roundTallies = new HashMap<>();  // round → [correct, total]
        int correct = 0, decided = 0, upsetsCalled = 0, upsetsMissed = 0;
        double logLossSum = 0;
        int logLossN = 0;

        for (PredictionEvaluation e : evals) {
            Game g = e.getGame();
            if (e.getPredictedSpread() == null && e.getPredictedHomeWinProb() == null) continue;

            boolean homePick = pickIsHome(e);
            Verdict verdict = toVerdict(e, homePick);
            overlay.put(g.getId(), verdict);

            if (verdict.correct() != null) {
                decided++;
                if (verdict.correct()) correct++;
                String round = g.getTournamentRound() != null ? g.getTournamentRound() : "Other";
                roundTallies.computeIfAbsent(round, k -> new int[2]);
                roundTallies.get(round)[1]++;
                if (verdict.correct()) roundTallies.get(round)[0]++;

                // Upsets: seed favorite = lower seed number; only when seeds differ
                Integer hs = g.getHomeSeed(), as = g.getAwaySeed();
                if (hs != null && as != null && !hs.equals(as)) {
                    boolean seedFavoriteIsHome = hs < as;
                    boolean modelPickedUpset = homePick != seedFavoriteIsHome;
                    boolean upsetHappened = e.getHomeWon() != seedFavoriteIsHome;
                    if (modelPickedUpset && upsetHappened) upsetsCalled++;
                    if (!modelPickedUpset && upsetHappened) upsetsMissed++;
                }
            }
            if (e.getPredictedHomeWinProb() != null && e.getHomeWon() != null) {
                double p = clamp(e.getPredictedHomeWinProb());
                logLossSum += e.getHomeWon() ? -Math.log(p) : -Math.log(1 - p);
                logLossN++;
            }
        }

        List<RoundRecord> byRound = roundTallies.entrySet().stream()
                .sorted(Comparator.comparingInt(en -> {
                    int idx = TournamentRounds.indexOf(en.getKey());
                    return idx >= 0 ? idx : TournamentRounds.ORDER.size();
                }))
                .map(en -> new RoundRecord(en.getKey(), en.getValue()[0], en.getValue()[1]))
                .toList();

        return new BracketOverlayPage(model, year, bracketService.bracketYears(),
                bracket, overlay, byRound, correct, decided,
                logLossN > 0 ? logLossSum / logLossN : null,
                upsetsCalled, upsetsMissed);
    }

    /** The model's pick is home when the predicted margin is positive (or prob ≥ 50%). */
    private static boolean pickIsHome(PredictionEvaluation e) {
        if (e.getPredictedSpread() != null) return e.getPredictedSpread() > 0;
        return e.getPredictedHomeWinProb() != null && e.getPredictedHomeWinProb() >= 0.5;
    }

    private static Verdict toVerdict(PredictionEvaluation e, boolean homePick) {
        Game g = e.getGame();
        String pickName = abbr(homePick ? g.getHomeTeam() : g.getAwayTeam());
        Double prob = e.getPredictedHomeWinProb();
        double pickProb = prob == null ? Double.NaN : (homePick ? prob : 1 - prob);
        String label = Double.isNaN(pickProb) ? pickName
                : String.format(Locale.US, "%s %.0f%%", pickName, pickProb * 100);
        Boolean correctPick = e.getHomeWon() == null ? null : homePick == e.getHomeWon();
        return new Verdict(label, correctPick);
    }

    private static String abbr(com.yotto.basketball.entity.Team team) {
        String a = team.getAbbreviation();
        return a != null && !a.isBlank() ? a : team.getName();
    }

    private static double clamp(double p) {
        return Math.min(0.999999, Math.max(0.000001, p));
    }
}
