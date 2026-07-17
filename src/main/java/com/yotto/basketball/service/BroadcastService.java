package com.yotto.basketball.service;

import com.yotto.basketball.entity.AuditEventType;
import com.yotto.basketball.entity.EmailBroadcast;
import com.yotto.basketball.entity.User;
import com.yotto.basketball.repository.EmailBroadcastRepository;
import com.yotto.basketball.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates admin broadcast emails (downtime notices, feature announcements) to
 * every verified, unlocked user. The compose flow renders Markdown once into a
 * sanitized, site-styled email; delivery then runs on the dedicated
 * {@code broadcastExecutor} one recipient at a time so a bulk fan-out never competes
 * with latency-sensitive transactional account mail.
 */
@Service
public class BroadcastService {

    private static final Logger log = LoggerFactory.getLogger(BroadcastService.class);

    private final MailService mailService;
    private final MarkdownService markdownService;
    private final TemplateEngine templateEngine;
    private final UserRepository userRepository;
    private final EmailBroadcastRepository broadcastRepository;
    private final UserAuditService auditService;

    public BroadcastService(MailService mailService,
                            MarkdownService markdownService,
                            TemplateEngine templateEngine,
                            UserRepository userRepository,
                            EmailBroadcastRepository broadcastRepository,
                            UserAuditService auditService) {
        this.mailService = mailService;
        this.markdownService = markdownService;
        this.templateEngine = templateEngine;
        this.userRepository = userRepository;
        this.broadcastRepository = broadcastRepository;
        this.auditService = auditService;
    }

    /** How many users a broadcast would reach right now. */
    public long recipientCount() {
        return userRepository.countByEnabledTrueAndLockedFalseAndEmailIsNotNull();
    }

    /** Just the sanitized body HTML, for the compose live-preview pane. */
    public String renderPreviewBody(String markdown) {
        return markdownService.toSafeHtml(markdown);
    }

    /** The full email HTML a recipient would see (used by compose preview and send). */
    public String renderEmailHtml(String subject, String markdown) {
        Context context = new Context();
        context.setVariable("subject", subject == null ? "" : subject);
        context.setVariable("bodyHtml", markdownService.toSafeHtml(markdown));
        return templateEngine.process("email/broadcast", context);
    }

    /**
     * Creates the broadcast history row and returns it. Delivery is kicked off
     * separately via {@link #deliver} so the @Async proxy is honored.
     */
    public EmailBroadcast create(String subject, String markdown, int attachmentCount,
                                 String attachmentNames, String adminUsername) {
        String html = renderEmailHtml(subject, markdown);

        EmailBroadcast broadcast = new EmailBroadcast();
        broadcast.setSubject(subject);
        broadcast.setBodyMarkdown(markdown);
        broadcast.setBodyHtml(html);
        broadcast.setSentByUsername(adminUsername);
        broadcast.setStatus(EmailBroadcast.Status.SENDING);
        broadcast.setRecipientCount((int) recipientCount());
        broadcast.setAttachmentCount(attachmentCount);
        broadcast.setAttachmentNames(attachmentNames);
        broadcast.setCreatedAt(Instant.now());
        return broadcastRepository.save(broadcast);
    }

    /** Sends the rendered message to a single address (admin test send). Throws on failure. */
    public void sendTest(String subject, String markdown, List<BroadcastAttachment> attachments, String toEmail) {
        String html = renderEmailHtml(subject, markdown);
        mailService.sendBroadcast(new BroadcastEmail(toEmail, "[TEST] " + subject, html, attachments));
    }

    /**
     * Delivers a prepared broadcast to every current recipient, one at a time,
     * updating the row's counts as it goes. Called from the controller (external
     * invocation) so Spring's @Async proxy runs it on {@code broadcastExecutor}.
     */
    @Async("broadcastExecutor")
    public void deliver(Long broadcastId, List<BroadcastAttachment> attachments, String adminUsername) {
        EmailBroadcast broadcast = broadcastRepository.findById(broadcastId)
                .orElseThrow(() -> new EntityNotFoundException("Broadcast " + broadcastId + " not found"));

        List<User> recipients = userRepository.findByEnabledTrueAndLockedFalseAndEmailIsNotNull();
        // De-duplicate by lowercased email in case of any historical collisions.
        Map<String, String> byEmail = new LinkedHashMap<>();
        for (User u : recipients) {
            byEmail.putIfAbsent(u.getEmail().toLowerCase(), u.getEmail());
        }

        broadcast.setRecipientCount(byEmail.size());
        int sent = 0;
        int failed = 0;
        for (String address : byEmail.values()) {
            try {
                mailService.sendBroadcast(
                        new BroadcastEmail(address, broadcast.getSubject(), broadcast.getBodyHtml(), attachments));
                sent++;
            } catch (Exception e) {
                failed++;
                log.warn("Broadcast {} failed to {}", broadcastId, address, e);
            }
            broadcast.setSentCount(sent);
            broadcast.setFailedCount(failed);
            broadcastRepository.save(broadcast);
        }

        broadcast.setStatus(failed == 0 ? EmailBroadcast.Status.SENT : EmailBroadcast.Status.FAILED);
        broadcast.setCompletedAt(Instant.now());
        broadcastRepository.save(broadcast);

        auditService.record(AuditEventType.BROADCAST_SENT, null, adminUsername,
                "\"" + broadcast.getSubject() + "\" sent=" + sent + " failed=" + failed);
        log.info("Broadcast {} complete: sent={} failed={}", broadcastId, sent, failed);
    }
}
