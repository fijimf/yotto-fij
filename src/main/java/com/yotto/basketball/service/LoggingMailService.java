package com.yotto.basketball.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Default (dev/test) mail implementation: logs the message instead of sending
 * it. The logged link lets a developer complete verify/reset flows locally.
 */
@Service
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingMailService implements MailService {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailService.class);

    @Override
    public void send(AccountMailEvent mail) {
        log.info("MAIL (logged, not sent) kind={} to={} username={} link={}",
                mail.kind(), mail.to(), mail.username(), mail.link());
    }
}
