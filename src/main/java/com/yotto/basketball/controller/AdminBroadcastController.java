package com.yotto.basketball.controller;

import com.yotto.basketball.entity.User;
import com.yotto.basketball.repository.EmailBroadcastRepository;
import com.yotto.basketball.repository.UserRepository;
import com.yotto.basketball.security.AppUserDetails;
import com.yotto.basketball.service.BroadcastAttachment;
import com.yotto.basketball.service.BroadcastService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Admin broadcast email: compose a Markdown message with optional attachments and
 * send it to every verified, unlocked user. Attachments are restricted to common
 * safe types with a 10MB total cap to protect deliverability. Delivery runs
 * asynchronously; this page shows a live history of past sends.
 */
@Controller
@RequestMapping("/admin/broadcast")
public class AdminBroadcastController {

    private static final Logger log = LoggerFactory.getLogger(AdminBroadcastController.class);

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "application/pdf");
    private static final long MAX_TOTAL_ATTACHMENT_BYTES = 10L * 1024 * 1024; // 10MB
    private static final int HISTORY_SIZE = 20;

    private final BroadcastService broadcastService;
    private final EmailBroadcastRepository broadcastRepository;
    private final UserRepository userRepository;

    public AdminBroadcastController(BroadcastService broadcastService,
                                    EmailBroadcastRepository broadcastRepository,
                                    UserRepository userRepository) {
        this.broadcastService = broadcastService;
        this.broadcastRepository = broadcastRepository;
        this.userRepository = userRepository;
    }

    @GetMapping
    public String compose(Model model) {
        model.addAttribute("recipientCount", broadcastService.recipientCount());
        addHistory(model);
        return "admin/broadcast";
    }

    /** HTMX-polled history fragment (mirrors the scrape/training status pattern). */
    @GetMapping("/history")
    public String history(Model model) {
        addHistory(model);
        return "admin/fragments/broadcast-history :: broadcast-history";
    }

    /** Live preview: render the authored Markdown to sanitized HTML for the compose pane. */
    @PostMapping(value = "/preview", produces = "text/html;charset=UTF-8")
    @ResponseBody
    public String preview(@RequestParam(required = false) String markdown) {
        return broadcastService.renderPreviewBody(markdown);
    }

    @PostMapping("/test")
    public String test(@RequestParam String subject,
                       @RequestParam String markdown,
                       @RequestParam(value = "attachments", required = false) MultipartFile[] files,
                       @AuthenticationPrincipal AppUserDetails principal,
                       RedirectAttributes ra) {
        User admin = userRepository.findById(principal.getId()).orElse(null);
        if (admin == null || !StringUtils.hasText(admin.getEmail())) {
            ra.addFlashAttribute("error", "Your account has no email address to send a test to.");
            return "redirect:/admin/broadcast";
        }
        try {
            validateContent(subject, markdown);
            List<BroadcastAttachment> attachments = readAttachments(files);
            broadcastService.sendTest(subject, markdown, attachments, admin.getEmail());
            ra.addFlashAttribute("success", "Test email sent to " + admin.getEmail() + ".");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        } catch (Exception e) {
            log.error("Test broadcast send failed", e);
            ra.addFlashAttribute("error", "Test send failed: " + e.getMessage());
        }
        return "redirect:/admin/broadcast";
    }

    @PostMapping("/send")
    public String send(@RequestParam String subject,
                       @RequestParam String markdown,
                       @RequestParam(value = "attachments", required = false) MultipartFile[] files,
                       @AuthenticationPrincipal AppUserDetails principal,
                       RedirectAttributes ra) {
        try {
            validateContent(subject, markdown);
            List<BroadcastAttachment> attachments = readAttachments(files);

            long count = broadcastService.recipientCount();
            if (count == 0) {
                ra.addFlashAttribute("error", "There are no verified users to send to.");
                return "redirect:/admin/broadcast";
            }

            String names = attachments.stream()
                    .map(BroadcastAttachment::filename)
                    .collect(Collectors.joining(", "));
            var broadcast = broadcastService.create(subject, markdown,
                    attachments.size(), StringUtils.hasText(names) ? names : null, principal.getUsername());
            broadcastService.deliver(broadcast.getId(), attachments, principal.getUsername());

            ra.addFlashAttribute("success",
                    "Broadcast queued to " + count + " recipient" + (count == 1 ? "" : "s") + ".");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        } catch (Exception e) {
            log.error("Broadcast send failed", e);
            ra.addFlashAttribute("error", "Send failed: " + e.getMessage());
        }
        return "redirect:/admin/broadcast";
    }

    private void addHistory(Model model) {
        model.addAttribute("broadcasts",
                broadcastRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, HISTORY_SIZE)).getContent());
    }

    private void validateContent(String subject, String markdown) {
        if (!StringUtils.hasText(subject)) {
            throw new IllegalArgumentException("Subject is required.");
        }
        if (!StringUtils.hasText(markdown)) {
            throw new IllegalArgumentException("Message body is required.");
        }
    }

    /** Reads uploaded files into memory, enforcing type and total-size limits. */
    List<BroadcastAttachment> readAttachments(MultipartFile[] files) throws IOException {
        List<BroadcastAttachment> attachments = new ArrayList<>();
        if (files == null) {
            return attachments;
        }
        long total = 0;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            String contentType = file.getContentType();
            if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
                throw new IllegalArgumentException(
                        "Attachment \"" + file.getOriginalFilename() + "\" has an unsupported type ("
                                + contentType + "). Allowed: PNG, JPEG, GIF, PDF.");
            }
            total += file.getSize();
            if (total > MAX_TOTAL_ATTACHMENT_BYTES) {
                throw new IllegalArgumentException("Attachments exceed the 10MB total limit.");
            }
            String name = StringUtils.hasText(file.getOriginalFilename())
                    ? file.getOriginalFilename() : "attachment";
            attachments.add(new BroadcastAttachment(name, contentType, file.getBytes()));
        }
        return attachments;
    }
}
