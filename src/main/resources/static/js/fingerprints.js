/* Team fingerprints — one radial four-factors glyph per team, the whole league
 * in a sorted grid, with a detail panel on hover/focus. Data from
 * /api/charts/fingerprints (league percentiles, outward = better). Filled shape
 * is offense, outline is defense; colors are team brand colors with the same
 * surface-contrast fallback the other charts use. */
(function () {
  "use strict";

  const root = document.querySelector("[data-fingerprints]");
  if (!root || root.dataset.fpInit) return;
  root.dataset.fpInit = "1";

  const gridEl = root.querySelector("[data-fp-grid]");
  const legendEl = root.querySelector("[data-fp-legend]");
  const detailEl = root.querySelector("[data-fp-detail]");
  const sortSel = document.querySelector("[data-fp-sort]");
  const confSel = document.querySelector("[data-fp-conf]");
  const findEl = document.querySelector("[data-fp-find]");
  const year = root.dataset.year, date = root.dataset.date;
  const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

  function status(msg) {
    gridEl.innerHTML = "";
    const p = document.createElement("p");
    p.className = "rating-river__status text-muted";
    p.textContent = msg;
    gridEl.appendChild(p);
  }

  // ── color helpers (shared rule with the river/landscape) ─────────────────
  function lum(c) {
    let r, g, b;
    const m = /^#?([0-9a-f]{6})$/i.exec(c || "");
    if (m) { const n = parseInt(m[1], 16); r = (n >> 16) & 255; g = (n >> 8) & 255; b = n & 255; }
    else { const q = /rgba?\((\d+),\s*(\d+),\s*(\d+)/.exec(c || ""); if (!q) return null; r = +q[1]; g = +q[2]; b = +q[3]; }
    const f = v => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
  }

  const url = `/api/charts/fingerprints?year=${encodeURIComponent(year)}${date ? `&date=${encodeURIComponent(date)}` : ""}`;
  fetch(url, { headers: { "Accept": "application/json" } })
    .then(r => { if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
    .then(render)
    .catch(() => status("Fingerprints aren’t available right now."));

  function render(data) {
    if (!data.teams.length) {
      status("No box-score stats for this season yet — fingerprints appear once team game stats are in.");
      return;
    }
    const theme = window.chartTheme();
    const surface = getComputedStyle(root).backgroundColor;
    const surfaceL = lum(surface) ?? 1;
    const readable = c => c && lum(c) != null && Math.abs(lum(c) - surfaceL) > 0.18;
    const colorOf = t => readable(t.color) ? t.color : readable(t.altColor) ? t.altColor : theme.series[0];
    const axes = data.axes, N = axes.length;
    const angle = i => -Math.PI / 2 + (2 * Math.PI * i) / N;

    // Percentile → polygon points for one side ("off" | "def"); tempo is shared.
    function poly(pct, side, R) {
      return axes.map((ax, i) => {
        const stat = side === "def" && ax.defStat ? ax.defStat : ax.offStat;
        const p = pct[stat];
        const r = p == null ? 0 : (0.08 + 0.92 * p) * R;
        return [Math.cos(angle(i)) * r, Math.sin(angle(i)) * r];
      });
    }
    const path = pts => "M" + pts.map(p => p.join(",")).join("L") + "Z";

    function drawGlyph(svg, team, size, opts) {
      const R = size / 2 - (opts.pad || 4);
      // Labeled glyphs need side room for the axis names beyond the spokes.
      const side = opts.labels ? 70 : 0;
      const g = svg.attr("viewBox", `${-size / 2 - side} ${-size / 2} ${size + 2 * side} ${size}`).append("g");
      g.append("circle").attr("class", "fp-ring").attr("r", R * (0.08 + 0.92 * 0.5));   // league median
      axes.forEach((ax, i) => g.append("line").attr("class", "fp-axis")
        .attr("x2", Math.cos(angle(i)) * R).attr("y2", Math.sin(angle(i)) * R));
      if (opts.ghost) {
        g.append("path").attr("class", "fp-ghost-off").attr("d", path(poly(opts.ghost, "off", R)));
        g.append("path").attr("class", "fp-ghost-def").attr("d", path(poly(opts.ghost, "def", R)));
      }
      const c = colorOf(team);
      g.append("path").attr("d", path(poly(team.pct, "off", R)))
        .attr("fill", c).attr("fill-opacity", 0.45).attr("stroke", c).attr("stroke-width", opts.stroke || 1.5).attr("stroke-linejoin", "round");
      g.append("path").attr("d", path(poly(team.pct, "def", R)))
        .attr("fill", "none").attr("stroke", "var(--color-text)").attr("stroke-width", opts.stroke || 1.5)
        .attr("stroke-dasharray", opts.dash || "3 2").attr("stroke-linejoin", "round");
      if (opts.labels) {
        axes.forEach((ax, i) => g.append("text").attr("class", "fp-axis-label")
          .attr("x", Math.cos(angle(i)) * (R + 12)).attr("y", Math.sin(angle(i)) * (R + 12))
          .attr("text-anchor", Math.abs(Math.cos(angle(i))) < 0.2 ? "middle" : Math.cos(angle(i)) > 0 ? "start" : "end")
          .attr("dy", "0.35em").text(ax.label.split(" (")[0]));
      }
      return g;
    }

    // Legend: an annotated glyph of a league-median team.
    const median = {};
    axes.forEach(ax => { median[ax.offStat] = 0.5; if (ax.defStat) median[ax.defStat] = 0.5; });
    legendEl.innerHTML = "";
    drawGlyph(d3.select(legendEl).append("svg").attr("width", 290).attr("height", 150),
      { pct: median, color: theme.series[0] }, 150, { pad: 36, labels: true });
    const key = document.createElement("div");
    key.className = "fp-legend__key";
    key.innerHTML = `<span><span class="fp-legend__swatch" style="background:${theme.series[0]};opacity:.6"></span>Offense (filled)</span>` +
                    `<span><span class="fp-legend__swatch" style="border:1px dashed var(--color-text)"></span>Defense (outline)</span>` +
                    `<span>Ring = league median. Bigger is better.</span>`;
    legendEl.appendChild(key);

    // Conference filter options.
    const confs = Array.from(new Set(data.teams.map(t => t.conf).filter(Boolean))).sort();
    confs.forEach(c => { const o = document.createElement("option"); o.value = c; o.textContent = c; confSel.appendChild(o); });

    // Cells.
    const teams = data.teams.slice();
    const rankByNet = new Map(teams.map((t, i) => [t.id, i + 1]));
    gridEl.innerHTML = "";
    const cells = new Map();
    teams.forEach(t => {
      const cell = document.createElement("button");
      cell.type = "button";
      cell.className = "fp-cell";
      cell.dataset.id = t.id;
      cell.title = t.name;
      cell.setAttribute("aria-label", `${t.name}${t.conf ? ", " + t.conf : ""}`);
      const svg = d3.select(cell).append("svg").attr("width", 56).attr("height", 56);
      drawGlyph(svg, t, 56, { pad: 3, stroke: 1.2, dash: "2 1.5" });
      const abbr = document.createElement("span"); abbr.className = "fp-cell__abbr"; abbr.textContent = t.abbr || t.name;
      const rk = document.createElement("span"); rk.className = "fp-cell__rank"; rk.textContent = t.net != null ? `#${rankByNet.get(t.id)}` : "";
      cell.appendChild(abbr); cell.appendChild(rk);
      cell.addEventListener("mouseenter", () => showDetail(t));
      cell.addEventListener("focus", () => showDetail(t));
      cell.addEventListener("click", () => { window.location.href = `/teams/${t.id}`; });
      cells.set(t.id, cell);
    });

    const fmtVal = (stat, v) => {
      if (v == null) return "—";
      if (stat === "pace") return v.toFixed(1);
      if (stat === "off_efficiency" || stat === "def_efficiency") return v.toFixed(1);
      return (v * 100).toFixed(1) + (/pct$/.test(stat) ? "%" : "");
    };
    const fmtPct = p => p == null ? "" : `${Math.round(p * 100)}th`;

    function showDetail(t) {
      cells.forEach((c, id) => c.classList.toggle("fp-cell--hot", id === t.id));
      const ghost = t.conf ? data.confMedianPct[t.conf] : null;
      detailEl.innerHTML = "";
      const head = document.createElement("div"); head.className = "fp-detail__head";
      head.innerHTML = `${t.logoUrl ? `<img class="fp-detail__logo" src="${esc(t.logoUrl)}" alt="">` : ""}` +
        `<div><div class="fp-detail__name">${esc(t.name)}</div>` +
        `<div class="fp-detail__sub">${esc(t.conf || "")}${t.net != null ? ` · net ${t.net > 0 ? "+" : ""}${t.net.toFixed(1)} (#${rankByNet.get(t.id)})` : ""}</div></div>`;
      detailEl.appendChild(head);
      const svg = d3.select(detailEl).append("svg").attr("width", 270).attr("height", 180);
      drawGlyph(svg, t, 270, { pad: 56, labels: true, ghost, stroke: 2, dash: "5 3" });
      const tbl = document.createElement("table"); tbl.className = "fp-detail__table";
      tbl.innerHTML = `<thead><tr><th>Factor</th><th style="text-align:right">Offense</th><th style="text-align:right">Defense</th></tr></thead>` +
        `<tbody>${axes.map(ax => `<tr><td>${esc(ax.label.split(" (")[0])}</td>` +
          `<td>${fmtVal(ax.offStat, t.values[ax.offStat])} <span class="fp-detail__pct">${fmtPct(t.pct[ax.offStat])}</span></td>` +
          `<td>${ax.defStat ? `${fmtVal(ax.defStat, t.values[ax.defStat])} <span class="fp-detail__pct">${fmtPct(t.pct[ax.defStat])}</span>` : "—"}</td></tr>`).join("")}` +
        `<tr><td>Efficiency</td><td>${fmtVal("off_efficiency", t.offEff)}</td><td>${fmtVal("def_efficiency", t.defEff)}</td></tr></tbody>`;
      detailEl.appendChild(tbl);
      if (ghost) { const n = document.createElement("p"); n.className = "fp-detail__sub"; n.textContent = `Gray ghost: ${t.conf} median.`; detailEl.appendChild(n); }
    }

    // Sorting + filtering.
    const sorters = {
      net:  (a, b) => (b.net ?? -1e9) - (a.net ?? -1e9),
      off:  (a, b) => (b.offEff ?? -1e9) - (a.offEff ?? -1e9),
      def:  (a, b) => (a.defEff ?? 1e9) - (b.defEff ?? 1e9),
      conf: (a, b) => (a.conf || "~").localeCompare(b.conf || "~") || ((b.net ?? -1e9) - (a.net ?? -1e9)),
      name: (a, b) => a.name.localeCompare(b.name)
    };
    function layout() {
      const sort = sortSel.value, conf = confSel.value;
      const q = (findEl.value || "").trim().toLowerCase();
      const ordered = teams.slice().sort(sorters[sort] || sorters.net);
      gridEl.innerHTML = "";
      let group = null;
      ordered.forEach(t => {
        const cell = cells.get(t.id);
        const visible = !conf || t.conf === conf;
        cell.classList.toggle("fp-cell--hidden", !visible);
        if (sort === "conf" && visible && t.conf !== group) {
          group = t.conf;
          const h = document.createElement("div"); h.className = "fp-group"; h.textContent = group || "Independent";
          gridEl.appendChild(h);
        }
        gridEl.appendChild(cell);
      });
      if (q) {
        const hit = ordered.find(t => t.name.toLowerCase().includes(q) || (t.abbr || "").toLowerCase() === q);
        if (hit) { showDetail(hit); cells.get(hit.id).scrollIntoView({ block: "nearest", behavior: "smooth" }); }
      }
    }
    sortSel.addEventListener("change", layout);
    confSel.addEventListener("change", layout);
    findEl.addEventListener("input", layout);
    layout();
    showDetail(teams[0]);
  }
})();
