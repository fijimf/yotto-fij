package com.yotto.basketball.service;

import com.yotto.basketball.entity.AuditEventType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends account emails asynchronously after the publishing transaction
 * commits, so an SMTP outage can neither fail nor slow a user-facing request,
 * and a rolled-back registration sends nothing. One retry after a pause;
 * every mail-dependent flow has a user-reachable resend path, so a lost email
 * is recoverable.
 */
@Component
public class MailEventListener {

    private static final Logger log = LoggerFactory.getLogger(MailEventListener.class);
    private static final long RETRY_DELAY_MS = 30_000;

    private final MailService mailService;
    private final UserAuditService auditService;
    private final Counter sentCounter;
    private final Counter failedCounter;

    public MailEventListener(MailService mailService, UserAuditService auditService,
                             MeterRegistry meterRegistry) {
        this.mailService = mailService;
        this.auditService = auditService;
        this.sentCounter = Counter.builder("auth.email").tag("outcome", "sent")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("auth.email").tag("outcome", "failed")
                .register(meterRegistry);
    }

    @Async("mailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAccountMail(AccountMailEvent event) {
        try {
            mailService.send(event);
            sentCounter.increment();
        } catch (Exception first) {
            log.warn("Email {} to {} failed, retrying once in {}s",
                    event.kind(), event.to(), RETRY_DELAY_MS / 1000, first);
            try {
                Thread.sleep(RETRY_DELAY_MS);
                mailService.send(event);
                sentCounter.increment();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                recordFailure(event, ie);
            } catch (Exception second) {
                recordFailure(event, second);
            }
        }
    }

    private void recordFailure(AccountMailEvent event, Exception e) {
        failedCounter.increment();
        log.error("Email {} to {} failed permanently", event.kind(), event.to(), e);
        auditService.record(AuditEventType.EMAIL_SEND_FAILED, null, event.username(),
                event.kind() + " to " + event.to());
    }
}
