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
     *
     * <p>Two layers: body simhash catches verbatim wire republishes; the
     * title-similarity fallback catches same-story rewrites from different
     * outlets, whose bodies hash far apart (and metadata-only articles, which
     * have no simhash at all).
     */
    @Transactional
    public void cluster(NewsArticle article) {
        Long representativeId = simhashMatch(article).orElseGet(() -> titleMatch(article).orElse(null));
        if (representativeId == null) {
            return;
        }
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

    /** Representative id of the closest simhash match within the window, if any. */
    private Optional<Long> simhashMatch(NewsArticle article) {
        if (article.getSimhash() == null) {
            return Optional.empty();
        }
        LocalDateTime since = LocalDateTime.now().minusDays(properties.getDedup().getWindowDays());
        int threshold = properties.getDedup().getHammingThreshold();

        return articleRepository.findSimhashCandidatesSince(since).stream()
                .filter(c -> !c.id().equals(article.getId()))
                .filter(c -> SimHasher.hammingDistance(c.simhash(), article.getSimhash()) <= threshold)
                .min(Comparator.comparing(SimhashCandidate::publishedAt))
                .map(c -> c.duplicateOfId() != null ? c.duplicateOfId() : c.id());
    }

    /** Representative id of the closest same-headline match within the (short) title window. */
    private Optional<Long> titleMatch(NewsArticle article) {
        double threshold = properties.getDedup().getTitleJaccardThreshold();
        int minShared = properties.getDedup().getTitleMinSharedTokens();
        java.util.Set<String> tokens = TitleSimilarity.tokens(article.getTitle());
        if (threshold > 1.0 || tokens.size() < minShared) {
            return Optional.empty();
        }
        LocalDateTime since = LocalDateTime.now().minusHours(properties.getDedup().getTitleWindowHours());

        return articleRepository.findTitleCandidatesSince(since).stream()
                .filter(c -> !c.id().equals(article.getId()))
                .filter(c -> {
                    java.util.Set<String> other = TitleSimilarity.tokens(c.title());
                    return TitleSimilarity.sharedCount(tokens, other) >= minShared
                            && TitleSimilarity.jaccard(tokens, other) >= threshold;
                })
                .min(Comparator.comparing(TitleCandidate::publishedAt))
                .map(c -> c.duplicateOfId() != null ? c.duplicateOfId() : c.id());
    }

    private static boolean outranks(NewsArticle candidate, NewsArticle representative) {
        int byScore = Double.compare(candidate.getStaticScore(), representative.getStaticScore());
        if (byScore != 0) {
            return byScore > 0;
        }
        return candidate.getPublishedAt().isBefore(representative.getPublishedAt());
    }
}
