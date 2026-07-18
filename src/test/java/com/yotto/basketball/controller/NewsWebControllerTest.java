package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.NewsArticle;
import com.yotto.basketball.entity.NewsSource;
import com.yotto.basketball.repository.NewsArticleRepository;
import com.yotto.basketball.repository.NewsSourceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@AutoConfigureMockMvc
class NewsWebControllerTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired NewsArticleRepository articleRepository;
    @Autowired NewsSourceRepository sourceRepository;

    private NewsArticle mkArticle(String title) {
        NewsSource source = new NewsSource();
        source.setName("ESPN");
        source.setDomain("espn.com");
        source.setFeedUrl("https://espn.com/feed-" + title.hashCode());
        source = sourceRepository.save(source);

        NewsArticle a = new NewsArticle();
        a.setUrlCanonical("https://espn.com/" + title.hashCode());
        a.setUrlOriginal(a.getUrlCanonical());
        a.setTitle(title);
        a.setSource(source);
        a.setPublishedAt(LocalDateTime.now().minusHours(2));
        a.setFetchedAt(LocalDateTime.now());
        a.setStaticScore(100.0);
        return articleRepository.save(a);
    }

    @Test
    void newsPageIsPublicAndRendersCards() throws Exception {
        mkArticle("Kansas tops Iowa State");
        mockMvc.perform(get("/news"))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/news"))
                .andExpect(content().string(containsString("Kansas tops Iowa State")));
    }

    @Test
    void newsPageEmptyStateRenders() throws Exception {
        mockMvc.perform(get("/news"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No stories yet")));
    }

    @Test
    void homePageShowsLatestNewsPanel() throws Exception {
        mkArticle("Duke rolls past North Carolina");
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Latest News")))
                .andExpect(content().string(containsString("Duke rolls past North Carolina")));
    }

    @Test
    void homePageWithoutNewsOmitsPanel() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Latest News"))));
    }

    @Test
    void missingThumbnailReturns404() throws Exception {
        NewsArticle article = mkArticle("No image story");
        mockMvc.perform(get("/news/img/" + article.getId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/news/img/999999"))
                .andExpect(status().isNotFound());
    }
}
