package com.yotto.basketball.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Per-IP throttle on login POSTs, in front of the authentication filter.
 * The per-user lockout counter (database-backed) handles targeted attacks;
 * this slows spray attacks from a single address.
 */
@Component
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimitFilter.class);

    private final RateLimitService rateLimitService;

    public LoginRateLimitFilter(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if ("POST".equals(request.getMethod()) && "/login".equals(request.getRequestURI())) {
            String ip = request.getRemoteAddr();
            if (!rateLimitService.tryConsume("login-ip", ip,
                    RateLimitService.LOGIN_IP_MAX, RateLimitService.LOGIN_IP_WINDOW)) {
                log.warn("Login rate limit exceeded for IP {}", ip);
                response.sendRedirect("/login?error=rate");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
