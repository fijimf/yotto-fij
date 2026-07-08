package com.yotto.basketball.service;

/**
 * An email to send after the surrounding transaction commits (a rolled-back
 * registration must never produce a verification email). Link is null for
 * notification-only mails.
 */
public record AccountMailEvent(MailKind kind, String to, String username, String link) {

    public static AccountMailEvent of(MailKind kind, String to, String username) {
        return new AccountMailEvent(kind, to, username, null);
    }
}
