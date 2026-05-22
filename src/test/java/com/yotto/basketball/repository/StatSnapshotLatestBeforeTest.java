package com.yotto.basketball.repository;

import com.yotto.basketball.BaseDataJpaTest;
import com.yotto.basketball.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class StatSnapshotLatestBeforeTest extends BaseDataJpaTest {

    @Autowired TeamSeasonStatSnapshotRepository snapshotRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired SeasonRepository seasonRepo;

    Team team;
    Season season;

    @BeforeEach
    void setUp() {

        team = new Team();
        team.setName("Duke");
        team.setEspnId("duke-id");
        team.setActive(true);
        teamRepo.save(team);

        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        seasonRepo.save(season);

        mkSnapshot(LocalDate.of(2025, 1, 5), 0.55);
        mkSnapshot(LocalDate.of(2025, 1, 10), 0.57);
        mkSnapshot(LocalDate.of(2025, 1, 20), 0.60);
    }

    @Test
    void findLatestBefore_returnsClosestSnapshotBeforeDate() {
        Optional<TeamSeasonStatSnapshot> result =
                snapshotRepo.findLatestBefore(team.getId(), season.getId(), LocalDate.of(2025, 1, 15));
        assertThat(result).isPresent();
        assertThat(result.get().getSnapshotDate()).isEqualTo(LocalDate.of(2025, 1, 10));
    }

    @Test
    void findLatestBefore_returnsEmptyIfNoneBeforeDate() {
        Optional<TeamSeasonStatSnapshot> result =
                snapshotRepo.findLatestBefore(team.getId(), season.getId(), LocalDate.of(2024, 12, 1));
        assertThat(result).isEmpty();
    }

    @Test
    void findLatestBefore_doesNotIncludeDateItself() {
        Optional<TeamSeasonStatSnapshot> result =
                snapshotRepo.findLatestBefore(team.getId(), season.getId(), LocalDate.of(2025, 1, 10));
        // Jan 10 is the cutoff — strictly before means return Jan 5
        assertThat(result).isPresent();
        assertThat(result.get().getSnapshotDate()).isEqualTo(LocalDate.of(2025, 1, 5));
    }

    private void mkSnapshot(LocalDate date, double rpi) {
        TeamSeasonStatSnapshot s = new TeamSeasonStatSnapshot();
        s.setTeam(team);
        s.setSeason(season);
        s.setSnapshotDate(date);
        s.setGamesPlayed(10);
        s.setRpi(rpi);
        snapshotRepo.save(s);
    }
}
