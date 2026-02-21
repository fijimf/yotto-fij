package com.yotto.basketball.repository;

import com.yotto.basketball.entity.ConferenceMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConferenceMembershipRepository extends JpaRepository<ConferenceMembership, Long> {

    Optional<ConferenceMembership> findByTeamIdAndSeasonId(Long teamId, Long seasonId);

    List<ConferenceMembership> findByTeamId(Long teamId);

    List<ConferenceMembership> findByConferenceId(Long conferenceId);

    List<ConferenceMembership> findBySeasonId(Long seasonId);

    List<ConferenceMembership> findByConferenceIdAndSeasonId(Long conferenceId, Long seasonId);

    boolean existsByTeamIdAndSeasonId(Long teamId, Long seasonId);

    @Query("SELECT cm FROM ConferenceMembership cm JOIN FETCH cm.conference JOIN FETCH cm.season WHERE cm.team.id = :teamId ORDER BY cm.season.year DESC")
    List<ConferenceMembership> findByTeamIdOrderBySeasonDesc(@Param("teamId") Long teamId);

    @Query("SELECT cm FROM ConferenceMembership cm WHERE cm.team.id = :teamId AND cm.season.year = " +
           "(SELECT MAX(s.year) FROM Season s JOIN ConferenceMembership cm2 ON cm2.season = s WHERE cm2.team.id = :teamId)")
    Optional<ConferenceMembership> findCurrentMembershipByTeamId(@Param("teamId") Long teamId);
}
