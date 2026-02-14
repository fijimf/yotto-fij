package com.yotto.basketball.controller;

import com.yotto.basketball.entity.Season;
import com.yotto.basketball.service.SeasonService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/seasons")
public class SeasonController {

    private final SeasonService seasonService;

    public SeasonController(SeasonService seasonService) {
        this.seasonService = seasonService;
    }

    @PostMapping
    public ResponseEntity<Season> create(@Valid @RequestBody Season season) {
        return new ResponseEntity<>(seasonService.create(season), HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Season> findById(@PathVariable Long id) {
        return ResponseEntity.ok(seasonService.findById(id));
    }

    @GetMapping
    public ResponseEntity<List<Season>> findAll() {
        return ResponseEntity.ok(seasonService.findAll());
    }

    @GetMapping("/year/{year}")
    public ResponseEntity<Season> findByYear(@PathVariable Integer year) {
        return ResponseEntity.ok(seasonService.findByYear(year));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Season> update(@PathVariable Long id, @Valid @RequestBody Season season) {
        return ResponseEntity.ok(seasonService.update(id, season));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        seasonService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
