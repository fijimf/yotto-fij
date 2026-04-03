# Quotes Feature Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a random inspirational quote banner to every page, backed by a database table seeded from `init-quotes.txt`, with admin CRUD management.

**Architecture:** A `Quote` JPA entity is stored in PostgreSQL; a `QuoteModelAdvice` (`@ControllerAdvice`) injects a random quote into every Thymeleaf model automatically; the quote renders in a banner strip in `layout/default.html` between the nav header and main content; an `AdminQuoteController` at `/admin/quotes` handles CRUD.

**Tech Stack:** Spring Boot 3.2.2, JPA/Hibernate, PostgreSQL 16, Flyway, Thymeleaf + Thymeleaf Layout Dialect, Java 17, Testcontainers (for integration tests)

---

## File Map

| Action | File | Responsibility |
|---|---|---|
| Create | `src/main/resources/db/migration/V10__quotes_schema.sql` | DDL for `quotes` table |
| Create | `src/main/java/com/yotto/basketball/entity/Quote.java` | JPA entity |
| Create | `src/main/java/com/yotto/basketball/repository/QuoteRepository.java` | Data access + random query |
| Create | `src/main/java/com/yotto/basketball/service/QuoteService.java` | Business logic wrapper |
| Create | `src/main/java/com/yotto/basketball/config/QuoteInitializer.java` | Seeds DB from `init-quotes.txt` on startup |
| Create | `src/main/java/com/yotto/basketball/controller/QuoteModelAdvice.java` | Injects `randomQuote` into every Thymeleaf model |
| Create | `src/main/java/com/yotto/basketball/controller/AdminQuoteController.java` | Admin CRUD controller |
| Create | `src/main/resources/templates/admin/quotes.html` | Admin list + add form |
| Create | `src/main/resources/templates/admin/quotes-edit.html` | Admin edit form |
| Modify | `src/main/resources/templates/layout/default.html` | Add quote banner between `<header>` and `<main>` |
| Modify | `src/main/resources/static/css/main.css` | Add `.quote-banner` styles |
| Modify | `src/main/resources/templates/admin/dashboard.html` | Add "Quotes" link to dashboard header |
| Create | `src/test/java/com/yotto/basketball/service/QuoteServiceTest.java` | Integration tests for service + repo |
| Create | `src/test/java/com/yotto/basketball/config/QuoteInitializerTest.java` | Integration tests for seeding logic |

---

## Task 1: DB Migration + Quote Entity

**Files:**
- Create: `src/main/resources/db/migration/V10__quotes_schema.sql`
- Create: `src/main/java/com/yotto/basketball/entity/Quote.java`

- [ ] **Step 1: Write the Flyway migration**

Create `src/main/resources/db/migration/V10__quotes_schema.sql`:

```sql
CREATE TABLE quotes (
    id          BIGSERIAL PRIMARY KEY,
    quote_text  TEXT         NOT NULL,
    attribution VARCHAR(500) NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT true
);
```

- [ ] **Step 2: Write the Quote entity**

Create `src/main/java/com/yotto/basketball/entity/Quote.java`:

```java
package com.yotto.basketball.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Objects;

@Entity
@Table(name = "quotes")
public class Quote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(columnDefinition = "text", nullable = false)
    private String quoteText;

    @NotBlank
    @Column(length = 500, nullable = false)
    private String attribution;

    @NotNull
    @Column(nullable = false)
    private Boolean active = true;

    public Quote() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getQuoteText() { return quoteText; }
    public void setQuoteText(String quoteText) { this.quoteText = quoteText; }

    public String getAttribution() { return attribution; }
    public void setAttribution(String attribution) { this.attribution = attribution; }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Quote quote = (Quote) o;
        return Objects.equals(id, quote.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V10__quotes_schema.sql \
        src/main/java/com/yotto/basketball/entity/Quote.java
git commit -m "feat: add Quote entity and V10 migration"
```

---

## Task 2: QuoteRepository + QuoteService + Tests

**Files:**
- Create: `src/main/java/com/yotto/basketball/repository/QuoteRepository.java`
- Create: `src/main/java/com/yotto/basketball/service/QuoteService.java`
- Create: `src/test/java/com/yotto/basketball/service/QuoteServiceTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/yotto/basketball/service/QuoteServiceTest.java`:

```java
package com.yotto.basketball.service;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Quote;
import com.yotto.basketball.repository.QuoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class QuoteServiceTest extends BaseIntegrationTest {

    @Autowired QuoteService quoteService;
    @Autowired QuoteRepository quoteRepository;

    @BeforeEach
    void setUp() {
        quoteRepository.deleteAll();
    }

    @Test
    void getRandomQuote_returnsEmptyWhenNoActiveQuotes() {
        assertThat(quoteService.getRandomQuote()).isEmpty();
    }

    @Test
    void getRandomQuote_returnsQuoteWhenOneExists() {
        Quote q = new Quote();
        q.setQuoteText("Anger is an energy.");
        q.setAttribution("\"Rise\" - Public Image Ltd.");
        q.setActive(true);
        quoteRepository.save(q);

        Optional<Quote> result = quoteService.getRandomQuote();

        assertThat(result).isPresent();
        assertThat(result.get().getQuoteText()).isEqualTo("Anger is an energy.");
    }

    @Test
    void getRandomQuote_doesNotReturnInactiveQuote() {
        Quote q = new Quote();
        q.setQuoteText("Inactive quote");
        q.setAttribution("Nobody");
        q.setActive(false);
        quoteRepository.save(q);

        assertThat(quoteService.getRandomQuote()).isEmpty();
    }

    @Test
    void save_persistsNewQuote() {
        Quote q = new Quote();
        q.setQuoteText("We need the funk.");
        q.setAttribution("\"Give Up the Funk\" - Parliament");
        q.setActive(true);

        Quote saved = quoteService.save(q);

        assertThat(saved.getId()).isNotNull();
        assertThat(quoteRepository.findById(saved.getId())).isPresent();
    }

    @Test
    void deleteById_removesQuote() {
        Quote q = new Quote();
        q.setQuoteText("Space travel's in my blood.");
        q.setAttribution("\"Another Girl, Another Planet\" - The Only Ones");
        q.setActive(true);
        Quote saved = quoteRepository.save(q);

        quoteService.deleteById(saved.getId());

        assertThat(quoteRepository.findById(saved.getId())).isEmpty();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./mvnw test -Dtest=QuoteServiceTest -pl . 2>&1 | tail -20
```

Expected: compilation failure — `QuoteService` and `QuoteRepository` don't exist yet.

- [ ] **Step 3: Create QuoteRepository**

Create `src/main/java/com/yotto/basketball/repository/QuoteRepository.java`:

```java
package com.yotto.basketball.repository;

import com.yotto.basketball.entity.Quote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface QuoteRepository extends JpaRepository<Quote, Long> {

    @Query(value = "SELECT * FROM quotes WHERE active = true ORDER BY RANDOM() LIMIT 1", nativeQuery = true)
    Optional<Quote> findRandom();
}
```

- [ ] **Step 4: Create QuoteService**

Create `src/main/java/com/yotto/basketball/service/QuoteService.java`:

```java
package com.yotto.basketball.service;

import com.yotto.basketball.entity.Quote;
import com.yotto.basketball.repository.QuoteRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class QuoteService {

    private final QuoteRepository quoteRepository;

    public QuoteService(QuoteRepository quoteRepository) {
        this.quoteRepository = quoteRepository;
    }

    public Optional<Quote> getRandomQuote() {
        return quoteRepository.findRandom();
    }

    public List<Quote> findAll() {
        return quoteRepository.findAll();
    }

    public Optional<Quote> findById(Long id) {
        return quoteRepository.findById(id);
    }

    public Quote save(Quote quote) {
        return quoteRepository.save(quote);
    }

    public void deleteById(Long id) {
        quoteRepository.deleteById(id);
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
./mvnw test -Dtest=QuoteServiceTest -pl . 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`, 5 tests passed.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yotto/basketball/repository/QuoteRepository.java \
        src/main/java/com/yotto/basketball/service/QuoteService.java \
        src/test/java/com/yotto/basketball/service/QuoteServiceTest.java
git commit -m "feat: add QuoteRepository and QuoteService with tests"
```

---

## Task 3: QuoteInitializer + Test

The initializer reads `src/main/resources/init-quotes.txt` at startup. The file format is:
`'quote text with ''escaped'' quotes', 'attribution'` (optional third field is ignored).
It inserts all quotes only when the `quotes` table is empty.

**Files:**
- Create: `src/main/java/com/yotto/basketball/config/QuoteInitializer.java`
- Create: `src/test/java/com/yotto/basketball/config/QuoteInitializerTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/yotto/basketball/config/QuoteInitializerTest.java`:

```java
package com.yotto.basketball.config;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.repository.QuoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class QuoteInitializerTest extends BaseIntegrationTest {

    @Autowired QuoteInitializer quoteInitializer;
    @Autowired QuoteRepository quoteRepository;

    @BeforeEach
    void setUp() {
        quoteRepository.deleteAll();
    }

    @Test
    void run_seedsQuotesFromFileWhenTableIsEmpty() throws Exception {
        quoteInitializer.run(null);

        assertThat(quoteRepository.count()).isGreaterThan(0);
    }

    @Test
    void run_doesNotReseedWhenTableAlreadyHasRows() throws Exception {
        quoteInitializer.run(null);
        long countAfterFirst = quoteRepository.count();

        quoteInitializer.run(null);

        assertThat(quoteRepository.count()).isEqualTo(countAfterFirst);
    }

    @Test
    void run_parsesEscapedSingleQuotesCorrectly() throws Exception {
        quoteInitializer.run(null);

        // "Can I kick it? Yes you can." — no escaped quotes, straight check
        assertThat(quoteRepository.findAll())
                .anyMatch(q -> q.getQuoteText().contains("Can I kick it?"));
    }

    @Test
    void run_parsesQuoteWithEscapedSingleQuoteInText() throws Exception {
        quoteInitializer.run(null);

        // "I'm gonna tell you how it's gonna be." — has '' in source
        assertThat(quoteRepository.findAll())
                .anyMatch(q -> q.getQuoteText().contains("I'm gonna tell you"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./mvnw test -Dtest=QuoteInitializerTest -pl . 2>&1 | tail -20
```

Expected: compilation failure — `QuoteInitializer` doesn't exist yet.

- [ ] **Step 3: Create QuoteInitializer**

Create `src/main/java/com/yotto/basketball/config/QuoteInitializer.java`:

```java
package com.yotto.basketball.config;

import com.yotto.basketball.entity.Quote;
import com.yotto.basketball.repository.QuoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class QuoteInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(QuoteInitializer.class);
    // Matches a single-quoted value: '...' where '' is an escaped single quote inside
    private static final Pattern VALUE_PATTERN = Pattern.compile("'((?:[^']|'')*)'");

    private final QuoteRepository quoteRepository;

    public QuoteInitializer(QuoteRepository quoteRepository) {
        this.quoteRepository = quoteRepository;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (quoteRepository.count() > 0) {
            return;
        }

        ClassPathResource resource = new ClassPathResource("init-quotes.txt");
        List<Quote> quotes = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                Matcher matcher = VALUE_PATTERN.matcher(line);
                String quoteText = null;
                String attribution = null;
                int matchCount = 0;

                while (matcher.find() && matchCount < 2) {
                    String value = matcher.group(1).replace("''", "'");
                    if (matchCount == 0) quoteText = value;
                    else attribution = value;
                    matchCount++;
                }

                if (quoteText != null && attribution != null) {
                    Quote quote = new Quote();
                    quote.setQuoteText(quoteText);
                    quote.setAttribution(attribution);
                    quote.setActive(true);
                    quotes.add(quote);
                }
            }
        }

        quoteRepository.saveAll(quotes);
        log.info("Seeded {} quotes from init-quotes.txt", quotes.size());
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./mvnw test -Dtest=QuoteInitializerTest -pl . 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`, 4 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yotto/basketball/config/QuoteInitializer.java \
        src/test/java/com/yotto/basketball/config/QuoteInitializerTest.java
git commit -m "feat: add QuoteInitializer to seed quotes from init-quotes.txt"
```

---

## Task 4: QuoteModelAdvice + Banner UI + CSS

**Files:**
- Create: `src/main/java/com/yotto/basketball/controller/QuoteModelAdvice.java`
- Modify: `src/main/resources/templates/layout/default.html`
- Modify: `src/main/resources/static/css/main.css`

- [ ] **Step 1: Create QuoteModelAdvice**

Create `src/main/java/com/yotto/basketball/controller/QuoteModelAdvice.java`:

```java
package com.yotto.basketball.controller;

import com.yotto.basketball.entity.Quote;
import com.yotto.basketball.service.QuoteService;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class QuoteModelAdvice {

    private final QuoteService quoteService;

    public QuoteModelAdvice(QuoteService quoteService) {
        this.quoteService = quoteService;
    }

    @ModelAttribute("randomQuote")
    public Quote randomQuote() {
        return quoteService.getRandomQuote().orElse(null);
    }
}
```

- [ ] **Step 2: Add the quote banner to the layout**

Edit `src/main/resources/templates/layout/default.html`. The current file is:

```html
<body hx-boost="true">

    <header class="site-header" th:replace="~{fragments/nav :: nav}"></header>

    <main layout:fragment="content">
```

Change it to:

```html
<body hx-boost="true">

    <header class="site-header" th:replace="~{fragments/nav :: nav}"></header>

    <div class="quote-banner" th:if="${randomQuote != null}">
        <span class="quote-banner__text" th:text="${randomQuote.quoteText}"></span>
        <span class="quote-banner__attribution" th:text="${randomQuote.attribution}"></span>
    </div>

    <main layout:fragment="content">
```

- [ ] **Step 3: Add CSS for the quote banner**

Append the following to `src/main/resources/static/css/main.css` (after the existing `/* ── Layout ── */` block, before `.nav`):

```css
/* ── Quote Banner ── */
.quote-banner {
    background: var(--color-bg-surface);
    border-bottom: 1px solid var(--color-border);
    padding: 0.4rem 1.5rem;
    text-align: center;
    font-size: 0.875rem;
}

.quote-banner__text {
    font-style: italic;
    color: var(--color-text);
}

.quote-banner__attribution {
    color: var(--color-text-muted);
    margin-left: 0.75rem;
    font-size: 0.8rem;
}
```

- [ ] **Step 4: Run the full test suite to confirm nothing broke**

```bash
./mvnw test 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Manual smoke test**

Start the app (`./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`) and navigate to any page. A quote should appear between the nav and the page content. Navigating to a different page should show a different (random) quote.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yotto/basketball/controller/QuoteModelAdvice.java \
        src/main/resources/templates/layout/default.html \
        src/main/resources/static/css/main.css
git commit -m "feat: add quote banner to all pages via QuoteModelAdvice"
```

---

## Task 5: Admin CRUD — Controller + Templates + Dashboard Link

**Files:**
- Create: `src/main/java/com/yotto/basketball/controller/AdminQuoteController.java`
- Create: `src/main/resources/templates/admin/quotes.html`
- Create: `src/main/resources/templates/admin/quotes-edit.html`
- Modify: `src/main/resources/templates/admin/dashboard.html`

- [ ] **Step 1: Create AdminQuoteController**

Create `src/main/java/com/yotto/basketball/controller/AdminQuoteController.java`:

```java
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
        if (quoteText == null || quoteText.isBlank() || attribution == null || attribution.isBlank()) {
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
                         RedirectAttributes redirectAttributes) {
        Quote quote = quoteService.findById(id).orElse(null);
        if (quote == null) {
            redirectAttributes.addFlashAttribute("error", "Quote not found");
            return "redirect:/admin/quotes";
        }
        if (quoteText == null || quoteText.isBlank() || attribution == null || attribution.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Quote text and attribution are required");
            return "redirect:/admin/quotes/" + id + "/edit";
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
```

- [ ] **Step 2: Create the admin quotes list/add template**

Create `src/main/resources/templates/admin/quotes.html`:

```html
<!DOCTYPE html>
<html lang="en"
      xmlns:th="http://www.thymeleaf.org"
      xmlns:layout="http://www.ultraq.net.nz/thymeleaf/layout"
      layout:decorate="~{layout/default}">
<head>
    <title>Manage Quotes</title>
</head>
<body>
<main layout:fragment="content">
    <div style="display: flex; align-items: center; justify-content: space-between; margin-bottom: 1.5rem;">
        <h1 class="page-title" style="margin-bottom: 0;">Manage Quotes</h1>
        <a href="/admin" class="btn btn--outline btn--sm">&#8592; Back to Dashboard</a>
    </div>

    <div th:if="${success}" class="alert alert--success" th:text="${success}"></div>
    <div th:if="${error}" class="alert alert--danger" th:text="${error}"></div>

    <!-- Add Quote -->
    <section class="admin-section">
        <div class="admin-section__header">
            <h2 class="admin-section__title">Add Quote</h2>
        </div>
        <div class="card">
            <form th:action="@{/admin/quotes}" method="post">
                <div style="display: flex; flex-direction: column; gap: 0.75rem;">
                    <textarea name="quoteText" placeholder="Quote text" class="form-input"
                              rows="2" required></textarea>
                    <input type="text" name="attribution" class="form-input" required
                           placeholder='Attribution (e.g. "Song Title" - Artist)' />
                    <div>
                        <button type="submit" class="btn btn--primary btn--sm">Add Quote</button>
                    </div>
                </div>
            </form>
        </div>
    </section>

    <!-- Quote List -->
    <section class="admin-section">
        <div class="admin-section__header">
            <h2 class="admin-section__title">All Quotes</h2>
        </div>
        <div class="card">
            <table>
                <thead>
                    <tr>
                        <th>Quote</th>
                        <th>Attribution</th>
                        <th>Active</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <tr th:each="quote : ${quotes}">
                        <td th:text="${#strings.abbreviate(quote.quoteText, 80)}">Quote text</td>
                        <td th:text="${quote.attribution}">Attribution</td>
                        <td th:text="${quote.active ? 'Yes' : 'No'}">Yes</td>
                        <td class="admin-actions">
                            <a th:href="@{/admin/quotes/{id}/edit(id=${quote.id})}"
                               class="btn btn--outline btn--sm">Edit</a>
                            <form th:action="@{/admin/quotes/{id}(id=${quote.id})}"
                                  method="post" class="inline-form">
                                <input type="hidden" name="_method" value="DELETE" />
                                <button type="submit" class="btn btn--danger btn--sm"
                                        onclick="return confirm('Delete this quote?')">Delete</button>
                            </form>
                        </td>
                    </tr>
                    <tr th:if="${#lists.isEmpty(quotes)}">
                        <td colspan="4" style="text-align: center; color: var(--color-text-muted);">
                            No quotes yet.
                        </td>
                    </tr>
                </tbody>
            </table>
        </div>
    </section>
</main>
</body>
</html>
```

- [ ] **Step 3: Create the admin quotes edit template**

Create `src/main/resources/templates/admin/quotes-edit.html`:

```html
<!DOCTYPE html>
<html lang="en"
      xmlns:th="http://www.thymeleaf.org"
      xmlns:layout="http://www.ultraq.net.nz/thymeleaf/layout"
      layout:decorate="~{layout/default}">
<head>
    <title>Edit Quote</title>
</head>
<body>
<main layout:fragment="content">
    <div style="display: flex; align-items: center; justify-content: space-between; margin-bottom: 1.5rem;">
        <h1 class="page-title" style="margin-bottom: 0;">Edit Quote</h1>
        <a href="/admin/quotes" class="btn btn--outline btn--sm">&#8592; Back to Quotes</a>
    </div>

    <div th:if="${error}" class="alert alert--danger" th:text="${error}"></div>

    <section class="admin-section">
        <div class="card">
            <form th:action="@{/admin/quotes/{id}(id=${quote.id})}" method="post">
                <div style="display: flex; flex-direction: column; gap: 0.75rem;">
                    <textarea name="quoteText" class="form-input" rows="3" required
                              th:text="${quote.quoteText}"></textarea>
                    <input type="text" name="attribution" class="form-input" required
                           th:value="${quote.attribution}" />
                    <label style="display: flex; align-items: center; gap: 0.5rem; cursor: pointer;">
                        <input type="checkbox" name="active" th:checked="${quote.active}" />
                        Active
                    </label>
                    <div>
                        <button type="submit" class="btn btn--primary btn--sm">Save</button>
                        <a href="/admin/quotes" class="btn btn--outline btn--sm"
                           style="margin-left: 0.5rem;">Cancel</a>
                    </div>
                </div>
            </form>
        </div>
    </section>
</main>
</body>
</html>
```

- [ ] **Step 4: Add Quotes link to the admin dashboard**

In `src/main/resources/templates/admin/dashboard.html`, find:

```html
    <div style="display: flex; align-items: center; justify-content: space-between; margin-bottom: 1.5rem;">
        <h1 class="page-title" style="margin-bottom: 0;">Admin Dashboard</h1>
        <a href="/admin/qa" class="btn btn--outline btn--sm">Scraping QA</a>
    </div>
```

Change it to:

```html
    <div style="display: flex; align-items: center; justify-content: space-between; margin-bottom: 1.5rem;">
        <h1 class="page-title" style="margin-bottom: 0;">Admin Dashboard</h1>
        <div style="display: flex; gap: 0.5rem;">
            <a href="/admin/quotes" class="btn btn--outline btn--sm">Quotes</a>
            <a href="/admin/qa" class="btn btn--outline btn--sm">Scraping QA</a>
        </div>
    </div>
```

- [ ] **Step 5: Run the full test suite**

```bash
./mvnw test 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Manual smoke test**

Start the app. Navigate to `/admin/quotes` (requires admin login). Verify:
- The seeded quotes list appears
- Adding a new quote works and shows a success flash
- Clicking Edit pre-fills the form; saving updates the quote
- Unchecking Active and saving means that quote no longer appears in the banner
- Delete removes the quote after confirmation

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/yotto/basketball/controller/AdminQuoteController.java \
        src/main/resources/templates/admin/quotes.html \
        src/main/resources/templates/admin/quotes-edit.html \
        src/main/resources/templates/admin/dashboard.html
git commit -m "feat: add admin CRUD for quotes"
```
