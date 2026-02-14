package com.yotto.basketball.controller;

import com.yotto.basketball.entity.Team;
import com.yotto.basketball.service.TeamService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/teams")
public class TeamController {

    private final TeamService teamService;

    public TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    @PostMapping
    public ResponseEntity<Team> create(@Valid @RequestBody Team team) {
        return new ResponseEntity<>(teamService.create(team), HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Team> findById(@PathVariable Long id) {
        return ResponseEntity.ok(teamService.findById(id));
    }

    @GetMapping
    public ResponseEntity<List<Team>> findAll() {
        return ResponseEntity.ok(teamService.findAll());
    }

    @GetMapping("/search")
    public ResponseEntity<List<Team>> searchByName(@RequestParam String name) {
        return ResponseEntity.ok(teamService.searchByName(name));
    }

    @GetMapping("/state/{state}")
    public ResponseEntity<List<Team>> findByState(@PathVariable String state) {
        return ResponseEntity.ok(teamService.findByState(state));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Team> update(@PathVariable Long id, @Valid @RequestBody Team team) {
        return ResponseEntity.ok(teamService.update(id, team));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        teamService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
