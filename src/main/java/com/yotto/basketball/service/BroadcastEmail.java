package com.yotto.basketball.service;

import java.util.List;

/**
 * A fully-rendered broadcast email ready to send to a single recipient. Subject and
 * HTML body are rendered once per broadcast and reused across all recipients; only
 * {@code to} varies. Attachments (already in memory) are shared across recipients.
 */
public record BroadcastEmail(String to, String subject, String html, List<BroadcastAttachment> attachments) {
}
