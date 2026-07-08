package com.yotto.basketball.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

/**
 * Bridges Spring Security auth events to the lockout counter, audit trail,
 * and Micrometer metrics (visible in netdata via the prometheus scrape).
 */
@Component
public class AuthEventListener {

    private final AccountLockoutService lockoutService;
    private final Counter loginSuccessCounter;
    private final Counter loginFailureCounter;

    public AuthEventListener(AccountLockoutService lockoutService, MeterRegistry meterRegistry) {
        this.lockoutService = lockoutService;
        this.loginSuccessCounter = Counter.builder("auth.login").tag("outcome", "success")
                .register(meterRegistry);
        this.loginFailureCounter = Counter.builder("auth.login").tag("outcome", "failure")
                .register(meterRegistry);
    }

    @EventListener
    public void onFailure(AuthenticationFailureBadCredentialsEvent event) {
        loginFailureCounter.increment();
        lockoutService.onLoginFailure(event.getAuthentication().getName());
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        loginSuccessCounter.increment();
        lockoutService.onLoginSuccess(event.getAuthentication().getName());
    }
}
