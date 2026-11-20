/* Rating river — a bump chart of each conference member's national power rank
 * through the season. One instance per [data-rating-river] block (the conference
 * page swaps its season panel with HTMX, so blocks come and go). Data comes from
 * /api/charts/rating-river; colors from team brand colors with --chart-* fallbacks.
 *
 * Reading guide: y is the team's order within the conference on that day (1 =
 * best national rank among members); hover shows the national rank and rating. */
(function () {
  "use strict";

  const W = 960, ROW = 30, MARGIN = { top: 18, right: 176, bottom: 30, left: 34 };
  const fmtDate = d3.timeFormat("%b %-d");
  const fmtRating = d3.format("+.2f");

  // ── color helpers ────────────────────────────────────────────────────────
  function rgb(c) {
    if (!c) return null;
    const m = /^#?([0-9a-f]{6})$/i.exec(c.trim());
    if (m) { const n = parseInt(m[1], 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; }
    const r = /rgba?\((\d+),\s*(\d+),\s*(\d+)/.exec(c);
    return r ? [+r[1], +r[2], +r[3]] : null;
  }
  function lum(c) {
    const v = rgb(c); if (!v) return null;
    const f = x => { x /= 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); };
    return 0.2126 * f(v[0]) + 0.7152 * f(v[1]) + 0.0722 * f(v[2]);
  }
  function dist(a, b) {
    const x = rgb(a), y = rgb(b); if (!x || !y) return 999;
    return Math.hypot(x[0] - y[0], x[1] - y[1], x[2] - y[2]);
  }
  /** Team brand color if it reads against the surface and isn't a near-duplicate
   *  of an earlier team's; else the alternate; else the shared categorical ramp. */
  function assignColors(teams, surface) {
    const bgL = lum(surface) ?? 1, used = [];
    return teams.map((t, i) => {
      const ok = c => c && lum(c) != null && Math.abs(lum(c) - bgL) > 0.18 && used.every(u => dist(u, c) > 60);
      let c = ok(t.color) ? t.color : ok(t.altColor) ? t.altColor : null;
      if (!c) { let k = i; do { c = window.chartColor(k++); } while (!used.every(u => dist(u, c) > 60) && k < i + 8); }
      used.push(c);
      return c;
    });
  }

  // ── one chart instance ───────────────────────────────────────────────────
  function init(root) {
    if (root.dataset.riverInit) return;
    root.dataset.riverInit = "1";

    const confId = root.dataset.conferenceId, year = root.dataset.year;
    const chartEl = root.querySelector("[data-river-chart]");
    const select  = root.querySelector("[data-river-model]");
    const scrub   = root.querySelector("[data-river-scrub]");
    const playBtn = root.querySelector("[data-river-play]");
    const range   = root.querySelector("[data-river-range]");
    const dateEl  = root.querySelector("[data-river-date]");
    const tip     = d3.select(root.querySelector("[data-river-tip]"));
    if (!chartEl || !confId || !year) return;

    let data = null, endIdx = 0, timer = null, view = null;

    function status(msg) {
      chartEl.innerHTML = "";
      const p = document.createElement("p");
      p.className = "rating-river__status text-muted";
      p.textContent = msg;
      chartEl.appendChild(p);
      scrub.hidden = true;
    }

    function load() {
      stop();
      status("Loading the season…");
      const model = select ? select.value : "massey";
      fetch(`/api/charts/rating-river?conferenceId=${encodeURIComponent(confId)}&year=${encodeURIComponent(year)}&model=${encodeURIComponent(model)}`,
            { headers: { "Accept": "application/json" } })
        .then(r => { if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
        .then(d => { data = d; render(); })
        .catch(() => status("The rating race isn’t available right now."));
    }

    function render() {
      if (!data || !data.dates.length || !data.teams.length) {
        status("No ratings for this season yet — the race appears after the first calculation run.");
        return;
      }
      const theme = window.chartTheme();
      const dates = data.dates.map(d => new Date(d + "T12:00:00"));
      const teams = data.teams;
      const N = teams.length, D = dates.length;

      // Conference position per day: order members by national rank; unranked → null.
      const pos = teams.map(() => new Array(D).fill(null));
      for (let i = 0; i < D; i++) {
        const ranked = teams.map((t, k) => [t.rank[i], k]).filter(x => x[0] != null).sort((a, b) => a[0] - b[0]);
        ranked.forEach(([, k], j) => { pos[k][i] = j + 1; });
      }
      const lastPos = k => { for (let i = endIdx; i >= 0; i--) if (pos[k][i] != null) return [pos[k][i], i]; return null; };

      const H = MARGIN.top + MARGIN.bottom + N * ROW;
      const x = d3.scaleTime().domain([dates[0], dates[D - 1]]).range([MARGIN.left, W - MARGIN.right]);
      const y = d3.scalePoint().domain(d3.range(1, N + 1)).range([MARGIN.top, H - MARGIN.bottom]).padding(0.5);
      const colors = assignColors(teams, getComputedStyle(root).backgroundColor);

      chartEl.innerHTML = "";
      const svg = d3.select(chartEl).append("svg")
        .attr("viewBox", `0 0 ${W} ${H}`).attr("role", "img")
        .attr("aria-label", `${data.conference} ${data.modelTitle} rating race, ${data.year}`);
      const clipId = `river-clip-${confId}-${Math.random().toString(36).slice(2, 8)}`;
      const clipRect = svg.append("clipPath").attr("id", clipId).append("rect")
        .attr("x", MARGIN.left - 4).attr("y", 0).attr("height", H).attr("width", 0);

      // Faint row guides + position numbers (recessive).
      const rows = svg.append("g");
      rows.selectAll("line").data(d3.range(1, N + 1)).enter().append("line")
        .attr("x1", MARGIN.left).attr("x2", W - MARGIN.right).attr("y1", y).attr("y2", y)
        .attr("stroke", theme.grid).attr("stroke-width", 1);
      rows.selectAll("text").data(d3.range(1, N + 1)).enter().append("text")
        .attr("class", "rank-label").attr("x", MARGIN.left - 8).attr("y", y).attr("dy", "0.35em")
        .attr("text-anchor", "end").text(d => d);
      svg.append("g").attr("class", "axis").attr("transform", `translate(0,${H - MARGIN.bottom + 6})`)
        .call(d3.axisBottom(x).ticks(Math.min(8, D)).tickFormat(d => d3.timeFormat(D > 60 ? "%b" : "%b %-d")(d)).tickSizeOuter(0));

      const line = d3.line()
        .defined((p) => p != null)
        .x((p, i) => x(dates[i])).y(p => y(p)).curve(d3.curveMonotoneX);

      const lines = svg.append("g").attr("clip-path", `url(#${clipId})`)
        .selectAll("path").data(teams).enter().append("path")
        .attr("class", "river-line").attr("d", (t, k) => line(pos[k])).attr("stroke", (t, k) => colors[k]);

      // Fat invisible hit targets so thin lines are easy to hover.
      const hits = svg.append("g").attr("clip-path", `url(#${clipId})`)
        .selectAll("path").data(teams).enter().append("path")
        .attr("d", (t, k) => line(pos[k])).attr("fill", "none").attr("stroke", "transparent").attr("stroke-width", 14)
        .style("cursor", "pointer");

      const labels = svg.append("g").selectAll("g").data(teams).enter().append("g")
        .attr("class", "team-label-group").style("cursor", "pointer");
      labels.each(function (t, k) {
        const g = d3.select(this);
        if (t.logoUrl) g.append("image").attr("href", t.logoUrl).attr("width", 18).attr("height", 18).attr("x", 8).attr("y", -9);
        g.append("text").attr("class", "team-label").attr("x", t.logoUrl ? 30 : 8).attr("dy", "0.35em").text(t.abbr || t.name);
        g.append("circle").attr("r", 4).attr("fill", colors[k]).attr("stroke", getComputedStyle(root).backgroundColor).attr("stroke-width", 1.5);
      });

      function place() {
        const xe = x(dates[endIdx]);
        clipRect.attr("width", Math.max(0, xe - MARGIN.left + 6));
        labels.attr("transform", (t, k) => {
          const lp = lastPos(k);
          return lp ? `translate(${xe},${y(lp[0])})` : "translate(-999,-999)";
        });
        if (dateEl) dateEl.textContent = endIdx === D - 1 ? `Latest · ${fmtDate(dates[endIdx])}` : fmtDate(dates[endIdx]);
        if (range) range.value = endIdx;
      }

      // Hover: dim the rest, read the national rank at the nearest date.
      function highlight(k) {
        lines.classed("river-line--dim", (t, j) => j !== k).classed("river-line--hot", (t, j) => j === k);
        labels.classed("team-label-group--dim", (t, j) => j !== k);
      }
      function reset() {
        lines.classed("river-line--dim", false).classed("river-line--hot", false);
        labels.classed("team-label-group--dim", false);
        tip.style("display", "none");
      }
      function showTip(evt, t, k) {
        const [mx] = d3.pointer(evt, svg.node());
        const dateAt = x.invert(mx);
        let i = d3.bisector(d => d).left(dates, dateAt);
        i = Math.max(0, Math.min(endIdx, i));
        if (i > 0 && Math.abs(dates[i - 1] - dateAt) < Math.abs(dates[i] - dateAt)) i--;
        const rk = t.rank[i], rt = t.rating[i];
        const box = root.getBoundingClientRect();
        tip.style("display", "block")
          .html(`<b>${t.name}</b>\n${fmtDate(dates[i])} · national #${rk ?? "—"}${rt != null ? ` · ${fmtRating(rt)}` : ""}`)
          .style("left", `${evt.clientX - box.left + 14}px`).style("top", `${evt.clientY - box.top - 10}px`);
      }
      hits.on("mouseenter", (e, t) => highlight(teams.indexOf(t)))
          .on("mousemove", (e, t) => showTip(e, t, teams.indexOf(t)))
          .on("mouseleave", reset);
      labels.on("mouseenter", (e, t) => highlight(teams.indexOf(t)))
            .on("mousemove", (e, t) => showTip(e, t, teams.indexOf(t)))
            .on("mouseleave", reset)
            .on("click", (e, t) => { window.location.href = `/teams/${t.id}`; });

      endIdx = D - 1;
      if (range) { range.max = D - 1; range.value = endIdx; }
      scrub.hidden = D < 2;
      view = { D, place };
      place();
    }

    function setEnd(i) { if (!view) return; endIdx = Math.max(0, Math.min(view.D - 1, i)); view.place(); }
    function stop() {
      if (timer) { clearInterval(timer); timer = null; }
      if (playBtn) playBtn.innerHTML = "&#9654; Replay season";
    }
    function play() {
      if (!view) return;
      if (timer) { stop(); return; }
      if (endIdx >= view.D - 1) setEnd(0);
      playBtn.textContent = "❚❚ Pause";
      timer = setInterval(() => {
        if (endIdx >= view.D - 1) { stop(); return; }
        setEnd(endIdx + 1);
      }, 90);
    }

    if (range) range.addEventListener("input", () => { stop(); setEnd(+range.value); });
    if (playBtn) playBtn.addEventListener("click", play);
    if (select) select.addEventListener("change", load);
    load();
  }

  function initAll(scope) {
    (scope || document).querySelectorAll("[data-rating-river]").forEach(init);
  }

  // hx-boost re-runs body scripts without DOMContentLoaded: gate on readyState,
  // and bind the document listeners exactly once.
  if (!window.__ratingRiverBound) {
    window.__ratingRiverBound = true;
    document.addEventListener("htmx:afterSettle", e => initAll(e.target));
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", () => initAll());
  else initAll();
})();
