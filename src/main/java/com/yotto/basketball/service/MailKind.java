package com.yotto.basketball.service;

/** The transactional emails the account system sends. */
public enum MailKind {
    VERIFY("email/verify", "Confirm your DeepFij account"),
    PASSWORD_RESET("email/password-reset", "Reset your DeepFij password"),
    PASSWORD_CHANGED("email/password-changed", "Your DeepFij password was changed"),
    EMAIL_CHANGE_CONFIRM("email/email-change-confirm", "Confirm your new email address"),
    EMAIL_CHANGE_NOTICE("email/email-change-notice", "Your DeepFij email address is being changed"),
    ALREADY_REGISTERED("email/already-registered", "You already have a DeepFij account");

    private final String template;
    private final String subject;

    MailKind(String template, String subject) {
        this.template = template;
        this.subject = subject;
    }

    public String getTemplate() {
        return template;
    }

    public String getSubject() {
        return subject;
    }
}
