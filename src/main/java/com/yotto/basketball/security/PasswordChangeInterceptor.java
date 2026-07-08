package com.yotto.basketball.security;

import com.yotto.basketball.entity.User;
import com.yotto.basketball.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Forces any authenticated user flagged password_must_change to the change
 * form before they can do anything else (bootstrap admin, admin-triggered resets).
 */
@Component
public class PasswordChangeInterceptor implements HandlerInterceptor {

    private static final String[] SKIP_PREFIXES = {
            "/login", "/logout", "/account/password",
            "/css/", "/js/", "/img/", "/api/", "/error",
            "/verify", "/register", "/forgot-password", "/reset-password",
            "/favicon"
    };

    private final UserRepository userRepository;

    public PasswordChangeInterceptor(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();
        for (String prefix : SKIP_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            User user = userRepository.findByUsernameIgnoreCase(auth.getName()).orElse(null);
            if (user != null && user.isPasswordMustChange()) {
                response.sendRedirect("/account/password");
                return false;
            }
        }

        return true;
    }
}
