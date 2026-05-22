package com.yotto.basketball.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only controller that throws each exception type handled by
 * {@link GlobalExceptionHandler}. Used by {@code GlobalExceptionHandlerTest}.
 * Lives under /stub/** so it can't collide with real endpoints.
 */
@RestController
@RequestMapping("/stub")
class ExceptionThrowingTestController {

    @GetMapping("/not-found")
    void notFound() {
        throw new EntityNotFoundException("ghost entity");
    }

    @GetMapping("/bad-arg")
    void badArg(@RequestParam String value) {
        throw new IllegalArgumentException("invalid value: " + value);
    }

    @GetMapping("/boom")
    void boom() {
        throw new RuntimeException("kaboom");
    }

    @PostMapping("/validate")
    void validate(@Valid @RequestBody Payload payload) {
        // never reached — validation fails first
    }

    record Payload(
            @NotBlank String name,
            @JsonProperty("age") @Min(0) Integer age
    ) {}
}
