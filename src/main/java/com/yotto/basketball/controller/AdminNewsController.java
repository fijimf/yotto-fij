package com.yotto.basketball.controller;

import com.yotto.basketball.entity.NewsAlias;
import com.yotto.basketball.entity.NewsArticle;
import com.yotto.basketball.news.AsyncNewsService;
import com.yotto.basketball.news.NewsAdminService;
import com.yotto.basketball.news.NewsScrapeService;
import com.yotto.basketball.repository.ConferenceRepository;
import com.yotto.basketball.repository.NewsArticleConferenceRepository;
import com.yotto.basketball.repository.NewsArticleRepository;
import com.yotto.basketball.repository.NewsArticleTeamRepository;
import com.yotto.basketball.repository.TeamRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Comparator;

/**
 * Admin surfaces for the news module (docs/NEWS_MODULE.md §7):
 * sources (with feed dry-run), tagging quality, and article management.
 */
@Controller
@RequestMapping("/admin/news")
public class AdminNewsController {

    private final NewsAdminService adminService;
    private final NewsScrapeService scrapeService;
    private final AsyncNewsService asyncNewsService;
    private final TeamRepository teamRepository;
    private final ConferenceRepository conferenceRepository;
    private final NewsArticleRepository articleRepository;
    private final NewsArticleTeamRepository articleTeamRepository;
    private final NewsArticleConferenceRepository articleConferenceRepository;

    public AdminNewsController(NewsAdminService adminService,
                               NewsScrapeService scrapeService,
                               AsyncNewsService asyncNewsService,
                               TeamRepository teamRepository,
                               ConferenceRepository conferenceRepository,
                               NewsArticleRepository articleRepository,
                               NewsArticleTeamRepository articleTeamRepository,
                               NewsArticleConferenceRepository articleConferenceRepository) {
        this.adminService = adminService;
        this.scrapeService = scrapeService;
        this.asyncNewsService = asyncNewsService;
        this.teamRepository = teamRepository;
        this.conferenceRepository = conferenceRepository;
        this.articleRepository = articleRepository;
        this.articleTeamRepository = articleTeamRepository;
        this.articleConferenceRepository = articleConferenceRepository;
    }

    // ---------- sources ----------

    @GetMapping("/sources")
    public String sources(Model model) {
        model.addAttribute("sources", adminService.sourceRows());
        return "admin/news-sources";
    }

    /** HTMX dry-run fragment: parse + evaluate a feed without saving anything (§7.1). */
    @GetMapping("/sources/test")
    public String testFeed(@RequestParam String feedUrl,
                           @RequestParam(defaultValue = "RSS") com.yotto.basketball.entity.NewsSource.SourceType sourceType,
                           @RequestParam(defaultValue = "false") boolean dedicatedCbb,
                           Model model) {
        model.addAttribute("items", scrapeService.dryRun(feedUrl, sourceType, dedicatedCbb, 10));
        model.addAttribute("feedUrl", feedUrl);
        return "admin/fragments/news-dryrun :: dryrun";
    }

    @PostMapping("/sources")
    public String createSource(@RequestParam String name,
                               @RequestParam String domain,
                               @RequestParam(required = false) String feedUrl,
                               @RequestParam(defaultValue = "RSS") com.yotto.basketball.entity.NewsSource.SourceType sourceType,
                               @RequestParam(defaultValue = "50") int authorityWeight,
                               @RequestParam(defaultValue = "false") boolean dedicatedCbb,
                               @RequestParam(required = false) String notes,
                               RedirectAttributes redirect) {
        try {
            adminService.createSource(name, domain, feedUrl, sourceType, authorityWeight, dedicatedCbb, notes);
            redirect.addFlashAttribute("success", "Source added");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/news/sources";
    }

    @PostMapping("/sources/{id}")
    public String updateSource(@PathVariable Long id,
                               @RequestParam String name,
                               @RequestParam String domain,
                               @RequestParam(required = false) String feedUrl,
                               @RequestParam(defaultValue = "RSS") com.yotto.basketball.entity.NewsSource.SourceType sourceType,
                               @RequestParam int authorityWeight,
                               @RequestParam(defaultValue = "false") boolean dedicatedCbb,
                               @RequestParam(defaultValue = "false") boolean active,
                               @RequestParam(required = false) String notes,
                               RedirectAttributes redirect) {
        adminService.updateSource(id, name, domain, feedUrl, sourceType, authorityWeight, dedicatedCbb, active, notes);
        redirect.addFlashAttribute("success", "Source updated");
        return "redirect:/admin/news/sources";
    }

    @PostMapping("/sources/{id}/reenable")
    public String reenableSource(@PathVariable Long id, RedirectAttributes redirect) {
        adminService.reenableSource(id);
        redirect.addFlashAttribute("success", "Source re-enabled");
        return "redirect:/admin/news/sources";
    }

    @PostMapping("/sources/{id}/delete")
    public String deleteSource(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            adminService.deleteSource(id);
            redirect.addFlashAttribute("success", "Source deleted");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/news/sources";
    }

    @PostMapping("/poll")
    public String pollNow(RedirectAttributes redirect) {
        asyncNewsService.pollAllAsync();
        redirect.addFlashAttribute("success", "News poll started — watch Scrape History on the dashboard");
        return "redirect:/admin/news/sources";
    }

    // ---------- tagging quality ----------

    @GetMapping("/tagging")
    public String tagging(@RequestParam(required = false) String aliasFilter, Model model) {
        model.addAttribute("untagged", adminService.untaggedRecent());
        model.addAttribute("nearMisses", adminService.nearMissTeamTags());
        model.addAttribute("aliases", adminService.aliasRows(aliasFilter));
        model.addAttribute("aliasFilter", aliasFilter);
        model.addAttribute("teams", teamRepository.findAll().stream()
                .filter(t -> !Boolean.FALSE.equals(t.getActive()))
                .sorted(Comparator.comparing(t -> t.getName() == null ? "" : t.getName()))
                .toList());
        model.addAttribute("conferences", conferenceRepository.findAll().stream()
                .sorted(Comparator.comparing(c -> c.getName() == null ? "" : c.getName()))
                .toList());
        return "admin/news-tagging";
    }

    @PostMapping("/articles/{id}/tag-team")
    public String tagTeam(@PathVariable Long id,
                          @RequestParam Long teamId,
                          @RequestParam(required = false) String newAlias,
                          @RequestParam(defaultValue = "false") boolean aliasAmbiguous,
                          @RequestParam(defaultValue = "tagging") String back,
                          RedirectAttributes redirect) {
        adminService.manualTagTeam(id, teamId, newAlias, aliasAmbiguous);
        redirect.addFlashAttribute("success", "Tag added"
                + (newAlias != null && !newAlias.isBlank() ? " and alias created" : ""));
        return redirectBack(back, id);
    }

    @PostMapping("/articles/{id}/tag-conference")
    public String tagConference(@PathVariable Long id,
                                @RequestParam Long conferenceId,
                                @RequestParam(defaultValue = "tagging") String back,
                                RedirectAttributes redirect) {
        adminService.manualTagConference(id, conferenceId);
        redirect.addFlashAttribute("success", "Conference tag added");
        return redirectBack(back, id);
    }

    @PostMapping("/articles/{id}/untag-team")
    public String untagTeam(@PathVariable Long id, @RequestParam Long teamId,
                            @RequestParam(defaultValue = "detail") String back,
                            RedirectAttributes redirect) {
        adminService.removeTeamTag(id, teamId);
        redirect.addFlashAttribute("success", "Tag removed");
        return redirectBack(back, id);
    }

    @PostMapping("/articles/{id}/untag-conference")
    public String untagConference(@PathVariable Long id, @RequestParam Long conferenceId,
                                  @RequestParam(defaultValue = "detail") String back,
                                  RedirectAttributes redirect) {
        adminService.removeConferenceTag(id, conferenceId);
        redirect.addFlashAttribute("success", "Tag removed");
        return redirectBack(back, id);
    }

    @PostMapping("/near-misses/{tagRowId}/confirm")
    public String confirmNearMiss(@PathVariable Long tagRowId, RedirectAttributes redirect) {
        adminService.confirmNearMiss(tagRowId);
        redirect.addFlashAttribute("success", "Tag confirmed");
        return "redirect:/admin/news/tagging";
    }

    @PostMapping("/near-misses/{tagRowId}/dismiss")
    public String dismissNearMiss(@PathVariable Long tagRowId, RedirectAttributes redirect) {
        adminService.dismissNearMiss(tagRowId);
        redirect.addFlashAttribute("success", "Near-miss dismissed");
        return "redirect:/admin/news/tagging";
    }

    @PostMapping("/aliases")
    public String createAlias(@RequestParam String alias,
                              @RequestParam Long teamId,
                              @RequestParam(defaultValue = "false") boolean ambiguous,
                              RedirectAttributes redirect) {
        adminService.createAliasForTeam(alias.strip(), teamId, ambiguous);
        redirect.addFlashAttribute("success", "Alias created");
        return "redirect:/admin/news/tagging";
    }

    @PostMapping("/aliases/{id}")
    public String updateAlias(@PathVariable Long id,
                              @RequestParam(defaultValue = "false") boolean enabled,
                              @RequestParam(defaultValue = "false") boolean ambiguous,
                              @RequestParam NewsAlias.Kind kind,
                              RedirectAttributes redirect) {
        adminService.updateAlias(id, enabled, ambiguous, kind);
        redirect.addFlashAttribute("success", "Alias updated");
        return "redirect:/admin/news/tagging";
    }

    @PostMapping("/aliases/{id}/delete")
    public String deleteAlias(@PathVariable Long id, RedirectAttributes redirect) {
        adminService.deleteAlias(id);
        redirect.addFlashAttribute("success", "Alias deleted");
        return "redirect:/admin/news/tagging";
    }

    @PostMapping("/aliases/reseed")
    public String reseedAliases(RedirectAttributes redirect) {
        int count = adminService.reseedAliases();
        redirect.addFlashAttribute("success", "Reseeded " + count + " AUTO aliases (manual/block rows untouched)");
        return "redirect:/admin/news/tagging";
    }

    @PostMapping("/retag")
    public String retag(@RequestParam(defaultValue = "30") int days, RedirectAttributes redirect) {
        asyncNewsService.retagAsync(days);
        redirect.addFlashAttribute("success",
                "Retag started over the last " + days + " days (add-only, titles+snippets)");
        return "redirect:/admin/news/tagging";
    }

    // ---------- articles ----------

    @GetMapping("/articles")
    public String articles(@RequestParam(required = false) String q,
                           @RequestParam(defaultValue = "0") int page,
                           Model model) {
        Page<NewsArticle> articles = (q == null || q.isBlank())
                ? articleRepository.findAllByOrderByPublishedAtDesc(PageRequest.of(page, 25))
                : articleRepository.findByTitleContainingIgnoreCaseOrderByPublishedAtDesc(
                        q.strip(), PageRequest.of(page, 25));
        model.addAttribute("articles", articles);
        model.addAttribute("q", q);
        return "admin/news-articles";
    }

    @GetMapping("/articles/{id}")
    public String articleDetail(@PathVariable Long id, Model model) {
        NewsArticle article = articleRepository.findById(id)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Article not found: " + id));
        model.addAttribute("article", article);
        model.addAttribute("teamTags", articleTeamRepository.findByArticleId(id));
        model.addAttribute("conferenceTags", articleConferenceRepository.findByArticleId(id));
        model.addAttribute("clusterMembers", adminService.clusterMembers(id));
        model.addAttribute("teams", teamRepository.findAll().stream()
                .filter(t -> !Boolean.FALSE.equals(t.getActive()))
                .sorted(Comparator.comparing(t -> t.getName() == null ? "" : t.getName()))
                .toList());
        model.addAttribute("conferences", conferenceRepository.findAll().stream()
                .sorted(Comparator.comparing(c -> c.getName() == null ? "" : c.getName()))
                .toList());
        return "admin/news-article-detail";
    }

    @PostMapping("/articles/{id}/hide")
    public String hideArticle(@PathVariable Long id,
                              @RequestParam boolean hidden,
                              RedirectAttributes redirect) {
        adminService.hideArticle(id, hidden);
        redirect.addFlashAttribute("success", hidden ? "Article hidden" : "Article unhidden");
        return "redirect:/admin/news/articles/" + id;
    }

    @PostMapping("/articles/{id}/break-cluster")
    public String breakCluster(@PathVariable Long id, RedirectAttributes redirect) {
        adminService.breakCluster(id);
        redirect.addFlashAttribute("success",
                "Cluster link removed (note: may re-form on a future poll)");
        return "redirect:/admin/news/articles/" + id;
    }

    @PostMapping("/articles/{id}/refetch")
    public String refetchAndRetag(@PathVariable Long id, RedirectAttributes redirect) {
        adminService.refetchAndRetag(id);
        redirect.addFlashAttribute("success", "Article refetched and retagged");
        return "redirect:/admin/news/articles/" + id;
    }

    private static String redirectBack(String back, Long articleId) {
        return "detail".equals(back)
                ? "redirect:/admin/news/articles/" + articleId
                : "redirect:/admin/news/tagging";
    }
}
