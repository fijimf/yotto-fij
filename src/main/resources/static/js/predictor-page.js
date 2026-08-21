/* Predictor detail page: the game-outcome scatter — each completed game plotted
 * at (home team's entering season-to-date value, away team's), green = home
 * win, red = home loss. Numbers are pre-computed server-side (StatPageService);
 * this file only draws them with D3. Split out of stat-page.js when the
 * predictor moved to its own page (spec §5.4). */
(function () {
    "use strict";

    var data = window.STAT_PAGE_DATA;
    if (!data) return;

    var THEME = window.chartTheme();
    var GREEN = THEME.win;  // home win
    var RED = THEME.loss;   // home loss
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

    // ── tooltip ──────────────────────────────────────────────────────────────────
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

    // Team abbreviations are data, not markup — escape before injecting into the tooltip.
    function esc(s) {
        return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    }

    function renderScatter(el) {
        var s = data.scatter;
        if (!s || !s.points || s.points.length === 0) return;
        el.innerHTML = "";

        var format = data.meta.format;
        var size = Math.max(260, Math.min(el.clientWidth || 420, 480));
        var margin = { top: 12, right: 12, bottom: 44, left: 52 };
        var w = size - margin.left - margin.right;
        var h = size - margin.top - margin.bottom;

        var svg = d3.select(el).append("svg")
            .attr("viewBox", "0 0 " + size + " " + size)
            .attr("width", "100%");
        var g = svg.append("g").attr("transform", "translate(" + margin.left + "," + margin.top + ")");

        var x = d3.scaleLinear().domain([s.axisMin, s.axisMax]).range([0, w]);
        var y = d3.scaleLinear().domain([s.axisMin, s.axisMax]).range([h, 0]);
        var tf = axisTickFormat(format);

        g.append("g").attr("transform", "translate(0," + h + ")")
            .call(d3.axisBottom(x).ticks(5).tickFormat(tf))
            .call(function (sel) { sel.selectAll("line,path").attr("stroke", GRID); sel.selectAll("text").attr("fill", AXIS); });
        g.append("g")
            .call(d3.axisLeft(y).ticks(5).tickFormat(tf))
            .call(function (sel) { sel.selectAll("line,path").attr("stroke", GRID); sel.selectAll("text").attr("fill", AXIS); });

        // y = x reference line (both teams entered with the same value)
        g.append("line")
            .attr("x1", x(s.axisMin)).attr("y1", y(s.axisMin))
            .attr("x2", x(s.axisMax)).attr("y2", y(s.axisMax))
            .attr("stroke", GRID).attr("stroke-dasharray", "5,4");

        g.append("text").attr("x", w / 2).attr("y", h + 38)
            .attr("text-anchor", "middle").attr("fill", AXIS).attr("font-size", 12).text("Home team");
        g.append("text").attr("transform", "rotate(-90)").attr("x", -h / 2).attr("y", -40)
            .attr("text-anchor", "middle").attr("fill", AXIS).attr("font-size", 12).text("Away team");

        g.selectAll("circle.pt").data(s.points).enter().append("circle")
            .attr("class", "pt")
            .attr("cx", function (d) { return x(d.x); })
            .attr("cy", function (d) { return y(d.y); })
            .attr("r", 3)
            .attr("fill", function (d) { return d.homeWin ? GREEN : RED; })
            .attr("fill-opacity", 0.55)
            .on("mouseenter", function (event, d) {
                var home = esc(d.homeAbbr || "Home");
                var away = esc(d.awayAbbr || "Away");
                showTip("Entering: " + home + " <strong>" + formatValue(d.x, format) + "</strong> · " + away
                    + " <strong>" + formatValue(d.y, format) + "</strong><br>"
                    + (d.homeWin ? home + " won" : home + " lost") + " (home)", event);
            })
            .on("mousemove", function (event, d) {
                tip.style("left", (event.pageX + 12) + "px").style("top", (event.pageY - 12) + "px");
            })
            .on("mouseleave", hideTip);
    }

    function renderAll() {
        var el = document.getElementById("stat-scatter");
        if (el) renderScatter(el);
    }

    renderAll();

    var resizeTimer;
    window.addEventListener("resize", function () {
        clearTimeout(resizeTimer);
        resizeTimer = setTimeout(renderAll, 200);
    });
})();
