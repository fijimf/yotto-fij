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

// Season tab switching (team detail page)
function switchSeasonTab(btn) {
    var season = btn.dataset.season;
    document.querySelectorAll('.season-tabs__tab').forEach(function(t) {
        t.classList.remove('season-tabs__tab--active');
    });
    btn.classList.add('season-tabs__tab--active');
    document.querySelectorAll('.season-panel').forEach(function(p) {
        p.style.display = 'none';
    });
    var panel = document.getElementById('season-' + season);
    if (panel) panel.style.display = '';
}

// Run on initial page load
document.addEventListener("DOMContentLoaded", initApp);

// Re-run after HTMX swaps new content into the page
document.addEventListener("htmx:afterSettle", initApp);
