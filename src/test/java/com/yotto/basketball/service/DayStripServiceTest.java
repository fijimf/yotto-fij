package com.yotto.basketball.service;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.BettingOdds;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.PredictionEvaluation;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.BettingOddsRepository;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.PredictionEvaluationRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamRepository;
import com.yotto.basketball.service.DayStripService.DayStrip;
import com.yotto.basketball.service.DayStripService.StripGame;
import com.yotto.basketball.util.EasternDates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DayStripServiceTest extends BaseIntegrationTest {

    @Autowired DayStripService service;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired GameRepository gameRepo;
    @Autowired BettingOddsRepository oddsRepo;
    @Autowired PredictionEvaluationRepository evalRepo;

    static final LocalDate D = LocalDate.of(2026, 1, 16);
    Season season; Team a, b, c, d;

    @BeforeEach
    void setUp() {
        season = new Season();
        season.setYear(2026);
        season.setStartDate(LocalDate.of(2025, 11, 1));
        season.setEndDate(LocalDate.of(2026, 4, 30));
        seasonRepo.save(season);
        a = mkTeam("Alabama", "ALA"); b = mkTeam("Auburn", "AUB"); c = mkTeam("Colgate", "COLG"); d = mkTeam("Duke", "DUKE");
    }

    @Test
    void build_usesEvaluationsForFinals_orientsToFavorite_andFallsBackToSpreadForBook() {
        // 7pm: home ALA favored 70% by the model, 65% by the book; AUB wins → upset
        Game g1 = mkGame(a, b, 70, 75, Game.GameStatus.FINAL, D, 19);
        eval(g1, "MASSEY", 0.70); eval(g1, "BOOK", 0.65);
        // 2pm: away DUKE favored (home prob 0.30), no BOOK eval but a -3 home spread; DUKE wins → held
        Game g2 = mkGame(c, d, 60, 80, Game.GameStatus.FINAL, D, 14);
        eval(g2, "MASSEY", 0.30);
        BettingOdds odds = new BettingOdds(); odds.setGame(g2); odds.setSpread(new BigDecimal("-3.0")); oddsRepo.save(odds);
        // a postponed game never appears
        mkGame(a, c, 0, 0, Game.GameStatus.POSTPONED, D, 21);

        DayStrip strip = service.build(D);

        assertThat(strip.games()).extracting(StripGame::favAbbr).containsExactly("DUKE", "ALA");   // tip order
        StripGame duke = strip.games().get(0), ala = strip.games().get(1);
        assertThat(duke.favIsHome()).isFalse();
        assertThat(duke.favProb()).isEqualTo(0.70);
        assertThat(duke.outcome()).isEqualTo("HELD");
        assertThat(duke.favScore()).isEqualTo(80);
        // book: Φ(3/11) ≈ 0.607 for the home side → 0.393 for the away favorite
        assertThat(duke.bookFavProb()).isCloseTo(0.393, within(0.01));
        assertThat(ala.favIsHome()).isTrue();
        assertThat(ala.favProb()).isEqualTo(0.70);
        assertThat(ala.bookFavProb()).isEqualTo(0.65);
        assertThat(ala.outcome()).isEqualTo("UPSET");
        assertThat(ala.barPct()).isEqualTo(40);
        assertThat(ala.bookPct()).isEqualTo(30);
        assertThat(strip.finals()).isEqualTo(2);
        assertThat(strip.upsets()).isEqualTo(1);
    }

    @Test
    void build_emptyDayIsEmpty_andGamesWithoutAModelViewAreSkipped() {
        assertThat(service.build(D).isEmpty()).isTrue();
        mkGame(a, b, 0, 0, Game.GameStatus.SCHEDULED, D, 19);   // no evaluation, no ratings → no model prob
        assertThat(service.build(D).isEmpty()).isTrue();
    }

    private Team mkTeam(String name, String abbr) {
        Team t = new Team(); t.setName(name); t.setAbbreviation(abbr); t.setEspnId(abbr.toLowerCase()); t.setActive(true);
        return teamRepo.save(t);
    }

    private Game mkGame(Team home, Team away, int hs, int as, Game.GameStatus st, LocalDate easternDate, int hour) {
        Game g = new Game();
        g.setHomeTeam(home); g.setAwayTeam(away); g.setSeason(season); g.setStatus(st);
        if (st == Game.GameStatus.FINAL) { g.setHomeScore(hs); g.setAwayScore(as); }
        g.setGameDate(easternDate.atTime(hour, 0).atZone(ZoneId.of("America/New_York")).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
        return gameRepo.save(g);
    }

    private void eval(Game g, String modelType, double homeWinProb) {
        PredictionEvaluation pe = new PredictionEvaluation();
        pe.setGame(g); pe.setSeason(season); pe.setModelType(modelType);
        pe.setGameDate(EasternDates.toEasternDate(g.getGameDate()));
        pe.setPredictedHomeWinProb(homeWinProb);
        int margin = g.getHomeScore() - g.getAwayScore();
        pe.setActualMargin(margin); pe.setActualTotal(g.getHomeScore() + g.getAwayScore()); pe.setHomeWon(margin > 0);
        pe.setEvaluatedAt(LocalDateTime.of(2026, 4, 30, 12, 0));
        evalRepo.save(pe);
    }
}
