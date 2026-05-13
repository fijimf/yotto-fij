package com.yotto.basketball.controller;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.repository.ConferenceRepository;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    private final TeamRepository teamRepository;
    private final GameRepository gameRepository;
    private final SeasonRepository seasonRepository;
    private final ConferenceRepository conferenceRepository;

    public HomeController(TeamRepository teamRepository,
                          GameRepository gameRepository,
                          SeasonRepository seasonRepository,
                          ConferenceRepository conferenceRepository) {
        this.teamRepository = teamRepository;
        this.gameRepository = gameRepository;
        this.seasonRepository = seasonRepository;
        this.conferenceRepository = conferenceRepository;
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("currentPage", "home");
        model.addAttribute("teamCount", teamRepository.count());
        model.addAttribute("gameCount", gameRepository.countByStatus(Game.GameStatus.FINAL));
        model.addAttribute("seasonCount", seasonRepository.count());
        model.addAttribute("conferenceCount", conferenceRepository.count());
        return "pages/home";
    }
}
