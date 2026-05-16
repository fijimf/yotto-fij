package com.yotto.basketball.repository;

import com.yotto.basketball.entity.TeamGameStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TeamGameStatsRepository extends JpaRepository<TeamGameStats, Long> {

    List<TeamGameStats> findByGameId(Long gameId);

    Optional<TeamGameStats> findByGameIdAndTeamId(Long gameId, Long teamId);

    boolean existsByGameId(Long gameId);
}
