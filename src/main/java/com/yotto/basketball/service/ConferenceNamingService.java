package com.yotto.basketball.service;

import com.yotto.basketball.entity.Conference;
import com.yotto.basketball.entity.ConferenceNameHistory;
import com.yotto.basketball.repository.ConferenceNameHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Resolves a conference's display identity (name/abbreviation/logo) for a given
 * season. The {@code conferences} row carries the current branding; superseded
 * brandings live in {@code conference_name_history} with the last season year
 * (inclusive) they applied to. Resolution picks the history row with the
 * smallest {@code lastSeasonYear >= seasonYear}, falling back to the canonical
 * row when none covers the season — a rule that composes across multiple
 * renames.
 */
@Service
public class ConferenceNamingService {

    private final ConferenceNameHistoryRepository historyRepository;

    public ConferenceNamingService(ConferenceNameHistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
    }

    /**
     * Loads all name history in one query. Call once per request and reuse the
     * returned snapshot when resolving many conferences (rankings, standings).
     */
    @Transactional(readOnly = true)
    public ConferenceNames load() {
        Map<Long, List<ConferenceNameHistory>> byConference = historyRepository.findAll().stream()
                .sorted(Comparator.comparing(ConferenceNameHistory::getLastSeasonYear))
                .collect(Collectors.groupingBy(h -> h.getConference().getId()));
        return new ConferenceNames(byConference);
    }

    /** Convenience for single lookups; prefer {@link #load()} inside loops. */
    @Transactional(readOnly = true)
    public ConferenceIdentity resolve(Conference conference, int seasonYear) {
        return load().identity(conference, seasonYear);
    }

    /** How a conference was branded in a given season. */
    public record ConferenceIdentity(String name, String abbreviation, String logoUrl) {}

    /** Immutable snapshot of the name history, safe to reuse across a request. */
    public static final class ConferenceNames {

        private final Map<Long, List<ConferenceNameHistory>> historyByConference;

        private ConferenceNames(Map<Long, List<ConferenceNameHistory>> historyByConference) {
            this.historyByConference = historyByConference;
        }

        public ConferenceIdentity identity(Conference conference, int seasonYear) {
            for (ConferenceNameHistory era : historyByConference.getOrDefault(conference.getId(), List.of())) {
                if (seasonYear <= era.getLastSeasonYear()) {
                    return new ConferenceIdentity(
                            era.getName(),
                            era.getAbbreviation() != null ? era.getAbbreviation() : conference.getAbbreviation(),
                            era.getLogoUrl() != null ? era.getLogoUrl() : conference.getLogoUrl());
                }
            }
            return new ConferenceIdentity(
                    conference.getName(), conference.getAbbreviation(), conference.getLogoUrl());
        }

        public String name(Conference conference, int seasonYear) {
            return identity(conference, seasonYear).name();
        }
    }
}
