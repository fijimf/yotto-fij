# Light Theme Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the public-facing site from a dark to a light theme (light grey body, dark navy nav, blue accent) while keeping the admin panel dark.

**Architecture:** All color tokens live in a single `:root` block in `main.css`. A new `[data-theme="dark"]` selector block restores the dark tokens for admin. `default.html` gets a Thymeleaf `th:attr` that sets `data-theme="dark"` on any `/admin` path. No new files are created.

**Tech Stack:** CSS custom properties, Thymeleaf `th:attr`, Spring MVC `#request` expression object.

**Spec:** `docs/superpowers/specs/2026-04-10-light-theme-design.md`

---

## Files Changed

| File | Change |
|------|--------|
| `src/main/resources/static/css/main.css` | Tasks 1–6: tokens, dark override, nav styles, alerts/badges, game badges, comp-rankings |
| `src/main/resources/templates/layout/default.html` | Task 7: `th:attr` on `<html>` |
| `UI.md` | Task 8: update color palette table |

---

## Task 1: Update `:root` color tokens and add nav tokens

**Files:**
- Modify: `src/main/resources/static/css/main.css:1-16`

- [ ] **Step 1: Replace the entire `:root` block**

  Open `src/main/resources/static/css/main.css`. The current `:root` block starts at line 1 and ends at line 16. Replace it entirely:

  ```css
  :root {
      --color-bg: #f4f6f8;
      --color-bg-surface: #ffffff;
      --color-bg-surface-hover: #eef3f8;
      --color-primary: #3b82f6;
      --color-primary-hover: #2563eb;
      --color-primary-rgb: 59, 130, 246;
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

      --font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      --radius: 8px;
      --shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  }
  ```

- [ ] **Step 2: Commit**

  ```bash
  git add src/main/resources/static/css/main.css
  git commit -m "style: update :root color tokens to light theme"
  ```

---

## Task 2: Add `[data-theme="dark"]` override block

**Files:**
- Modify: `src/main/resources/static/css/main.css` — insert after the `:root` block (after line 16 in original, after the closing `}` of the block edited in Task 1)

- [ ] **Step 1: Insert the dark override block immediately after the closing `}` of `:root`**

  Add this block between `:root { ... }` and `/* ── Reset ── */`:

  ```css
  /* ── Admin dark theme override ── */
  [data-theme="dark"] {
      --color-bg: #1a1a2e;
      --color-bg-surface: #16213e;
      --color-bg-surface-hover: #1f3056;
      --color-primary: #9393a3;
      --color-primary-hover: #b3b3d3;
      --color-primary-rgb: 147, 147, 163;
      --color-text: #e0e0e0;
      --color-text-muted: #a0a0b0;
      --color-text-inverse: #1a1a2e;
      --color-border: #2a2a4a;
      --color-success: #2ecc71;
      --color-danger: #e74c3c;

      --color-nav-bg: #16213e;
      --color-nav-text: #a0a0b0;
      --color-nav-text-active: #9393a3;
      --color-nav-border: #2a2a4a;

      --shadow: 0 2px 8px rgba(0, 0, 0, 0.3);
  }
  ```

- [ ] **Step 2: Commit**

  ```bash
  git add src/main/resources/static/css/main.css
  git commit -m "style: add [data-theme=dark] override block for admin"
  ```

---

## Task 3: Update nav and header component styles to use nav tokens

**Files:**
- Modify: `src/main/resources/static/css/main.css` — nav-related rules

The nav currently uses `--color-bg-surface` (now white) and `--color-text-muted` (now `#64748b`). The dark navy nav requires its own tokens added in Task 1.

- [ ] **Step 1: Update `.site-header`**

  Find:
  ```css
  .site-header {
      background: var(--color-bg-surface);
      border-bottom: 1px solid var(--color-border);
  ```
  Replace with:
  ```css
  .site-header {
      background: var(--color-nav-bg);
      border-bottom: 1px solid var(--color-nav-border);
  ```

- [ ] **Step 2: Update `.quote-banner`**

  Find:
  ```css
  .quote-banner {
      background: var(--color-bg-surface);
      border-top: 1px solid var(--color-primary);
      border-bottom: 1px solid var(--color-border);
  ```
  Replace with:
  ```css
  .quote-banner {
      background: var(--color-nav-bg);
      border-top: 1px solid var(--color-primary);
      border-bottom: 1px solid var(--color-nav-border);
  ```

- [ ] **Step 3: Update `.quote-banner__text` and `.quote-banner__attribution`**

  Find:
  ```css
  .quote-banner__text {
      font-style: italic;
      color: var(--color-primary-hover);
  }
  ```
  Replace with:
  ```css
  .quote-banner__text {
      font-style: italic;
      color: var(--color-nav-text);
  }
  ```

  Find:
  ```css
  .quote-banner__attribution {
      color: var(--color-text-muted);
  ```
  Replace with:
  ```css
  .quote-banner__attribution {
      color: var(--color-nav-text);
  ```

- [ ] **Step 4: Update `.nav__brand`**

  Find:
  ```css
  .nav__brand {
      font-size: 1.25rem;
      font-weight: 700;
      color: var(--color-primary);
  ```
  Replace with:
  ```css
  .nav__brand {
      font-size: 1.25rem;
      font-weight: 700;
      color: var(--color-nav-text-active);
  ```

- [ ] **Step 5: Update `.nav__link` and hover/active states**

  Find:
  ```css
  .nav__link {
      color: var(--color-text-muted);
  ```
  Replace with:
  ```css
  .nav__link {
      color: var(--color-nav-text);
  ```

  Find:
  ```css
  .nav__link:hover {
      color: var(--color-text);
      background: var(--color-bg-surface-hover);
  }
  ```
  Replace with:
  ```css
  .nav__link:hover {
      color: var(--color-nav-text-active);
      background: rgba(255, 255, 255, 0.1);
  }
  ```

  Find:
  ```css
  .nav__link--active {
      color: var(--color-primary);
  }
  ```
  Replace with:
  ```css
  .nav__link--active {
      color: var(--color-nav-text-active);
  }
  ```

- [ ] **Step 6: Update `.nav__toggle`**

  Find:
  ```css
  .nav__toggle {
      display: none;
      background: none;
      border: none;
      color: var(--color-text);
  ```
  Replace with:
  ```css
  .nav__toggle {
      display: none;
      background: none;
      border: none;
      color: var(--color-nav-text-active);
  ```

- [ ] **Step 7: Update responsive `.nav__links` dropdown**

  In the `@media (max-width: 768px)` block, find:
  ```css
      .nav__links {
          display: none;
          position: absolute;
          top: 3.5rem;
          left: 0;
          right: 0;
          flex-direction: column;
          background: var(--color-bg-surface);
          border-bottom: 1px solid var(--color-border);
  ```
  Replace with:
  ```css
      .nav__links {
          display: none;
          position: absolute;
          top: 3.5rem;
          left: 0;
          right: 0;
          flex-direction: column;
          background: var(--color-nav-bg);
          border-bottom: 1px solid var(--color-nav-border);
  ```

- [ ] **Step 8: Commit**

  ```bash
  git add src/main/resources/static/css/main.css
  git commit -m "style: update nav and header components to use nav tokens"
  ```

---

## Task 4: Fix hardcoded colors in alerts, status badges, and buttons

**Files:**
- Modify: `src/main/resources/static/css/main.css:309-427`

These components have hardcoded rgba values tuned for dark backgrounds. The opacity and hue values need adjusting for a white surface.

- [ ] **Step 1: Update button hover colors**

  Find:
  ```css
  .btn--danger:hover {
      background: #c0392b;
  }
  ```
  Replace with:
  ```css
  .btn--danger:hover {
      background: #b91c1c;
  }
  ```

  Find:
  ```css
  .btn--success:hover {
      background: #27ae60;
  }
  ```
  Replace with:
  ```css
  .btn--success:hover {
      background: #15803d;
  }
  ```

- [ ] **Step 2: Update alert backgrounds**

  Find:
  ```css
  .alert--danger {
      background: rgba(231, 76, 60, 0.15);
      border: 1px solid var(--color-danger);
      color: var(--color-danger);
  }
  ```
  Replace with:
  ```css
  .alert--danger {
      background: rgba(220, 38, 38, 0.08);
      border: 1px solid var(--color-danger);
      color: var(--color-danger);
  }
  ```

  Find:
  ```css
  .alert--success {
      background: rgba(46, 204, 113, 0.15);
      border: 1px solid var(--color-success);
      color: var(--color-success);
  }
  ```
  Replace with:
  ```css
  .alert--success {
      background: rgba(22, 163, 74, 0.08);
      border: 1px solid var(--color-success);
      color: var(--color-success);
  }
  ```

  Find:
  ```css
  .alert--warning {
      background: rgba(241, 196, 15, 0.15);
      border: 1px solid #f1c40f;
      color: #f1c40f;
  }
  ```
  Replace with:
  ```css
  .alert--warning {
      background: rgba(217, 119, 6, 0.08);
      border: 1px solid #d97706;
      color: #d97706;
  }
  ```

- [ ] **Step 3: Update status badge colors**

  Find:
  ```css
  .status-badge--running {
      background: rgba(52, 152, 219, 0.2);
      color: #3498db;
  }
  ```
  Replace with:
  ```css
  .status-badge--running {
      background: rgba(59, 130, 246, 0.12);
      color: #2563eb;
  }
  ```

  Find:
  ```css
  .status-badge--completed {
      background: rgba(46, 204, 113, 0.2);
      color: var(--color-success);
  }
  ```
  Replace with:
  ```css
  .status-badge--completed {
      background: rgba(22, 163, 74, 0.12);
      color: var(--color-success);
  }
  ```

  Find:
  ```css
  .status-badge--failed {
      background: rgba(231, 76, 60, 0.2);
      color: var(--color-danger);
  }
  ```
  Replace with:
  ```css
  .status-badge--failed {
      background: rgba(220, 38, 38, 0.12);
      color: var(--color-danger);
  }
  ```

  Find:
  ```css
  .status-badge--partial {
      background: rgba(241, 196, 15, 0.2);
      color: #f1c40f;
  }
  ```
  Replace with:
  ```css
  .status-badge--partial {
      background: rgba(217, 119, 6, 0.12);
      color: #d97706;
  }
  ```

- [ ] **Step 4: Commit**

  ```bash
  git add src/main/resources/static/css/main.css
  git commit -m "style: update alert, badge, and button colors for light theme"
  ```

---

## Task 5: Fix game badges, QA badges, and prediction chips

**Files:**
- Modify: `src/main/resources/static/css/main.css` — lines ~928, ~1189, ~1197, ~1591, ~1647

These components use dark-specific inverted colors (dark green/red backgrounds with light text) that look wrong on a white surface.

- [ ] **Step 1: Update QA page badges**

  Find:
  ```css
  .qa-badge--ok {
      background: rgba(46, 204, 113, 0.15);
      color: var(--color-success);
      border: 1px solid rgba(46, 204, 113, 0.3);
  }
  
  .qa-badge--warn {
      background: rgba(243, 156, 18, 0.15);
      color: #f39c12;
      border: 1px solid rgba(243, 156, 18, 0.3);
  }
  ```
  Replace with:
  ```css
  .qa-badge--ok {
      background: rgba(22, 163, 74, 0.08);
      color: var(--color-success);
      border: 1px solid rgba(22, 163, 74, 0.3);
  }
  
  .qa-badge--warn {
      background: rgba(217, 119, 6, 0.08);
      color: #d97706;
      border: 1px solid rgba(217, 119, 6, 0.3);
  }
  ```

- [ ] **Step 2: Update edge chip and ML badge**

  Find:
  ```css
  .edge-chip--high { background: #065f46; color: #d1fae5; }
  ```
  Replace with:
  ```css
  .edge-chip--high { background: #d1fae5; color: #065f46; }
  ```

  Find:
  ```css
  .ml-badge {
      display: inline-block;
      padding: 1px 4px;
      border-radius: 3px;
      background: #3b1fa8;
      color: #c4b5fd;
  ```
  Replace with:
  ```css
  .ml-badge {
      display: inline-block;
      padding: 1px 4px;
      border-radius: 3px;
      background: #ede9fe;
      color: #6d28d9;
  ```

- [ ] **Step 3: Update game status badges**

  Find:
  ```css
  .game-status-badge--final { background: #14532d; color: #86efac; border-color: #14532d; }
  .game-status-badge--scheduled { background: #1e3a5f; color: #93c5fd; border-color: #1e3a5f; }
  ```
  Replace with:
  ```css
  .game-status-badge--final { background: #dcfce7; color: #15803d; border-color: #16a34a; }
  .game-status-badge--scheduled { background: #dbeafe; color: #1d4ed8; border-color: #3b82f6; }
  ```

- [ ] **Step 4: Update game detail outcome badges**

  Find:
  ```css
  .game-detail-outcome--win { background: #14532d; color: #86efac; }
  .game-detail-outcome--loss { background: #7f1d1d; color: #fca5a5; }
  ```
  Replace with:
  ```css
  .game-detail-outcome--win { background: #dcfce7; color: #15803d; }
  .game-detail-outcome--loss { background: #fee2e2; color: #b91c1c; }
  ```

- [ ] **Step 5: Commit**

  ```bash
  git add src/main/resources/static/css/main.css
  git commit -m "style: update game badges, QA badges, and prediction chips for light theme"
  ```

---

## Task 6: Fix comprehensive rankings hardcoded dark colors

**Files:**
- Modify: `src/main/resources/static/css/main.css:1907-2006`

The comprehensive rankings table has many hardcoded dark colors for its column-group header rows, sticky column fallbacks, and body text. These need light-theme equivalents that preserve the color-coded group identity (blue=identity, green=scoring, purple=ratings).

- [ ] **Step 1: Update sticky column background fallbacks**

  Find:
  ```css
  .comp-rankings-table th:nth-child(1),
  .comp-rankings-table td:nth-child(1) {
      position: sticky;
      left: 0;
      z-index: 2;
      background: var(--color-bg, #0f0f1a);
  }
  .comp-rankings-table th:nth-child(2),
  .comp-rankings-table td:nth-child(2) {
      position: sticky;
      left: 32px;
      z-index: 2;
      background: var(--color-bg, #0f0f1a);
  }
  .comp-rankings-table tbody tr:hover td:nth-child(1),
  .comp-rankings-table tbody tr:hover td:nth-child(2) {
      background: var(--color-surface-hover, #16162a);
  }
  ```
  Replace with:
  ```css
  .comp-rankings-table th:nth-child(1),
  .comp-rankings-table td:nth-child(1) {
      position: sticky;
      left: 0;
      z-index: 2;
      background: var(--color-bg, #f4f6f8);
  }
  .comp-rankings-table th:nth-child(2),
  .comp-rankings-table td:nth-child(2) {
      position: sticky;
      left: 32px;
      z-index: 2;
      background: var(--color-bg, #f4f6f8);
  }
  .comp-rankings-table tbody tr:hover td:nth-child(1),
  .comp-rankings-table tbody tr:hover td:nth-child(2) {
      background: var(--color-bg-surface-hover, #eef3f8);
  }
  ```

- [ ] **Step 2: Update column group header rows (identity, record, scoring, ratings)**

  Find:
  ```css
  .comp-rankings__g-identity {
      background: #131324;
      border-color: #1a1a2e;
      color: #5050a0;
      text-align: left;
  }
  .comp-rankings__g-record {
      background: #161630;
      border-color: #2a2a6a;
      color: #7070b8;
  }
  .comp-rankings__g-scoring {
      background: #152a1a;
      border-color: #1a5a2a;
      color: #507050;
  }
  .comp-rankings__g-ratings {
      background: #2a1a30;
      border-color: #5a2a6a;
      color: #8050a0;
  }
  ```
  Replace with:
  ```css
  .comp-rankings__g-identity {
      background: #eff6ff;
      border-color: #bfdbfe;
      color: #3730a3;
      text-align: left;
  }
  .comp-rankings__g-record {
      background: #f0f9ff;
      border-color: #bae6fd;
      color: #0369a1;
  }
  .comp-rankings__g-scoring {
      background: #f0fdf4;
      border-color: #bbf7d0;
      color: #15803d;
  }
  .comp-rankings__g-ratings {
      background: #faf5ff;
      border-color: #e9d5ff;
      color: #7c3aed;
  }
  ```

- [ ] **Step 3: Update sortable column header row**

  Find:
  ```css
      border-bottom: 1px solid #2a2a44;
      cursor: pointer;
      user-select: none;
      color: #8080b0;
  }
  .comp-rankings-col-row th:hover { color: #c0c0f0; }
  .comp-rankings__col--sort-active { color: #a0a0f0 !important; }
  ```
  Replace with:
  ```css
      border-bottom: 1px solid #e2e8f0;
      cursor: pointer;
      user-select: none;
      color: #64748b;
  }
  .comp-rankings-col-row th:hover { color: #334155; }
  .comp-rankings__col--sort-active { color: #3b82f6 !important; }
  ```

- [ ] **Step 4: Update rank and conf column colors**

  Find:
  ```css
  .comp-rankings__col--rank { width: 32px; color: #5060a0; }
  .comp-rankings__cell--rank { color: #5060a0; text-align: right; }
  ```
  Replace with:
  ```css
  .comp-rankings__col--rank { width: 32px; color: #94a3b8; }
  .comp-rankings__cell--rank { color: #94a3b8; text-align: right; }
  ```

  Find:
  ```css
  .comp-rankings__cell--conf { text-align: left; color: #8080b0; }
  ```
  Replace with:
  ```css
  .comp-rankings__cell--conf { text-align: left; color: #64748b; }
  ```

- [ ] **Step 5: Update body row border, hover, and text color**

  Find:
  ```css
  .comp-rankings-table tbody tr {
      border-bottom: 1px solid #18182e;
  }
  .comp-rankings-table tbody tr:hover { background: var(--color-surface-hover, #16162a); }
  .comp-rankings-table tbody td {
      padding: 5px 7px;
      text-align: right;
      color: #c8c8dc;
      font-variant-numeric: tabular-nums;
  }
  ```
  Replace with:
  ```css
  .comp-rankings-table tbody tr {
      border-bottom: 1px solid #f1f5f9;
  }
  .comp-rankings-table tbody tr:hover { background: var(--color-bg-surface-hover, #eef3f8); }
  .comp-rankings-table tbody td {
      padding: 5px 7px;
      text-align: right;
      color: #334155;
      font-variant-numeric: tabular-nums;
  }
  ```

- [ ] **Step 6: Update group cell tints (body cells and column headers)**

  Find:
  ```css
  .comp-rankings__g-record-td   { background: rgba(22,22,48,0.25); }
  .comp-rankings__g-scoring-td  { background: rgba(21,42,26,0.25); }
  .comp-rankings__g-ratings-td  { background: rgba(42,26,48,0.25); }
  ```
  Replace with:
  ```css
  .comp-rankings__g-record-td   { background: rgba(186, 230, 253, 0.2); }
  .comp-rankings__g-scoring-td  { background: rgba(187, 247, 208, 0.2); }
  .comp-rankings__g-ratings-td  { background: rgba(233, 213, 255, 0.2); }
  ```

  Find:
  ```css
  .comp-rankings__g-record-col   { background: rgba(22,22,48,0.5); }
  .comp-rankings__g-scoring-col  { background: rgba(21,42,26,0.5); }
  .comp-rankings__g-ratings-col  { background: rgba(42,26,48,0.5); }
  ```
  Replace with:
  ```css
  .comp-rankings__g-record-col   { background: rgba(186, 230, 253, 0.3); }
  .comp-rankings__g-scoring-col  { background: rgba(187, 247, 208, 0.3); }
  .comp-rankings__g-ratings-col  { background: rgba(233, 213, 255, 0.3); }
  ```

- [ ] **Step 7: Commit**

  ```bash
  git add src/main/resources/static/css/main.css
  git commit -m "style: update comprehensive rankings table colors for light theme"
  ```

---

## Task 7: Add `data-theme="dark"` to admin routes in layout template

**Files:**
- Modify: `src/main/resources/templates/layout/default.html:2-4`

- [ ] **Step 1: Add `th:attr` to the `<html>` element**

  Find:
  ```html
  <html lang="en"
        xmlns:th="http://www.thymeleaf.org"
        xmlns:layout="http://www.ultraq.net.nz/thymeleaf/layout">
  ```
  Replace with:
  ```html
  <html lang="en"
        th:attr="data-theme=${#request.requestURI.startsWith('/admin') ? 'dark' : null}"
        xmlns:th="http://www.thymeleaf.org"
        xmlns:layout="http://www.ultraq.net.nz/thymeleaf/layout">
  ```

  `#request` is Thymeleaf's standard expression object for `HttpServletRequest`. When the attribute value evaluates to `null`, Thymeleaf omits the attribute from the rendered HTML entirely — public pages will have no `data-theme` attribute and inherit `:root` (light) tokens.

- [ ] **Step 2: Commit**

  ```bash
  git add src/main/resources/templates/layout/default.html
  git commit -m "style: set data-theme=dark on admin routes via Thymeleaf"
  ```

---

## Task 8: Update UI.md color palette documentation

**Files:**
- Modify: `UI.md`

- [ ] **Step 1: Replace the color palette table and `:root` block**

  In `UI.md`, find the `### Color Palette` section. Replace the table:

  ```markdown
  ### Color Palette

  | Token | Hex | Usage |
  |-------|-----|-------|
  | `--color-bg` | `#f4f6f8` | Page background |
  | `--color-bg-surface` | `#ffffff` | Cards, panels, elevated surfaces |
  | `--color-bg-surface-hover` | `#eef3f8` | Hovered cards/rows |
  | `--color-primary` | `#3b82f6` | Primary accent — headings, buttons, links, active states |
  | `--color-primary-hover` | `#2563eb` | Hovered primary elements |
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
  ```

  Then find the `### CSS Custom Properties` code block and replace its `:root` example to match the new values:

  ```css
  :root {
      --color-bg: #f4f6f8;
      --color-bg-surface: #ffffff;
      --color-bg-surface-hover: #eef3f8;
      --color-primary: #3b82f6;
      --color-primary-hover: #2563eb;
      --color-primary-rgb: 59, 130, 246;
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

      --font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      --radius: 8px;
      --shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  }
  ```

  Also update the General Rules section — change:
  ```
  1. **Dark theme only** — the entire UI uses a dark color scheme. Do not add a light mode toggle.
  ```
  to:
  ```
  1. **Light theme (public), dark theme (admin)** — public pages use a light color scheme; `/admin` routes use `[data-theme="dark"]` and retain the dark palette. Do not add a user-facing mode toggle.
  ```

- [ ] **Step 2: Commit**

  ```bash
  git add UI.md
  git commit -m "docs: update UI.md color palette for light theme"
  ```

---

## Task 9: Build and smoke test

- [ ] **Step 1: Ensure Docker is running, then start the app**

  ```bash
  ./scripts/start-postgres.sh
  ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
  ```

- [ ] **Step 2: Verify public pages are light themed**

  Open `http://localhost:8080` in a browser. Check:
  - Page background is light grey (`#f4f6f8`), not dark navy
  - Navigation bar is dark navy (`#1e3a5f`)
  - Cards are white with a subtle border
  - Links and active nav items are blue (`#3b82f6`)
  - Win streaks are green, loss streaks are red

  Navigate to: Home, Teams, Rankings, Comprehensive Rankings, Predictions, a Team Detail page, a Game Detail page.

- [ ] **Step 3: Verify admin pages retain dark theme**

  Open `http://localhost:8080/admin` (login with admin credentials). Check:
  - Page background is dark navy (`#1a1a2e`)
  - Cards are dark (`#16213e`)
  - Status badges display correctly (dark backgrounds)

  Navigate to: Dashboard, Quotes.

- [ ] **Step 4: Run the test suite**

  ```bash
  ./mvnw test
  ```

  Expected: all tests pass (tests are integration tests against the database; CSS/template changes don't affect them, but this confirms nothing else broke).

- [ ] **Step 5: Commit if any last-minute fixes were needed**

  ```bash
  git add -p   # review and stage only what changed
  git commit -m "style: fix light theme regressions from smoke test"
  ```
  (Skip this step if no fixes were needed.)
