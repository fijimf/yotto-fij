package com.yotto.basketball.news;

import com.yotto.basketball.config.NewsProperties;
import com.yotto.basketball.entity.Conference;
import com.yotto.basketball.entity.NewsAlias;
import com.yotto.basketball.entity.NewsArticle;
import com.yotto.basketball.entity.NewsArticleConference;
import com.yotto.basketball.entity.NewsArticleTeam;
import com.yotto.basketball.entity.NewsSource;
import com.yotto.basketball.entity.ScrapeBatch;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.ConferenceRepository;
import com.yotto.basketball.repository.NewsAliasRepository;
import com.yotto.basketball.repository.NewsArticleConferenceRepository;
import com.yotto.basketball.repository.NewsArticleRepository;
import com.yotto.basketball.repository.NewsArticleTeamRepository;
import com.yotto.basketball.repository.NewsSourceRepository;
import com.yotto.basketball.repository.ScrapeBatchRepository;
import com.yotto.basketball.repository.TeamRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Backend for the /admin/news pages (docs/NEWS_MODULE.md §7): source
 * management, the tagging-quality improvement loop, and per-article actions.
 */
@Service
public class NewsAdminService {

    private static final Logger log = LoggerFactory.getLogger(NewsAdminService.class);

    private final NewsProperties properties;
    private final NewsSourceRepository sourceRepository;
    private final NewsArticleRepository articleRepository;
    private final NewsArticleTeamRepository articleTeamRepository;
    private final NewsArticleConferenceRepository articleConferenceRepository;
    private final NewsAliasRepository aliasRepository;
    private final TeamRepository teamRepository;
    private final ConferenceRepository conferenceRepository;
    private final ScrapeBatchRepository scrapeBatchRepository;
    private final TeamTagger teamTagger;
    private final ConferenceResolver conferenceResolver;
    private final ArticleFetcher articleFetcher;
    private final NewsAliasSeeder aliasSeeder;

    public NewsAdminService(NewsProperties properties,
                            NewsSourceRepository sourceRepository,
                            NewsArticleRepository articleRepository,
                            NewsArticleTeamRepository articleTeamRepository,
                            NewsArticleConferenceRepository articleConferenceRepository,
                            NewsAliasRepository aliasRepository,
                            TeamRepository teamRepository,
                            ConferenceRepository conferenceRepository,
                            ScrapeBatchRepository scrapeBatchRepository,
                            TeamTagger teamTagger,
                            ConferenceResolver conferenceResolver,
                            ArticleFetcher articleFetcher,
                            NewsAliasSeeder aliasSeeder) {
        this.properties = properties;
        this.sourceRepository = sourceRepository;
        this.articleRepository = articleRepository;
        this.articleTeamRepository = articleTeamRepository;
        this.articleConferenceRepository = articleConferenceRepository;
        this.aliasRepository = aliasRepository;
        this.teamRepository = teamRepository;
        this.conferenceRepository = conferenceRepository;
        this.scrapeBatchRepository = scrapeBatchRepository;
        this.teamTagger = teamTagger;
        this.conferenceResolver = conferenceResolver;
        this.articleFetcher = articleFetcher;
        this.aliasSeeder = aliasSeeder;
    }

    // ---------- sources ----------

    public record SourceRow(NewsSource source, long articlesLast7Days) {
    }

    public List<SourceRow> sourceRows() {
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
        return sourceRepository.findAllByOrderByName().stream()
                .map(s -> new SourceRow(s, articleRepository.countBySourceIdAndPublishedAtAfter(s.getId(), weekAgo)))
                .toList();
    }

    public NewsSource createSource(String name, String domain, String feedUrl,
                                   NewsSource.SourceType sourceType,
                                   int authorityWeight, boolean dedicatedCbb, String notes) {
        if (feedUrl != null && !feedUrl.isBlank()
                && sourceRepository.findByFeedUrl(feedUrl.strip()).isPresent()) {
            throw new IllegalArgumentException("A source with that feed URL already exists");
        }
        NewsSource source = new NewsSource();
        source.setName(name.strip());
        source.setDomain(domain.strip().toLowerCase());
        source.setFeedUrl(feedUrl == null || feedUrl.isBlank() ? null : feedUrl.strip());
        source.setSourceType(sourceType);
        source.setAuthorityWeight(clampWeight(authorityWeight));
        source.setDedicatedCbb(dedicatedCbb);
        source.setNotes(blankToNull(notes));
        return sourceRepository.save(source);
    }

    public void updateSource(Long id, String name, String domain, String feedUrl,
                             NewsSource.SourceType sourceType,
                             int authorityWeight, boolean dedicatedCbb, boolean active, String notes) {
        NewsSource source = sourceRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("News source not found: " + id));
        source.setName(name.strip());
        source.setDomain(domain.strip().toLowerCase());
        source.setFeedUrl(feedUrl == null || feedUrl.isBlank() ? null : feedUrl.strip());
        source.setSourceType(sourceType);
        source.setAuthorityWeight(clampWeight(authorityWeight));
        source.setDedicatedCbb(dedicatedCbb);
        source.setActive(active);
        source.setNotes(blankToNull(notes));
        sourceRepository.save(source);
    }

    /** Clears an auto-disable so the next poll retries immediately. */
    public void reenableSource(Long id) {
        NewsSource source = sourceRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("News source not found: " + id));
        source.setAutoDisabledAt(null);
        source.setConsecutiveFailures(0);
        source.setActive(true);
        sourceRepository.save(source);
    }

    /** Delete only when no articles reference the source; deactivate otherwise (§7.1). */
    public void deleteSource(Long id) {
        NewsSource source = sourceRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("News source not found: " + id));
        if (articleRepository.countBySourceId(id) > 0) {
            throw new IllegalArgumentException(
                    "Source has articles — deactivate it instead of deleting");
        }
        sourceRepository.delete(source);
    }

    // ---------- tagging quality ----------

    public List<NewsArticle> untaggedRecent() {
        return articleRepository.findByTagStatusAndPublishedAtAfterOrderByPublishedAtDesc(
                NewsArticle.TagStatus.UNTAGGED, LocalDateTime.now().minusDays(7));
    }

    public List<NewsArticleTeam> nearMissTeamTags() {
        return articleTeamRepository
                .findByManualFalseAndConfidenceLessThanAndArticlePublishedAtAfterOrderByArticlePublishedAtDesc(
                        properties.getRanking().getTeamPageMinConfidence(), LocalDateTime.now().minusDays(7));
    }

    public record AliasRow(NewsAlias alias, long hits30Days) {
    }

    /** Alias browser rows with per-alias hit counts over the last 30 days (§7.2 panel 3). */
    public List<AliasRow> aliasRows(String filter) {
        List<NewsAlias> aliases = (filter == null || filter.isBlank())
                ? aliasRepository.findAll()
                : aliasRepository.findByAliasContainingIgnoreCaseOrderByAlias(filter.strip());

        Map<String, Long> hitsByAliasText = new HashMap<>();
        for (NewsArticleTeam tag : articleTeamRepository.findByArticlePublishedAtAfter(
                LocalDateTime.now().minusDays(30))) {
            if (tag.getMatchedVia() == null) {
                continue;
            }
            for (String via : tag.getMatchedVia().split(",\\s*")) {
                hitsByAliasText.merge(via.toLowerCase(), 1L, Long::sum);
            }
        }
        return aliases.stream()
                .map(a -> new AliasRow(a, hitsByAliasText.getOrDefault(a.getAlias().toLowerCase(), 0L)))
                .sorted((x, y) -> Long.compare(y.hits30Days(), x.hits30Days()))
                .toList();
    }

    @Transactional
    public void manualTagTeam(Long articleId, Long teamId, String newAlias, boolean aliasAmbiguous) {
        NewsArticle article = requireArticle(articleId);
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new EntityNotFoundException("Team not found: " + teamId));
        if (articleTeamRepository.findByArticleIdAndTeamId(articleId, teamId).isEmpty()) {
            articleTeamRepository.save(new NewsArticleTeam(article, team, 1.0, "manual", true));
        }
        article.setTagStatus(NewsArticle.TagStatus.MANUAL);
        articleRepository.save(article);
        if (newAlias != null && !newAlias.isBlank()) {
            createAliasForTeam(newAlias.strip(), teamId, aliasAmbiguous);
        }
    }

    @Transactional
    public void manualTagConference(Long articleId, Long conferenceId) {
        NewsArticle article = requireArticle(articleId);
        Conference conference = conferenceRepository.findById(conferenceId)
                .orElseThrow(() -> new EntityNotFoundException("Conference not found: " + conferenceId));
        if (articleConferenceRepository.findByArticleIdAndConferenceId(articleId, conferenceId).isEmpty()) {
            articleConferenceRepository.save(
                    new NewsArticleConference(article, conference, 1.0, "manual", true));
        }
        article.setTagStatus(NewsArticle.TagStatus.MANUAL);
        articleRepository.save(article);
    }

    /** Confirms a near-miss: promotes the existing low-confidence row to a manual tag. */
    @Transactional
    public void confirmNearMiss(Long tagRowId) {
        NewsArticleTeam tag = articleTeamRepository.findById(tagRowId)
                .orElseThrow(() -> new EntityNotFoundException("Tag row not found: " + tagRowId));
        tag.setManual(true);
        tag.setConfidence(1.0);
        articleTeamRepository.save(tag);
        NewsArticle article = tag.getArticle();
        article.setTagStatus(NewsArticle.TagStatus.MANUAL);
        articleRepository.save(article);
    }

    @Transactional
    public void dismissNearMiss(Long tagRowId) {
        articleTeamRepository.deleteById(tagRowId);
    }

    @Transactional
    public void removeTeamTag(Long articleId, Long teamId) {
        articleTeamRepository.deleteByArticleIdAndTeamId(articleId, teamId);
    }

    @Transactional
    public void removeConferenceTag(Long articleId, Long conferenceId) {
        articleConferenceRepository.deleteByArticleIdAndConferenceId(articleId, conferenceId);
    }

    // ---------- aliases ----------

    public void createAliasForTeam(String aliasText, Long teamId, boolean ambiguous) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new EntityNotFoundException("Team not found: " + teamId));
        if (aliasRepository.findByAliasAndTeamId(aliasText, teamId).isPresent()) {
            return;
        }
        NewsAlias alias = NewsAlias.forTeam(aliasText, team, NewsAlias.Kind.MANUAL, ambiguous,
                aliasText.length() <= 4);
        alias.setCreatedBy("admin");
        aliasRepository.save(alias);
        teamTagger.invalidate();
    }

    public void updateAlias(Long id, boolean enabled, boolean ambiguous, NewsAlias.Kind kind) {
        NewsAlias alias = aliasRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Alias not found: " + id));
        alias.setEnabled(enabled);
        alias.setAmbiguous(ambiguous);
        // editing an AUTO row pins it as MANUAL so reseeds keep the change (§5.6)
        alias.setKind(alias.getKind() == NewsAlias.Kind.AUTO && kind == NewsAlias.Kind.AUTO
                ? NewsAlias.Kind.MANUAL : kind);
        aliasRepository.save(alias);
        teamTagger.invalidate();
    }

    public void deleteAlias(Long id) {
        aliasRepository.deleteById(id);
        teamTagger.invalidate();
    }

    public int reseedAliases() {
        return aliasSeeder.reseed();
    }

    // ---------- retag (add-only, title+subtitle only — §7.2) ----------

    public int retag(int days) {
        ScrapeBatch batch = ScrapeBatch.start(
                ConferenceResolver.seasonYearFor(LocalDate.now()), ScrapeBatch.ScrapeType.NEWS,
                ScrapeBatch.Source.MANUAL, null, null);
        batch.setCurrentStep("retag last " + days + " days");
        batch = scrapeBatchRepository.save(batch);
        int tagsAdded = 0;
        try {
            List<NewsArticle> articles = articleRepository
                    .findByPublishedAtAfterOrderByPublishedAtDesc(LocalDateTime.now().minusDays(days));
            batch.setProgressTotal(articles.size());
            for (NewsArticle article : articles) {
                if (article.getTagStatus() == NewsArticle.TagStatus.MANUAL) {
                    continue;
                }
                tagsAdded += retagOne(article, article.getSubtitle() == null ? "" : article.getSubtitle());
            }
            batch.setRecordsUpdated(tagsAdded);
            batch.complete();
        } catch (Exception e) {
            log.error("Retag failed", e);
            batch.fail(e.getMessage());
        }
        scrapeBatchRepository.save(batch);
        return tagsAdded;
    }

    /** Full-strength single-article refetch & retag (§7.3): re-pulls the body. */
    @Transactional
    public void refetchAndRetag(Long articleId) {
        NewsArticle article = requireArticle(articleId);
        ExtractedPage page = articleFetcher.fetchAndExtract(article.getUrlOriginal());
        String body = page.fetchSucceeded() && page.bodyText() != null ? page.bodyText() : "";
        int tokens = SimHasher.tokenize(body).length;
        if (tokens >= properties.getDedup().getMinBodyTokens()) {
            article.setSimhash(SimHasher.hash(body));
            article.setBodyTokenCount(tokens);
        }
        retagOne(article, body);
    }

    /** Add-only merge of fresh tag results into an article. Returns tags added. */
    private int retagOne(NewsArticle article, String bodyOrSubtitle) {
        TagResult tags = teamTagger.tag(article.getTitle(), bodyOrSubtitle);
        double nearMiss = properties.getTagging().getNearMissThreshold();
        double tagThreshold = properties.getTagging().getTagThreshold();
        int added = 0;
        boolean anyRealTag = false;

        for (Map.Entry<Long, TagResult.TargetScore> e : tags.teams().entrySet()) {
            if (e.getValue().score() < nearMiss || !teamRepository.existsById(e.getKey())) {
                continue;
            }
            anyRealTag |= e.getValue().score() >= tagThreshold;
            if (articleTeamRepository.findByArticleIdAndTeamId(article.getId(), e.getKey()).isEmpty()) {
                articleTeamRepository.save(new NewsArticleTeam(article,
                        teamRepository.getReferenceById(e.getKey()),
                        e.getValue().confidence(), e.getValue().matchedVia(), false));
                added++;
            }
        }
        Map<Long, TagResult.TargetScore> conferences = conferenceResolver.resolve(
                tags.teams(), tags.conferences(), article.getPublishedAt().toLocalDate(), tagThreshold);
        for (Map.Entry<Long, TagResult.TargetScore> e : conferences.entrySet()) {
            if (e.getValue().score() < nearMiss || !conferenceRepository.existsById(e.getKey())) {
                continue;
            }
            anyRealTag |= e.getValue().score() >= tagThreshold;
            if (articleConferenceRepository.findByArticleIdAndConferenceId(article.getId(), e.getKey()).isEmpty()) {
                articleConferenceRepository.save(new NewsArticleConference(article,
                        conferenceRepository.getReferenceById(e.getKey()),
                        e.getValue().confidence(), e.getValue().matchedVia(), false));
                added++;
            }
        }
        if (anyRealTag && article.getTagStatus() == NewsArticle.TagStatus.UNTAGGED) {
            article.setTagStatus(NewsArticle.TagStatus.TAGGED);
            articleRepository.save(article);
        }
        return added;
    }

    // ---------- articles ----------

    @Transactional
    public void hideArticle(Long articleId, boolean hidden) {
        NewsArticle article = requireArticle(articleId);
        article.setHidden(hidden);
        articleRepository.save(article);
    }

    /** Clears a duplicate link (§7.3 — may re-form on a later poll; accepted for v1). */
    @Transactional
    public void breakCluster(Long articleId) {
        NewsArticle article = requireArticle(articleId);
        article.setDuplicateOf(null);
        articleRepository.save(article);
    }

    public List<NewsArticle> clusterMembers(Long representativeId) {
        return articleRepository.findByDuplicateOfId(representativeId);
    }

    private NewsArticle requireArticle(Long articleId) {
        return articleRepository.findById(articleId)
                .orElseThrow(() -> new EntityNotFoundException("Article not found: " + articleId));
    }

    // ---------- dashboard counts ----------

    public record NewsDashboard(long articlesToday, long untaggedToday, long sourcesFailing,
                                Optional<ScrapeBatch> lastNewsBatch) {
    }

    public NewsDashboard dashboard() {
        LocalDateTime midnight = LocalDate.now().atStartOfDay();
        Optional<ScrapeBatch> lastNews = scrapeBatchRepository.findTop20ByOrderByStartedAtDesc().stream()
                .filter(b -> b.getScrapeType() == ScrapeBatch.ScrapeType.NEWS)
                .findFirst();
        return new NewsDashboard(
                articleRepository.countByFetchedAtAfter(midnight),
                articleRepository.countByTagStatusAndFetchedAtAfter(NewsArticle.TagStatus.UNTAGGED, midnight),
                sourceRepository.countByActiveTrueAndAutoDisabledAtIsNotNull(),
                lastNews);
    }

    private static int clampWeight(int weight) {
        return Math.max(0, Math.min(100, weight));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
