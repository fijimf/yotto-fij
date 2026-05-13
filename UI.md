# Yotto FIJ — UI Documentation

## Tech Stack

| Layer | Technology | Purpose |
|-------|-----------|---------|
| **Templating** | [Thymeleaf](https://www.thymeleaf.org/) | Server-side HTML rendering via Spring Boot integration |
| **Interactivity** | [HTMX](https://htmx.org/) | AJAX requests, partial page updates, SPA-like feel without a JS framework |
| **Styling** | Hand-written CSS | Custom styles, no utility framework |
| **Server** | Spring Boot `@Controller` | Returns Thymeleaf view names (distinct from `@RestController` API endpoints) |
| **Static assets** | Spring Boot static resource handling | Served from `src/main/resources/static/` |

### Dependencies to Add

Thymeleaf is not yet in `pom.xml`. When building out the UI, add:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-thymeleaf</artifactId>
</dependency>
<dependency>
    <groupId>nz.net.ultraq.thymeleaf</groupId>
    <artifactId>thymeleaf-layout-dialect</artifactId>
</dependency>
```

HTMX is loaded via CDN `<script>` tag in the layout — no server-side dependency required.

---

## Architecture

### Directory Structure

```
src/main/resources/
├── static/
│   ├── css/
│   │   └── main.css          # Global styles
│   ├── js/
│   │   └── app.js            # Minimal JS (HTMX handles most interactivity)
│   └── index.html            # Static landing page (pre-Thymeleaf fallback)
└── templates/
    ├── layout/
    │   └── default.html       # Base layout (nav, footer, HTMX/CSS imports)
    ├── fragments/
    │   ├── nav.html           # Navigation bar fragment
    │   └── footer.html        # Footer fragment
    ├── pages/
    │   ├── home.html          # Dashboard / landing
    │   ├── teams.html         # Team listing
    │   ├── team-detail.html   # Single team view
    │   ├── games.html         # Game listing / scoreboard
    │   ├── conferences.html   # Conference listing
    │   └── seasons.html       # Season listing
    └── components/
        ├── team-card.html     # Reusable team card (HTMX partial)
        ├── game-row.html      # Reusable game row (HTMX partial)
        └── search-box.html    # Search input with HTMX typeahead
```

### Controller Convention

Web controllers live alongside REST controllers but use `@Controller` (not `@RestController`) and return view names:

```java
@Controller
@RequestMapping("/teams")
public class TeamWebController {
    @GetMapping
    public String listTeams(Model model) {
        model.addAttribute("teams", teamService.findAll());
        return "pages/teams";       // resolves to templates/pages/teams.html
    }
}
```

Name web controllers `{Entity}WebController` to distinguish from REST controllers.

### HTMX Patterns

Use HTMX for all dynamic interactions. Prefer server-rendered HTML fragments over client-side JSON processing.

```html
<!-- Trigger a search on keyup, swap results into #results -->
<input type="search" name="name"
       hx-get="/teams/search"
       hx-trigger="keyup changed delay:300ms"
       hx-target="#results"
       hx-swap="innerHTML" />

<div id="results">
    <!-- Server returns rendered HTML fragment here -->
</div>
```

HTMX endpoints return **partial HTML** (a fragment), not full pages. Use a separate controller method or check for the `HX-Request` header:

```java
@GetMapping("/search")
public String searchTeams(@RequestParam String name, Model model) {
    model.addAttribute("teams", teamService.searchByName(name));
    return "components/team-card :: team-list";  // return fragment
}
```

---

## Branding

### Name & Identity

- **Product name:** Yotto FIJ
- **Tagline:** College Basketball Statistics
- **Domain:** fijimf.com

### Color Palette

| Token | Hex | Usage |
|-------|-----|-------|
| `--color-bg` | `#f4f6f8` | Page background |
| `--color-bg-surface` | `#ffffff` | Cards, panels, elevated surfaces |
| `--color-bg-surface-hover` | `#eef3f8` | Hovered cards/rows |
| `--color-primary` | `#b45309` | Primary accent — headings, buttons, links, active states |
| `--color-primary-hover` | `#92400e` | Hovered primary elements |
| `--color-text` | `#1e293b` | Body text |
| `--color-text-muted` | `#64748b` | Secondary text, labels, captions |
| `--color-text-inverse` | `#ffffff` | Text on primary-colored backgrounds |
| `--color-border` | `#dce3ea` | Subtle borders and dividers |
| `--color-success` | `#16a34a` | Win indicators, positive stats |
| `--color-danger` | `#dc2626` | Loss indicators, errors |
| `--color-nav-bg` | `#1e3a5f` | Navigation bar background |
| `--color-nav-text` | `#93b4d8` | Navigation link text |
| `--color-nav-text-active` | `#ffffff` | Active/hover navigation link text |
| `--color-nav-border` | `#2d5188` | Navigation bar border |

### Typography

- **Body font:** `Inter` (Google Fonts) — `var(--font-family)`. Excellent tabular figures for data tables.
- **Display font:** `Barlow Condensed` (Google Fonts, weights 600–800) — `var(--font-display)`. Used for all headings, page titles, team names, score numbers, section headers, and the nav brand.
- **Body:** 1rem base, `--color-text`, `line-height: 1.5`
- **Small/captions:** 0.85rem, `--color-text-muted`
- Apply `var(--font-display)` to any heading or large label. Never use it for table cell content or body copy.

### CSS Custom Properties

Define all tokens as CSS custom properties on `:root` in `main.css`:

```css
:root {
    --color-bg: #f4f6f8;
    --color-bg-surface: #ffffff;
    --color-bg-surface-hover: #eef3f8;
    --color-primary: #b45309;
    --color-primary-hover: #92400e;
    --color-primary-rgb: 180, 83, 9;
    --color-text: #1e293b;
    --color-text-muted: #64748b;
    --color-text-inverse: #ffffff;
    --color-border: #dce3ea;
    --color-success: #16a34a;
    --color-danger: #dc2626;

    --color-nav-bg: #1e3a5f;
    --color-nav-text: #93b4d8;
    --color-nav-text-active: #ffffff;
    --color-nav-border: #2d5188;

    --font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
    --font-display: 'Barlow Condensed', sans-serif;
    --radius: 8px;
    --shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}
```

Always reference tokens (`var(--color-primary)`) rather than raw hex values in component styles.

---

## Guidelines

### General Rules

1. **Light theme (public), dark theme (admin)** — public pages use a light color scheme; `/admin` routes use `[data-theme="dark"]` and retain the dark palette. Do not add a user-facing mode toggle.
2. **Server-rendered first** — pages load fully rendered. HTMX enhances with partial updates.
3. **No JS frameworks** — no React, Vue, Angular, etc. HTMX + vanilla JS covers all needs.
4. **No CSS frameworks** — no Tailwind, Bootstrap, etc. Write semantic CSS using the design tokens above.
5. **Mobile responsive** — all pages must work on mobile. Use CSS grid/flexbox, not fixed widths.

### HTML & Thymeleaf

- Use Thymeleaf natural templating: templates should render as valid static HTML when opened directly.
- Prefer `th:each`, `th:text`, `th:if` over complex inline expressions.
- Use layout dialect for page inheritance: `layout:decorate="~{layout/default}"`.
- Extract repeated markup into `fragments/` and include with `th:replace`.

### HTMX

- Load HTMX from CDN in the base layout: `<script src="https://unpkg.com/htmx.org@2.0.4"></script>`
- Use `hx-boost="true"` on nav links for SPA-style page transitions.
- Add `hx-indicator` for loading states on slower requests.
- Return fragment HTML from HTMX endpoints, never JSON.
- Use `hx-swap="innerHTML"` as the default swap strategy.

### CSS

- One main stylesheet (`static/css/main.css`) loaded in the base layout.
- Use BEM-like naming: `.team-card`, `.team-card__name`, `.team-card--highlighted`.
- Keep selectors flat — avoid nesting deeper than two levels.
- Use `var(--token)` for all colors, radii, and shadows.

### Accessibility

- All images need `alt` text.
- Use semantic HTML (`<nav>`, `<main>`, `<article>`, `<table>`) over generic `<div>`.
- Interactive elements must be keyboard-navigable.
- Maintain sufficient contrast ratios (the palette above is designed for WCAG AA on dark backgrounds).
