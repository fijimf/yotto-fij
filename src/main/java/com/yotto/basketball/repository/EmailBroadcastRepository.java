package com.yotto.basketball.repository;

import com.yotto.basketball.entity.EmailBroadcast;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailBroadcastRepository extends JpaRepository<EmailBroadcast, Long> {

    /** Most recent broadcasts first, for the admin history view. */
    Page<EmailBroadcast> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
