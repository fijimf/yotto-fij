package com.yotto.basketball.config;

import com.yotto.basketball.security.AppUserDetailsService;
import com.yotto.basketball.security.AuthFailureHandler;
import com.yotto.basketball.security.LoginRateLimitFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.security.web.session.HttpSessionEventPublisher;

import javax.sql.DataSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final int REMEMBER_ME_SECONDS = 30 * 24 * 60 * 60; // 30 days

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   AppUserDetailsService userDetailsService,
                                                   AuthFailureHandler authFailureHandler,
                                                   LoginRateLimitFilter loginRateLimitFilter,
                                                   PersistentTokenRepository persistentTokenRepository,
                                                   SessionRegistry sessionRegistry) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/register/**", "/verify/**",
                                "/forgot-password/**", "/reset-password/**").permitAll()
                        // Legacy bookmark — redirects to /login
                        .requestMatchers("/admin/login").permitAll()
                        // ADMIN passes hasRole('USER') via the role hierarchy
                        .requestMatchers("/account/**").hasRole("USER")
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/**").permitAll()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .failureHandler(authFailureHandler)
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/")
                        .deleteCookies("JSESSIONID", "remember-me")
                        .permitAll()
                )
                .rememberMe(remember -> remember
                        .rememberMeParameter("remember-me")
                        .tokenRepository(persistentTokenRepository)
                        .tokenValiditySeconds(REMEMBER_ME_SECONDS)
                        .userDetailsService(userDetailsService)
                )
                // Unlimited concurrent sessions; the registry exists so password
                // change / admin lock can force-expire a user's other sessions.
                .sessionManagement(session -> session
                        .maximumSessions(-1)
                        .sessionRegistry(sessionRegistry)
                        .expiredUrl("/login?expired")
                )
                .csrf(csrf -> csrf
                        .ignoringRequestMatchers("/api/**")
                )
                .exceptionHandling(handling -> handling
                        .accessDeniedPage("/error/403")
                )
                // HTTP Basic enables script-based access to /admin/** (e.g. retrain.sh reload call)
                .httpBasic(Customizer.withDefaults())
                .addFilterBefore(loginRateLimitFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // {bcrypt}-prefixed hashes; V22 prefixed the migrated admin hash to match
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** admin > user; anonymous is Spring's implicit ROLE_ANONYMOUS. */
    @Bean
    static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("ROLE_ADMIN > ROLE_USER");
    }

    @Bean
    public PersistentTokenRepository persistentTokenRepository(DataSource dataSource) {
        JdbcTokenRepositoryImpl repository = new JdbcTokenRepositoryImpl();
        repository.setDataSource(dataSource); // persistent_logins table created by V22
        return repository;
    }

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /** Required so the SessionRegistry hears session-destroyed events. */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }
}
