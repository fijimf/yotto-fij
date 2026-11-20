/* Efficiency landscape — every team plotted by adjusted offensive efficiency (x)
 * and adjusted defensive efficiency (y, lower is better so the axis is flipped),
 * played across the season. Data: /api/charts/efficiency-landscape. Dots use team
 * brand colors (identity), the highlighted conference's hull uses --chart-1,
 * everything else comes from the chart tokens. */
(function () {
  "use strict";

  const W = 960, H = 620, MARGIN = { top: 20, right: 40, bottom: 48, left: 56 };
  const fmtDate = d3.timeFormat("%b %-d, %Y");
  const f1 = d3.format(".1f"), fs = d3.format("+.1f");

  const root = document.querySelector("[data-landscape]");
  if (!root || root.dataset.landscapeInit) return;
  root.dataset.landscapeInit = "1";

  const chartEl = root.querySelector("[data-landscape-chart]");
  const confSel = root.querySelector("[data-landscape-conf]");
  const labelsCb = root.querySelector("[data-landscape-labels]");
  const isoCb = root.querySelector("[data-landscape-isonet]");
  const findEl = root.querySelector("[data-landscape-find]");
  const scrub = root.querySelector("[data-landscape-scrub]");
  const playBtn = root.querySelector("[data-landscape-play]");
  const range = root.querySelector("[data-landscape-range]");
  const dateEl = root.querySelector("[data-landscape-date]");
  const tip = d3.select(root.querySelector("[data-landscape-tip]"));
  const year = root.dataset.year;

  function status(msg) {
    chartEl.innerHTML = "";
    const p = document.createElement("p");
    p.className = "rating-river__status text-muted";
    p.textContent = msg;
    chartEl.appendChild(p);
    scrub.hidden = true;
  }

  fetch(`/api/charts/efficiency-landscape?year=${encodeURIComponent(year)}`, { headers: { "Accept": "application/json" } })
    .then(r => { if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
    .then(render)
    .catch(() => status("The landscape isn’t available right now."));

  function render(data) {
    if (!data.dates.length || !data.teams.length) {
      status("No adjusted-efficiency ratings for this season yet — they appear after the first calculation run.");
      return;
    }
    const theme = window.chartTheme();
    const surface = getComputedStyle(root).backgroundColor;
    const dates = data.dates.map(d => new Date(d + "T12:00:00"));
    const teams = data.teams;
    const F = dates.length;
    let k = F - 1, timer = null, hot = null, found = null;

    // Fixed domains across the season so playback doesn't rescale under the viewer.
    const offs = teams.flatMap(t => t.off).filter(v => v != null);
    const defs = teams.flatMap(t => t.def).filter(v => v != null);
    const [o0, o1] = [d3.quantile(offs.sort(d3.ascending), 0.005), d3.quantile(offs, 0.995)];
    const [d0, d1] = [d3.quantile(defs.sort(d3.ascending), 0.005), d3.quantile(defs, 0.995)];
    const pad = 2;
    const x = d3.scaleLinear().domain([o0 - pad, o1 + pad]).nice().range([MARGIN.left, W - MARGIN.right]);
    const y = d3.scaleLinear().domain([d1 + pad, d0 - pad]).nice().range([H - MARGIN.bottom, MARGIN.top]); // flipped: lower AdjD at top

    chartEl.innerHTML = "";
    const svg = d3.select(chartEl).append("svg").attr("viewBox", `0 0 ${W} ${H}`).attr("role", "img")
      .attr("aria-label", `Adjusted efficiency landscape, ${data.year}`);
    const plotClip = `land-clip-${Math.random().toString(36).slice(2, 8)}`;
    svg.append("clipPath").attr("id", plotClip).append("rect")
      .attr("x", MARGIN.left).attr("y", MARGIN.top).attr("width", W - MARGIN.left - MARGIN.right).attr("height", H - MARGIN.top - MARGIN.bottom);

    // Grid + axes (recessive).
    svg.append("g").attr("class", "axis").attr("transform", `translate(0,${H - MARGIN.bottom})`)
      .call(d3.axisBottom(x).ticks(10).tickSize(-(H - MARGIN.top - MARGIN.bottom)).tickSizeOuter(0))
      .call(g => g.selectAll(".tick line").attr("stroke", theme.grid));
    svg.append("g").attr("class", "axis").attr("transform", `translate(${MARGIN.left},0)`)
      .call(d3.axisLeft(y).ticks(10).tickSize(-(W - MARGIN.left - MARGIN.right)).tickSizeOuter(0))
      .call(g => g.selectAll(".tick line").attr("stroke", theme.grid));
    svg.append("text").attr("class", "axis").attr("x", (MARGIN.left + W - MARGIN.right) / 2).attr("y", H - 10)
      .attr("text-anchor", "middle").attr("fill", theme.axis).attr("font-size", 12)
      .text("Adjusted offensive efficiency (points per 100 possessions) →");
    svg.append("text").attr("class", "axis").attr("transform", `translate(16,${(MARGIN.top + H - MARGIN.bottom) / 2}) rotate(-90)`)
      .attr("text-anchor", "middle").attr("fill", theme.axis).attr("font-size", 12)
      .text("← Adjusted defensive efficiency (points allowed per 100; lower is better)");

    const plot = svg.append("g").attr("clip-path", `url(#${plotClip})`);

    // Iso-net guides: lines where off − def is constant.
    const isoG = plot.append("g");
    const nets = d3.range(-30, 41, 10);
    const iso = isoG.selectAll("g").data(nets).enter().append("g");
    iso.append("line").attr("class", "iso-net")
      .attr("x1", d => x(x.domain()[0])).attr("y1", d => y(x.domain()[0] - d))
      .attr("x2", d => x(x.domain()[1])).attr("y2", d => y(x.domain()[1] - d));
    iso.append("text").attr("class", "iso-net-label")
      .attr("x", W - MARGIN.right - 4).attr("y", d => y(x.domain()[1] - d) - 4).attr("text-anchor", "end")
      .text(d => (d === 0 ? "net 0" : d3.format("+d")(d)));
    const meanX = plot.append("line").attr("class", "mean-line");
    const meanY = plot.append("line").attr("class", "mean-line");

    const hull = plot.append("path").attr("class", "hull").attr("fill", theme.series[0]).attr("stroke", theme.series[0]).attr("display", "none");

    // Team brand color if it reads against the card surface (navy on a dark theme
    // disappears); else the alternate; else the neutral token. Identity still comes
    // from the label and tooltip, so a fallback never misleads.
    const lum = c => {
      const m = /^#?([0-9a-f]{6})$/i.exec(c || "") || (/rgba?\((\d+),\s*(\d+),\s*(\d+)/.exec(c || "") && { 1: null });
      let r, g, b;
      if (m && m[1] != null) { const n = parseInt(m[1], 16); r = (n >> 16) & 255; g = (n >> 8) & 255; b = n & 255; }
      else { const q = /rgba?\((\d+),\s*(\d+),\s*(\d+)/.exec(c || ""); if (!q) return null; r = +q[1]; g = +q[2]; b = +q[3]; }
      const f = v => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
      return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
    };
    const surfaceL = lum(surface) ?? 1;
    const readable = c => c && lum(c) != null && Math.abs(lum(c) - surfaceL) > 0.18;
    const dotColor = t => readable(t.color) ? t.color : readable(t.altColor) ? t.altColor : theme.neutral;
    const pts = plot.append("g").selectAll("circle").data(teams, t => t.id).enter().append("circle")
      .attr("class", "point").attr("r", 5).attr("fill", dotColor).style("cursor", "pointer");
    const labels = plot.append("g").selectAll("text").data(teams, t => t.id).enter().append("text")
      .attr("class", "point-label").attr("dx", 8).attr("dy", "0.35em").text(t => t.abbr || t.name);

    // Conference picker.
    const confs = Array.from(new Set(teams.map(t => t.conf).filter(Boolean))).sort();
    confs.forEach(c => { const o = document.createElement("option"); o.value = c; o.textContent = c; confSel.appendChild(o); });

    const has = t => t.off[k] != null && t.def[k] != null;
    const net = t => t.off[k] - t.def[k];

    function frame(animate) {
      const conf = confSel.value;
      const live = teams.filter(has);
      const ranked = live.slice().sort((a, b) => net(b) - net(a));
      const rankOf = new Map(ranked.map((t, i) => [t.id, i + 1]));
      const top = new Set(ranked.slice(0, 10).map(t => t.id));
      const sel = live.filter(t => !conf || t.conf === conf);

      const tr = s => animate ? s.transition("frame").duration(650).ease(d3.easeCubicInOut) : s;
      tr(pts).attr("cx", t => has(t) ? x(t.off[k]) : -50).attr("cy", t => has(t) ? y(t.def[k]) : -50)
        .attr("display", t => has(t) ? null : "none");
      pts.classed("point--dim", t => conf && t.conf !== conf && t !== hot && t !== found)
         .classed("point--hot", t => t === hot || t === found);

      const showLabel = t => has(t) && (t === hot || t === found || (labelsCb.checked && top.has(t.id) && (!conf || t.conf === conf)) || (conf && t.conf === conf && sel.length <= 20));
      tr(labels).attr("x", t => has(t) ? x(t.off[k]) : -50).attr("y", t => has(t) ? y(t.def[k]) : -50);
      labels.attr("display", t => showLabel(t) ? null : "none");

      // Hull around the chosen conference (needs 3+ teams).
      const hp = sel.map(t => [x(t.off[k]), y(t.def[k])]);
      const poly = conf && hp.length >= 3 ? d3.polygonHull(hp) : null;
      if (poly) tr(hull.attr("display", null)).attr("d", "M" + poly.join("L") + "Z"); else hull.attr("display", "none");

      // League mean cross-hairs for this frame.
      const mo = d3.mean(live, t => t.off[k]), md = d3.mean(live, t => t.def[k]);
      tr(meanX).attr("x1", x(mo)).attr("x2", x(mo)).attr("y1", MARGIN.top).attr("y2", H - MARGIN.bottom);
      tr(meanY).attr("y1", y(md)).attr("y2", y(md)).attr("x1", MARGIN.left).attr("x2", W - MARGIN.right);
      isoG.attr("display", isoCb.checked ? null : "none");

      if (dateEl) dateEl.textContent = k === F - 1 ? `Latest · ${fmtDate(dates[k])}` : fmtDate(dates[k]);
      if (range) range.value = k;
      pts.property("__rank", t => rankOf.get(t.id));
    }

    function showTip(evt, t) {
      if (!has(t)) return;
      const box = root.getBoundingClientRect();
      const rk = evt.currentTarget.__rank;
      tip.style("display", "block")
        .html(`<b>${t.name}</b>${t.conf ? ` · ${t.conf}` : ""}\n${fmtDate(dates[k])}${rk ? ` · #${rk} by net` : ""}\n` +
              `AdjO ${f1(t.off[k])} · AdjD ${f1(t.def[k])} · Net ${fs(net(t))}` +
              `${t.tempo[k] != null ? `\nTempo ${f1(t.tempo[k])}` : ""}${t.gp[k] != null ? ` · ${t.gp[k]} GP` : ""}`)
        .style("left", `${evt.clientX - box.left + 14}px`).style("top", `${evt.clientY - box.top - 10}px`);
    }
    pts.on("mouseenter", (e, t) => { hot = t; frame(false); })
       .on("mousemove", showTip)
       .on("mouseleave", () => { hot = null; tip.style("display", "none"); frame(false); })
       .on("click", (e, t) => { window.location.href = `/teams/${t.id}`; });

    function setFrame(i, animate) { k = Math.max(0, Math.min(F - 1, i)); frame(animate); }
    function stop() { if (timer) { clearInterval(timer); timer = null; } playBtn.innerHTML = "&#9654; Play season"; }
    function play() {
      if (timer) { stop(); return; }
      if (k >= F - 1) setFrame(0, false);
      playBtn.textContent = "❚❚ Pause";
      timer = setInterval(() => { if (k >= F - 1) { stop(); return; } setFrame(k + 1, true); }, 750);
    }

    range.max = F - 1; range.value = k; scrub.hidden = F < 2;
    range.addEventListener("input", () => { stop(); setFrame(+range.value, false); });
    playBtn.addEventListener("click", play);
    confSel.addEventListener("change", () => frame(true));
    labelsCb.addEventListener("change", () => frame(false));
    isoCb.addEventListener("change", () => frame(false));
    if (findEl) findEl.addEventListener("input", () => {
      const q = findEl.value.trim().toLowerCase();
      found = q ? teams.find(t => t.name.toLowerCase().includes(q) || (t.abbr || "").toLowerCase() === q) || null : null;
      frame(false);
    });
    frame(false);
  }
})();
