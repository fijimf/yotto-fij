package com.yotto.basketball.controller.dto;

import java.time.LocalDate;

public record LastMeetingDto(
        Long gameId,
        LocalDate date,
        String winnerAbbr,
        int winnerScore,
        int loserScore,
        String loserAbbr
) {}
