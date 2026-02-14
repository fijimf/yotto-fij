package com.yotto.basketball.service;

import com.yotto.basketball.entity.Conference;
import com.yotto.basketball.entity.ConferenceMembership;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.ConferenceMembershipRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class ConferenceMembershipService {

    private final ConferenceMembershipRepository membershipRepository;
    private final TeamService teamService;
    private final ConferenceService conferenceService;
    private final SeasonService seasonService;

    public ConferenceMembershipService(ConferenceMembershipRepository membershipRepository,
                                       TeamService teamService,
                                       ConferenceService conferenceService,
                                       SeasonService seasonService) {
        this.membershipRepository = membershipRepository;
        this.teamService = teamService;
        this.conferenceService = conferenceService;
        this.seasonService = seasonService;
    }

    public ConferenceMembership create(Long teamId, Long conferenceId, Long seasonId) {
        if (membershipRepository.existsByTeamIdAndSeasonId(teamId, seasonId)) {
            throw new IllegalArgumentException("Team already has a conference membership for this season");
        }

        Team team = teamService.findById(teamId);
        Conference conference = conferenceService.findById(conferenceId);
        Season season = seasonService.findById(seasonId);

        ConferenceMembership membership = new ConferenceMembership();
        membership.setTeam(team);
        membership.setConference(conference);
        membership.setSeason(season);

        return membershipRepository.save(membership);
    }

    @Transactional(readOnly = true)
    public ConferenceMembership findById(Long id) {
        return membershipRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Conference membership not found with id: " + id));
    }

    @Transactional(readOnly = true)
    public Optional<ConferenceMembership> findByTeamAndSeason(Long teamId, Long seasonId) {
        return membershipRepository.findByTeamIdAndSeasonId(teamId, seasonId);
    }

    @Transactional(readOnly = true)
    public ConferenceMembership getByTeamAndSeason(Long teamId, Long seasonId) {
        return membershipRepository.findByTeamIdAndSeasonId(teamId, seasonId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Conference membership not found for team " + teamId + " in season " + seasonId));
    }

    @Transactional(readOnly = true)
    public List<ConferenceMembership> findByTeam(Long teamId) {
        return membershipRepository.findByTeamIdOrderBySeasonDesc(teamId);
    }

    @Transactional(readOnly = true)
    public List<ConferenceMembership> findByConference(Long conferenceId) {
        return membershipRepository.findByConferenceId(conferenceId);
    }

    @Transactional(readOnly = true)
    public List<ConferenceMembership> findBySeason(Long seasonId) {
        return membershipRepository.findBySeasonId(seasonId);
    }

    @Transactional(readOnly = true)
    public List<ConferenceMembership> findTeamsInConferenceForSeason(Long conferenceId, Long seasonId) {
        return membershipRepository.findByConferenceIdAndSeasonId(conferenceId, seasonId);
    }

    @Transactional(readOnly = true)
    public Optional<ConferenceMembership> findCurrentMembership(Long teamId) {
        return membershipRepository.findCurrentMembershipByTeamId(teamId);
    }

    public ConferenceMembership update(Long id, Long conferenceId) {
        ConferenceMembership existing = findById(id);
        Conference conference = conferenceService.findById(conferenceId);
        existing.setConference(conference);
        return membershipRepository.save(existing);
    }

    public void delete(Long id) {
        if (!membershipRepository.existsById(id)) {
            throw new EntityNotFoundException("Conference membership not found with id: " + id);
        }
        membershipRepository.deleteById(id);
    }
}
