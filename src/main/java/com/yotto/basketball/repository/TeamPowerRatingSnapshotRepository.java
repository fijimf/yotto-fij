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
}
