package com.yotto.basketball.controller;

import com.yotto.basketball.entity.BettingOdds;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.service.PredictionResult;
import com.yotto.basketball.service.PredictionService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class GameDetailController {

    private final GameRepository gameRepository;
    private final PredictionService predictionService;

    public GameDetailController(GameRepository gameRepository, PredictionService predictionService) {
        this.gameRepository = gameRepository;
        this.predictionService = predictionService;
    }

    @GetMapping("/games/{id}")
    public String gameDetail(@PathVariable Long id, Model model) {
        // Load with eager joins so lazy associations are ready before method returns
        Game game = gameRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new EntityNotFoundException("Game not found: " + id));

        Team home = game.getHomeTeam();
        Team away = game.getAwayTeam();
        BettingOdds odds = game.getBettingOdds(); // null if no odds

        // Pre-extract all values needed by the template to avoid lazy-load issues
        model.addAttribute("gameId", game.getId());
        model.addAttribute("homeTeamId", home.getId());
        model.addAttribute("homeTeamName", home.getName());
        model.addAttribute("homeTeamMascot", home.getMascot());
        model.addAttribute("homeTeamLogoUrl", home.getLogoUrl());
        model.addAttribute("homeTeamColor", home.getColor());
        model.addAttribute("homeTeamSlug", home.getSlug());
        model.addAttribute("awayTeamId", away.getId());
        model.addAttribute("awayTeamName", away.getName());
        model.addAttribute("awayTeamMascot", away.getMascot());
        model.addAttribute("awayTeamLogoUrl", away.getLogoUrl());
        model.addAttribute("awayTeamColor", away.getColor());
        model.addAttribute("awayTeamSlug", away.getSlug());
        model.addAttribute("gameDate", game.getGameDate());
        model.addAttribute("venue", game.getVenue());
        model.addAttribute("neutralSite", Boolean.TRUE.equals(game.getNeutralSite()));
        model.addAttribute("conferenceGame", Boolean.TRUE.equals(game.getConferenceGame()));
        model.addAttribute("status", game.getStatus());
        model.addAttribute("homeScore", game.getHomeScore());
        model.addAttribute("awayScore", game.getAwayScore());
        model.addAttribute("periods", game.getPeriods());
        model.addAttribute("odds", odds);

        PredictionResult prediction = predictionService.predict(id);
        model.addAttribute("prediction", prediction);
        model.addAttribute("currentPage", "games");

        return "pages/game-detail";
    }
}
