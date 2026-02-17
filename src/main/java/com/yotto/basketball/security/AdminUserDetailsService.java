package com.yotto.basketball.security;

import com.yotto.basketball.entity.AdminUser;
import com.yotto.basketball.repository.AdminUserRepository;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AdminUserDetailsService implements UserDetailsService {

    private final AdminUserRepository adminUserRepository;
    private final LoginAttemptService loginAttemptService;

    public AdminUserDetailsService(AdminUserRepository adminUserRepository,
                                   LoginAttemptService loginAttemptService) {
        this.adminUserRepository = adminUserRepository;
        this.loginAttemptService = loginAttemptService;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (loginAttemptService.isBlocked(username)) {
            throw new LockedException("Account temporarily locked due to too many failed login attempts");
        }

        AdminUser adminUser = adminUserRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        return User.builder()
                .username(adminUser.getUsername())
                .password(adminUser.getPasswordHash())
                .roles("ADMIN")
                .build();
    }
}
