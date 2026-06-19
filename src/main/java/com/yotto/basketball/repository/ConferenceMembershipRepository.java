package com.yotto.basketball.repository;

import com.yotto.basketball.entity.ConferenceMembership;
import com.yotto.basketball.entity.Season;
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

    @Query("SELECT cm FROM ConferenceMembership cm JOIN FETCH cm.team " +
           "WHERE cm.conference.id = :conferenceId AND cm.season.id = :seasonId")
    List<ConferenceMembership> findByConferenceIdAndSeasonIdWithTeam(@Param("conferenceId") Long conferenceId,
                                                                     @Param("seasonId") Long seasonId);

    @Query("SELECT DISTINCT s FROM ConferenceMembership cm JOIN cm.season s " +
           "WHERE cm.conference.id = :conferenceId ORDER BY s.year DESC")
    List<Season> findSeasonsByConferenceId(@Param("conferenceId") Long conferenceId);

    boolean existsByTeamIdAndSeasonId(Long teamId, Long seasonId);

    long countBySeasonId(Long seasonId);

    @Query("SELECT COUNT(DISTINCT cm.conference.id) FROM ConferenceMembership cm WHERE cm.season.id = :seasonId")
    long countDistinctConferencesBySeasonId(@Param("seasonId") Long seasonId);

    @Query("SELECT cm FROM ConferenceMembership cm JOIN FETCH cm.conference JOIN FETCH cm.season WHERE cm.team.id = :teamId ORDER BY cm.season.year DESC")
    List<ConferenceMembership> findByTeamIdOrderBySeasonDesc(@Param("teamId") Long teamId);

    @Query("SELECT cm FROM ConferenceMembership cm JOIN FETCH cm.team JOIN FETCH cm.conference JOIN FETCH cm.season " +
           "WHERE cm.id = :id")
    Optional<ConferenceMembership> findByIdWithDetails(@Param("id") Long id);

    @Query("SELECT cm FROM ConferenceMembership cm JOIN FETCH cm.team JOIN FETCH cm.conference JOIN FETCH cm.season " +
           "WHERE cm.team.id = :teamId AND cm.season.id = :seasonId")
    Optional<ConferenceMembership> findByTeamIdAndSeasonIdWithDetails(@Param("teamId") Long teamId,
                                                                      @Param("seasonId") Long seasonId);

    @Query("SELECT cm FROM ConferenceMembership cm JOIN FETCH cm.team JOIN FETCH cm.conference JOIN FETCH cm.season " +
           "WHERE cm.team.id = :teamId AND cm.season.year = " +
           "(SELECT MAX(s.year) FROM Season s JOIN ConferenceMembership cm2 ON cm2.season = s WHERE cm2.team.id = :teamId)")
    Optional<ConferenceMembership> findCurrentMembershipByTeamId(@Param("teamId") Long teamId);
}
