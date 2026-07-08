package com.yotto.basketball.service;

import com.yotto.basketball.entity.AuditEventType;
import com.yotto.basketball.entity.User;
import com.yotto.basketball.entity.UserAuditEvent;
import com.yotto.basketball.repository.UserAuditEventRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes security audit events. IP and user agent are pulled from the current
 * request when one is bound to the thread (auth events, controller calls);
 * background jobs record events without them.
 */
@Service
public class UserAuditService {

    private static final Logger log = LoggerFactory.getLogger(UserAuditService.class);

    private final UserAuditEventRepository repository;

    public UserAuditService(UserAuditEventRepository repository) {
        this.repository = repository;
    }

    public void record(AuditEventType type, User user, String detail) {
        record(type, user == null ? null : user.getId(),
                user == null ? null : user.getUsername(), detail);
    }

    public void record(AuditEventType type, Long userId, String username, String detail) {
        try {
            UserAuditEvent event = new UserAuditEvent();
            event.setEventType(type);
            event.setUserId(userId);
            event.setUsername(truncate(username, 254));
            event.setDetail(truncate(detail, 500));

            HttpServletRequest request = currentRequest();
            if (request != null) {
                event.setIpAddress(truncate(request.getRemoteAddr(), 45));
                event.setUserAgent(truncate(request.getHeader("User-Agent"), 255));
            }
            repository.save(event);
        } catch (Exception e) {
            // Auditing must never break the flow it observes
            log.error("Failed to record audit event {} for user {}", type, username, e);
        }
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
