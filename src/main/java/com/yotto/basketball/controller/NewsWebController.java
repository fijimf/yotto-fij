package com.yotto.basketball.controller;

import com.yotto.basketball.news.NewsQueryService;
import com.yotto.basketball.repository.ConferenceRepository;
import com.yotto.basketball.repository.TeamRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Comparator;
import java.util.List;

/** Public /news page (docs/NEWS_MODULE.md §6). */
@Controller
public class NewsWebController {

    private static final int PAGE_SIZE = 24;

    private final NewsQueryService newsQueryService;
    private final TeamRepository teamRepository;
    private final ConferenceRepository conferenceRepository;

    public NewsWebController(NewsQueryService newsQueryService,
                             TeamRepository teamRepository,
                             ConferenceRepository conferenceRepository) {
        this.newsQueryService = newsQueryService;
        this.teamRepository = teamRepository;
        this.conferenceRepository = conferenceRepository;
    }

    @GetMapping("/news")
    public String news(@RequestParam(required = false) Long teamId,
                       @RequestParam(required = false) Long conferenceId,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        List<NewsQueryService.NewsCard> cards =
                newsQueryService.newsPage(teamId, conferenceId, Math.max(0, page), PAGE_SIZE);
        boolean hasNext = cards.size() > PAGE_SIZE;

        model.addAttribute("currentPage", "news");
        model.addAttribute("currentSection", "news");
        model.addAttribute("cards", hasNext ? cards.subList(0, PAGE_SIZE) : cards);
        model.addAttribute("page", Math.max(0, page));
        model.addAttribute("hasNext", hasNext);
        model.addAttribute("teamId", teamId);
        model.addAttribute("conferenceId", conferenceId);
        model.addAttribute("teams", teamRepository.findAll().stream()
                .filter(t -> !Boolean.FALSE.equals(t.getActive()))
                .sorted(Comparator.comparing(t -> t.getName() == null ? "" : t.getName()))
                .toList());
        model.addAttribute("conferences", conferenceRepository.findAll().stream()
                .sorted(Comparator.comparing(c -> c.getName() == null ? "" : c.getName()))
                .toList());
        return "pages/news";
    }
}
