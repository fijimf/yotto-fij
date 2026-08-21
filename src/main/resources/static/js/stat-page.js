/* Per-statistic page charts: a team-distribution histogram with a KDE overlay
 * plus the rankings team-finder. All numbers are pre-computed server-side
 * (StatPageService) — this file only draws them with D3. The game-outcome
 * scatter moved to the Predictor page (js/predictor-page.js). */
(function () {
    "use strict";

    var data = window.STAT_PAGE_DATA;
    if (!data) return;

    function cssVar(name) {
        return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    }

    var THEME = window.chartTheme();
    var AXIS = THEME.axis;
    var GRID = THEME.grid;

    // ── value formatting (mirrors StatFormat.java) ───────────────────────────────
    function formatValue(v, format) {
        if (v == null) return "—";
        switch (format) {
            case "PERCENT":
            case "RATE":
                return (v * 100).toFixed(1) + "%";
            case "RATING":
            case "PER_GAME":
                return v.toFixed(1);
            case "RATIO":
                return v.toFixed(2);
            default:
                return v.toFixed(2);
        }
    }
    function axisTickFormat(format) {
        return function (v) {
            if (format === "PERCENT" || format === "RATE") return (v * 100).toFixed(0) + "%";
            return (+v).toFixed(format === "RATIO" ? 2 : 1);
        };
    }

    // ── shared tooltip ───────────────────────────────────────────────────────────
    var tip = d3.select("body").append("div").attr("class", "stat-tooltip").style("opacity", 0);
    function showTip(html, event) {
        tip.html(html)
            .style("left", (event.pageX + 12) + "px")
            .style("top", (event.pageY - 12) + "px")
            .transition().duration(80).style("opacity", 1);
    }
    function hideTip() {
        tip.transition().duration(120).style("opacity", 0);
    }

    function clear(el) { el.innerHTML = ""; }

    // ── team distribution: histogram + KDE ───────────────────────────────────────
    function renderHistogram(el) {
        var hist = data.histogram;
        if (!hist || !hist.binCounts || hist.binCounts.length === 0) return;
        clear(el);

        var format = data.meta.format;
        var width = Math.max(280, el.clientWidth || 460);
        var height = 320;
        var margin = { top: 12, right: 14, bottom: 44, left: 40 };
        var w = width - margin.left - margin.right;
        var h = height - margin.top - margin.bottom;

        var svg = d3.select(el).append("svg")
            .attr("viewBox", "0 0 " + width + " " + height)
            .attr("width", "100%");
        var g = svg.append("g").attr("transform", "translate(" + margin.left + "," + margin.top + ")");

        var edges = hist.binEdges;
        var counts = hist.binCounts;
        var x = d3.scaleLinear().domain([edges[0], edges[edges.length - 1]]).range([0, w]);
        var yCount = d3.scaleLinear().domain([0, d3.max(counts)]).nice().range([h, 0]);
        var tf = axisTickFormat(format);

        g.append("g").attr("transform", "translate(0," + h + ")")
            .call(d3.axisBottom(x).ticks(6).tickFormat(tf))
            .call(function (sel) { sel.selectAll("line,path").attr("stroke", GRID); sel.selectAll("text").attr("fill", AXIS); });
        g.append("g")
            .call(d3.axisLeft(yCount).ticks(4))
            .call(function (sel) { sel.selectAll("line,path").attr("stroke", GRID); sel.selectAll("text").attr("fill", AXIS); });

        // KDE first so it sits *behind* the bars
        var kde = data.histogram.kde;
        if (kde && kde.x && kde.x.length > 1) {
            var yDens = d3.scaleLinear().domain([0, d3.max(kde.y)]).range([h, 0]);
            var line = d3.line()
                .x(function (_, i) { return x(kde.x[i]); })
                .y(function (_, i) { return yDens(kde.y[i]); })
                .curve(d3.curveBasis);
            g.append("path").datum(kde.y)
                .attr("fill", "none").attr("stroke", cssVar("--color-primary")).attr("stroke-width", 2)
                .attr("opacity", 0.85).attr("d", line);
        }

        // bars on top, semi-transparent so the curve shows through
        g.selectAll("rect.bar").data(counts).enter().append("rect")
            .attr("class", "bar")
            .attr("x", function (_, i) { return x(edges[i]) + 1; })
            .attr("width", function (_, i) { return Math.max(0, x(edges[i + 1]) - x(edges[i]) - 2); })
            .attr("y", function (d) { return yCount(d); })
            .attr("height", function (d) { return h - yCount(d); })
            .attr("fill", cssVar("--color-nav-bg")).attr("fill-opacity", 0.6)
            .on("mouseenter", function (event, d) {
                var i = counts.indexOf(d);
                showTip(formatValue(edges[i], format) + " – " + formatValue(edges[i + 1], format)
                    + "<br><strong>" + d + "</strong> team" + (d === 1 ? "" : "s"), event);
            })
            .on("mouseleave", hideTip);

        // mean marker
        var mean = data.population && data.population.mean;
        if (mean != null && mean >= edges[0] && mean <= edges[edges.length - 1]) {
            g.append("line").attr("x1", x(mean)).attr("x2", x(mean)).attr("y1", 0).attr("y2", h)
                .attr("stroke", AXIS).attr("stroke-dasharray", "4,3");
        }
    }

    function renderAll() {
        var histEl = document.getElementById("stat-histogram");
        if (histEl) renderHistogram(histEl);
    }

    // ── team finder: highlight matching rows and scroll the table to them ────────
    function initTeamFinder() {
        var input = document.getElementById("stat-team-find");
        var container = document.getElementById("stat-rank-container");
        if (!input || !container) return;

        var cycleIndex = 0; // which match Enter jumps to next

        function scrollToRow(row) {
            var wrap = container.querySelector(".schedule-table-wrap");
            if (!wrap) return;
            // tr.offsetTop is relative to the table, which starts at the top of
            // the scrollable wrap — center the row in view
            var target = row.offsetTop - wrap.clientHeight / 2 + row.offsetHeight / 2;
            wrap.scrollTo({ top: Math.max(0, target), behavior: "smooth" });
        }

        /** Toggle the hit class on every row; returns the matching rows in rank order. */
        function applyQuery() {
            var q = input.value.trim().toLowerCase();
            var matches = [];
            container.querySelectorAll("tbody tr").forEach(function (row) {
                var link = row.querySelector(".schedule-table__opp-link");
                var name = link ? link.textContent.trim().toLowerCase() : "";
                var hit = q !== "" && name.indexOf(q) !== -1;
                row.classList.toggle("stat-row--hit", hit);
                if (hit) matches.push(row);
            });
            return matches;
        }

        input.addEventListener("input", function () {
            cycleIndex = 0;
            var matches = applyQuery();
            if (matches.length > 0) scrollToRow(matches[0]);
        });
        // Enter jumps to the next match (wraps around)
        input.addEventListener("keydown", function (event) {
            if (event.key !== "Enter") return;
            event.preventDefault();
            var matches = applyQuery();
            if (matches.length === 0) return;
            cycleIndex = (cycleIndex + 1) % matches.length;
            scrollToRow(matches[cycleIndex]);
        });

        // The date picker swaps the table via HTMX — re-apply the current query
        document.body.addEventListener("htmx:afterSwap", function (event) {
            if (event.target === container && input.value.trim() !== "") {
                cycleIndex = 0;
                var matches = applyQuery();
                if (matches.length > 0) scrollToRow(matches[0]);
            }
        });
    }

    initTeamFinder();
    renderAll();

    var resizeTimer;
    window.addEventListener("resize", function () {
        clearTimeout(resizeTimer);
        resizeTimer = setTimeout(renderAll, 200);
    });
})();
