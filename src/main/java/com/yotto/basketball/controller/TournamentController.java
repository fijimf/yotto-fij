package com.yotto.basketball.controller;

import com.yotto.basketball.entity.Tournament;
import com.yotto.basketball.service.TournamentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tournaments")
public class TournamentController {

    private final TournamentService tournamentService;

    public TournamentController(TournamentService tournamentService) {
        this.tournamentService = tournamentService;
    }

    @PostMapping
    public ResponseEntity<Tournament> create(@Valid @RequestBody Tournament tournament,
                                             @RequestParam Long seasonId) {
        return new ResponseEntity<>(tournamentService.create(tournament, seasonId), HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Tournament> findById(@PathVariable Long id) {
        return ResponseEntity.ok(tournamentService.findById(id));
    }

    @GetMapping
    public ResponseEntity<List<Tournament>> findAll() {
        return ResponseEntity.ok(tournamentService.findAll());
    }

    @GetMapping("/season/{seasonId}")
    public ResponseEntity<List<Tournament>> findBySeason(@PathVariable Long seasonId) {
        return ResponseEntity.ok(tournamentService.findBySeason(seasonId));
    }

    @GetMapping("/type/{type}")
    public ResponseEntity<List<Tournament>> findByType(@PathVariable Tournament.TournamentType type) {
        return ResponseEntity.ok(tournamentService.findByType(type));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Tournament> update(@PathVariable Long id, @Valid @RequestBody Tournament tournament) {
        return ResponseEntity.ok(tournamentService.update(id, tournament));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        tournamentService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
