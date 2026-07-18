package com.yotto.basketball.news;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Conference;
import com.yotto.basketball.entity.ConferenceMembership;
import com.yotto.basketball.entity.NewsArticle;
import com.yotto.basketball.entity.NewsSource;
import com.yotto.basketball.entity.ScrapeBatch;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.ConferenceMembershipRepository;
import com.yotto.basketball.repository.ConferenceRepository;
import com.yotto.basketball.repository.NewsArticleConferenceRepository;
import com.yotto.basketball.repository.NewsArticleRepository;
import com.yotto.basketball.repository.NewsArticleTeamRepository;
import com.yotto.basketball.repository.NewsSourceRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * End-to-end pipeline tests over a mocked HTTP layer: poll → canonicalize →
 * filter → tag → dedup → persist, plus idempotency, wire clustering with
 * representative repointing, and source health / retry probes.
 */
class NewsScrapePipelineIntegrationTest extends BaseIntegrationTest {

    private static final String ESPN_FEED = "https://www.espn.com/espn/rss/ncb/news";
    private static final String BLOG_FEED = "https://hoopsblog.example.com/feed";

    // ~90 tokens so it clears the simhash min-body-tokens guard (80)
    private static final String KANSAS_BODY = """
            Hunter Dickinson scored 21 points and grabbed 12 rebounds as fourth-ranked Kansas
            beat Iowa State 78-65 on Saturday night at Allen Fieldhouse in a Big 12 college
            basketball showdown. Dajuan Harris added 14 points and eight assists for the
            Jayhawks, who extended their home winning streak to sixteen games with the win.
            Kansas shot 52 percent from the field and held the Cyclones to 38 percent while
            dominating the paint after halftime. Iowa State was led by Curtis Jones with 18
            points and six rebounds. The Jayhawks host Baylor on Tuesday night before facing
            Houston in a first-place showdown at the weekend, while the Cyclones return home
            to face Kansas State in another basketball matchup between the two old rivals.
            """;

    @MockBean
    private NewsHttpClient httpClient;

    @Autowired
    private NewsScrapeService scrapeService;
    @Autowired
    private NewsAdminService adminService;
    @Autowired
    private NewsAliasSeeder aliasSeeder;
    @Autowired
    private NewsSourceRepository sourceRepository;
    @Autowired
    private NewsArticleRepository articleRepository;
    @Autowired
    private NewsArticleTeamRepository articleTeamRepository;
    @Autowired
    private NewsArticleConferenceRepository articleConferenceRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private ConferenceRepository conferenceRepository;
    @Autowired
    private SeasonRepository seasonRepository;
    @Autowired
    private ConferenceMembershipRepository membershipRepository;

    private Team kansas;
    private Conference big12;
    private NewsSource espn;
    private NewsSource blog;
    private final Map<String, String> pagesByUrl = new HashMap<>();

    @BeforeEach
    void seedWorld() {
        pagesByUrl.clear();

        big12 = conference("Big 12 Conference", "B12");
        Conference acc = conference("Atlantic Coast Conference", "ACC");
        kansas = team("Kansas", "Jayhawks", "KU");
        Team iowaState = team("Iowa State", "Cyclones", "ISU");
        Team duke = team("Duke", "Blue Devils", "DUKE");

        Season season = new Season();
        season.setYear(ConferenceResolver.seasonYearFor(LocalDate.now()));
        seasonRepository.save(season);
        membership(kansas, big12, season);
        membership(iowaState, big12, season);
        membership(duke, acc, season);

        aliasSeeder.reseed();

        espn = source("ESPN CBB", "espn.com", ESPN_FEED, 100, true);
        blog = source("Hoops Blog", "hoopsblog.example.com", BLOG_FEED, 40, false);

        // default HTTP behavior: any page fetch resolves from the fixture map, else fails
        when(httpClient.fetchPage(anyString())).thenAnswer(inv -> {
            String url = inv.getArgument(0);
            String html = pagesByUrl.get(url);
            if (html == null) {
                return new NewsHttpClient.FetchResult(0, url, null, null, null, null);
            }
            return new NewsHttpClient.FetchResult(200, url, "text/html; charset=utf-8",
                    html.getBytes(StandardCharsets.UTF_8), null, null);
        });
        when(httpClient.fetchBytes(anyString(), anyInt())).thenAnswer(inv ->
                new NewsHttpClient.FetchResult(0, inv.getArgument(0), null, null, null, null));
        feed(ESPN_FEED, "");
        feed(BLOG_FEED, "");
    }

    // ---------- fixture helpers ----------

    private Conference conference(String name, String abbr) {
        Conference c = new Conference();
        c.setName(name);
        c.setAbbreviation(abbr);
        c.setEspnId("nc" + Math.floorMod(name.hashCode(), 100000));
        return conferenceRepository.save(c);
    }

    private Team team(String location, String mascot, String abbr) {
        Team t = new Team();
        t.setName(location);
        t.setMascot(mascot);
        t.setAbbreviation(abbr);
        t.setEspnId("nt" + Math.floorMod(location.hashCode(), 100000));
        t.setActive(true);
        return teamRepository.save(t);
    }

    private void membership(Team team, Conference conference, Season season) {
        ConferenceMembership m = new ConferenceMembership();
        m.setTeam(team);
        m.setConference(conference);
        m.setSeason(season);
        membershipRepository.save(m);
    }

    private NewsSource source(String name, String domain, String feedUrl, int weight, boolean dedicated) {
        NewsSource s = new NewsSource();
        s.setName(name);
        s.setDomain(domain);
        s.setFeedUrl(feedUrl);
        s.setAuthorityWeight(weight);
        s.setDedicatedCbb(dedicated);
        return sourceRepository.save(s);
    }

    private void feed(String feedUrl, String itemsXml) {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel>
                <title>Fixture Feed</title><link>%s</link><description>fixture</description>
                %s
                </channel></rss>
                """.formatted(feedUrl, itemsXml);
        when(httpClient.fetchFeed(eq(feedUrl), any(), any())).thenReturn(
                new NewsHttpClient.FetchResult(200, feedUrl, "application/rss+xml",
                        xml.getBytes(StandardCharsets.UTF_8), null, null));
    }

    private static String item(String title, String url, ZonedDateTime pubDate) {
        return """
                <item><title>%s</title><link>%s</link><pubDate>%s</pubDate></item>
                """.formatted(title, url, DateTimeFormatter.RFC_1123_DATE_TIME.format(pubDate));
    }

    private void page(String url, String title, String body) {
        pagesByUrl.put(url, """
                <html><head><meta property="og:title" content="%s"/></head>
                <body><article>%s</article></body></html>
                """.formatted(title, paragraphs(body)));
    }

    private static String paragraphs(String body) {
        StringBuilder sb = new StringBuilder();
        for (String sentence : body.split("\\.\\s+")) {
            if (!sentence.isBlank()) {
                sb.append("<p>").append(sentence.strip()).append(".</p>");
            }
        }
        return sb.toString();
    }

    private static ZonedDateTime hoursAgo(int hours) {
        return ZonedDateTime.now(ZoneOffset.UTC).minusHours(hours);
    }

    // ---------- tests ----------

    @Test
    void pollIngestsTagsAndDerivesConferences() {
        String url = "https://www.espn.com/mens-college-basketball/story/_/id/1001/kansas-tops-iowa-state";
        feed(ESPN_FEED, item("Kansas tops Iowa State", url, hoursAgo(2)));
        page(url, "Kansas tops Iowa State behind Dickinson double-double", KANSAS_BODY);

        ScrapeBatch batch = scrapeService.pollAll(ScrapeBatch.Source.MANUAL);

        assertEquals(ScrapeBatch.ScrapeStatus.COMPLETED, batch.getStatus());
        assertEquals(1, batch.getRecordsCreated());
        assertEquals(2, batch.getDatesSucceeded());

        List<NewsArticle> articles = articleRepository.findAll();
        assertEquals(1, articles.size());
        NewsArticle article = articles.get(0);
        assertEquals(NewsArticle.TagStatus.TAGGED, article.getTagStatus());
        assertEquals(100.0, article.getStaticScore());
        assertNotNull(article.getSimhash());
        assertTrue(article.getBodyTokenCount() >= 80);
        // ESPN attributed as publisher by domain
        assertEquals(espn.getId(), articleRepository.findAll().get(0).getSource().getId());

        var teamTags = articleTeamRepository.findByArticleId(article.getId());
        assertTrue(teamTags.stream().anyMatch(t -> t.getTeam().getId().equals(kansas.getId())
                && t.getConfidence() >= 0.4));
        // Big 12 derived from Kansas/Iowa State membership even without a direct mention
        var confTags = articleConferenceRepository.findByArticleId(article.getId());
        assertTrue(confTags.stream().anyMatch(c -> c.getConference().getId().equals(big12.getId())));
    }

    @Test
    void secondPollCreatesNothing() {
        String url = "https://www.espn.com/story/_/id/1001/kansas-tops-iowa-state";
        feed(ESPN_FEED, item("Kansas tops Iowa State", url, hoursAgo(2)));
        page(url, "Kansas tops Iowa State", KANSAS_BODY);

        scrapeService.pollAll(ScrapeBatch.Source.MANUAL);
        ScrapeBatch second = scrapeService.pollAll(ScrapeBatch.Source.MANUAL);

        assertEquals(0, second.getRecordsCreated());
        assertEquals(1, articleRepository.count());
    }

    @Test
    void trackingParamVariantsCollapseToOneArticle() {
        String base = "https://www.espn.com/story/_/id/1001/kansas-tops-iowa-state";
        feed(ESPN_FEED, item("Kansas tops Iowa State", base + "?utm_source=rss", hoursAgo(2)));
        feed(BLOG_FEED, item("Kansas tops Iowa State", base + "?utm_source=blogroll&fbclid=xyz", hoursAgo(3)));
        page(base + "?utm_source=rss", "Kansas tops Iowa State", KANSAS_BODY);
        page(base + "?utm_source=blogroll&fbclid=xyz", "Kansas tops Iowa State", KANSAS_BODY);

        scrapeService.pollAll(ScrapeBatch.Source.MANUAL);

        assertEquals(1, articleRepository.count());
    }

    @Test
    void sportFilterDiscardsFootballFromNonDedicatedSource() {
        String footballUrl = "https://hoopsblog.example.com/kansas-football-recruiting";
        String hoopsUrl = "https://hoopsblog.example.com/kansas-hoops-notes";
        feed(BLOG_FEED,
                item("Kansas lands five-star recruit", footballUrl, hoursAgo(1))
                        + item("Kansas basketball notes", hoopsUrl, hoursAgo(2)));
        page(footballUrl, "Kansas lands five-star recruit",
                "Kansas football coaches celebrated as the five-star quarterback committed on Saturday. "
                        + "The touchdown-minded recruit chose the Jayhawks over several programs after a long football recruitment. "
                        + "Football recruiting analysts called it the biggest gridiron win of the cycle for the program this season overall.");
        page(hoopsUrl, "Kansas basketball notes", KANSAS_BODY);

        scrapeService.pollAll(ScrapeBatch.Source.MANUAL);

        List<NewsArticle> articles = articleRepository.findAll();
        assertEquals(1, articles.size());
        assertTrue(articles.get(0).getUrlCanonical().contains("hoops-notes"));
    }

    @Test
    void wireStoryClustersUnderHigherAuthorityEvenWhenItArrivesLater() {
        // blog copy arrives first at authority 40
        String blogUrl = "https://hoopsblog.example.com/kansas-recap";
        feed(BLOG_FEED, item("Kansas tops Iowa State", blogUrl, hoursAgo(5)));
        page(blogUrl, "Kansas tops Iowa State", KANSAS_BODY + " Local fans celebrated downtown afterward.");
        scrapeService.pollAll(ScrapeBatch.Source.MANUAL);

        NewsArticle blogArticle = articleRepository.findAll().get(0);
        assertNull(blogArticle.getDuplicateOf());

        // ESPN copy of the same wire body arrives on the next poll at authority 100
        String espnUrl = "https://www.espn.com/story/_/id/1001/kansas-tops-iowa-state";
        feed(BLOG_FEED, "");
        feed(ESPN_FEED, item("Kansas tops Iowa State", espnUrl, hoursAgo(6)));
        page(espnUrl, "Kansas tops Iowa State", KANSAS_BODY);
        scrapeService.pollAll(ScrapeBatch.Source.MANUAL);

        assertEquals(2, articleRepository.count());
        NewsArticle espnArticle = articleRepository.findByUrlCanonical(
                "https://espn.com/story/_/id/1001/kansas-tops-iowa-state").orElseThrow();
        NewsArticle refreshedBlog = articleRepository.findById(blogArticle.getId()).orElseThrow();
        // representative flipped to the higher-authority copy
        assertNull(espnArticle.getDuplicateOf());
        assertNotNull(refreshedBlog.getDuplicateOf());
        assertEquals(espnArticle.getId(), refreshedBlog.getDuplicateOf().getId());
    }

    @Test
    void notModifiedFeedIsSuccessWithoutWork() {
        when(httpClient.fetchFeed(eq(ESPN_FEED), any(), any())).thenReturn(
                new NewsHttpClient.FetchResult(304, ESPN_FEED, null, null, null, null));

        ScrapeBatch batch = scrapeService.pollAll(ScrapeBatch.Source.MANUAL);

        assertEquals(ScrapeBatch.ScrapeStatus.COMPLETED, batch.getStatus());
        assertEquals(0, articleRepository.count());
        assertEquals(0, sourceRepository.findById(espn.getId()).orElseThrow().getConsecutiveFailures());
    }

    @Test
    void failingFeedIncrementsFailuresAndEventuallyAutoDisables() {
        when(httpClient.fetchFeed(eq(BLOG_FEED), any(), any())).thenReturn(
                new NewsHttpClient.FetchResult(500, BLOG_FEED, null, null, null, null));

        ScrapeBatch batch = scrapeService.pollAll(ScrapeBatch.Source.MANUAL);
        assertEquals(ScrapeBatch.ScrapeStatus.PARTIAL, batch.getStatus());
        NewsSource refreshed = sourceRepository.findById(blog.getId()).orElseThrow();
        assertEquals(1, refreshed.getConsecutiveFailures());
        assertNull(refreshed.getAutoDisabledAt());

        refreshed.setConsecutiveFailures(9);
        sourceRepository.save(refreshed);
        scrapeService.pollAll(ScrapeBatch.Source.MANUAL);
        refreshed = sourceRepository.findById(blog.getId()).orElseThrow();
        assertEquals(10, refreshed.getConsecutiveFailures());
        assertNotNull(refreshed.getAutoDisabledAt());

        // disabled source is skipped on the next run
        LocalDateTime disabledAt = refreshed.getAutoDisabledAt();
        scrapeService.pollAll(ScrapeBatch.Source.MANUAL);
        refreshed = sourceRepository.findById(blog.getId()).orElseThrow();
        assertEquals(10, refreshed.getConsecutiveFailures());
        assertEquals(disabledAt, refreshed.getAutoDisabledAt());
    }

    @Test
    void weeklyRetryProbeReenablesRecoveredSource() {
        blog.setConsecutiveFailures(10);
        blog.setAutoDisabledAt(LocalDateTime.now().minusDays(8));
        sourceRepository.save(blog);

        String url = "https://hoopsblog.example.com/kansas-hoops-notes";
        feed(BLOG_FEED, item("Kansas basketball notes", url, hoursAgo(1)));
        page(url, "Kansas basketball notes", KANSAS_BODY);

        scrapeService.pollAll(ScrapeBatch.Source.MANUAL);

        NewsSource refreshed = sourceRepository.findById(blog.getId()).orElseThrow();
        assertNull(refreshed.getAutoDisabledAt());
        assertEquals(0, refreshed.getConsecutiveFailures());
        assertEquals(1, articleRepository.count());
    }

    @Test
    void futurePublishDateClampedToNow() {
        String url = "https://www.espn.com/story/_/id/1009/kansas-future-dated";
        feed(ESPN_FEED, item("Kansas future dated story", url,
                ZonedDateTime.now(ZoneOffset.UTC).plusDays(2)));
        page(url, "Kansas future dated story", KANSAS_BODY);

        scrapeService.pollAll(ScrapeBatch.Source.MANUAL);

        NewsArticle article = articleRepository.findAll().get(0);
        assertTrue(article.getPublishedAt().isBefore(LocalDateTime.now().plusMinutes(5)));
    }

    @Test
    void espnApiSourceIngestsJsonWithChallengedArticlePages() {
        // ESPN scenario: the API returns JSON; article pages answer with a
        // 202 HTML bot challenge → metadata-only ingest, image from the API.
        String apiUrl = "https://site.api.espn.com/apis/site/v2/sports/basketball/mens-college-basketball/news";
        NewsSource espnApi = source("ESPN API", "site.api.espn.com", apiUrl, 100, true);
        espnApi.setSourceType(NewsSource.SourceType.ESPN_API);
        sourceRepository.save(espnApi);

        String json = """
                {"articles": [{
                  "headline": "Kansas Jayhawks land top transfer guard",
                  "description": "The Jayhawks added a veteran guard from the portal on Thursday.",
                  "published": "%s",
                  "links": {"web": {"href": "https://www.espn.com/mens-college-basketball/story/_/id/3001/kansas-transfer"}},
                  "images": [{"url": "https://a.espncdn.com/photo/kansas.jpg"}]
                }]}
                """.formatted(hoursAgo(2).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        when(httpClient.fetchFeed(eq(apiUrl), any(), any())).thenReturn(
                new NewsHttpClient.FetchResult(200, apiUrl, "application/json",
                        json.getBytes(StandardCharsets.UTF_8), null, null));
        when(httpClient.fetchPage(anyString())).thenReturn(
                new NewsHttpClient.FetchResult(202, "https://www.espn.com/challenged",
                        "text/html", "<!DOCTYPE html><html>bot check</html>".getBytes(StandardCharsets.UTF_8),
                        null, null));

        scrapeService.pollAll(ScrapeBatch.Source.MANUAL);

        List<NewsArticle> articles = articleRepository.findAll();
        assertEquals(1, articles.size());
        NewsArticle article = articles.get(0);
        assertEquals("Kansas Jayhawks land top transfer guard", article.getTitle());
        assertEquals("https://a.espncdn.com/photo/kansas.jpg", article.getImageUrl());
        assertEquals(0, article.getBodyTokenCount());
        assertTrue(articleTeamRepository.findByArticleId(article.getId()).stream()
                .anyMatch(t -> t.getTeam().getId().equals(kansas.getId())));
    }

    @Test
    void urlVerdictOverridesDedicatedFlagAndKeywordFilter() {
        // ESPN's "ncb" feed carries football items: a /college-football/ URL is
        // discarded even from a dedicated source, and a /mens-college-basketball/
        // URL is kept from a non-dedicated source without any keyword help.
        String footballUrl = "https://www.espn.com/college-football/story/_/id/2001/swac-tv-deal";
        String cbbUrl = "https://hoopsblog.example.com/mens-college-basketball/kansas-quiet-note";
        feed(ESPN_FEED, item("SWAC reaches TV deal", footballUrl, hoursAgo(1)));
        feed(BLOG_FEED, item("Quiet Kansas note", cbbUrl, hoursAgo(1)));
        page(footballUrl, "SWAC reaches TV deal",
                "The conference agreed to a television deal covering multiple sports for years to come.");
        page(cbbUrl, "Quiet Kansas note",
                "A short program note with deliberately no filter keywords about the Jayhawks roster at all.");

        scrapeService.pollAll(ScrapeBatch.Source.MANUAL);

        List<NewsArticle> articles = articleRepository.findAll();
        assertEquals(1, articles.size());
        assertTrue(articles.get(0).getUrlCanonical().contains("kansas-quiet-note"));
    }

    @Test
    void retagIsAddOnlyAndPicksUpNewAliases() {
        // ingest an article whose title only a future alias can match
        String url = "https://www.espn.com/story/_/id/1011/self-presser";
        feed(ESPN_FEED, item("Bill Self praises the rotation depth", url, hoursAgo(1)));
        scrapeService.pollAll(ScrapeBatch.Source.MANUAL);

        NewsArticle article = articleRepository.findAll().get(0);
        assertEquals(NewsArticle.TagStatus.UNTAGGED, article.getTagStatus());

        adminService.createAliasForTeam("Bill Self", kansas.getId(), false);
        int added = adminService.retag(30);

        assertTrue(added >= 1);
        NewsArticle refreshed = articleRepository.findById(article.getId()).orElseThrow();
        assertEquals(NewsArticle.TagStatus.TAGGED, refreshed.getTagStatus());
        assertTrue(articleTeamRepository.findByArticleId(article.getId()).stream()
                .anyMatch(t -> t.getTeam().getId().equals(kansas.getId())));
    }

    @Test
    void metadataOnlyItemIngestsFromDedicatedSourceWithoutSimhash() {
        String url = "https://www.espn.com/story/_/id/1010/kansas-page-down";
        feed(ESPN_FEED, item("Kansas coach discusses Jayhawks rotation", url, hoursAgo(1)));
        // no page fixture → fetch fails → metadata-only

        scrapeService.pollAll(ScrapeBatch.Source.MANUAL);

        List<NewsArticle> articles = articleRepository.findAll();
        assertEquals(1, articles.size());
        assertNull(articles.get(0).getSimhash());
        assertEquals(0, articles.get(0).getBodyTokenCount());
        // tagged from the title alone
        assertTrue(articleTeamRepository.findByArticleId(articles.get(0).getId()).stream()
                .anyMatch(t -> t.getTeam().getId().equals(kansas.getId())));
    }
}
