package com.yotto.basketball.repository;

import com.yotto.basketball.entity.UserAuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface UserAuditEventRepository extends JpaRepository<UserAuditEvent, Long> {

    Page<UserAuditEvent> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    long deleteByCreatedAtBefore(Instant cutoff);
}
