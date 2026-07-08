function initApp() {
    // Mobile nav toggle
    var toggle = document.querySelector(".nav__toggle");
    var links = document.querySelector(".nav__links");
    if (toggle && links) {
        toggle.addEventListener("click", function () {
            links.classList.toggle("nav__links--open");
        });
    }

    // Teams search
    var searchInput = document.getElementById("team-search");
    if (searchInput) {
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

// User menu (username → Profile / Sign out). Delegated on document so it
// keeps working after HTMX body swaps without stacking listeners.
document.addEventListener("click", function (e) {
    var dropdown = document.querySelector(".nav__dropdown");
    if (!dropdown) return;
    var toggle = e.target.closest(".nav__dropdown-toggle");
    if (toggle) {
        var open = dropdown.classList.toggle("nav__dropdown--open");
        toggle.setAttribute("aria-expanded", open ? "true" : "false");
    } else if (!e.target.closest(".nav__dropdown")) {
        dropdown.classList.remove("nav__dropdown--open");
        var t = dropdown.querySelector(".nav__dropdown-toggle");
        if (t) t.setAttribute("aria-expanded", "false");
    }
});

document.addEventListener("keydown", function (e) {
    if (e.key === "Escape") {
        var open = document.querySelector(".nav__dropdown--open");
        if (open) {
            open.classList.remove("nav__dropdown--open");
            var t = open.querySelector(".nav__dropdown-toggle");
            if (t) t.setAttribute("aria-expanded", "false");
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

// Re-run after HTMX swaps new content into the page
document.addEventListener("htmx:afterSettle", initApp);
