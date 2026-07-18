package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.NewsAlias;
import com.yotto.basketball.entity.NewsArticle;
import com.yotto.basketball.entity.NewsArticleTeam;
import com.yotto.basketball.entity.NewsSource;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.news.NewsHttpClient;
import com.yotto.basketball.repository.NewsAliasRepository;
import com.yotto.basketball.repository.NewsArticleRepository;
import com.yotto.basketball.repository.NewsArticleTeamRepository;
import com.yotto.basketball.repository.NewsSourceRepository;
import com.yotto.basketball.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@AutoConfigureMockMvc
class AdminNewsControllerTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired NewsSourceRepository sourceRepository;
    @Autowired NewsArticleRepository articleRepository;
    @Autowired NewsArticleTeamRepository articleTeamRepository;
    @Autowired NewsAliasRepository aliasRepository;
    @Autowired TeamRepository teamRepository;

    @MockBean NewsHttpClient httpClient;
    // Mocked so the retag endpoint doesn't spawn real background work that
    // races the between-test TRUNCATE
    @MockBean com.yotto.basketball.news.AsyncNewsService asyncNewsService;

    private Team kansas;

    @BeforeEach
    void seed() {
        kansas = new Team();
        kansas.setName("Kansas");
        kansas.setMascot("Jayhawks");
        kansas.setEspnId("t-ku");
        kansas.setActive(true);
        kansas = teamRepository.save(kansas);
    }

    private NewsSource mkSource(String name, String feedUrl) {
        NewsSource s = new NewsSource();
        s.setName(name);
        s.setDomain("example.com");
        s.setFeedUrl(feedUrl);
        return sourceRepository.save(s);
    }

    private NewsArticle mkArticle(String title) {
        NewsArticle a = new NewsArticle();
        a.setUrlCanonical("https://example.com/" + title.hashCode());
        a.setUrlOriginal(a.getUrlCanonical());
        a.setTitle(title);
        a.setPublishedAt(LocalDateTime.now().minusHours(1));
        a.setFetchedAt(LocalDateTime.now());
        return articleRepository.save(a);
    }

    @Test
    void anonymousIsRedirectedToLogin() throws Exception {
        mockMvc.perform(get("/admin/news/sources")
                        .accept(org.springframework.http.MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(roles = "USER")
    void plainUserIsForbidden() throws Exception {
        mockMvc.perform(get("/admin/news/sources"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void sourcesPageRenders() throws Exception {
        mkSource("ESPN", "https://espn.com/feed");
        mockMvc.perform(get("/admin/news/sources"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/news-sources"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createSourcePersists() throws Exception {
        mockMvc.perform(post("/admin/news/sources").with(csrf())
                        .param("name", "CBS Sports CBB")
                        .param("domain", "CBSSports.com")
                        .param("feedUrl", "https://www.cbssports.com/rss/headlines/college-basketball/")
                        .param("authorityWeight", "90")
                        .param("dedicatedCbb", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("success"));

        NewsSource saved = sourceRepository.findByFeedUrl(
                "https://www.cbssports.com/rss/headlines/college-basketball/").orElseThrow();
        assertThat(saved.getDomain()).isEqualTo("cbssports.com");
        assertThat(saved.getAuthorityWeight()).isEqualTo(90);
        assertThat(saved.getDedicatedCbb()).isTrue();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void duplicateFeedUrlRejected() throws Exception {
        mkSource("ESPN", "https://espn.com/feed");
        mockMvc.perform(post("/admin/news/sources").with(csrf())
                        .param("name", "ESPN again")
                        .param("domain", "espn.com")
                        .param("feedUrl", "https://espn.com/feed"))
                .andExpect(flash().attributeExists("error"));
        assertThat(sourceRepository.count()).isEqualTo(1);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void reenableClearsAutoDisable() throws Exception {
        NewsSource source = mkSource("Flaky", "https://flaky.example.com/feed");
        source.setAutoDisabledAt(LocalDateTime.now());
        source.setConsecutiveFailures(10);
        sourceRepository.save(source);

        mockMvc.perform(post("/admin/news/sources/" + source.getId() + "/reenable").with(csrf()))
                .andExpect(status().is3xxRedirection());

        NewsSource refreshed = sourceRepository.findById(source.getId()).orElseThrow();
        assertThat(refreshed.getAutoDisabledAt()).isNull();
        assertThat(refreshed.getConsecutiveFailures()).isZero();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteSourceWithArticlesRefused() throws Exception {
        NewsSource source = mkSource("HasArticles", "https://has.example.com/feed");
        NewsArticle article = mkArticle("Story");
        article.setSource(source);
        articleRepository.save(article);

        mockMvc.perform(post("/admin/news/sources/" + source.getId() + "/delete").with(csrf()))
                .andExpect(flash().attributeExists("error"));
        assertThat(sourceRepository.existsById(source.getId())).isTrue();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void taggingPageRenders() throws Exception {
        mkArticle("Untagged story");
        mockMvc.perform(get("/admin/news/tagging"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/news-tagging"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void manualTagWithAliasCreation() throws Exception {
        NewsArticle article = mkArticle("Self talks roster");

        mockMvc.perform(post("/admin/news/articles/" + article.getId() + "/tag-team").with(csrf())
                        .param("teamId", kansas.getId().toString())
                        .param("newAlias", "Bill Self")
                        .param("aliasAmbiguous", "false"))
                .andExpect(status().is3xxRedirection());

        NewsArticleTeam tag = articleTeamRepository
                .findByArticleIdAndTeamId(article.getId(), kansas.getId()).orElseThrow();
        assertThat(tag.getManual()).isTrue();
        assertThat(articleRepository.findById(article.getId()).orElseThrow().getTagStatus())
                .isEqualTo(NewsArticle.TagStatus.MANUAL);
        NewsAlias alias = aliasRepository.findByAliasAndTeamId("Bill Self", kansas.getId()).orElseThrow();
        assertThat(alias.getKind()).isEqualTo(NewsAlias.Kind.MANUAL);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void editingAutoAliasPinsItAsManual() throws Exception {
        NewsAlias auto = aliasRepository.save(
                NewsAlias.forTeam("Jayhawks", kansas, NewsAlias.Kind.AUTO, true, false));

        mockMvc.perform(post("/admin/news/aliases/" + auto.getId()).with(csrf())
                        .param("enabled", "true")
                        .param("ambiguous", "false")
                        .param("kind", "AUTO"))
                .andExpect(status().is3xxRedirection());

        NewsAlias refreshed = aliasRepository.findById(auto.getId()).orElseThrow();
        assertThat(refreshed.getKind()).isEqualTo(NewsAlias.Kind.MANUAL);
        assertThat(refreshed.getAmbiguous()).isFalse();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void hideAndBreakClusterActions() throws Exception {
        NewsArticle rep = mkArticle("Original");
        NewsArticle dup = mkArticle("Republished");
        dup.setDuplicateOf(rep);
        articleRepository.save(dup);

        mockMvc.perform(post("/admin/news/articles/" + rep.getId() + "/hide").with(csrf())
                        .param("hidden", "true"))
                .andExpect(status().is3xxRedirection());
        assertThat(articleRepository.findById(rep.getId()).orElseThrow().getHidden()).isTrue();

        mockMvc.perform(post("/admin/news/articles/" + dup.getId() + "/break-cluster").with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(articleRepository.findById(dup.getId()).orElseThrow().getDuplicateOf()).isNull();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void articleDetailRenders() throws Exception {
        NewsArticle article = mkArticle("Detail story");
        mockMvc.perform(get("/admin/news/articles/" + article.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/news-article-detail"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void articlesPageRendersWithSearch() throws Exception {
        mkArticle("Kansas beats Baylor");
        mkArticle("Duke wins again");
        mockMvc.perform(get("/admin/news/articles").param("q", "kansas"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/news-articles"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void retagRedirectsWithFlash() throws Exception {
        mockMvc.perform(post("/admin/news/retag").with(csrf()).param("days", "14"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("success"));
    }
}
