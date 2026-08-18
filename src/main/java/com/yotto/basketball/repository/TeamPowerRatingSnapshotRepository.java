package com.yotto.basketball.repository;

import com.yotto.basketball.entity.TeamPowerRatingSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;


@Repository
public interface TeamPowerRatingSnapshotRepository extends JpaRepository<TeamPowerRatingSnapshot, Long> {

    @Query("SELECT s FROM TeamPowerRatingSnapshot s JOIN FETCH s.team " +
           "WHERE s.season.id = :seasonId AND s.modelType = :modelType AND s.snapshotDate = :date " +
           "ORDER BY s.rank ASC NULLS LAST")
    List<TeamPowerRatingSnapshot> findBySeasonModelAndDate(
            @Param("seasonId") Long seasonId,
            @Param("modelType") String modelType,
            @Param("date") LocalDate date);

    @Query("SELECT s FROM TeamPowerRatingSnapshot s JOIN FETCH s.team " +
           "WHERE s.team.id = :teamId AND s.season.id = :seasonId AND s.modelType = :modelType " +
           "ORDER BY s.snapshotDate ASC")
    List<TeamPowerRatingSnapshot> findByTeamSeasonAndModel(
            @Param("teamId") Long teamId,
            @Param("seasonId") Long seasonId,
            @Param("modelType") String modelType);

    /** A team's most recent snapshots for one model in a season, newest first (pass a limit). */
    @Query("SELECT s FROM TeamPowerRatingSnapshot s " +
           "WHERE s.team.id = :teamId AND s.season.id = :seasonId AND s.modelType = :modelType " +
           "ORDER BY s.snapshotDate DESC")
    List<TeamPowerRatingSnapshot> findLatestForTeam(
            @Param("teamId") Long teamId,
            @Param("seasonId") Long seasonId,
            @Param("modelType") String modelType,
            org.springframework.data.domain.Pageable pageable);

    @Query("SELECT MAX(s.snapshotDate) FROM TeamPowerRatingSnapshot s " +
           "WHERE s.season.id = :seasonId AND s.modelType = :modelType")
    Optional<LocalDate> findLatestSnapshotDate(
            @Param("seasonId") Long seasonId,
            @Param("modelType") String modelType);

    @Query("SELECT DISTINCT s.snapshotDate FROM TeamPowerRatingSnapshot s " +
           "WHERE s.season.id = :seasonId AND s.modelType = :modelType " +
           "ORDER BY s.snapshotDate ASC")
    List<LocalDate> findSnapshotDates(
            @Param("seasonId") Long seasonId,
            @Param("modelType") String modelType);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM TeamPowerRatingSnapshot s WHERE s.season.id = :seasonId AND s.modelType = :modelType")
    void deleteBySeasonIdAndModelType(
            @Param("seasonId") Long seasonId,
            @Param("modelType") String modelType);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM TeamPowerRatingSnapshot s WHERE s.season.id = :seasonId AND s.modelType = :modelType " +
           "AND s.snapshotDate >= :fromDate")
    void deleteBySeasonIdAndModelTypeFromDate(
            @Param("seasonId") Long seasonId,
            @Param("modelType") String modelType,
            @Param("fromDate") LocalDate fromDate);

    /** The final snapshot of a season for a team/model (no cutoff) — preseason-prior lookups. */
    @Query(value = "SELECT * FROM team_power_rating_snapshots " +
                   "WHERE team_id = :teamId AND season_id = :seasonId AND model_type = :modelType " +
                   "ORDER BY snapshot_date DESC LIMIT 1",
           nativeQuery = true)
    Optional<TeamPowerRatingSnapshot> findLatest(
            @Param("teamId") Long teamId,
            @Param("seasonId") Long seasonId,
            @Param("modelType") String modelType);

    /** Most recent snapshot for a team/season/model strictly before the given date. */
    @Query(value = "SELECT * FROM team_power_rating_snapshots " +
                   "WHERE team_id = :teamId AND season_id = :seasonId " +
                   "  AND model_type = :modelType AND snapshot_date < :beforeDate " +
                   "ORDER BY snapshot_date DESC LIMIT 1",
           nativeQuery = true)
    Optional<TeamPowerRatingSnapshot> findLatestBefore(
            @Param("teamId") Long teamId,
            @Param("seasonId") Long seasonId,
            @Param("modelType") String modelType,
            @Param("beforeDate") LocalDate beforeDate);

    /** Lightweight row for bulk season loads (SeasonPredictionCache). */
    interface RatingRow {
        Long getTeamId();
        String getModelType();
        LocalDate getSnapshotDate();
        Double getRating();
        Integer getGamesPlayed();
    }

    @org.springframework.data.jpa.repository.Query(
            "SELECT r.team.id AS teamId, r.modelType AS modelType, r.snapshotDate AS snapshotDate, " +
            "       r.rating AS rating, r.gamesPlayed AS gamesPlayed " +
            "FROM TeamPowerRatingSnapshot r WHERE r.season.id = :seasonId")
    List<RatingRow> findRowsBySeasonId(@Param("seasonId") Long seasonId);
}
