package com.yotto.basketball.scraping;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.SeasonRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Re-runs TournamentClassifier over already-scraped Game rows, using the persisted
 * espn_note_raw + espn_season_type. No ESPN calls. Useful after tweaking the classifier.
 */
@Service
public class TournamentReclassifier {

    private static final Logger log = LoggerFactory.getLogger(TournamentReclassifier.class);

    private final GameRepository gameRepository;
    private final SeasonRepository seasonRepository;
    private final TournamentClassifier classifier;

    public TournamentReclassifier(GameRepository gameRepository,
                                  SeasonRepository seasonRepository,
                                  TournamentClassifier classifier) {
        this.gameRepository = gameRepository;
        this.seasonRepository = seasonRepository;
        this.classifier = classifier;
    }

    public record Result(int seasonYear, int total, int updated) {}

    @Transactional
    public Result reclassifySeason(int seasonYear) {
        Season season = seasonRepository.findByYear(seasonYear).orElse(null);
        if (season == null) {
            log.warn("Reclassify: season {} not found", seasonYear);
            return new Result(seasonYear, 0, 0);
        }

        List<Game> games = gameRepository.findBySeasonId(season.getId());
        int updated = 0;
        for (Game g : games) {
            String note = g.getEspnNoteRaw();
            String seasonType = g.getEspnSeasonType() == null ? null : g.getEspnSeasonType().toString();
            TournamentClassifier.Result tc = classifier.classify(
                    note, seasonType,
                    g.getGameDate() == null ? null : g.getGameDate().toLocalDate(),
                    g.getHomeTeam(), g.getAwayTeam(), season);

            if (changed(g, tc)) {
                g.setTournamentType(tc.type());
                g.setTournamentName(tc.name());
                g.setTournamentRound(tc.round());
                g.setTournamentRegion(tc.region());
                gameRepository.save(g);
                updated++;
            }
        }
        log.info("Reclassified season {}: {}/{} games updated", seasonYear, updated, games.size());
        return new Result(seasonYear, games.size(), updated);
    }

    private boolean changed(Game g, TournamentClassifier.Result tc) {
        return !java.util.Objects.equals(g.getTournamentType(), tc.type())
                || !java.util.Objects.equals(g.getTournamentName(), tc.name())
                || !java.util.Objects.equals(g.getTournamentRound(), tc.round())
                || !java.util.Objects.equals(g.getTournamentRegion(), tc.region());
    }
}
