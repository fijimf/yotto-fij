package com.yotto.basketball.dto;

import com.yotto.basketball.entity.Team;

public record ComprehensiveRankingRow(
        Team team,
        String conferenceName,
        String conferenceAbbr,
        Integer wins,
        Integer losses,
        Double winPct,
        Double meanPtsFor,
        Double meanPtsAgainst,
        Double meanMargin,
        Double rpi,
        Double masseyRating,
        Double bradleyTerryRating,
        Double bradleyTerryWeightedRating
) {}
