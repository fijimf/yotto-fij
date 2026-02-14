package com.yotto.basketball.controller;

import com.yotto.basketball.entity.BettingOdds;
import com.yotto.basketball.service.BettingOddsService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/betting-odds")
public class BettingOddsController {

    private final BettingOddsService bettingOddsService;

    public BettingOddsController(BettingOddsService bettingOddsService) {
        this.bettingOddsService = bettingOddsService;
    }

    @PostMapping
    public ResponseEntity<BettingOdds> create(@Valid @RequestBody BettingOdds odds, @RequestParam Long gameId) {
        return new ResponseEntity<>(bettingOddsService.create(odds, gameId), HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    public ResponseEntity<BettingOdds> findById(@PathVariable Long id) {
        return ResponseEntity.ok(bettingOddsService.findById(id));
    }

    @GetMapping
    public ResponseEntity<List<BettingOdds>> findAll() {
        return ResponseEntity.ok(bettingOddsService.findAll());
    }

    @GetMapping("/game/{gameId}")
    public ResponseEntity<BettingOdds> findByGameId(@PathVariable Long gameId) {
        return ResponseEntity.ok(bettingOddsService.findByGameId(gameId));
    }

    @GetMapping("/season/{seasonId}")
    public ResponseEntity<List<BettingOdds>> findBySeason(@PathVariable Long seasonId) {
        return ResponseEntity.ok(bettingOddsService.findBySeason(seasonId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<BettingOdds> update(@PathVariable Long id, @Valid @RequestBody BettingOdds odds) {
        return ResponseEntity.ok(bettingOddsService.update(id, odds));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        bettingOddsService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
