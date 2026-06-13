package com.yotto.basketball.repository;

import com.yotto.basketball.entity.SeasonPopulationStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface SeasonPopulationStatRepository extends JpaRepository<SeasonPopulationStat, Long> {

    @Query("SELECT s FROM SeasonPopulationStat s WHERE s.season.id = :seasonId AND s.statDate = :date AND s.conference IS NULL")
    List<SeasonPopulationStat> findLeagueWideBySeasonAndDate(@Param("seasonId") Long seasonId, @Param("date") LocalDate date);

    @Query("SELECT s FROM SeasonPopulationStat s WHERE s.season.id = :seasonId AND s.statDate = :date AND s.conference.id = :conferenceId")
    List<SeasonPopulationStat> findBySeasonDateAndConference(@Param("seasonId") Long seasonId, @Param("date") LocalDate date, @Param("conferenceId") Long conferenceId);

    @Query("SELECT MAX(s.statDate) FROM SeasonPopulationStat s WHERE s.season.id = :seasonId AND s.conference IS NULL")
    Optional<LocalDate> findLatestStatDate(@Param("seasonId") Long seasonId);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM SeasonPopulationStat s WHERE s.season.id = :seasonId")
    void deleteBySeasonId(@Param("seasonId") Long seasonId);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM SeasonPopulationStat s WHERE s.season.id = :seasonId AND s.statDate >= :fromDate")
    void deleteBySeasonIdFromDate(@Param("seasonId") Long seasonId, @Param("fromDate") LocalDate fromDate);

    // Stat-name-scoped deletes: multiple services share this table (each owns a set of
    // stat names), so a service must never wipe another's rows.

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM SeasonPopulationStat s WHERE s.season.id = :seasonId AND s.statName IN :statNames")
    void deleteBySeasonIdAndStatNames(@Param("seasonId") Long seasonId,
                                      @Param("statNames") java.util.Collection<String> statNames);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM SeasonPopulationStat s WHERE s.season.id = :seasonId AND s.statDate >= :fromDate " +
           "AND s.statName IN :statNames")
    void deleteBySeasonIdFromDateAndStatNames(@Param("seasonId") Long seasonId,
                                              @Param("fromDate") LocalDate fromDate,
                                              @Param("statNames") java.util.Collection<String> statNames);
}
