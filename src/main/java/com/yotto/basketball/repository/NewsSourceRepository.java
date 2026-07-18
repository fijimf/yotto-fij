package com.yotto.basketball.repository;

import com.yotto.basketball.entity.NewsSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface NewsSourceRepository extends JpaRepository<NewsSource, Long> {

    Optional<NewsSource> findByFeedUrl(String feedUrl);

    Optional<NewsSource> findFirstByDomain(String domain);

    List<NewsSource> findByActiveTrueAndAutoDisabledAtIsNullOrderByName();

    /** Auto-disabled sources whose weekly retry probe is due. */
    @Query("select s from NewsSource s where s.active = true and s.autoDisabledAt is not null and s.autoDisabledAt < :cutoff")
    List<NewsSource> findRetryProbeCandidates(@Param("cutoff") LocalDateTime cutoff);

    List<NewsSource> findAllByOrderByName();

    long countByActiveTrueAndAutoDisabledAtIsNotNull();
}
