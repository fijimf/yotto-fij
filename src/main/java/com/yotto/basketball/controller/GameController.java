package com.yotto.basketball.controller;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.service.GameService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/games")
public class GameController {

    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    @PostMapping
    public ResponseEntity<Game> create(@Valid @RequestBody Game game,
                                       @RequestParam Long homeTeamId,
                                       @RequestParam Long awayTeamId,
                                       @RequestParam Long seasonId,
                                       @RequestParam(required = false) Long tournamentId) {
        return new ResponseEntity<>(gameService.create(game, homeTeamId, awayTeamId, seasonId, tournamentId), HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Game> findById(@PathVariable Long id) {
        return ResponseEntity.ok(gameService.findById(id));
    }

    @GetMapping
    public ResponseEntity<List<Game>> findAll() {
        return ResponseEntity.ok(gameService.findAll());
    }

    @GetMapping("/season/{seasonId}")
    public ResponseEntity<List<Game>> findBySeason(@PathVariable Long seasonId) {
        return ResponseEntity.ok(gameService.findBySeason(seasonId));
    }

    @GetMapping("/team/{teamId}")
    public ResponseEntity<List<Game>> findByTeam(@PathVariable Long teamId) {
        return ResponseEntity.ok(gameService.findByTeam(teamId));
    }

    @GetMapping("/team/{teamId}/season/{seasonId}")
    public ResponseEntity<List<Game>> findByTeamAndSeason(@PathVariable Long teamId, @PathVariable Long seasonId) {
        return ResponseEntity.ok(gameService.findByTeamAndSeason(teamId, seasonId));
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<Game>> findByStatus(@PathVariable Game.GameStatus status) {
        return ResponseEntity.ok(gameService.findByStatus(status));
    }

    @GetMapping("/date-range")
    public ResponseEntity<List<Game>> findByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        return ResponseEntity.ok(gameService.findByDateRange(start, end));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Game> update(@PathVariable Long id, @Valid @RequestBody Game game) {
        return ResponseEntity.ok(gameService.update(id, game));
    }

    @PutMapping("/{id}/score")
    public ResponseEntity<Game> updateScore(@PathVariable Long id,
                                            @RequestParam Integer homeScore,
                                            @RequestParam Integer awayScore) {
        return ResponseEntity.ok(gameService.updateScore(id, homeScore, awayScore));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<Game> updateStatus(@PathVariable Long id, @RequestParam Game.GameStatus status) {
        return ResponseEntity.ok(gameService.updateStatus(id, status));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        gameService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
