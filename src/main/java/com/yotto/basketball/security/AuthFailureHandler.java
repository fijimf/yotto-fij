package com.yotto.basketball.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Maps authentication failures to distinct login-page messages. Bad username
 * and bad password produce the identical generic message (no account
 * enumeration); unverified and locked accounts get actionable messages — by
 * then the caller has presented a valid identifier, which usernames don't hide.
 */
@Component
public class AuthFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        String target;
        if (exception instanceof DisabledException) {
            target = "/login?error=unverified";
        } else if (exception instanceof LockedException) {
            target = "/login?error=locked";
        } else {
            target = "/login?error=bad";
        }
        setDefaultFailureUrl(target);
        super.onAuthenticationFailure(request, response, exception);
    }
}
