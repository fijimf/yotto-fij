package com.yotto.basketball.scraping;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.ScrapeBatch;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Reproduces the in-season shape of the scheduled re-scrape with no ESPN traffic:
 * ~115 future dates x 45 scheduled games, then a current-season re-scrape that
 * touches every one of them without changing anything. Run on demand only:
 * {@code ./mvnw test -Dtest=GameScraperRescrapeBenchmarkTest -Dbenchmark=true}
 */
@Tag("benchmark")
@EnabledIfSystemProperty(named = "benchmark", matches = "true")
class GameScraperRescrapeBenchmarkTest extends BaseIntegrationTest {

    static final int DATES = 115;
    static final int GAMES_PER_DATE = 45;
    static final int TEAMS = 90;

    @Autowired GameScraper gameScraper;
    @Autowired GameRepository gameRepository;
    @Autowired TeamRepository teamRepository;
    @Autowired SeasonRepository seasonRepository;
    @MockitoBean EspnApiClient espnApiClient;

    private final ObjectMapper mapper = new JsonMapper();

    @Test
    void rescrapeOfFullRemainingSchedule() throws Exception {
        for (int i = 1; i <= TEAMS; i++) {
            Team t = new Team();
            t.setEspnId(String.valueOf(i));
            t.setName("Team " + i);
            t.setAbbreviation("T" + i);
            t.setActive(true);
            teamRepository.save(t);
        }
        Season season = new Season();
        season.setYear(2027);
        season.setStartDate(LocalDate.of(2026, 11, 1));
        season.setEndDate(LocalDate.of(2027, 4, 30));
        seasonRepository.save(season);

        LocalDate today = LocalDate.now();
        when(espnApiClient.fetchScoreboard(any(LocalDate.class))).thenAnswer(inv -> {
            LocalDate d = inv.getArgument(0);
            long offset = java.time.temporal.ChronoUnit.DAYS.between(today, d);
            if (offset < 1 || offset > DATES) return mapper.readTree("{\"sports\":[{\"leagues\":[{\"events\":[]}]}]}");
            StringBuilder sb = new StringBuilder("{\"sports\":[{\"leagues\":[{\"events\":[");
            for (int g = 0; g < GAMES_PER_DATE; g++) {
                int home = (g * 2) % TEAMS + 1, away = (g * 2 + 1) % TEAMS + 1;
                if (g > 0) sb.append(',');
                sb.append("{\"id\":\"").append(4000000 + offset * 100 + g).append("\",")
                  .append("\"date\":\"").append(d).append("T00:00:00Z\",")
                  .append("\"location\":\"Arena\",\"neutralSite\":false,")
                  .append("\"competitors\":[{\"id\":\"").append(home).append("\",\"homeAway\":\"home\",\"score\":\"\"},")
                  .append("{\"id\":\"").append(away).append("\",\"homeAway\":\"away\",\"score\":\"\"}],")
                  .append("\"fullStatus\":{\"type\":{\"name\":\"STATUS_SCHEDULED\",\"state\":\"pre\"}}}");
            }
            return mapper.readTree(sb.append("]}]}]}").toString());
        });

        long t0 = System.nanoTime();
        ScrapeBatch seed = gameScraper.scrapeFullSeason(2027);
        long seedMs = (System.nanoTime() - t0) / 1_000_000;
        assertThat(seed.getRecordsCreated()).isEqualTo(DATES * GAMES_PER_DATE);

        long t1 = System.nanoTime();
        ScrapeBatch rescrape = gameScraper.scrapeCurrentSeason(2027);
        long rescrapeMs = (System.nanoTime() - t1) / 1_000_000;

        System.out.printf("%n=== BENCHMARK seed(full season, %d dates) = %d ms; rescrape(current season, %d dates, %d games) = %d ms%n%n",
                seed.getDatesSucceeded(), seedMs, rescrape.getDatesSucceeded(), rescrape.getRecordsUpdated(), rescrapeMs);
        assertThat(rescrape.getRecordsUpdated()).isEqualTo(DATES * GAMES_PER_DATE);
        assertThat(gameRepository.count()).isEqualTo(DATES * GAMES_PER_DATE);
    }
}
