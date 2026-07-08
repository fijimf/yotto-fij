package com.yotto.basketball.entity;

import java.time.Duration;

public enum TokenType {
    EMAIL_VERIFICATION(Duration.ofHours(24)),
    PASSWORD_RESET(Duration.ofHours(1)),
    EMAIL_CHANGE(Duration.ofHours(1));

    private final Duration validity;

    TokenType(Duration validity) {
        this.validity = validity;
    }

    public Duration getValidity() {
        return validity;
    }
}
