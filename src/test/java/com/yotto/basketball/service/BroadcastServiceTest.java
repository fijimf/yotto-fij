package com.yotto.basketball.service;

import com.yotto.basketball.entity.AuditEventType;
import com.yotto.basketball.entity.EmailBroadcast;
import com.yotto.basketball.entity.User;
import com.yotto.basketball.repository.EmailBroadcastRepository;
import com.yotto.basketball.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thymeleaf.TemplateEngine;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BroadcastServiceTest {

    @Mock private MailService mailService;
    @Mock private MarkdownService markdownService;
    @Mock private TemplateEngine templateEngine;
    @Mock private UserRepository userRepository;
    @Mock private EmailBroadcastRepository broadcastRepository;
    @Mock private UserAuditService auditService;

    private BroadcastService service;

    @BeforeEach
    void setUp() {
        service = new BroadcastService(mailService, markdownService, templateEngine,
                userRepository, broadcastRepository, auditService);
    }

    private User userWithEmail(String email) {
        User u = new User();
        u.setEmail(email);
        return u;
    }

    @Test
    void deliverSendsToEveryRecipientAndMarksSent() {
        EmailBroadcast broadcast = new EmailBroadcast();
        broadcast.setSubject("Maintenance");
        broadcast.setBodyHtml("<p>hi</p>");
        when(broadcastRepository.findById(1L)).thenReturn(java.util.Optional.of(broadcast));
        when(userRepository.findByEnabledTrueAndLockedFalseAndEmailIsNotNull())
                .thenReturn(List.of(userWithEmail("a@x.com"), userWithEmail("b@x.com")));

        service.deliver(1L, List.of(), "admin");

        verify(mailService, times(2)).sendBroadcast(any(BroadcastEmail.class));
        assertThat(broadcast.getSentCount()).isEqualTo(2);
        assertThat(broadcast.getFailedCount()).isZero();
        assertThat(broadcast.getStatus()).isEqualTo(EmailBroadcast.Status.SENT);
        assertThat(broadcast.getCompletedAt()).isNotNull();
        verify(auditService).record(eq(AuditEventType.BROADCAST_SENT), isNull(), eq("admin"), anyString());
    }

    @Test
    void deliverDeduplicatesByEmailCaseInsensitively() {
        EmailBroadcast broadcast = new EmailBroadcast();
        broadcast.setSubject("Hi");
        broadcast.setBodyHtml("<p>hi</p>");
        when(broadcastRepository.findById(1L)).thenReturn(java.util.Optional.of(broadcast));
        when(userRepository.findByEnabledTrueAndLockedFalseAndEmailIsNotNull())
                .thenReturn(List.of(userWithEmail("Dup@X.com"), userWithEmail("dup@x.com")));

        service.deliver(1L, List.of(), "admin");

        verify(mailService, times(1)).sendBroadcast(any(BroadcastEmail.class));
        assertThat(broadcast.getRecipientCount()).isEqualTo(1);
    }

    @Test
    void deliverCountsFailuresAndMarksFailed() {
        EmailBroadcast broadcast = new EmailBroadcast();
        broadcast.setSubject("Hi");
        broadcast.setBodyHtml("<p>hi</p>");
        when(broadcastRepository.findById(1L)).thenReturn(java.util.Optional.of(broadcast));
        when(userRepository.findByEnabledTrueAndLockedFalseAndEmailIsNotNull())
                .thenReturn(List.of(userWithEmail("a@x.com"), userWithEmail("b@x.com")));
        // First recipient succeeds, second throws — independent of iteration order.
        doNothing().doThrow(new RuntimeException("smtp down"))
                .when(mailService).sendBroadcast(any(BroadcastEmail.class));

        service.deliver(1L, List.of(), "admin");

        assertThat(broadcast.getSentCount()).isEqualTo(1);
        assertThat(broadcast.getFailedCount()).isEqualTo(1);
        assertThat(broadcast.getStatus()).isEqualTo(EmailBroadcast.Status.FAILED);
    }

    @Test
    void sendTestPrefixesSubjectAndSendsToOneAddress() {
        when(templateEngine.process(eq("email/broadcast"), any())).thenReturn("<html>body</html>");

        service.sendTest("Hello", "**hi**", List.of(), "me@x.com");

        ArgumentCaptor<BroadcastEmail> captor = ArgumentCaptor.forClass(BroadcastEmail.class);
        verify(mailService).sendBroadcast(captor.capture());
        assertThat(captor.getValue().to()).isEqualTo("me@x.com");
        assertThat(captor.getValue().subject()).isEqualTo("[TEST] Hello");
    }

    @Test
    void createPersistsBroadcastWithRenderedHtmlAndCounts() {
        when(templateEngine.process(eq("email/broadcast"), any())).thenReturn("<html>rendered</html>");
        when(userRepository.countByEnabledTrueAndLockedFalseAndEmailIsNotNull()).thenReturn(7L);
        when(broadcastRepository.save(any(EmailBroadcast.class))).thenAnswer(i -> i.getArgument(0));

        EmailBroadcast saved = service.create("Subj", "body", 2, "a.png, b.pdf", "admin");

        assertThat(saved.getSubject()).isEqualTo("Subj");
        assertThat(saved.getBodyHtml()).isEqualTo("<html>rendered</html>");
        assertThat(saved.getRecipientCount()).isEqualTo(7);
        assertThat(saved.getAttachmentCount()).isEqualTo(2);
        assertThat(saved.getStatus()).isEqualTo(EmailBroadcast.Status.SENDING);
    }
}
