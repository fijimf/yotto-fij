package com.yotto.basketball.repository;

import com.yotto.basketball.entity.NewsArticleConference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NewsArticleConferenceRepository extends JpaRepository<NewsArticleConference, Long> {

    List<NewsArticleConference> findByArticleId(Long articleId);

    Optional<NewsArticleConference> findByArticleIdAndConferenceId(Long articleId, Long conferenceId);

    boolean existsByArticleIdAndConferenceId(Long articleId, Long conferenceId);

    void deleteByArticleIdAndManualFalse(Long articleId);

    long deleteByArticleIdAndConferenceId(Long articleId, Long conferenceId);
}
