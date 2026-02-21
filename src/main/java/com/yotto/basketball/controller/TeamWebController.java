package com.yotto.basketball.controller;

import com.yotto.basketball.entity.Conference;
import com.yotto.basketball.entity.ConferenceMembership;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.ConferenceMembershipRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamRepository;
import com.yotto.basketball.service.RecordCalculationService;
import com.yotto.basketball.service.RecordCalculationService.TeamRecord;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.*;
import java.util.stream.Collectors;

@Controller
public class TeamWebController {

    private final TeamRepository teamRepository;
    private final SeasonRepository seasonRepository;
    private final ConferenceMembershipRepository membershipRepository;
    private final RecordCalculationService recordCalculationService;

    public TeamWebController(TeamRepository teamRepository,
                             SeasonRepository seasonRepository,
                             ConferenceMembershipRepository membershipRepository,
                             RecordCalculationService recordCalculationService) {
        this.teamRepository = teamRepository;
        this.seasonRepository = seasonRepository;
        this.membershipRepository = membershipRepository;
        this.recordCalculationService = recordCalculationService;
    }

    @GetMapping("/teams")
    public String teams(Model model) {
        Optional<Season> latestSeason = seasonRepository.findTopByOrderByYearDesc();

        List<TeamSummary> teamSummaries;
        Integer seasonYear = null;

        if (latestSeason.isPresent()) {
            Season season = latestSeason.get();
            seasonYear = season.getYear();

            Map<Long, TeamRecord> records = recordCalculationService.calculateRecords(season.getId());

            List<ConferenceMembership> memberships = membershipRepository.findBySeasonId(season.getId());
            Map<Long, Conference> conferenceByTeamId = new HashMap<>();
            for (ConferenceMembership cm : memberships) {
                conferenceByTeamId.put(cm.getTeam().getId(), cm.getConference());
            }

            List<Team> allTeams = teamRepository.findAll();

            teamSummaries = allTeams.stream()
                    .map(team -> {
                        TeamRecord rec = records.get(team.getId());
                        Conference conf = conferenceByTeamId.get(team.getId());
                        return new TeamSummary(
                                team.getId(),
                                team.getName(),
                                team.getNickname(),
                                team.getMascot(),
                                team.getLogoUrl(),
                                team.getColor(),
                                conf != null ? conf.getName() : null,
                                conf != null ? conf.getAbbreviation() : null,
                                conf != null ? conf.getLogoUrl() : null,
                                rec != null ? rec.getWins() : null,
                                rec != null ? rec.getLosses() : null,
                                rec != null ? rec.getConferenceWins() : null,
                                rec != null ? rec.getConferenceLosses() : null
                        );
                    })
                    .sorted(Comparator.comparing(
                            (TeamSummary ts) -> ts.conferenceName() != null ? ts.conferenceName() : "zzz")
                            .thenComparing(ts -> ts.name() != null ? ts.name() : ""))
                    .toList();
        } else {
            List<Team> allTeams = teamRepository.findAll();
            teamSummaries = allTeams.stream()
                    .map(team -> new TeamSummary(
                            team.getId(), team.getName(), team.getNickname(),
                            team.getMascot(), team.getLogoUrl(), team.getColor(),
                            null, null, null, null, null, null, null))
                    .sorted(Comparator.comparing(ts -> ts.name() != null ? ts.name() : ""))
                    .toList();
        }

        Map<ConferenceInfo, List<TeamSummary>> confGroups = teamSummaries.stream()
                .collect(Collectors.groupingBy(
                        ts -> new ConferenceInfo(
                                ts.conferenceName() != null ? ts.conferenceName() : "Independent",
                                ts.conferenceLogoUrl()),
                        () -> new TreeMap<>(Comparator.comparing(ConferenceInfo::name)),
                        Collectors.toList()));

        model.addAttribute("currentPage", "teams");
        model.addAttribute("conferenceGroups", confGroups);
        model.addAttribute("teamCount", teamSummaries.size());
        model.addAttribute("seasonYear", seasonYear);

        return "pages/teams";
    }

    public record ConferenceInfo(String name, String logoUrl) {}

    public record TeamSummary(
            Long id,
            String name,
            String nickname,
            String mascot,
            String logoUrl,
            String color,
            String conferenceName,
            String conferenceAbbreviation,
            String conferenceLogoUrl,
            Integer wins,
            Integer losses,
            Integer conferenceWins,
            Integer conferenceLosses
    ) {
        public String record() {
            if (wins == null || losses == null) return "";
            return wins + "-" + losses;
        }

        public String conferenceRecord() {
            if (conferenceWins == null || conferenceLosses == null) return "";
            return conferenceWins + "-" + conferenceLosses;
        }
    }
}
