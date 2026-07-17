package com.yotto.basketball.config;

import com.yotto.basketball.security.AppUserDetailsService;
import com.yotto.basketball.security.AuthFailureHandler;
import com.yotto.basketball.security.LoginRateLimitFilter;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.server.CookieSameSiteSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.multipart.support.MultipartFilter;
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
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
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
                        // Public REST API is READ-ONLY for anonymous callers. Every
                        // mutating verb (POST/PUT/PATCH/DELETE) requires ADMIN so a
                        // stray curl can't rewrite or delete games/teams/scores.
                        .requestMatchers(HttpMethod.GET, "/api/**").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/api/**").permitAll()
                        .requestMatchers("/api/**").hasRole("ADMIN")
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
                // CSRF is enforced everywhere, including /api/** — its mutating
                // endpoints are now ADMIN-only and browser-reachable, so they must
                // carry a token. GET/HEAD are never CSRF-checked, so public API
                // reads are unaffected.
                .csrf(Customizer.withDefaults())
                .headers(headers -> headers
                        // Defense-in-depth against XSS/clickjacking/plugin abuse.
                        // 'unsafe-inline' is required because the Thymeleaf templates
                        // carry inline <script>/<style>; external CDNs used by the UI
                        // (htmx, chart.js, d3, Google Fonts) are explicitly allowlisted.
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; " +
                                "script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net https://unpkg.com; " +
                                "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; " +
                                "font-src 'self' https://fonts.gstatic.com data:; " +
                                "img-src 'self' data: https:; " +
                                "connect-src 'self'; " +
                                "object-src 'none'; " +
                                "base-uri 'self'; " +
                                "frame-ancestors 'none'; " +
                                "form-action 'self'"))
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                )
                .exceptionHandling(handling -> handling
                        .accessDeniedPage("/error/403")
                )
                // HTTP Basic enables script-based access to /admin/** (e.g. retrain.sh reload call)
                .httpBasic(Customizer.withDefaults())
                .addFilterBefore(loginRateLimitFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Runs Spring's MultipartFilter <em>before</em> the security filter chain so the
     * {@code _csrf} token carried in a multipart/form-data body (the admin broadcast
     * compose form uploads attachments) is parsed in time for CsrfFilter to see it.
     * Without this, a multipart POST would 403 because CsrfFilter runs before the
     * DispatcherServlet's multipart resolver. Non-multipart requests pass straight
     * through untouched.
     */
    @Bean
    public FilterRegistrationBean<MultipartFilter> multipartFilterRegistration() {
        FilterRegistrationBean<MultipartFilter> registration =
                new FilterRegistrationBean<>(new MultipartFilter());
        registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER - 1);
        return registration;
    }

    /**
     * Spring Security's remember-me cookie is written by the container, not through
     * {@code server.servlet.session.cookie.*} (which only governs JSESSIONID), so it
     * would otherwise ship without a SameSite attribute. Tag it {@code SameSite=Lax}
     * so this long-lived (30-day) auth cookie isn't sent on cross-site requests.
     */
    @Bean
    public CookieSameSiteSupplier rememberMeCookieSameSiteSupplier() {
        return CookieSameSiteSupplier.ofLax().whenHasName("remember-me");
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
