package com.yotto.basketball.controller;

import com.yotto.basketball.entity.Quote;
import com.yotto.basketball.service.QuoteService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/quotes")
public class AdminQuoteController {

    private final QuoteService quoteService;

    public AdminQuoteController(QuoteService quoteService) {
        this.quoteService = quoteService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("quotes", quoteService.findAll());
        return "admin/quotes";
    }

    @PostMapping
    public String create(@RequestParam String quoteText,
                         @RequestParam String attribution,
                         RedirectAttributes redirectAttributes) {
        if (quoteText.isBlank() || attribution.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Quote text and attribution are required");
            return "redirect:/admin/quotes";
        }
        Quote quote = new Quote();
        quote.setQuoteText(quoteText.trim());
        quote.setAttribution(attribution.trim());
        quote.setActive(true);
        quoteService.save(quote);
        redirectAttributes.addFlashAttribute("success", "Quote added");
        return "redirect:/admin/quotes";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        return quoteService.findById(id)
                .map(quote -> {
                    model.addAttribute("quote", quote);
                    return "admin/quotes-edit";
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("error", "Quote not found");
                    return "redirect:/admin/quotes";
                });
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @RequestParam String quoteText,
                         @RequestParam String attribution,
                         @RequestParam(defaultValue = "false") boolean active,
                         Model model,
                         RedirectAttributes redirectAttributes) {
        Quote quote = quoteService.findById(id).orElse(null);
        if (quote == null) {
            redirectAttributes.addFlashAttribute("error", "Quote not found");
            return "redirect:/admin/quotes";
        }
        if (quoteText.isBlank() || attribution.isBlank()) {
            quote.setQuoteText(quoteText);
            quote.setAttribution(attribution);
            quote.setActive(active);
            model.addAttribute("quote", quote);
            model.addAttribute("error", "Quote text and attribution are required");
            return "admin/quotes-edit";
        }
        quote.setQuoteText(quoteText.trim());
        quote.setAttribution(attribution.trim());
        quote.setActive(active);
        quoteService.save(quote);
        redirectAttributes.addFlashAttribute("success", "Quote updated");
        return "redirect:/admin/quotes";
    }

    @DeleteMapping("/{id}")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        if (quoteService.findById(id).isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Quote not found");
            return "redirect:/admin/quotes";
        }
        quoteService.deleteById(id);
        redirectAttributes.addFlashAttribute("success", "Quote deleted");
        return "redirect:/admin/quotes";
    }
}
