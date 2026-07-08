package com.yotto.basketball.security;

import com.yotto.basketball.entity.TokenType;
import com.yotto.basketball.entity.User;
import com.yotto.basketball.entity.UserToken;
import com.yotto.basketball.repository.UserTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * One-time token lifecycle. Raw tokens are 32 random bytes (base64url) that
 * exist only in the emailed link; the database stores their SHA-256 hex.
 */
@Service
public class TokenService {

    private static final int TOKEN_BYTES = 32;

    private final UserTokenRepository tokenRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public TokenService(UserTokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    /**
     * Issues a fresh token, invalidating any outstanding tokens of the same
     * type so only the newest link works. Returns the raw token for the email link.
     */
    @Transactional
    public String issue(User user, TokenType type, String payload) {
        tokenRepository.invalidateOutstanding(user, type, Instant.now());

        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        UserToken token = new UserToken();
        token.setUser(user);
        token.setTokenHash(sha256Hex(raw));
        token.setType(type);
        token.setPayload(payload);
        token.setExpiresAt(Instant.now().plus(type.getValidity()));
        tokenRepository.save(token);
        return raw;
    }

    /** Validates without consuming — used to render the confirmation form. */
    @Transactional(readOnly = true)
    public Optional<UserToken> peek(String rawToken, TokenType type) {
        if (rawToken == null || rawToken.isBlank()) return Optional.empty();
        return tokenRepository.findByTokenHashAndType(sha256Hex(rawToken), type)
                .filter(UserToken::isUsable);
    }

    /**
     * Atomically consumes the token (single-use even under concurrent requests).
     * Empty result means invalid, expired, or already used.
     */
    @Transactional
    public Optional<UserToken> consume(String rawToken, TokenType type) {
        if (rawToken == null || rawToken.isBlank()) return Optional.empty();
        String hash = sha256Hex(rawToken);
        int updated = tokenRepository.consume(hash, type, Instant.now());
        if (updated == 0) return Optional.empty();
        return tokenRepository.findByTokenHashAndType(hash, type);
    }

    static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
