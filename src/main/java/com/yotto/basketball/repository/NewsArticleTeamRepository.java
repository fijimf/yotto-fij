package com.yotto.basketball.repository;

import com.yotto.basketball.entity.NewsArticleTeam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NewsArticleTeamRepository extends JpaRepository<NewsArticleTeam, Long> {

    List<NewsArticleTeam> findByArticleId(Long articleId);

    Optional<NewsArticleTeam> findByArticleIdAndTeamId(Long articleId, Long teamId);

    boolean existsByArticleIdAndTeamId(Long articleId, Long teamId);

    void deleteByArticleIdAndManualFalse(Long articleId);

    long deleteByArticleIdAndTeamId(Long articleId, Long teamId);

    /** Near-misses for the admin tagging page: auto tags below the display threshold. */
    List<NewsArticleTeam> findByManualFalseAndConfidenceLessThanAndArticlePublishedAtAfterOrderByArticlePublishedAtDesc(
            Double confidence, java.time.LocalDateTime after);

    /** Recent tag rows for per-alias hit counting (matched_via split happens in Java). */
    List<NewsArticleTeam> findByArticlePublishedAtAfter(java.time.LocalDateTime after);
}
