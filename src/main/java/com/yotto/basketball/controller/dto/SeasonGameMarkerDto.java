package com.yotto.basketball.controller.dto;

public record SeasonGameMarkerDto(
        long gameId,
        String date,        // ISO date string e.g. "2025-01-15"
        int teamScore,
        int opponentScore,
        String opponentAbbr,
        boolean win
) {}
