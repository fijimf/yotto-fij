package com.yotto.basketball.service;

import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.TeamStatSnapshot;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamStatSnapshotRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Assembles a stat-category landing page (/seasons/{year}/stats/{categorySlug}):
 * one card per stat in the category with its top-10 leaderboard as of a date.
 */
@Service
public class StatCategoryPageService {

    private static final int LEADERS_PER_CARD = 10;

    private final SeasonRepository seasonRepository;
    private final TeamStatSnapshotRepository snapshotRepository;

    public StatCategoryPageService(SeasonRepository seasonRepository,
                                   TeamStatSnapshotRepository snapshotRepository) {
        this.seasonRepository = seasonRepository;
        this.snapshotRepository = snapshotRepository;
    }

    public record RankRow(int rank, Long teamId, String teamName, String logoUrl, Double value) {}

    public record CategoryCard(StatCatalog.StatInfo info, List<RankRow> leaders) {}

    public record CategoryPage(StatCategory category,
                               int year,
                               List<Integer> availableSeasons,
                               LocalDate date,
                               LocalDate latestDate,
                               LocalDate seasonStart,
                               List<CategoryCard> cards) {

        public boolean hasData() {
            return cards.stream().anyMatch(c -> !c.leaders().isEmpty());
        }
    }

    @Transactional(readOnly = true)
    public CategoryPage build(int year, StatCategory category, LocalDate requestedDate) {
        Season season = seasonRepository.findByYear(year)
                .orElseThrow(() -> new EntityNotFoundException("Season not found: " + year));

        LocalDate latestDate = snapshotRepository.findLatestSnapshotDate(season.getId()).orElse(null);
        LocalDate date = requestedDate != null ? requestedDate : latestDate;

        List<CategoryCard> cards = new ArrayList<>();
        for (StatCatalog.StatInfo info : category.stats()) {
            List<RankRow> leaders = date == null ? List.of()
                    : snapshotRepository.findBySeasonStatAndDate(season.getId(), info.name(), date).stream()
                            .limit(LEADERS_PER_CARD)
                            .map(StatCategoryPageService::toRow)
                            .toList();
            cards.add(new CategoryCard(info, leaders));
        }

        List<Integer> years = seasonRepository.findAll().stream()
                .map(Season::getYear)
                .sorted(java.util.Comparator.reverseOrder())
                .toList();

        return new CategoryPage(category, year, years, date, latestDate, season.getStartDate(), cards);
    }

    private static RankRow toRow(TeamStatSnapshot s) {
        return new RankRow(s.getRank() != null ? s.getRank() : 0,
                s.getTeam().getId(), s.getTeam().getName(), s.getTeam().getLogoUrl(), s.getValue());
    }
}
