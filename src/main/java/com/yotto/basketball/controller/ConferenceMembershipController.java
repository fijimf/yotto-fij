package com.yotto.basketball.controller;

import com.yotto.basketball.entity.ConferenceMembership;
import com.yotto.basketball.service.ConferenceMembershipService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/conference-memberships")
public class ConferenceMembershipController {

    private final ConferenceMembershipService membershipService;

    public ConferenceMembershipController(ConferenceMembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @PostMapping
    public ResponseEntity<ConferenceMembership> create(@RequestParam Long teamId,
                                                       @RequestParam Long conferenceId,
                                                       @RequestParam Long seasonId) {
        return new ResponseEntity<>(membershipService.create(teamId, conferenceId, seasonId), HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConferenceMembership> findById(@PathVariable Long id) {
        return ResponseEntity.ok(membershipService.findById(id));
    }

    @GetMapping("/team/{teamId}")
    public ResponseEntity<List<ConferenceMembership>> findByTeam(@PathVariable Long teamId) {
        return ResponseEntity.ok(membershipService.findByTeam(teamId));
    }

    @GetMapping("/team/{teamId}/season/{seasonId}")
    public ResponseEntity<ConferenceMembership> findByTeamAndSeason(@PathVariable Long teamId,
                                                                     @PathVariable Long seasonId) {
        return ResponseEntity.ok(membershipService.getByTeamAndSeason(teamId, seasonId));
    }

    @GetMapping("/team/{teamId}/current")
    public ResponseEntity<ConferenceMembership> findCurrentMembership(@PathVariable Long teamId) {
        return membershipService.findCurrentMembership(teamId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/conference/{conferenceId}")
    public ResponseEntity<List<ConferenceMembership>> findByConference(@PathVariable Long conferenceId) {
        return ResponseEntity.ok(membershipService.findByConference(conferenceId));
    }

    @GetMapping("/conference/{conferenceId}/season/{seasonId}")
    public ResponseEntity<List<ConferenceMembership>> findTeamsInConferenceForSeason(
            @PathVariable Long conferenceId, @PathVariable Long seasonId) {
        return ResponseEntity.ok(membershipService.findTeamsInConferenceForSeason(conferenceId, seasonId));
    }

    @GetMapping("/season/{seasonId}")
    public ResponseEntity<List<ConferenceMembership>> findBySeason(@PathVariable Long seasonId) {
        return ResponseEntity.ok(membershipService.findBySeason(seasonId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ConferenceMembership> update(@PathVariable Long id, @RequestParam Long conferenceId) {
        return ResponseEntity.ok(membershipService.update(id, conferenceId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        membershipService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
