package com.yotto.basketball.security;

import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.stereotype.Service;

/**
 * Force-expires a user's live sessions and remember-me tokens. Used on
 * password change/reset, admin lock, role change, and account deletion.
 * Registry is in-memory (single instance), matching the deployment.
 */
@Service
public class SessionInvalidationService {

    private final SessionRegistry sessionRegistry;
    private final PersistentTokenRepository persistentTokenRepository;

    public SessionInvalidationService(SessionRegistry sessionRegistry,
                                      PersistentTokenRepository persistentTokenRepository) {
        this.sessionRegistry = sessionRegistry;
        this.persistentTokenRepository = persistentTokenRepository;
    }

    /** Expires every session and remember-me token for the user. */
    public void invalidateAll(String username) {
        invalidateAllExcept(username, null);
    }

    /** Expires all sessions except {@code keepSessionId} (e.g. the one changing the password). */
    public void invalidateAllExcept(String username, String keepSessionId) {
        sessionRegistry.getAllPrincipals().stream()
                .filter(p -> p instanceof UserDetails ud && ud.getUsername().equalsIgnoreCase(username))
                .flatMap(p -> sessionRegistry.getAllSessions(p, false).stream())
                .filter(info -> keepSessionId == null || !info.getSessionId().equals(keepSessionId))
                .forEach(SessionInformation::expireNow);
        persistentTokenRepository.removeUserTokens(username);
    }
}
