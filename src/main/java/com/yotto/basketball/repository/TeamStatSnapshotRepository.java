package com.yotto.basketball.repository;

import com.yotto.basketball.entity.TeamStatSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface TeamStatSnapshotRepository extends JpaRepository<TeamStatSnapshot, Long> {

    /** Leaderboard: every team's value for one stat on one date, best rank first. */
    @Query("SELECT s FROM TeamStatSnapshot s JOIN FETCH s.team " +
           "WHERE s.season.id = :seasonId AND s.statName = :statName AND s.snapshotDate = :date " +
           "ORDER BY s.rank ASC NULLS LAST")
    List<TeamStatSnapshot> findBySeasonStatAndDate(
            @Param("seasonId") Long seasonId,
            @Param("statName") String statName,
            @Param("date") LocalDate date);

    /** Trajectory: one team's series for one stat across the season. */
    @Query("SELECT s FROM TeamStatSnapshot s JOIN FETCH s.team " +
           "WHERE s.team.id = :teamId AND s.season.id = :seasonId AND s.statName = :statName " +
           "ORDER BY s.snapshotDate ASC")
    List<TeamStatSnapshot> findByTeamSeasonAndStat(
            @Param("teamId") Long teamId,
            @Param("seasonId") Long seasonId,
            @Param("statName") String statName);

    /** All stats for one team on one date (team profile page). */
    @Query("SELECT s FROM TeamStatSnapshot s " +
           "WHERE s.team.id = :teamId AND s.season.id = :seasonId AND s.snapshotDate = :date")
    List<TeamStatSnapshot> findByTeamSeasonAndDate(
            @Param("teamId") Long teamId,
            @Param("seasonId") Long seasonId,
            @Param("date") LocalDate date);

    /** All stats for one team at the most recent snapshot date strictly before the given date. */
    @Query(value = "SELECT * FROM team_stat_snapshots " +
                   "WHERE team_id = :teamId AND season_id = :seasonId " +
                   "  AND snapshot_date = (SELECT MAX(snapshot_date) FROM team_stat_snapshots " +
                   "    WHERE team_id = :teamId AND season_id = :seasonId AND snapshot_date < :beforeDate)",
           nativeQuery = true)
    List<TeamStatSnapshot> findLatestBefore(
            @Param("teamId") Long teamId,
            @Param("seasonId") Long seasonId,
            @Param("beforeDate") LocalDate beforeDate);

    @Query("SELECT MAX(s.snapshotDate) FROM TeamStatSnapshot s WHERE s.season.id = :seasonId")
    Optional<LocalDate> findLatestSnapshotDate(@Param("seasonId") Long seasonId);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM TeamStatSnapshot s WHERE s.season.id = :seasonId")
    void deleteBySeasonId(@Param("seasonId") Long seasonId);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM TeamStatSnapshot s WHERE s.season.id = :seasonId AND s.snapshotDate >= :fromDate")
    void deleteBySeasonIdFromDate(@Param("seasonId") Long seasonId, @Param("fromDate") LocalDate fromDate);
}
