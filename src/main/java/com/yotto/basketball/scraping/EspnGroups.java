package com.yotto.basketball.scraping;

import java.util.Set;

/**
 * ESPN "groups" that appear in the conferences/standings feeds but are NOT real conferences.
 * ESPN models these identically to conferences (same shape, {@code parentGroupId} = "50"), so
 * there is no field to distinguish them — they must be filtered by id.
 *
 * <p>If one of these is allowed through, {@code ConferenceScraper} creates a bogus Conference and
 * {@code StandingsScraper} then re-points the event's participants' (team, season) membership at it,
 * clobbering their real conference. See migration {@code V20} for the cleanup of the Crown leak.
 */
final class EspnGroups {

    static final Set<String> NON_CONFERENCE_GROUP_IDS = Set.of(
            "50",   // NCAA Division I — the parent group, not a conference
            "104"   // College Basketball Crown — a postseason tournament
    );

    private EspnGroups() {
    }

    static boolean isNonConference(String groupId) {
        return NON_CONFERENCE_GROUP_IDS.contains(groupId);
    }
}
