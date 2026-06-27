/* Per-statistic page charts: a per-game scatter and a team-distribution
 * histogram with a KDE overlay. All numbers are pre-computed server-side
 * (StatPageService) — this file only draws them with D3. */
(function () {
    "use strict";

    var data = window.STAT_PAGE_DATA;
    if (!data) return;

    var GREEN = "#16a34a"; // home win  (matches --color-success)
    var RED = "#dc2626";   // home loss (matches --color-danger)
    var AXIS = "#64748b";  // --color-text-muted
    var GRID = "#dce3ea";  // --color-border

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

    // ── per-game scatter ─────────────────────────────────────────────────────────
    function renderScatter(el) {
        var s = data.scatter;
        if (!s || !s.points || s.points.length === 0) return;
        clear(el);

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

        // y = x reference line (a game both teams matched)
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
                showTip("Home <strong>" + formatValue(d.x, format) + "</strong> · Away <strong>"
                    + formatValue(d.y, format) + "</strong><br>" + (d.homeWin ? "Home won" : "Home lost"), event);
            })
            .on("mousemove", function (event, d) {
                tip.style("left", (event.pageX + 12) + "px").style("top", (event.pageY - 12) + "px");
            })
            .on("mouseleave", hideTip);
    }

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
                .attr("fill", "none").attr("stroke", "#b45309").attr("stroke-width", 2)
                .attr("opacity", 0.85).attr("d", line);
        }

        // bars on top, semi-transparent so the curve shows through
        g.selectAll("rect.bar").data(counts).enter().append("rect")
            .attr("class", "bar")
            .attr("x", function (_, i) { return x(edges[i]) + 1; })
            .attr("width", function (_, i) { return Math.max(0, x(edges[i + 1]) - x(edges[i]) - 2); })
            .attr("y", function (d) { return yCount(d); })
            .attr("height", function (d) { return h - yCount(d); })
            .attr("fill", "#1e3a5f").attr("fill-opacity", 0.6)
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
        var scatterEl = document.getElementById("stat-scatter");
        var histEl = document.getElementById("stat-histogram");
        if (scatterEl) renderScatter(scatterEl);
        if (histEl) renderHistogram(histEl);
    }

    renderAll();

    var resizeTimer;
    window.addEventListener("resize", function () {
        clearTimeout(resizeTimer);
        resizeTimer = setTimeout(renderAll, 200);
    });
})();
