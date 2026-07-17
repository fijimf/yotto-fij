package com.yotto.basketball.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Durable record of one admin-authored broadcast email (downtime notice, feature
 * announcement) sent to every verified, unlocked user. The row is created when the
 * admin triggers the send; a background job then delivers per recipient and updates
 * the counts and {@link Status} as it progresses.
 */
@Entity
@Table(name = "email_broadcasts")
public class EmailBroadcast {

    public enum Status { SENDING, SENT, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(length = 255)
    private String subject;

    /** The Markdown exactly as the admin authored it. */
    @NotNull
    @Column(name = "body_markdown", columnDefinition = "TEXT")
    private String bodyMarkdown;

    /** Rendered, sanitized HTML actually emailed. */
    @NotNull
    @Column(name = "body_html", columnDefinition = "TEXT")
    private String bodyHtml;

    @Column(name = "sent_by_username", length = 30)
    private String sentByUsername;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Status status = Status.SENDING;

    @Column(name = "recipient_count")
    private int recipientCount;

    @Column(name = "sent_count")
    private int sentCount;

    @Column(name = "failed_count")
    private int failedCount;

    @Column(name = "attachment_count")
    private int attachmentCount;

    @Column(name = "attachment_names", length = 1000)
    private String attachmentNames;

    @NotNull
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    public EmailBroadcast() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getBodyMarkdown() { return bodyMarkdown; }
    public void setBodyMarkdown(String bodyMarkdown) { this.bodyMarkdown = bodyMarkdown; }

    public String getBodyHtml() { return bodyHtml; }
    public void setBodyHtml(String bodyHtml) { this.bodyHtml = bodyHtml; }

    public String getSentByUsername() { return sentByUsername; }
    public void setSentByUsername(String sentByUsername) { this.sentByUsername = sentByUsername; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public int getRecipientCount() { return recipientCount; }
    public void setRecipientCount(int recipientCount) { this.recipientCount = recipientCount; }

    public int getSentCount() { return sentCount; }
    public void setSentCount(int sentCount) { this.sentCount = sentCount; }

    public int getFailedCount() { return failedCount; }
    public void setFailedCount(int failedCount) { this.failedCount = failedCount; }

    public int getAttachmentCount() { return attachmentCount; }
    public void setAttachmentCount(int attachmentCount) { this.attachmentCount = attachmentCount; }

    public String getAttachmentNames() { return attachmentNames; }
    public void setAttachmentNames(String attachmentNames) { this.attachmentNames = attachmentNames; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
