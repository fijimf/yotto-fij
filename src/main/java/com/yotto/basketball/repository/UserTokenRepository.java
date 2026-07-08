package com.yotto.basketball.repository;

import com.yotto.basketball.entity.TokenType;
import com.yotto.basketball.entity.User;
import com.yotto.basketball.entity.UserToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface UserTokenRepository extends JpaRepository<UserToken, Long> {

    Optional<UserToken> findByTokenHashAndType(String tokenHash, TokenType type);

    /**
     * Atomic single-use consumption: marks the token used only if it is still
     * unused and unexpired. Returns the number of rows updated (0 = the token
     * is invalid, expired, or already consumed — including by a concurrent request).
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE UserToken t SET t.usedAt = :now "
            + "WHERE t.tokenHash = :tokenHash AND t.type = :type "
            + "AND t.usedAt IS NULL AND t.expiresAt > :now")
    int consume(@Param("tokenHash") String tokenHash,
                @Param("type") TokenType type,
                @Param("now") Instant now);

    /** Invalidates all outstanding tokens of one type so only the newest link works. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE UserToken t SET t.usedAt = :now "
            + "WHERE t.user = :user AND t.type = :type AND t.usedAt IS NULL")
    int invalidateOutstanding(@Param("user") User user,
                              @Param("type") TokenType type,
                              @Param("now") Instant now);

    long deleteByExpiresAtBefore(Instant cutoff);
}
