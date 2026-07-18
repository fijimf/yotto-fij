package com.yotto.basketball.news;

import com.yotto.basketball.entity.ConferenceMembership;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.repository.ConferenceMembershipRepository;
import com.yotto.basketball.repository.SeasonRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Derives conference tags from tagged teams via season-scoped membership
 * (docs/NEWS_MODULE.md §5.8): each tagged team contributes its conference for
 * the season the article falls in, at half the team's confidence, so direct
 * conference mentions outrank incidental ones.
 */
@Component
public class ConferenceResolver {

    static final double DERIVED_CONFIDENCE_FACTOR = 0.5;

    private final SeasonRepository seasonRepository;
    private final ConferenceMembershipRepository membershipRepository;

    public ConferenceResolver(SeasonRepository seasonRepository,
                              ConferenceMembershipRepository membershipRepository) {
        this.seasonRepository = seasonRepository;
        this.membershipRepository = membershipRepository;
    }

    /**
     * Season attribution: seasons are named by their ending year, and an
     * offseason article (July onward) belongs to the upcoming season.
     */
    static int seasonYearFor(LocalDate date) {
        return date.getMonthValue() >= 7 ? date.getYear() + 1 : date.getYear();
    }

    /**
     * Merges derived conference scores into {@code directConferences} (from
     * gazetteer hits), keeping the higher confidence when both exist.
     */
    @Transactional(readOnly = true)
    public Map<Long, TagResult.TargetScore> resolve(Map<Long, TagResult.TargetScore> teamScores,
                                                    Map<Long, TagResult.TargetScore> directConferences,
                                                    LocalDate articleDate,
                                                    double minTeamScore) {
        Map<Long, TagResult.TargetScore> merged = new HashMap<>(directConferences);
        Optional<Season> season = seasonRepository.findByYear(seasonYearFor(articleDate));

        for (Map.Entry<Long, TagResult.TargetScore> entry : teamScores.entrySet()) {
            if (entry.getValue().score() < minTeamScore) {
                continue;
            }
            Optional<ConferenceMembership> membership = season
                    .flatMap(s -> membershipRepository.findByTeamIdAndSeasonId(entry.getKey(), s.getId()));
            if (membership.isEmpty()) {
                // upcoming season not populated yet (or historical gap): latest known membership
                membership = membershipRepository.findCurrentMembershipByTeamId(entry.getKey());
            }
            if (membership.isEmpty()) {
                continue;
            }
            Long conferenceId = membership.get().getConference().getId();
            TagResult.TargetScore teamScore = entry.getValue();
            double derivedConfidence = teamScore.confidence() * DERIVED_CONFIDENCE_FACTOR;
            TagResult.TargetScore existing = merged.get(conferenceId);
            if (existing == null || existing.confidence() < derivedConfidence) {
                merged.put(conferenceId, new TagResult.TargetScore(
                        teamScore.score() * DERIVED_CONFIDENCE_FACTOR,
                        derivedConfidence,
                        "via " + teamScore.matchedVia()));
            }
        }
        return merged;
    }
}
