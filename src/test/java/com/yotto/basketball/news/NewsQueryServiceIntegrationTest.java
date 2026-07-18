package com.yotto.basketball.news;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Conference;
import com.yotto.basketball.entity.NewsArticle;
import com.yotto.basketball.entity.NewsArticleConference;
import com.yotto.basketball.entity.NewsArticleTeam;
import com.yotto.basketball.entity.NewsSource;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.ConferenceRepository;
import com.yotto.basketball.repository.NewsArticleConferenceRepository;
import com.yotto.basketball.repository.NewsArticleRepository;
import com.yotto.basketball.repository.NewsArticleTeamRepository;
import com.yotto.basketball.repository.NewsSourceRepository;
import com.yotto.basketball.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Read-side listing rules: decay ordering, per-source cap, filters, visibility. */
class NewsQueryServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired NewsQueryService queryService;
    @Autowired NewsArticleRepository articleRepository;
    @Autowired NewsArticleTeamRepository articleTeamRepository;
    @Autowired NewsArticleConferenceRepository articleConferenceRepository;
    @Autowired NewsSourceRepository sourceRepository;
    @Autowired TeamRepository teamRepository;
    @Autowired ConferenceRepository conferenceRepository;

    private NewsSource espn;
    private NewsSource blog;
    private Team kansas;
    private Conference big12;

    @BeforeEach
    void seed() {
        espn = source("ESPN", "espn.com", 100);
        blog = source("Blog", "blog.example.com", 40);
        kansas = new Team();
        kansas.setName("Kansas");
        kansas.setEspnId("t-ku");
        kansas.setActive(true);
        kansas = teamRepository.save(kansas);
        big12 = new Conference();
        big12.setName("Big 12");
        big12.setEspnId("c-b12");
        big12 = conferenceRepository.save(big12);
    }

    private NewsSource source(String name, String domain, int weight) {
        NewsSource s = new NewsSource();
        s.setName(name);
        s.setDomain(domain);
        s.setAuthorityWeight(weight);
        return sourceRepository.save(s);
    }

    private NewsArticle article(String title, NewsSource src, int hoursOld, double score) {
        NewsArticle a = new NewsArticle();
        a.setUrlCanonical("https://" + (src != null ? src.getDomain() : "x.example.com") + "/" + title.hashCode());
        a.setUrlOriginal(a.getUrlCanonical());
        a.setTitle(title);
        a.setSource(src);
        a.setPublishedAt(LocalDateTime.now().minusHours(hoursOld));
        a.setFetchedAt(LocalDateTime.now());
        a.setStaticScore(score);
        return articleRepository.save(a);
    }

    @Test
    void frontPageAppliesPerSourceCap() {
        // 4 fresh high-authority ESPN stories + 2 older blog stories
        for (int i = 0; i < 4; i++) {
            article("ESPN story " + i, espn, 1, 100);
        }
        article("Blog story 1", blog, 6, 40);
        article("Blog story 2", blog, 7, 40);

        List<NewsQueryService.NewsCard> cards = queryService.frontPage();

        long espnCount = cards.stream().filter(c -> "ESPN".equals(c.sourceName())).count();
        assertEquals(2, espnCount, "per-source cap of 2 must hold even for the top authority");
        assertTrue(cards.stream().anyMatch(c -> "Blog".equals(c.sourceName())));
    }

    @Test
    void recencyDecayLetsFreshLowAuthorityBeatStaleHighAuthority() {
        // 100-authority story from 4 days ago vs 40-authority story from 1 hour ago:
        // with a 36h half-life the old one has decayed past the fresh one
        article("Stale ESPN story", espn, 96, 100);
        article("Fresh blog story", blog, 1, 40);

        List<NewsQueryService.NewsCard> cards = queryService.newsPage(null, null, 0, 10);
        assertEquals("Fresh blog story", cards.get(0).title());
    }

    @Test
    void hiddenAndDuplicateArticlesExcludedEverywhere() {
        NewsArticle visible = article("Visible", espn, 1, 100);
        NewsArticle hidden = article("Hidden", espn, 1, 100);
        hidden.setHidden(true);
        articleRepository.save(hidden);
        NewsArticle dup = article("Duplicate", blog, 1, 40);
        dup.setDuplicateOf(visible);
        articleRepository.save(dup);

        List<NewsQueryService.NewsCard> cards = queryService.newsPage(null, null, 0, 10);
        assertEquals(1, cards.size());
        assertEquals("Visible", cards.get(0).title());
    }

    @Test
    void teamFilterHonorsConfidenceThreshold() {
        NewsArticle strong = article("Strong Kansas story", espn, 1, 100);
        articleTeamRepository.save(new NewsArticleTeam(strong, kansas, 0.6, "Kansas Jayhawks", false));
        NewsArticle weak = article("Weak Kansas mention", blog, 1, 40);
        articleTeamRepository.save(new NewsArticleTeam(weak, kansas, 0.2, "Jayhawks", false));

        List<NewsQueryService.NewsCard> cards = queryService.teamNews(kansas.getId(), 10);
        assertEquals(1, cards.size());
        assertEquals("Strong Kansas story", cards.get(0).title());
    }

    @Test
    void conferenceFilterIncludesDerivedTags() {
        NewsArticle a = article("Big 12 roundup", espn, 1, 100);
        // derived conference tags carry half confidence — 0.3 must pass the /2 threshold
        articleConferenceRepository.save(new NewsArticleConference(a, big12, 0.3, "via Kansas", false));

        List<NewsQueryService.NewsCard> cards = queryService.conferenceNews(big12.getId(), 10);
        assertEquals(1, cards.size());
    }

    @Test
    void unattributedArticleFallsBackToDiscoveringFeedName() {
        NewsArticle a = article("Long-tail story", null, 1, 30);
        a.setDiscoveredVia(blog);
        articleRepository.save(a);

        List<NewsQueryService.NewsCard> cards = queryService.newsPage(null, null, 0, 10);
        assertEquals("Blog", cards.get(0).sourceName());
    }
}
