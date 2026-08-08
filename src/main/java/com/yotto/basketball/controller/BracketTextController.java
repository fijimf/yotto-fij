package com.yotto.basketball.controller;

import com.yotto.basketball.service.BracketService;
import com.yotto.basketball.service.BracketTextRenderer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The {@code curl deepfij.com/bracket.txt} easter egg: the live bracket in monospace.
 * Not linked from any nav — you either know or you don't.
 */
@RestController
public class BracketTextController {

    private final BracketService bracketService;

    public BracketTextController(BracketService bracketService) {
        this.bracketService = bracketService;
    }

    @GetMapping(value = "/bracket.txt", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> latest() {
        return bracketService.latestBracketYear()
                .map(this::renderYear)
                .orElseGet(BracketTextController::noBracket);
    }

    @GetMapping(value = "/seasons/{year}/bracket.txt", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> forYear(@PathVariable int year) {
        return renderYear(year);
    }

    private ResponseEntity<String> renderYear(int year) {
        return bracketService.buildBracket(year)
                .map(b -> ResponseEntity.ok(BracketTextRenderer.render(b)))
                .orElseGet(BracketTextController::noBracket);
    }

    private static ResponseEntity<String> noBracket() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body("no bracket yet. see you in march.\n");
    }
}
