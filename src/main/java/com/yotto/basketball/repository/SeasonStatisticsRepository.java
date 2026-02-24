package com.yotto.basketball.repository;

import com.yotto.basketball.entity.SeasonStatistics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SeasonStatisticsRepository extends JpaRepository<SeasonStatistics, Long> {

    Optional<SeasonStatistics> findByTeamIdAndSeasonId(Long teamId, Long seasonId);

    List<SeasonStatistics> findBySeasonId(Long seasonId);

    @Query("SELECT ss FROM SeasonStatistics ss JOIN FETCH ss.team JOIN FETCH ss.conference WHERE ss.season.id = :seasonId")
    List<SeasonStatistics> findBySeasonIdWithTeamAndConference(@Param("seasonId") Long seasonId);

    @Query("SELECT ss FROM SeasonStatistics ss JOIN FETCH ss.season JOIN FETCH ss.conference " +
           "WHERE ss.team.id = :teamId ORDER BY ss.season.year DESC")
    List<SeasonStatistics> findByTeamIdWithSeasonAndConference(@Param("teamId") Long teamId);

    @Query("SELECT ss FROM SeasonStatistics ss JOIN FETCH ss.team JOIN FETCH ss.conference " +
           "WHERE ss.season.id = :seasonId ORDER BY ss.team.name")
    List<SeasonStatistics> findBySeasonIdWithTeamAndConferenceOrdered(@Param("seasonId") Long seasonId);
}
