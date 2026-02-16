package com.yotto.basketball.repository;

import com.yotto.basketball.entity.SeasonStatistics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SeasonStatisticsRepository extends JpaRepository<SeasonStatistics, Long> {

    Optional<SeasonStatistics> findByTeamIdAndSeasonId(Long teamId, Long seasonId);

    List<SeasonStatistics> findBySeasonId(Long seasonId);
}
