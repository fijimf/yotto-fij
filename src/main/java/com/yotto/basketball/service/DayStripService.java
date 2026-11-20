package com.yotto.basketball.service;

import com.yotto.basketball.config.CacheConfig;
import com.yotto.basketball.entity.BettingOdds;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Game.GameStatus;
import com.yotto.basketball.entity.PredictionEvaluation;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.PredictionEvaluationRepository;
import com.yotto.basketball.util.EasternDates;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The "upset-o-meter": one bar per game on a date, sized by the site's default
 * model's win probability for the favorite (short bar = toss-up), with a tick
 * for the book's implied probability and, once final, an outcome.
 *
 * <p>Played games use the leakage-free {@code prediction_evaluations} rows
 * (model + BOOK) so the bars show what was believed before tip; games without
 * evaluations yet fall back to live predictions and the current odds.
 */
@Service
public class DayStripService {

    public record StripGame(long gameId, LocalDateTime tipEastern,
                            String favName, String favAbbr, String dogName, String dogAbbr,
                            boolean favIsHome, boolean neutralSite,
                            double favProb, Double bookFavProb,
                            GameStatus status, Integer favScore, Integer dogScore,
                            /** "HELD" | "UPSET" | null (not final) */ String outcome) {

        /** Bar height: 50% → floor, 100% → full. */
        public int barPct() { return (int) Math.round(Math.max(0.06, (favProb - 0.5) / 0.5) * 100); }
        public Integer bookPct() {
            return bookFavProb == null ? null : (int) Math.round(Math.max(0.0, Math.min(1.0, (bookFavProb - 0.5) / 0.5)) * 100);
        }
        public boolean isFinal() { return status == GameStatus.FINAL; }
        public boolean isLive() { return status == GameStatus.IN_PROGRESS; }
        public boolean isUpset() { return "UPSET".equals(outcome); }
        public String probLabel() { return Math.round(favProb * 100) + "%"; }
        public String bookLabel() { return bookFavProb == null ? null : Math.round(bookFavProb * 100) + "%"; }
    }

    public record DayStrip(LocalDate date, String modelLabel, List<StripGame> games, int finals, int upsets) {
        public boolean isEmpty() { return games.isEmpty(); }
    }

    private final GameRepository gameRepository;
    private final PredictionService predictionService;
    private final PredictionsPageService predictionsPageService;
    private final PredictionEvaluationRepository evaluationRepository;
    private final double marginSigma;

    public DayStripService(GameRepository gameRepository,
                           PredictionService predictionService,
                           PredictionsPageService predictionsPageService,
                           PredictionEvaluationRepository evaluationRepository,
                           @Value("${app.prediction.margin-sigma:11.0}") double marginSigma) {
        this.gameRepository = gameRepository;
        this.predictionService = predictionService;
        this.predictionsPageService = predictionsPageService;
        this.evaluationRepository = evaluationRepository;
        this.marginSigma = marginSigma;
    }

    /** Cached per date (evicted with the page caches after scrapes); empty days are never cached. */
    @Cacheable(value = CacheConfig.CHART_DAY_STRIP, key = "#date", unless = "#result.isEmpty()")
    @Transactional(readOnly = true)
    public DayStrip build(LocalDate date) {
        String modelKey = predictionsPageService.defaultModelKey();
        String modelLabel = predictionsPageService.modelLabel(modelKey);
        String evalType = evaluationModelType(modelKey);

        Map<Long, Double> modelEval = new HashMap<>(), bookEval = new HashMap<>();
        for (PredictionEvaluation e : evaluationRepository.findByModelAndDate(evalType, date)) {
            if (e.getPredictedHomeWinProb() != null) modelEval.put(e.getGame().getId(), e.getPredictedHomeWinProb());
        }
        for (PredictionEvaluation e : evaluationRepository.findByModelAndDate(PredictionEvaluationService.MODEL_BOOK, date)) {
            if (e.getPredictedHomeWinProb() != null) bookEval.put(e.getGame().getId(), e.getPredictedHomeWinProb());
        }

        LocalDateTime[] w = EasternDates.dayWindowUtc(date);
        List<StripGame> out = new ArrayList<>();
        int finals = 0, upsets = 0;
        for (Game g : gameRepository.findInUtcWindow(w[0], w[1])) {
            if (g.getStatus() == GameStatus.CANCELLED || g.getStatus() == GameStatus.POSTPONED) continue;

            Double homeProb = modelEval.get(g.getId());
            if (homeProb == null) {
                homeProb = PredictionCardView.from(predictionService.buildPrediction(g), modelKey).predHomeWinProb();
            }
            if (homeProb == null) continue;   // no model view of this game (e.g. non-D1 opponent)

            Double bookHome = bookEval.get(g.getId());
            if (bookHome == null) bookHome = bookHomeProb(g.getBettingOdds());

            boolean favIsHome = homeProb >= 0.5;
            double favProb = favIsHome ? homeProb : 1 - homeProb;
            Double bookFav = bookHome == null ? null : (favIsHome ? bookHome : 1 - bookHome);

            String outcome = null;
            Integer favScore = null, dogScore = null;
            if (g.getStatus() == GameStatus.FINAL && g.getHomeScore() != null && g.getAwayScore() != null) {
                favScore = favIsHome ? g.getHomeScore() : g.getAwayScore();
                dogScore = favIsHome ? g.getAwayScore() : g.getHomeScore();
                outcome = favScore > dogScore ? "HELD" : "UPSET";
                finals++;
                if ("UPSET".equals(outcome)) upsets++;
            }
            var fav = favIsHome ? g.getHomeTeam() : g.getAwayTeam();
            var dog = favIsHome ? g.getAwayTeam() : g.getHomeTeam();
            out.add(new StripGame(g.getId(), EasternDates.toEasternTime(g.getGameDate()),
                    fav.getName(), abbr(fav.getAbbreviation(), fav.getName()),
                    dog.getName(), abbr(dog.getAbbreviation(), dog.getName()),
                    favIsHome, Boolean.TRUE.equals(g.getNeutralSite()),
                    favProb, bookFav, g.getStatus(), favScore, dogScore, outcome));
        }
        out.sort(Comparator.comparing(StripGame::tipEastern).thenComparing(StripGame::gameId));
        return new DayStrip(date, modelLabel, out, finals, upsets);
    }

    /** Book's home win probability: de-vigged moneylines, else Φ(−spread/σ) (spread is a handicap). */
    private Double bookHomeProb(BettingOdds odds) {
        if (odds == null) return null;
        Double p = PredictionEvaluationService.impliedHomeWinProb(odds.getHomeMoneyline(), odds.getAwayMoneyline());
        if (p == null && odds.getSpread() != null) p = WinProbability.fromMargin(-odds.getSpread().doubleValue(), marginSigma);
        return p;
    }

    static String evaluationModelType(String modelKey) {
        if (modelKey != null && modelKey.startsWith(PredictionCardView.ML_PREFIX)) {
            return PredictionEvaluationService.ML_TYPE_PREFIX + modelKey.substring(PredictionCardView.ML_PREFIX.length());
        }
        return "MASSEY";
    }

    private static String abbr(String abbr, String name) {
        return abbr != null && !abbr.isBlank() ? abbr : name;
    }
}
