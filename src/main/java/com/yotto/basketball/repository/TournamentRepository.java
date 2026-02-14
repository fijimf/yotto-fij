package com.yotto.basketball.repository;

import com.yotto.basketball.entity.Tournament;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TournamentRepository extends JpaRepository<Tournament, Long> {

    List<Tournament> findBySeasonId(Long seasonId);

    List<Tournament> findByType(Tournament.TournamentType type);

    List<Tournament> findBySeasonIdAndType(Long seasonId, Tournament.TournamentType type);
}
