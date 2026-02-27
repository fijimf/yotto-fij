package com.yotto.basketball.repository;

import com.yotto.basketball.entity.TeamSeasonStatSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface TeamSeasonStatSnapshotRepository extends JpaRepository<TeamSeasonStatSnapshot, Long> {

    @Query("SELECT s FROM TeamSeasonStatSnapshot s JOIN FETCH s.team WHERE s.team.id = :teamId AND s.season.id = :seasonId ORDER BY s.snapshotDate ASC")
    List<TeamSeasonStatSnapshot> findByTeamAndSeason(@Param("teamId") Long teamId, @Param("seasonId") Long seasonId);

    @Query("SELECT s FROM TeamSeasonStatSnapshot s JOIN FETCH s.team WHERE s.season.id = :seasonId AND s.snapshotDate = :date ORDER BY s.winPct DESC NULLS LAST")
    List<TeamSeasonStatSnapshot> findBySeasonAndDate(@Param("seasonId") Long seasonId, @Param("date") LocalDate date);

    @Query("SELECT MAX(s.snapshotDate) FROM TeamSeasonStatSnapshot s WHERE s.season.id = :seasonId")
    Optional<LocalDate> findLatestSnapshotDate(@Param("seasonId") Long seasonId);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM TeamSeasonStatSnapshot s WHERE s.season.id = :seasonId")
    void deleteBySeasonId(@Param("seasonId") Long seasonId);
}
