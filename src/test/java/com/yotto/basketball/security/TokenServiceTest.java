package com.yotto.basketball.security;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Role;
import com.yotto.basketball.entity.TokenType;
import com.yotto.basketball.entity.User;
import com.yotto.basketball.entity.UserToken;
import com.yotto.basketball.repository.UserRepository;
import com.yotto.basketball.repository.UserTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TokenServiceTest extends BaseIntegrationTest {

    @Autowired private TokenService tokenService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserTokenRepository tokenRepository;

    private User user;

    @BeforeEach
    void fixture() {
        user = new User();
        user.setUsername("token-user");
        user.setEmail("token-user@example.com");
        user.setPasswordHash("{noop}irrelevant");
        user.setRole(Role.USER);
        user.setEnabled(true);
        user = userRepository.save(user);
    }

    @Test
    void issue_storesOnlyTheHash() {
        String raw = tokenService.issue(user, TokenType.EMAIL_VERIFICATION, null);

        assertThat(raw).hasSizeGreaterThanOrEqualTo(43); // 32 bytes base64url
        UserToken stored = tokenRepository.findAll().get(0);
        assertThat(stored.getTokenHash()).isNotEqualTo(raw);
        assertThat(stored.getTokenHash()).isEqualTo(TokenService.sha256Hex(raw));
    }

    @Test
    void peek_validatesWithoutConsuming() {
        String raw = tokenService.issue(user, TokenType.PASSWORD_RESET, null);

        assertThat(tokenService.peek(raw, TokenType.PASSWORD_RESET)).isPresent();
        assertThat(tokenService.peek(raw, TokenType.PASSWORD_RESET)).isPresent(); // still there
        // Wrong type does not match
        assertThat(tokenService.peek(raw, TokenType.EMAIL_VERIFICATION)).isEmpty();
    }

    @Test
    void consume_isSingleUse() {
        String raw = tokenService.issue(user, TokenType.EMAIL_VERIFICATION, null);

        assertThat(tokenService.consume(raw, TokenType.EMAIL_VERIFICATION)).isPresent();
        assertThat(tokenService.consume(raw, TokenType.EMAIL_VERIFICATION)).isEmpty();
        assertThat(tokenService.peek(raw, TokenType.EMAIL_VERIFICATION)).isEmpty();
    }

    @Test
    void consume_rejectsExpiredToken() {
        String raw = tokenService.issue(user, TokenType.PASSWORD_RESET, null);
        UserToken stored = tokenRepository.findAll().get(0);
        stored.setExpiresAt(Instant.now().minusSeconds(5));
        tokenRepository.save(stored);

        assertThat(tokenService.consume(raw, TokenType.PASSWORD_RESET)).isEmpty();
    }

    @Test
    void issue_invalidatesOutstandingTokensOfSameType() {
        String first = tokenService.issue(user, TokenType.PASSWORD_RESET, null);
        String second = tokenService.issue(user, TokenType.PASSWORD_RESET, null);

        assertThat(tokenService.consume(first, TokenType.PASSWORD_RESET)).isEmpty();
        assertThat(tokenService.consume(second, TokenType.PASSWORD_RESET)).isPresent();
    }

    @Test
    void issue_doesNotInvalidateOtherTypes() {
        String verify = tokenService.issue(user, TokenType.EMAIL_VERIFICATION, null);
        tokenService.issue(user, TokenType.PASSWORD_RESET, null);

        assertThat(tokenService.peek(verify, TokenType.EMAIL_VERIFICATION)).isPresent();
    }

    @Test
    void payload_isPreserved() {
        String raw = tokenService.issue(user, TokenType.EMAIL_CHANGE, "new@example.com");

        Optional<UserToken> consumed = tokenService.consume(raw, TokenType.EMAIL_CHANGE);
        assertThat(consumed).isPresent();
        assertThat(consumed.get().getPayload()).isEqualTo("new@example.com");
    }

    @Test
    void blankOrNullTokens_areRejectedCheaply() {
        assertThat(tokenService.peek(null, TokenType.PASSWORD_RESET)).isEmpty();
        assertThat(tokenService.peek("", TokenType.PASSWORD_RESET)).isEmpty();
        assertThat(tokenService.consume(null, TokenType.PASSWORD_RESET)).isEmpty();
    }
}
