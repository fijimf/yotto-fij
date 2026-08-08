package com.yotto.basketball.service;

import com.yotto.basketball.entity.User;
import com.yotto.basketball.repository.UserPreferenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The daily update email: the front-page composition rendered per user (their Your Teams strip
 * included) and sent to everyone opted into {@code email.daily-update}. Off by default —
 * {@code app.digest.enabled=true} turns it on; the cron default lands after the morning scrape.
 *
 * <p>Double-send safety: the job claims the calendar day with an
 * {@code INSERT ... ON CONFLICT DO NOTHING} into {@code daily_digest_runs} before sending, so a
 * restart mid-run (or a second scheduler) can't re-send. Per-recipient failures are logged and
 * skipped — one bad address never aborts the run. Empty digests (nothing beyond archive trivia)
 * are not sent.
 */
@Component
public class DailyDigestJob {

    private static final Logger log = LoggerFactory.getLogger(DailyDigestJob.class);

    private final HomePageService homePageService;
    private final UserPreferenceRepository userPreferenceRepository;
    private final MailService mailService;
    private final TemplateEngine templateEngine;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final boolean enabled;
    private final String baseUrl;

    public DailyDigestJob(HomePageService homePageService,
                          UserPreferenceRepository userPreferenceRepository,
                          MailService mailService,
                          TemplateEngine templateEngine,
                          JdbcTemplate jdbcTemplate,
                          Clock clock,
                          @Value("${app.digest.enabled:false}") boolean enabled,
                          @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.homePageService = homePageService;
        this.userPreferenceRepository = userPreferenceRepository;
        this.mailService = mailService;
        this.templateEngine = templateEngine;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.enabled = enabled;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** Default 12:30 UTC ≈ post-morning-scrape; override with app.digest.cron. */
    @Scheduled(cron = "${app.digest.cron:0 30 12 * * *}")
    public void scheduledRun() {
        if (!enabled) {
            return;
        }
        runOnce();
    }

    /** Runs one digest cycle; returns the number of emails sent (0 if today was already claimed). */
    public int runOnce() {
        LocalDate today = LocalDate.now(clock);
        int claimed = jdbcTemplate.update(
                "INSERT INTO daily_digest_runs (run_date) VALUES (?) ON CONFLICT DO NOTHING", today);
        if (claimed == 0) {
            log.info("Daily digest already ran for {} — skipping", today);
            return 0;
        }

        List<User> recipients = userPreferenceRepository
                .findUsersWithPreference(PreferenceKeys.DAILY_UPDATE_EMAIL, "true");
        int sent = 0;
        int skippedEmpty = 0;
        for (User user : recipients) {
            try {
                HomePageService.DigestView digest = homePageService.buildDigest(user.getId());
                if (!digest.hasContent()) {
                    skippedEmpty++;
                    continue;
                }
                Context ctx = new Context();
                ctx.setVariable("digest", digest);
                ctx.setVariable("baseUrl", baseUrl);
                String html = templateEngine.process("email/daily-digest", ctx);
                String subject = "DeepFij Daily — " + digest.date()
                        .format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US));
                mailService.sendBroadcast(new BroadcastEmail(user.getEmail(), subject, html, List.of()));
                sent++;
            } catch (Exception e) {
                log.warn("Daily digest failed for user {}: {}", user.getUsername(), e.getMessage());
            }
        }
        jdbcTemplate.update("UPDATE daily_digest_runs SET sent_count = ? WHERE run_date = ?", sent, today);
        log.info("Daily digest for {}: {} sent, {} empty-skipped, {} recipients total",
                today, sent, skippedEmpty, recipients.size());
        return sent;
    }
}
