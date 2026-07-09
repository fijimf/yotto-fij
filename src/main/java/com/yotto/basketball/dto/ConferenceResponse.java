package com.yotto.basketball.dto;

import com.yotto.basketball.entity.Conference;
import com.yotto.basketball.entity.ConferenceNameHistory;

import java.util.List;

/**
 * Conference plus its season-scoped name history (superseded brandings and the
 * last Season.year each applied to). Same fields as the bare entity JSON, with
 * {@code nameHistory} appended, so existing API consumers are unaffected.
 */
public record ConferenceResponse(
        Long id, String espnId, String name, String abbreviation,
        String division, String logoUrl, List<NameEra> nameHistory
) {

    public record NameEra(String name, String abbreviation, String logoUrl, Integer lastSeasonYear) {}

    public static ConferenceResponse of(Conference c, List<ConferenceNameHistory> history) {
        List<NameEra> eras = history.stream()
                .map(h -> new NameEra(h.getName(), h.getAbbreviation(), h.getLogoUrl(), h.getLastSeasonYear()))
                .toList();
        return new ConferenceResponse(c.getId(), c.getEspnId(), c.getName(), c.getAbbreviation(),
                c.getDivision(), c.getLogoUrl(), eras);
    }
}
