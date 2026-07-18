package com.yotto.basketball.news;

import com.yotto.basketball.config.NewsProperties;
import com.yotto.basketball.entity.NewsArticle;
import com.yotto.basketball.entity.NewsSource;
import com.yotto.basketball.entity.ScrapeBatch;
import com.yotto.basketball.repository.ConferenceRepository;
import com.yotto.basketball.repository.NewsArticleConferenceRepository;
import com.yotto.basketball.repository.NewsArticleRepository;
import com.yotto.basketball.repository.NewsArticleTeamRepository;
import com.yotto.basketball.repository.NewsSourceRepository;
import com.yotto.basketball.repository.ScrapeBatchRepository;
import com.yotto.basketball.repository.TeamRepository;
import com.yotto.basketball.entity.NewsArticleConference;
import com.yotto.basketball.entity.NewsArticleTeam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One news polling run (docs/NEWS_MODULE.md §4): per pollable source, fetch
 * the feed (conditional GET), then per new item: canonicalize, fetch page,
 * resolve dates, sport-filter, simhash-dedup, tag, persist. Per-source and
 * per-item failures never abort the run.
 */
@Service
public class NewsScrapeService {

    private static final Logger log = LoggerFactory.getLogger(NewsScrapeService.class);

    private final NewsProperties properties;
    private final NewsSourceRepository sourceRepository;
    private final NewsArticleRepository articleRepository;
    private final NewsArticleTeamRepository articleTeamRepository;
    private final NewsArticleConferenceRepository articleConferenceRepository;
    private final ScrapeBatchRepository scrapeBatchRepository;
    private final TeamRepository teamRepository;
    private final ConferenceRepository conferenceRepository;
    private final NewsHttpClient httpClient;
    private final FeedPoller feedPoller;
    private final ArticleFetcher articleFetcher;
    private final TeamTagger teamTagger;
    private final ConferenceResolver conferenceResolver;
    private final NewsDeduplicator deduplicator;
    private final NewsThumbnailService thumbnailService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public NewsScrapeService(NewsProperties properties,
                             NewsSourceRepository sourceRepository,
                             NewsArticleRepository articleRepository,
                             NewsArticleTeamRepository articleTeamRepository,
                             NewsArticleConferenceRepository articleConferenceRepository,
                             ScrapeBatchRepository scrapeBatchRepository,
                             TeamRepository teamRepository,
                             ConferenceRepository conferenceRepository,
                             NewsHttpClient httpClient,
                             FeedPoller feedPoller,
                             ArticleFetcher articleFetcher,
                             TeamTagger teamTagger,
                             ConferenceResolver conferenceResolver,
                             NewsDeduplicator deduplicator,
                             NewsThumbnailService thumbnailService) {
        this.properties = properties;
        this.sourceRepository = sourceRepository;
        this.articleRepository = articleRepository;
        this.articleTeamRepository = articleTeamRepository;
        this.articleConferenceRepository = articleConferenceRepository;
        this.scrapeBatchRepository = scrapeBatchRepository;
        this.teamRepository = teamRepository;
        this.conferenceRepository = conferenceRepository;
        this.httpClient = httpClient;
        this.feedPoller = feedPoller;
        this.articleFetcher = articleFetcher;
        this.teamTagger = teamTagger;
        this.conferenceResolver = conferenceResolver;
        this.deduplicator = deduplicator;
        this.thumbnailService = thumbnailService;
    }

    /**
     * Polls every pollable source plus any auto-disabled sources due a weekly
     * retry probe. Returns the completed batch, or null when a run was already
     * in progress (double-run guard, same posture as the game scraper).
     */
    public ScrapeBatch pollAll(ScrapeBatch.Source batchSource) {
        if (!running.compareAndSet(false, true)) {
            log.info("News poll skipped — another run is in progress");
            return null;
        }
        try {
            return doPollAll(batchSource);
        } finally {
            running.set(false);
        }
    }

    private ScrapeBatch doPollAll(ScrapeBatch.Source batchSource) {
        List<NewsSource> sources = new ArrayList<>(
                sourceRepository.findByActiveTrueAndAutoDisabledAtIsNullOrderByName());
        List<NewsSource> probes = sourceRepository.findRetryProbeCandidates(
                LocalDateTime.now().minusDays(properties.getRetryProbeDays()));
        sources.addAll(probes);

        ScrapeBatch batch = ScrapeBatch.start(
                ConferenceResolver.seasonYearFor(LocalDate.now()), ScrapeBatch.ScrapeType.NEWS,
                batchSource, null, null);
        batch.setProgressTotal(sources.size());
        batch = scrapeBatchRepository.save(batch);

        log.info("News poll starting: {} sources ({} retry probes)", sources.size(), probes.size());
        for (NewsSource source : sources) {
            batch.setCurrentStep(source.getName());
            scrapeBatchRepository.save(batch);
            try {
                pollSource(source, batch);
                source.recordSuccess();
                batch.incrementDatesSucceeded();
            } catch (Exception e) {
                log.warn("News source '{}' failed: {}", source.getName(), e.toString());
                source.recordFailure(properties.getAutoDisableAfterFailures());
                batch.incrementDatesFailed();
            }
            source.setLastPolledAt(LocalDateTime.now());
            sourceRepository.save(source);
        }
        batch.setCurrentStep(null);
        batch.complete();
        batch = scrapeBatchRepository.save(batch);
        log.info("News poll finished: {} created, {} clustered, {}/{} sources ok",
                batch.getRecordsCreated(), batch.getRecordsUpdated(),
                batch.getDatesSucceeded(), sources.size());
        return batch;
    }

    private void pollSource(NewsSource source, ScrapeBatch batch) {
        if (source.getFeedUrl() == null || source.getSourceType() != NewsSource.SourceType.RSS) {
            return; // HTML_INDEX reserved for a later version
        }
        NewsHttpClient.FetchResult result = httpClient.fetchFeed(
                source.getFeedUrl(), source.getEtag(), source.getLastModifiedHeader());
        if (result.isNotModified()) {
            log.debug("Feed 304 for {}", source.getName());
            return;
        }
        if (!result.isSuccess()) {
            throw new IllegalStateException("Feed fetch failed with status " + result.status());
        }
        source.setEtag(result.etag());
        source.setLastModifiedHeader(result.lastModified());

        List<FeedItem> items = feedPoller.parse(result.body(), source.getFeedUrl());
        int processed = 0;
        for (FeedItem item : items) {
            if (processed++ >= properties.getPollCapPerSource()) {
                break;
            }
            try {
                processItem(source, item, batch);
            } catch (Exception e) {
                log.debug("Item failed ({}): {}", item.link(), e.toString());
            }
        }
    }

    private void processItem(NewsSource feedSource, FeedItem item, ScrapeBatch batch) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime oldestAllowed = now.minusDays(properties.getMaxItemAgeDays());

        // Cheap pre-fetch skips: known URL, or feed-dated far in the past
        String preliminaryCanonical = UrlCanonicalizer.canonicalize(item.link());
        if (articleRepository.existsByUrlCanonical(preliminaryCanonical)) {
            return;
        }
        LocalDateTime feedDate = item.publishedDate() != null ? item.publishedDate() : item.updatedDate();
        if (feedDate != null && feedDate.isBefore(oldestAllowed)) {
            return;
        }

        ExtractedPage page = articleFetcher.fetchAndExtract(item.link());
        String canonical = resolveCanonicalUrl(item, page, preliminaryCanonical);
        if (!canonical.equals(preliminaryCanonical) && articleRepository.existsByUrlCanonical(canonical)) {
            return;
        }

        LocalDateTime publishedAt = resolvePublishedAt(item, page, now);
        if (publishedAt.isBefore(oldestAllowed)) {
            return;
        }

        String title = firstNonBlank(page.ogTitle(), item.title());
        String subtitle = ArticleFetcher.trimSnippet(
                firstNonBlank(page.ogDescription(), item.summary()), properties.getSubtitleMaxChars());
        String body = page.fetchSucceeded() && page.bodyText() != null ? page.bodyText() : "";

        TagResult tags = teamTagger.tag(title, body + " " + nullToEmpty(subtitle));

        // URL verdict first (§5.4): the canonical path names the sport on major
        // sites and survives failed body extraction. A clear other-sport URL is
        // discarded even from dedicated feeds (ESPN's "ncb" feed carries
        // football items); a clear CBB URL skips keyword filtering entirely.
        Boolean urlVerdict = SportFilter.urlVerdict(canonical);
        if (Boolean.FALSE.equals(urlVerdict)) {
            return;
        }
        if (!Boolean.TRUE.equals(feedSource.getDedicatedCbb()) && !Boolean.TRUE.equals(urlVerdict)) {
            String fullText = title + " " + nullToEmpty(subtitle) + " " + body;
            if (!SportFilter.keep(fullText, tags.hasAnyMatch())) {
                return;
            }
        }

        NewsSource publisher = attributePublisher(canonical).orElse(null);

        NewsArticle article = new NewsArticle();
        article.setUrlCanonical(canonical);
        article.setUrlOriginal(item.link());
        article.setSource(publisher);
        article.setDiscoveredVia(feedSource);
        article.setTitle(title);
        article.setSubtitle(subtitle);
        article.setImageUrl(cleanImageUrl(page.ogImage()));
        article.setPublishedAt(publishedAt);
        article.setFetchedAt(now);
        article.setStaticScore((double) (publisher != null
                ? publisher.getAuthorityWeight() : properties.getDefaultAuthorityWeight()));

        int bodyTokens = SimHasher.tokenize(body).length;
        article.setBodyTokenCount(bodyTokens);
        if (bodyTokens >= properties.getDedup().getMinBodyTokens()) {
            article.setSimhash(SimHasher.hash(body));
        }

        article = articleRepository.save(article);
        persistTags(article, tags, publishedAt.toLocalDate());
        deduplicator.cluster(article);
        thumbnailService.storeThumbnail(article);
        batch.incrementCreated();
        if (article.getDuplicateOf() != null) {
            batch.incrementUpdated(); // recordsUpdated doubles as "clustered as duplicate"
        }
    }

    private void persistTags(NewsArticle article, TagResult tags, LocalDate articleDate) {
        double nearMiss = properties.getTagging().getNearMissThreshold();
        double tagThreshold = properties.getTagging().getTagThreshold();
        boolean anyRealTag = false;

        Set<Long> validTeamIds = new HashSet<>();
        for (Map.Entry<Long, TagResult.TargetScore> e : tags.teams().entrySet()) {
            if (e.getValue().score() < nearMiss) {
                continue;
            }
            if (!teamRepository.existsById(e.getKey())) {
                continue; // alias table can outlive a deleted team between reseeds
            }
            validTeamIds.add(e.getKey());
            articleTeamRepository.save(new NewsArticleTeam(article,
                    teamRepository.getReferenceById(e.getKey()),
                    e.getValue().confidence(), e.getValue().matchedVia(), false));
            anyRealTag |= e.getValue().score() >= tagThreshold;
        }

        Map<Long, TagResult.TargetScore> conferences = conferenceResolver.resolve(
                tags.teams().entrySet().stream()
                        .filter(e -> validTeamIds.contains(e.getKey()))
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)),
                tags.conferences(), articleDate, properties.getTagging().getTagThreshold());
        for (Map.Entry<Long, TagResult.TargetScore> e : conferences.entrySet()) {
            if (e.getValue().score() < nearMiss || !conferenceRepository.existsById(e.getKey())) {
                continue;
            }
            articleConferenceRepository.save(new NewsArticleConference(article,
                    conferenceRepository.getReferenceById(e.getKey()),
                    e.getValue().confidence(), e.getValue().matchedVia(), false));
            anyRealTag |= e.getValue().score() >= tagThreshold;
        }

        article.setTagStatus(anyRealTag ? NewsArticle.TagStatus.TAGGED : NewsArticle.TagStatus.UNTAGGED);
        articleRepository.save(article);
    }

    private String resolveCanonicalUrl(FeedItem item, ExtractedPage page, String preliminary) {
        // The page's own rel=canonical wins (§5.2 — syndicated pages often point
        // at the wire original); then the post-redirect URL; then the feed link.
        if (page.fetchSucceeded() && page.canonicalUrl() != null) {
            try {
                return UrlCanonicalizer.canonicalize(page.canonicalUrl());
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        if (page.finalUrl() != null && !page.finalUrl().equals(item.link())) {
            try {
                return UrlCanonicalizer.canonicalize(page.finalUrl());
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        return preliminary;
    }

    private LocalDateTime resolvePublishedAt(FeedItem item, ExtractedPage page, LocalDateTime now) {
        LocalDateTime resolved = item.publishedDate() != null ? item.publishedDate()
                : page.publishedTime() != null ? page.publishedTime()
                : item.updatedDate() != null ? item.updatedDate()
                : now;
        // Future dates are CMS clock bugs: clamp anything more than an hour ahead
        if (resolved.isAfter(now.plusHours(1))) {
            return now;
        }
        return resolved;
    }

    /** Attribution by final domain, walking up subdomains (sports.espn.com → espn.com). */
    private Optional<NewsSource> attributePublisher(String canonicalUrl) {
        String host;
        try {
            host = URI.create(canonicalUrl).getHost();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (host == null) {
            return Optional.empty();
        }
        String current = host.toLowerCase(Locale.ROOT);
        while (true) {
            Optional<NewsSource> match = sourceRepository.findFirstByDomain(current);
            if (match.isPresent()) {
                return match;
            }
            int dot = current.indexOf('.');
            if (dot < 0 || current.indexOf('.', dot + 1) < 0) {
                return Optional.empty(); // never match on a bare TLD
            }
            current = current.substring(dot + 1);
        }
    }

    /** §5.11: skip images that are obviously site logos or placeholders. */
    static String cleanImageUrl(String imageUrl) {
        if (imageUrl == null) {
            return null;
        }
        String lower = imageUrl.toLowerCase(Locale.ROOT);
        if (lower.contains("logo") || lower.contains("default") || lower.contains("favicon")
                || lower.contains("placeholder")) {
            return null;
        }
        return imageUrl;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // ---------- admin dry-run (docs/NEWS_MODULE.md §7.1) ----------

    /**
     * @param kept    whether the pipeline would ingest this item
     * @param verdict human-readable reason / outcome summary
     */
    public record DryRunItem(String title, String url, LocalDateTime publishedAt,
                             boolean kept, String verdict, List<String> tags) {
    }

    /**
     * Fetches and evaluates the first {@code limit} items of a feed without
     * persisting anything — powers the admin "Test" button.
     */
    public List<DryRunItem> dryRun(String feedUrl, boolean dedicatedCbb, int limit) {
        NewsHttpClient.FetchResult result = httpClient.fetchFeed(feedUrl, null, null);
        if (!result.isSuccess()) {
            return List.of(new DryRunItem(null, feedUrl, null, false,
                    "Feed fetch failed (HTTP " + result.status() + ")", List.of()));
        }
        List<FeedItem> items;
        try {
            items = feedPoller.parse(result.body(), feedUrl);
        } catch (FeedPoller.FeedParseException e) {
            return List.of(new DryRunItem(null, feedUrl, null, false,
                    "Not a parseable RSS/Atom feed: " + e.getMessage(), List.of()));
        }
        if (items.isEmpty()) {
            return List.of(new DryRunItem(null, feedUrl, null, false, "Feed parsed but has no entries", List.of()));
        }

        LocalDateTime now = LocalDateTime.now();
        List<DryRunItem> out = new ArrayList<>();
        for (FeedItem item : items.subList(0, Math.min(limit, items.size()))) {
            try {
                out.add(dryRunItem(item, dedicatedCbb, now));
            } catch (Exception e) {
                out.add(new DryRunItem(item.title(), item.link(), item.publishedDate(), false,
                        "Error: " + e.getMessage(), List.of()));
            }
        }
        return out;
    }

    private DryRunItem dryRunItem(FeedItem item, boolean dedicatedCbb, LocalDateTime now) {
        String canonical = UrlCanonicalizer.canonicalize(item.link());
        boolean exists = articleRepository.existsByUrlCanonical(canonical);

        ExtractedPage page = articleFetcher.fetchAndExtract(item.link());
        canonical = resolveCanonicalUrl(item, page, canonical);
        LocalDateTime publishedAt = resolvePublishedAt(item, page, now);
        String title = firstNonBlank(page.ogTitle(), item.title());
        String body = page.fetchSucceeded() && page.bodyText() != null ? page.bodyText() : "";

        TagResult tags = teamTagger.tag(title, body);
        List<String> tagSummaries = new ArrayList<>();
        double tagThreshold = properties.getTagging().getTagThreshold();
        tags.teams().forEach((id, ts) -> {
            if (ts.score() >= tagThreshold) {
                teamRepository.findById(id).ifPresent(t ->
                        tagSummaries.add(t.getName() + " (" + ts.matchedVia() + ")"));
            }
        });
        tags.conferences().forEach((id, ts) -> {
            if (ts.score() >= tagThreshold) {
                conferenceRepository.findById(id).ifPresent(c -> tagSummaries.add(c.getName()));
            }
        });

        if (exists) {
            return new DryRunItem(title, canonical, publishedAt, false, "Already ingested", tagSummaries);
        }
        if (publishedAt.isBefore(now.minusDays(properties.getMaxItemAgeDays()))) {
            return new DryRunItem(title, canonical, publishedAt, false,
                    "Older than " + properties.getMaxItemAgeDays() + " days", tagSummaries);
        }
        Boolean urlVerdict = SportFilter.urlVerdict(canonical);
        if (Boolean.FALSE.equals(urlVerdict)) {
            return new DryRunItem(title, canonical, publishedAt, false,
                    "Discarded — URL indicates another sport", tagSummaries);
        }
        if (!page.fetchSucceeded()) {
            return new DryRunItem(title, canonical, publishedAt, true,
                    "Page fetch failed — would ingest with feed metadata only", tagSummaries);
        }
        if (!dedicatedCbb && !Boolean.TRUE.equals(urlVerdict)) {
            String fullText = title + " " + nullToEmpty(item.summary()) + " " + body;
            if (!SportFilter.keep(fullText, tags.hasAnyMatch())) {
                return new DryRunItem(title, canonical, publishedAt, false,
                        "Discarded by sport filter (not men's college basketball)", tagSummaries);
            }
        }
        return new DryRunItem(title, canonical, publishedAt, true,
                tagSummaries.isEmpty() ? "Would ingest (untagged)" : "Would ingest", tagSummaries);
    }
}
