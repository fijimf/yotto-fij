# Quotes Feature Design

**Date:** 2026-04-03
**Status:** Approved

## Overview

Add a random inspirational quote banner to every page of the site. Quotes are stored in the database, seeded from `init-quotes.txt`, rotate on every navigation (via server-side rendering + hx-boost), and are manageable via an admin CRUD interface.

---

## Data Model

### `Quote` Entity

Table: `quotes`

| Column | Type | Constraints |
|---|---|---|
| `id` | bigserial | PK |
| `quote_text` | text | NOT NULL |
| `attribution` | varchar(500) | NOT NULL |
| `active` | boolean | NOT NULL, DEFAULT true |

The `active` flag allows soft-disabling quotes without deleting them.

### Flyway Migration

`V10__quotes_schema.sql` — creates the `quotes` table.

### Seeding

`QuoteInitializer` (`@Component` + `ApplicationRunner`) reads `src/main/resources/init-quotes.txt` at startup. If the `quotes` table is empty, it parses each line (format: `'quote_text', 'attribution'` with optional ignored third field) and inserts all quotes. Idempotent — does nothing if rows already exist.

The third field present on some lines in `init-quotes.txt` is ignored entirely.

---

## Backend

### `QuoteRepository`

JPA repository. One custom native query:

```java
@Query(value = "SELECT * FROM quotes WHERE active = true ORDER BY RANDOM() LIMIT 1", nativeQuery = true)
Optional<Quote> findRandom();
```

### `QuoteService`

Spring `@Service`. Methods:
- `getRandomQuote()` → `Optional<Quote>`
- `findAll()` → `List<Quote>`
- `findById(Long id)` → `Optional<Quote>`
- `save(Quote quote)` → `Quote`
- `deleteById(Long id)`

### `QuoteModelAdvice`

`@ControllerAdvice` with a `@ModelAttribute("randomQuote")` method. Called automatically before every web controller. Puts the result of `quoteService.getRandomQuote()` (or `null` if the table is empty) into every Thymeleaf model. No changes required to any existing controller.

### `AdminQuoteController`

`@Controller` at `/admin/quotes`. Follows the existing redirect-after-POST pattern.

| Method | Path | Action |
|---|---|---|
| GET | `/admin/quotes` | List all quotes; model includes `quotes` list and blank `Quote` form object |
| POST | `/admin/quotes` | Create new quote, redirect to `/admin/quotes` |
| GET | `/admin/quotes/{id}/edit` | Show edit form pre-filled with existing quote |
| POST | `/admin/quotes/{id}` | Update quote, redirect to `/admin/quotes` |
| DELETE | `/admin/quotes/{id}` | Delete quote (POST with hidden `_method=DELETE`), redirect to `/admin/quotes` |

Flash attributes carry `success` / `error` messages consistent with `AdminController` pattern.

---

## UI

### Quote Banner

Added to `layout/default.html` between `<header>` and `<main>`:

```html
<div class="quote-banner" th:if="${randomQuote != null}">
    <span class="quote-banner__text" th:text="${randomQuote.quoteText}"></span>
    <span class="quote-banner__attribution" th:text="${randomQuote.attribution}"></span>
</div>
```

Styled in `main.css` as a slim strip: muted background color, italic quote text, smaller muted attribution text. No animation (server-side render on each hx-boost navigation is sufficient).

### Admin Quotes Page (`admin/quotes.html`)

Follows the card/table pattern of `admin/dashboard.html`. Two sections:
1. **Table** — lists all quotes with columns: Quote, Attribution, Active, Actions (Edit / Delete)
2. **Add form** — two text inputs (Quote Text, Attribution) + submit button

### Admin Edit Page (`admin/quotes-edit.html`)

Standalone page with a pre-filled form for Quote Text and Attribution. Submit POSTs to `/admin/quotes/{id}`. Cancel link returns to `/admin/quotes`.

### Admin Dashboard Link

A link to `/admin/quotes` added to the dashboard header area alongside the existing "Scraping QA" button.

---

## Error Handling

- If no active quotes exist, `randomQuote` model attribute is `null` and the banner renders nothing (no error).
- `AdminQuoteController` uses `EntityNotFoundException` for unknown IDs → 404, consistent with `GlobalExceptionHandler`.
- Validation: `@NotBlank` on `quoteText` and `attribution`; create/update failures redirect back with a flash error message (consistent with existing admin pattern — no inline `BindingResult` form re-render).

---

## Testing

- Integration test for `QuoteInitializer` verifying rows are inserted when table is empty and not re-inserted on second run.
- Integration test for `QuoteService.getRandomQuote()` verifying it returns a quote when data exists and empty Optional when table is empty.
- No controller tests needed beyond what manual admin QA covers (consistent with existing test approach).

---

## Out of Scope

- Team-specific quote filtering (the third field in `init-quotes.txt` is ignored)
- Quote transition animations
- Pagination on the admin quotes list (not needed at current scale)
