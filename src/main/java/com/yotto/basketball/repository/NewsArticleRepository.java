package com.yotto.basketball.repository;

import com.yotto.basketball.entity.NewsArticle;
import com.yotto.basketball.news.SimhashCandidate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface NewsArticleRepository extends JpaRepository<NewsArticle, Long> {

    boolean existsByUrlCanonical(String urlCanonical);

    Optional<NewsArticle> findByUrlCanonical(String urlCanonical);

    /** Recent hashable articles for the dedup window scan (left join keeps un-clustered rows). */
    @Query("""
            select new com.yotto.basketball.news.SimhashCandidate(
                a.id, a.simhash, a.staticScore, a.publishedAt, d.id)
            from NewsArticle a left join a.duplicateOf d
            where a.simhash is not null and a.publishedAt >= :since
            """)
    List<SimhashCandidate> findSimhashCandidatesSince(@Param("since") LocalDateTime since);

    List<NewsArticle> findByDuplicateOfId(Long representativeId);

    /** Repoint every member of a cluster (and the old representative) at a new representative. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update NewsArticle a set a.duplicateOf.id = :newRepId
            where a.duplicateOf.id = :oldRepId or (a.id = :oldRepId and a.id <> :newRepId)
            """)
    int repointCluster(@Param("oldRepId") Long oldRepId, @Param("newRepId") Long newRepId);

    List<NewsArticle> findByTagStatusAndPublishedAtAfterOrderByPublishedAtDesc(
            NewsArticle.TagStatus tagStatus, LocalDateTime after);

    List<NewsArticle> findByPublishedAtAfterOrderByPublishedAtDesc(LocalDateTime after);

    Page<NewsArticle> findByTitleContainingIgnoreCaseOrderByPublishedAtDesc(String q, Pageable pageable);

    Page<NewsArticle> findAllByOrderByPublishedAtDesc(Pageable pageable);

    long countBySourceId(Long sourceId);

    long countBySourceIdAndPublishedAtAfter(Long sourceId, LocalDateTime after);

    long countByFetchedAtAfter(LocalDateTime after);

    long countByTagStatusAndFetchedAtAfter(NewsArticle.TagStatus tagStatus, LocalDateTime after);
}
