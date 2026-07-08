package com.yotto.basketball.service;

/**
 * Sends account emails. SMTP in production ({@code app.mail.enabled=true});
 * the logging implementation (default) writes the message — including the
 * link — to the log, which makes dev/test flows exercisable without SMTP.
 */
public interface MailService {

    void send(AccountMailEvent mail);
}
