package com.yotto.basketball.service;

import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.nio.charset.StandardCharsets;

@Service
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "true")
public class SmtpMailService implements MailService {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final String from;

    public SmtpMailService(JavaMailSender mailSender,
                           TemplateEngine templateEngine,
                           @Value("${app.mail.from}") String from) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.from = from;
    }

    @Override
    public void send(AccountMailEvent mail) {
        try {
            Context context = new Context();
            context.setVariable("username", mail.username());
            context.setVariable("link", mail.link());

            String html = templateEngine.process(mail.kind().getTemplate(), context);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(mail.to());
            helper.setSubject(mail.kind().getSubject());
            helper.setText(html, true);
            mailSender.send(message);
        } catch (Exception e) {
            throw new MailDeliveryException("Failed to send " + mail.kind() + " email", e);
        }
    }

    @Override
    public void sendBroadcast(BroadcastEmail email) {
        try {
            boolean multipart = email.attachments() != null && !email.attachments().isEmpty();
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper =
                    new MimeMessageHelper(message, multipart, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(email.to());
            helper.setSubject(email.subject());
            helper.setText(email.html(), true);
            if (multipart) {
                for (BroadcastAttachment att : email.attachments()) {
                    helper.addAttachment(att.filename(),
                            new ByteArrayResource(att.data()), att.contentType());
                }
            }
            mailSender.send(message);
        } catch (Exception e) {
            throw new MailDeliveryException("Failed to send broadcast email to " + email.to(), e);
        }
    }
}
