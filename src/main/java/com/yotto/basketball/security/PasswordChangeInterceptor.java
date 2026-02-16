package com.yotto.basketball.security;

import com.yotto.basketball.entity.AdminUser;
import com.yotto.basketball.repository.AdminUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class PasswordChangeInterceptor implements HandlerInterceptor {

    private final AdminUserRepository adminUserRepository;

    public PasswordChangeInterceptor(AdminUserRepository adminUserRepository) {
        this.adminUserRepository = adminUserRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();

        // Don't intercept login, change-password, logout, or static resources
        if (uri.startsWith("/admin/login") || uri.startsWith("/admin/change-password")
                || uri.startsWith("/admin/logout") || uri.startsWith("/css/")
                || uri.startsWith("/js/") || uri.startsWith("/api/")) {
            return true;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            AdminUser user = adminUserRepository.findByUsername(auth.getName()).orElse(null);
            if (user != null && Boolean.TRUE.equals(user.getPasswordMustChange())) {
                response.sendRedirect("/admin/change-password");
                return false;
            }
        }

        return true;
    }
}
