package com.yotto.basketball.security;

/** Thrown by web controllers when a rate limit is hit; rendered as a 429 page. */
public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException(String message) {
        super(message);
    }
}
