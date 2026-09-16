// This script lives inside <body>, and hx-boost re-executes it on every boosted
// navigation while `document` and `window` persist. Everything registered at
// document level below is therefore guarded so it binds exactly once; otherwise
// each navigation stacks another click handler and the nav dropdowns toggle
// open-then-closed (a page refresh "fixed" it by resetting to one listener).
// Per-element bindings in initApp are marked on the element for the same reason.
if (window.__deepfijAppLoaded) {
    // no-op: listeners from the first load still apply to the swapped-in body
} else {
    window.__deepfijAppLoaded = true;
    installApp();
}

function initApp() {
    // Mobile nav toggle
    var toggle = document.querySelector(".nav__toggle");
    var links = document.querySelector(".nav__links");
    if (toggle && links && !toggle.dataset.bound) {
        toggle.dataset.bound = "1";
        toggle.addEventListener("click", function () {
            links.classList.toggle("nav__links--open");
        });
    }

    // Teams search
    var searchInput = document.getElementById("team-search");
    if (searchInput && !searchInput.dataset.bound) {
        searchInput.dataset.bound = "1";
        var searchCount = document.getElementById("search-count");
        var noResults = document.getElementById("no-results");
        var groups = document.querySelectorAll(".conference-group");

        searchInput.addEventListener("input", function () {
            var query = this.value.toLowerCase().trim();
            var totalVisible = 0;

            groups.forEach(function (group) {
                var cards = group.querySelectorAll(".team-card");
                var groupVisible = 0;

                cards.forEach(function (card) {
                    var name = (card.dataset.name || "").toLowerCase();
                    var mascot = (card.dataset.mascot || "").toLowerCase();
                    var nickname = (card.dataset.nickname || "").toLowerCase();
                    var match = !query || name.indexOf(query) !== -1
                        || mascot.indexOf(query) !== -1
                        || nickname.indexOf(query) !== -1;
                    card.style.display = match ? "" : "none";
                    if (match) groupVisible++;
                });

                group.style.display = groupVisible > 0 ? "" : "none";
                totalVisible += groupVisible;
            });

            if (query) {
                searchCount.textContent = totalVisible + " result" + (totalVisible !== 1 ? "s" : "");
                noResults.style.display = totalVisible === 0 ? "" : "none";
            } else {
                searchCount.textContent = "";
                noResults.style.display = "none";
            }
        });
    }
}

// Nav dropdowns (section menus + user menu). Click-to-open; opening one closes
// its siblings; outside click or Esc closes everything. Delegated on document
// so it keeps working after HTMX body swaps (bound once via installApp). The
// same code drives the mobile accordion (menus render static there), which is
// how "one section open at a time" falls out for free.
function closeNavDropdowns(except) {
    document.querySelectorAll(".nav__dropdown--open").forEach(function (dd) {
        if (dd === except) return;
        dd.classList.remove("nav__dropdown--open");
        var t = dd.querySelector(".nav__dropdown-toggle");
        if (t) t.setAttribute("aria-expanded", "false");
    });
}

function installApp() {
document.addEventListener("click", function (e) {
    var toggle = e.target.closest(".nav__dropdown-toggle");
    if (toggle) {
        var dropdown = toggle.closest(".nav__dropdown");
        var open = dropdown.classList.toggle("nav__dropdown--open");
        toggle.setAttribute("aria-expanded", open ? "true" : "false");
        closeNavDropdowns(dropdown);
    } else if (!e.target.closest(".nav__dropdown")) {
        closeNavDropdowns(null);
    }
});

document.addEventListener("keydown", function (e) {
    if (e.key === "Escape") {
        var open = document.querySelector(".nav__dropdown--open");
        closeNavDropdowns(null);
        // Return focus to the toggle so keyboard users aren't stranded
        if (open) {
            var t = open.querySelector(".nav__dropdown-toggle");
            if (t) t.focus();
        }
    }
});

// Season tab active state — driven by HTMX requests (team detail page)
document.addEventListener('htmx:beforeRequest', function(evt) {
    if (evt.detail.elt.classList.contains('season-tabs__tab')) {
        document.querySelectorAll('.season-tabs__tab').forEach(function(t) {
            t.classList.remove('season-tabs__tab--active');
        });
        evt.detail.elt.classList.add('season-tabs__tab--active');
    }
});

// Run on initial page load
document.addEventListener("DOMContentLoaded", initApp);

// Re-run after HTMX swaps new content into the page (boosted navigations included)
document.addEventListener("htmx:afterSettle", initApp);
}
