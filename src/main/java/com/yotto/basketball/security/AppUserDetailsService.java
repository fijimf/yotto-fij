package com.yotto.basketball.security;

import com.yotto.basketball.entity.User;
import com.yotto.basketball.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads accounts for form login, HTTP Basic, and remember-me. The login
 * identifier may be a username or an email address; both are matched
 * case-insensitively. Locked/disabled checks are left to
 * DaoAuthenticationProvider so the standard exceptions (and their events) fire.
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public AppUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String usernameOrEmail) throws UsernameNotFoundException {
        User user = userRepository.findByUsernameIgnoreCase(usernameOrEmail)
                .or(() -> userRepository.findByEmailIgnoreCase(usernameOrEmail))
                .orElseThrow(() -> new UsernameNotFoundException("No user: " + usernameOrEmail));
        return new AppUserDetails(user);
    }
}
