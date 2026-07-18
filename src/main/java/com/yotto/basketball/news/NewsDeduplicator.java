package com.yotto.basketball.news;

import com.yotto.basketball.config.NewsProperties;
import com.yotto.basketball.entity.NewsArticle;
import com.yotto.basketball.repository.NewsArticleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * SimHash near-duplicate clustering (docs/NEWS_MODULE.md §5.5). URL-level
 * dedup happens earlier (unique url_canonical + existence check); this layer
 * catches wire stories republished at different URLs.
 */
@Component
public class NewsDeduplicator {

    private static final Logger log = LoggerFactory.getLogger(NewsDeduplicator.class);

    private final NewsArticleRepository articleRepository;
    private final NewsProperties properties;

    public NewsDeduplicator(NewsArticleRepository articleRepository, NewsProperties properties) {
        this.articleRepository = articleRepository;
        this.properties = properties;
    }

    /**
     * Clusters a just-saved article against the recent window. Either the
     * article joins an existing cluster as a suppressed duplicate, or — when it
     * outranks the current representative — the whole cluster is repointed at
     * it (§5.5: highest authority wins, earliest publish breaks ties).
     */
    @Transactional
    public void cluster(NewsArticle article) {
        if (article.getSimhash() == null) {
            return;
        }
        LocalDateTime since = LocalDateTime.now().minusDays(properties.getDedup().getWindowDays());
        int threshold = properties.getDedup().getHammingThreshold();

        List<SimhashCandidate> window = articleRepository.findSimhashCandidatesSince(since);
        Optional<SimhashCandidate> match = window.stream()
                .filter(c -> !c.id().equals(article.getId()))
                .filter(c -> SimHasher.hammingDistance(c.simhash(), article.getSimhash()) <= threshold)
                .min(Comparator.comparing(SimhashCandidate::publishedAt));
        if (match.isEmpty()) {
            return;
        }

        Long representativeId = match.get().duplicateOfId() != null
                ? match.get().duplicateOfId()
                : match.get().id();
        NewsArticle representative = articleRepository.findById(representativeId).orElse(null);
        if (representative == null || representative.getId().equals(article.getId())) {
            return;
        }

        if (outranks(article, representative)) {
            int repointed = articleRepository.repointCluster(representative.getId(), article.getId());
            log.debug("Article {} takes over cluster from {} ({} rows repointed)",
                    article.getId(), representative.getId(), repointed);
        } else {
            article.setDuplicateOf(representative);
            articleRepository.save(article);
            log.debug("Article {} clustered under representative {}", article.getId(), representative.getId());
        }
    }

    private static boolean outranks(NewsArticle candidate, NewsArticle representative) {
        int byScore = Double.compare(candidate.getStaticScore(), representative.getStaticScore());
        if (byScore != 0) {
            return byScore > 0;
        }
        return candidate.getPublishedAt().isBefore(representative.getPublishedAt());
    }
}
