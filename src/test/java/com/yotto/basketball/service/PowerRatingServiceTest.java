package com.yotto.basketball.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** PowerRatingService must fan out to every rating engine on both entry paths. */
@ExtendWith(MockitoExtension.class)
class PowerRatingServiceTest {

    @Mock MasseyRatingService massey;
    @Mock BradleyTerryRatingService bradleyTerry;
    @Mock AdjustedEfficiencyRatingService adjustedEfficiency;

    @Test
    void seasonYearPath_callsAllEngines() {
        LocalDate fromDate = LocalDate.of(2025, 1, 10);
        new PowerRatingService(massey, bradleyTerry, adjustedEfficiency)
                .calculateAndStoreForSeason(2025, fromDate);

        verify(massey).calculateAndStoreForSeason(2025, fromDate);
        verify(bradleyTerry).calculateAndStoreForSeason(2025, fromDate);
        verify(adjustedEfficiency).calculateAndStoreForSeason(2025, fromDate);
    }

    @Test
    void sharedDataPath_callsAllEngines() {
        SeasonGameData data = mock(SeasonGameData.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        new PowerRatingService(massey, bradleyTerry, adjustedEfficiency)
                .calculateAndStoreForSeason(data, null);

        verify(massey).calculateAndStoreForSeason(data, null);
        verify(bradleyTerry).calculateAndStoreForSeason(data, null);
        verify(adjustedEfficiency).calculateAndStoreForSeason(data, null);
    }
}
