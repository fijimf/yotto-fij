package com.yotto.basketball.controller;

import com.yotto.basketball.entity.Conference;
import com.yotto.basketball.service.ConferenceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/conferences")
public class ConferenceController {

    private final ConferenceService conferenceService;

    public ConferenceController(ConferenceService conferenceService) {
        this.conferenceService = conferenceService;
    }

    @PostMapping
    public ResponseEntity<Conference> create(@Valid @RequestBody Conference conference) {
        return new ResponseEntity<>(conferenceService.create(conference), HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Conference> findById(@PathVariable Long id) {
        return ResponseEntity.ok(conferenceService.findById(id));
    }

    @GetMapping
    public ResponseEntity<List<Conference>> findAll() {
        return ResponseEntity.ok(conferenceService.findAll());
    }

    @GetMapping("/name/{name}")
    public ResponseEntity<Conference> findByName(@PathVariable String name) {
        return ResponseEntity.ok(conferenceService.findByName(name));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Conference> update(@PathVariable Long id, @Valid @RequestBody Conference conference) {
        return ResponseEntity.ok(conferenceService.update(id, conference));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        conferenceService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
