/* Game Analysis chart — score-space view of one game.
 *
 *   Score view:        x = away team score, y = home team score.
 *   Margin/Total view: the same plane rotated 45° clockwise, so
 *                      x = total points, y = home margin. The spread line
 *                      becomes horizontal, the O/U line vertical, and the four
 *                      betting quadrants become literal rectangles.
 *
 * Every pre-game input (book lines, model predictions, each team's season form)
 * and the final result live on the same plane, so "who covers", "over or under"
 * and "how far off was the model" are all geometry. The rotation is a rigid
 * transform of the plot group; text and glyphs counter-rotate to stay upright.
 *
 * Data arrives inline as window.GAME_CHART_DATA (ChartDataDto). All colors come
 * from the --chart-* / --color-* design tokens via chart-theme.js.
 */
(function () {
  "use strict";

  const data = window.GAME_CHART_DATA;
  const loadingEl = document.getElementById("game-chart-loading");
  const errorEl   = document.getElementById("game-chart-error");
  const retryBtn  = document.getElementById("game-chart-retry");
  const container = document.getElementById("game-chart-container");
  const legendEl  = document.getElementById("game-chart-legend");
  const readoutEl = document.getElementById("game-chart-readout");
  const colorToggleBtn = document.getElementById("game-chart-color-toggle");
  const viewToggleBtn  = document.getElementById("game-chart-view-toggle");
  const densitySelect  = document.getElementById("game-chart-density-select");
  const scrubEl    = document.getElementById("game-chart-scrub");
  const scrubPlay  = document.getElementById("game-chart-scrub-play");
  const scrubRange = document.getElementById("game-chart-scrub-range");
  const scrubDate  = document.getElementById("game-chart-scrub-date");

  if (!data) {
    if (loadingEl) loadingEl.style.display = "none";
    return;
  }

  // ── Theme ────────────────────────────────────────────────────────────────
  const THEME = window.chartTheme();
  const rootStyle = getComputedStyle(document.documentElement);
  const SURFACE = rootStyle.getPropertyValue("--color-bg-surface").trim() || "#ffffff";
  const TEXT    = rootStyle.getPropertyValue("--color-text").trim() || "#1e293b";
  const BOOK_COLOR = THEME.benchmark;
  const REDUCED_MOTION = window.matchMedia &&
        window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  const TEAM_HOME_COLOR = data.homeColor ? "#" + data.homeColor : THEME.series[1];
  const TEAM_AWAY_COLOR = data.awayColor ? "#" + data.awayColor : THEME.neg;
  const STD_HOME_COLOR  = THEME.series[1];
  const STD_AWAY_COLOR  = THEME.neg;

  // Teams with near-identical palettes (navy vs navy) start on the standard
  // blue/red pair; the toggle button still lets the reader switch back.
  function hexRgb(h) {
    const m = /^#?([0-9a-f]{6})$/i.exec(h || "");
    if (!m) return null;
    const n = parseInt(m[1], 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }
  function colorsTooClose(a, b) {
    const ra = hexRgb(a), rb = hexRgb(b);
    if (!ra || !rb) return false;
    const d = Math.hypot(ra[0] - rb[0], ra[1] - rb[1], ra[2] - rb[2]);
    return d < 90;   // out of a 441 max; navy/navy ≈ 30, navy/royal ≈ 120
  }
  const teamColorsClash = colorsTooClose(TEAM_HOME_COLOR, TEAM_AWAY_COLOR);

  let useTeamColors = !teamColorsClash;
  let homeColor = useTeamColors ? TEAM_HOME_COLOR : STD_HOME_COLOR;
  let awayColor = useTeamColors ? TEAM_AWAY_COLOR : STD_AWAY_COLOR;
  if (colorToggleBtn && !useTeamColors) {
    colorToggleBtn.classList.add("active");
    colorToggleBtn.textContent = "Use Team Colors";
    colorToggleBtn.title = "Team colors are too similar to tell apart; showing standard blue/red";
  }

  // Domain fitting: below this many games a team's ellipse doesn't stretch the
  // axes, and the whole span is capped so a handful of points fill the plot.
  const MIN_GAMES_FOR_FIT = 10;
  const SPARSE_MAX_SPAN = 70;

  // χ² (2 dof) quantiles for the density bands and the 95% form ellipse
  const CHI2 = { 25: 0.5754, 50: 1.3863, 75: 2.7726, 95: 5.9915 };

  // View rotation (degrees, clockwise on screen). 45° maps the h = a diagonal
  // to horizontal, so y becomes home margin and x becomes total.
  const ROT = 45;
  let rotated = false;

  // ── Derived inputs ───────────────────────────────────────────────────────
  const isFinal   = data.actualHomeScore != null && data.actualAwayScore != null;
  const hasBook   = data.spread != null && data.overUnder != null;
  const models    = Array.isArray(data.models) ? data.models : [];
  const meetings  = Array.isArray(data.pastMeetings) ? data.pastMeetings : [];
  const homeGames = sortByDate(data.homeGames || []);
  const awayGames = sortByDate(data.awayGames || []);
  const sigmaM = data.marginSigma > 0 ? data.marginSigma : 11;
  const sigmaT = data.totalSigma  > 0 ? data.totalSigma  : 15;

  function sortByDate(games) {
    return games.slice().sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : 0));
  }
  const homeSeries = Array.isArray(data.homeSeries) ? data.homeSeries : [];
  const awaySeries = Array.isArray(data.awaySeries) ? data.awaySeries : [];

  // ── Season timeline (calendar days from the first game to game day) ──────
  const isoToDay = iso => Math.round(Date.UTC(+iso.slice(0, 4), +iso.slice(5, 7) - 1, +iso.slice(8, 10)) / 86400000);
  const dayToIso = d => new Date(d * 86400000).toISOString().slice(0, 10);
  const allGames = homeGames.concat(awayGames);
  const timeline = (allGames.length && data.gameDate) ? {
    start: Math.min(...allGames.map(gm => isoToDay(gm.date))),
    end: isoToDay(data.gameDate),
  } : null;
  let scrubDay = null;      // null = pre-game (full) state; otherwise an absolute day number
  let applyScrub = null;    // assigned by drawChart
  let playTimer = null;

  // Book implied point (handicap spread: negative = home favored)
  const implied = hasBook
    ? { home: (data.overUnder - data.spread) / 2, away: (data.overUnder + data.spread) / 2 }
    : null;
  const hasOpening = data.openingSpread != null && data.openingOverUnder != null;
  const openingImplied = hasOpening
    ? { home: (data.openingOverUnder - data.openingSpread) / 2,
        away: (data.openingOverUnder + data.openingSpread) / 2 }
    : null;
  const lineMoved = hasBook && hasOpening &&
    (Math.abs(data.openingSpread - data.spread) > 0.01 ||
     Math.abs(data.openingOverUnder - data.overUnder) > 0.01);

  // Model points in score space (model spread = home margin)
  const modelPoints = models.map(m => ({
    type: m.type, label: m.label,
    margin: m.spread, total: m.total, winProb: m.homeWinProbability,
    home: (m.total + m.spread) / 2, away: (m.total - m.spread) / 2,
    color: window.modelColor(m.type),
    short: shortModelName(m),
  }));

  function shortModelName(m) {
    if (m.type === "MASSEY")  return "MAS";
    if (m.type === "ADJ_EFF") return "ADJ";
    const s = (m.label || m.type.replace(/^ML:/, "")).replace(/[^A-Za-z0-9\-]/g, "");
    return s.length > 8 ? s.slice(0, 8) : s;
  }

  // Density sources: book + every model with a spread/total
  const densitySources = [];
  if (hasBook) densitySources.push({
    key: "BOOK", label: "Book", color: BOOK_COLOR,
    margin: -data.spread, total: data.overUnder, winProb: null,
  });
  modelPoints.forEach(p => densitySources.push({
    key: p.type, label: p.label, color: p.color,
    margin: p.margin, total: p.total, winProb: p.winProb,
  }));
  let densityKey = pickDefaultDensity();
  function pickDefaultDensity() {
    const ml = densitySources.find(s => s.key.indexOf("ML:") === 0);
    if (ml) return ml.key;
    if (densitySources.length) return densitySources[0].key;
    return null;
  }

  // ── Layer state (legend toggles) ─────────────────────────────────────────
  const state = {
    regions:       { label: "Win Regions",        group: "This game", visible: true,  kind: "area" },
    quadrants:     { label: "Betting Quadrants",  group: "This game", visible: true,  kind: "line" },
    spreadLine:    { label: "Spread Line",        group: "This game", visible: true,  kind: "line" },
    ouLine:        { label: "O/U Line",           group: "This game", visible: true,  kind: "line" },
    lineMovement:  { label: "Line Movement",      group: "This game", visible: true,  kind: "line" },
    impliedMarker: { label: "Book Implied Score", group: "This game", visible: true,  kind: "dot"  },
    modelMarkers:  { label: "Model Predictions",  group: "This game", visible: true,  kind: "dot"  },
    density:       { label: "Outcome Density",    group: "This game", visible: true,  kind: "area" },
    residuals:     { label: "Prediction Misses",  group: "This game", visible: true,  kind: "line" },
    resultMarker:  { label: "Result",             group: "This game", visible: true,  kind: "dot"  },
    seasonMarkers: { label: "Season Games",       group: "Season form", visible: true, kind: "dot"  },
    pastMeetings:  { label: "Past Meetings",      group: "Season form", visible: true, kind: "dot"  },
    avgMarker:     { label: "Avg Score",          group: "Season form", visible: true, kind: "dot"  },
    iqrBox:        { label: "IQR Box",            group: "Season form", visible: true, kind: "area" },
    ellipse:       { label: "95% Ellipse",        group: "Season form", visible: true, kind: "line" },
    marginals:     { label: "Score Rug",          group: "Season form", visible: true, kind: "line" },
  };

  // ── Warning conditions (incomplete data) ─────────────────────────────────
  const warnings = {};
  if (data.homeForQ1 == null || data.awayForQ1 == null)
    warnings.iqrBox = "Insufficient season games for IQR (need ≥4)";
  if (data.homeSdFor == null || data.homeSdAgainst == null ||
      data.awaySdFor == null || data.awaySdAgainst == null ||
      data.homeCorr == null  || data.awayCorr == null)
    warnings.ellipse = "Std dev / correlation stats unavailable";
  if (!homeGames.length && !awayGames.length)
    warnings.marginals = "No season games for score rug";
  if (data.spread == null)    warnings.spreadLine = "No spread data";
  if (data.overUnder == null) warnings.ouLine = "No over/under data";
  if (!hasBook)               warnings.impliedMarker = "Requires spread and O/U";
  if (!hasBook)               warnings.quadrants = "Requires spread and O/U";
  if (!hasOpening)            warnings.lineMovement = "No opening line recorded";
  else if (!lineMoved)        warnings.lineMovement = "Line has not moved since open";
  if (!isFinal)               warnings.resultMarker = "Game not yet final";
  if (!modelPoints.length)    warnings.modelMarkers = "No model predictions for this game";
  if (!meetings.length)       warnings.pastMeetings = "No prior meetings on record";
  if (!isFinal)               warnings.residuals = "Game not yet final";
  else if (!hasBook && !modelPoints.length) warnings.residuals = "No predictions to compare";
  if (!densitySources.length) warnings.density = "No spread/total source available";

  // ── Domain: fit to everything we will draw ───────────────────────────────
  function computeDomain() {
    const xs = [], ys = [];
    const push = (x, y) => { if (x != null && y != null && isFinite(x) && isFinite(y)) { xs.push(x); ys.push(y); } };
    homeGames.forEach(g => push(g.opponentScore, g.teamScore));
    awayGames.forEach(g => push(g.teamScore, g.opponentScore));
    meetings.forEach(m => push(m.awayTeamScore, m.homeTeamScore));
    modelPoints.forEach(p => push(p.away, p.home));
    if (implied) push(implied.away, implied.home);
    if (openingImplied) push(openingImplied.away, openingImplied.home);
    if (isFinal) push(data.actualAwayScore, data.actualHomeScore);
    if (data.homeAvgFor > 0) push(data.homeAvgAgainst, data.homeAvgFor);
    if (data.awayAvgFor > 0) push(data.awayAvgFor, data.awayAvgAgainst);
    // A team's 95% ellipse only stretches the domain once it has a real sample;
    // early-season ellipses are huge and would dwarf the actual games.
    const k = Math.sqrt(CHI2[95]);
    const sparse = homeGames.length < MIN_GAMES_FOR_FIT || awayGames.length < MIN_GAMES_FOR_FIT;
    if (homeGames.length >= MIN_GAMES_FOR_FIT && data.homeSdFor != null && data.homeSdAgainst != null) {
      push(data.homeMeanAgainst - k * data.homeSdAgainst, data.homeMeanFor - k * data.homeSdFor);
      push(data.homeMeanAgainst + k * data.homeSdAgainst, data.homeMeanFor + k * data.homeSdFor);
    }
    if (awayGames.length >= MIN_GAMES_FOR_FIT && data.awaySdFor != null && data.awaySdAgainst != null) {
      push(data.awayMeanFor - k * data.awaySdFor, data.awayMeanAgainst - k * data.awaySdAgainst);
      push(data.awayMeanFor + k * data.awaySdFor, data.awayMeanAgainst + k * data.awaySdAgainst);
    }
    // The selected density source's 95% band: extreme home/away offsets are
    // ±(k/2)·√(σm² + σt²) around its centre.
    const dsrc = densitySources.find(s => s.key === densityKey);
    if (dsrc && state.density.visible) {
      const reach = (k / 2) * Math.sqrt(sigmaM * sigmaM + sigmaT * sigmaT);
      const ch = (dsrc.total + dsrc.margin) / 2, ca = (dsrc.total - dsrc.margin) / 2;
      push(ca - reach, ch - reach); push(ca + reach, ch + reach);
    }
    if (!xs.length) return [40, 120];
    let min = Math.min(d3.min(xs), d3.min(ys));
    let max = Math.max(d3.max(xs), d3.max(ys));
    if (sparse && max - min > SPARSE_MAX_SPAN) {
      // Cap the span around the centre of the point cloud; whatever falls
      // outside is clipped at the plot edge rather than shrinking everything.
      const mid = (min + max) / 2;
      min = mid - SPARSE_MAX_SPAN / 2; max = mid + SPARSE_MAX_SPAN / 2;
    }
    let lo = Math.floor((min - 4) / 5) * 5;
    let hi = Math.ceil((max + 4) / 5) * 5;
    lo = Math.max(20, lo); hi = Math.min(160, hi);
    if (hi - lo < 30) { lo -= 5; hi += 5; }
    return [lo, hi];
  }

  // ── Sutherland–Hodgman clip of a polygon by the half-plane a·x + b·y + c ≥ 0 ─
  function clipHalfPlane(poly, a, b, c) {
    const out = [];
    const n = poly.length;
    for (let i = 0; i < n; i++) {
      const p = poly[i], q = poly[(i + 1) % n];
      const fp = a * p[0] + b * p[1] + c, fq = a * q[0] + b * q[1] + c;
      if (fp >= 0) out.push(p);
      if ((fp >= 0) !== (fq >= 0)) {
        const t = fp / (fp - fq);
        out.push([p[0] + t * (q[0] - p[0]), p[1] + t * (q[1] - p[1])]);
      }
    }
    return out;
  }

  // ── Draw ─────────────────────────────────────────────────────────────────
  let firstDraw = true;
  let setView = null;   // assigned by drawChart; animates between the two views

  function drawChart(animate) {
    const compact = container.clientWidth > 0 && container.clientWidth < 500;
    const MARGIN = compact
      ? { top: 34, right: 40, bottom: 62, left: 66 }
      : { top: 52, right: 72, bottom: 84, left: 96 };
    const SIZE = compact ? 520 : 800;
    // The plot area is exactly square so the h = a diagonal sits at 45° on
    // screen and a rigid 45° rotation lands it perfectly horizontal.
    const innerW = SIZE - MARGIN.left - MARGIN.right;
    const innerH = innerW;
    const totalW = SIZE, totalH = innerH + MARGIN.top + MARGIN.bottom;
    const anim = animate && !REDUCED_MOTION;
    const T = (delay, dur) => anim ? { delay, dur } : { delay: 0, dur: 0 };

    const [LO, HI] = computeDomain();
    const SPAN = HI - LO, MID = (LO + HI) / 2;
    const PX = innerW / SPAN;   // pixels per point
    const xScale = d3.scaleLinear().domain([LO, HI]).range([0, innerW]);
    const yScale = d3.scaleLinear().domain([LO, HI]).range([innerH, 0]);
    const RECT = [[LO, LO], [HI, LO], [HI, HI], [LO, HI]];
    // Oversized square: tints/lines drawn to it still fill the viewport after
    // rotation (the plot clip trims the excess in either view).
    const BIG = [[LO - SPAN, LO - SPAN], [HI + SPAN, LO - SPAN], [HI + SPAN, HI + SPAN], [LO - SPAN, HI + SPAN]];
    const toPath = poly => "M" + poly.map(p => xScale(p[0]) + "," + yScale(p[1])).join("L") + "Z";
    const cx = innerW / 2, cy = innerH / 2;

    // Margin/Total view scales (what the rotated plot lines up with). A unit
    // step on screen along the rotated axes is √2 score points.
    const REACH = SPAN / Math.SQRT2;
    const totalScale  = d3.scaleLinear().domain([2 * MID - REACH, 2 * MID + REACH]).range([0, innerW]);
    const marginScale = d3.scaleLinear().domain([-REACH, REACH]).range([innerH, 0]);
    /** Screen position of score-space point (away, home) in the current view. */
    function proj(a, h) {
      return rotated ? [totalScale(a + h), marginScale(h - a)] : [xScale(a), yScale(h)];
    }
    const plotTransform = deg => `translate(${cx},${cy}) rotate(${deg}) translate(${-cx},${-cy})`;

    const svg = d3.select(container).append("svg")
      .attr("viewBox", `0 0 ${totalW} ${totalH}`)
      .attr("preserveAspectRatio", "xMidYMid meet")
      .attr("font-family", "inherit");
    const defs = svg.append("defs");
    const g = svg.append("g").attr("transform", `translate(${MARGIN.left},${MARGIN.top})`);

    defs.append("clipPath").attr("id", "game-chart-plot-clip")
      .append("rect").attr("x", 0).attr("y", 0).attr("width", innerW).attr("height", innerH);

    // Arrowheads, one per color (marker fill can't follow the path's stroke)
    const arrowIds = {};
    function arrowFor(color) {
      const id = "gc-arrow-" + color.replace(/[^a-z0-9]/gi, "");
      if (!arrowIds[id]) {
        defs.append("marker").attr("id", id)
          .attr("viewBox", "0 0 10 10").attr("refX", 9).attr("refY", 5)
          .attr("markerWidth", 7).attr("markerHeight", 7).attr("orient", "auto-start-reverse")
          .append("path").attr("d", "M0,0 L10,5 L0,10 Z").attr("fill", color);
        arrowIds[id] = true;
      }
      return `url(#${id})`;
    }

    // ── Frames: stationary chrome for each view (grid, axes, titles, rugs) ─
    const scoreFrame = g.append("g").attr("class", "gc-frame-score")
      .attr("opacity", rotated ? 0 : 1).attr("pointer-events", rotated ? "none" : null);
    const mtFrame = g.append("g").attr("class", "gc-frame-mt")
      .attr("opacity", rotated ? 1 : 0).attr("pointer-events", rotated ? null : "none");
    const frameClipS = scoreFrame.append("g").attr("clip-path", "url(#game-chart-plot-clip)");
    const frameClipM = mtFrame.append("g").attr("clip-path", "url(#game-chart-plot-clip)");

    // Score grid + axes
    const majorStep = SPAN <= 100 ? 10 : 20;
    const majorTicks = d3.range(Math.ceil(LO / majorStep) * majorStep, HI + 0.01, majorStep);
    const minorTicks = d3.range(LO, HI + 0.01, majorStep / 2).filter(v => !majorTicks.includes(v));
    function drawGrid(parent, xs, ys, xsMinor, ysMinor, sx, sy) {
      [[xs, ys, 1, 0.5], [xsMinor, ysMinor, 0.5, 0.3]].forEach(([tx, ty, w, op]) => {
        parent.selectAll(null).data(tx).enter().append("line")
          .attr("x1", d => sx(d)).attr("x2", d => sx(d)).attr("y1", 0).attr("y2", innerH)
          .attr("stroke", THEME.grid).attr("stroke-width", w).attr("stroke-opacity", op);
        parent.selectAll(null).data(ty).enter().append("line")
          .attr("x1", 0).attr("x2", innerW).attr("y1", d => sy(d)).attr("y2", d => sy(d))
          .attr("stroke", THEME.grid).attr("stroke-width", w).attr("stroke-opacity", op);
      });
    }
    drawGrid(frameClipS, majorTicks, majorTicks, minorTicks, minorTicks, xScale, yScale);
    scoreFrame.append("g").attr("transform", `translate(0,${innerH})`)
      .call(d3.axisBottom(xScale).tickValues(majorTicks));
    scoreFrame.append("g").call(d3.axisLeft(yScale).tickValues(majorTicks));

    // Margin/Total grid + axes
    const tTicks = totalScale.ticks(8), mTicks = marginScale.ticks(8);
    drawGrid(frameClipM, tTicks, mTicks, [], [], totalScale, marginScale);
    // Zero-margin line + win labels: the "who wins" boundary in this view
    // (the rotated diagonal lands here too, but this survives layer toggles)
    {
      const y0 = marginScale(0);
      frameClipM.append("line").attr("x1", 0).attr("x2", innerW).attr("y1", y0).attr("y2", y0)
        .attr("stroke", THEME.neutral).attr("stroke-width", 1.25).attr("stroke-opacity", 0.7);
      if (!compact) {
        frameClipM.append("text").attr("x", 14).attr("y", y0 - 7)
          .attr("font-size", "11px").attr("font-weight", "700").attr("letter-spacing", "0.08em")
          .attr("fill", homeColor).attr("fill-opacity", 0.75)
          .text(`${data.homeAbbr.toUpperCase()} WINS`);
        frameClipM.append("text").attr("x", 14).attr("y", y0 + 16)
          .attr("font-size", "11px").attr("font-weight", "700").attr("letter-spacing", "0.08em")
          .attr("fill", awayColor).attr("fill-opacity", 0.75)
          .text(`${data.awayAbbr.toUpperCase()} WINS`);
      }
    }
    mtFrame.append("g").attr("transform", `translate(0,${innerH})`)
      .call(d3.axisBottom(totalScale).tickValues(tTicks));
    mtFrame.append("g").call(d3.axisLeft(marginScale).tickValues(mTicks)
      .tickFormat(v => (v > 0 ? "+" : "") + v));

    g.selectAll(".domain, .tick line").attr("stroke", THEME.axis);
    g.selectAll(".tick text").attr("fill", THEME.axis);
    // Top/right borders shared by both views
    g.append("line").attr("x1", 0).attr("y1", 0).attr("x2", innerW).attr("y2", 0)
      .attr("stroke", THEME.grid).attr("stroke-width", 1);
    g.append("line").attr("x1", innerW).attr("y1", 0).attr("x2", innerW).attr("y2", innerH)
      .attr("stroke", THEME.grid).attr("stroke-width", 1);

    // ── Axis labels: score view = [logo] team name; MT view = titles ───────
    const LOGO = compact ? 24 : 32;
    const labelSize = compact ? "12px" : "14px";
    const xLabelY = innerH + (compact ? 46 : 60);
    {
      const xLabelG = scoreFrame.append("g");
      if (data.awayLogoUrl) {
        xLabelG.append("image").attr("href", data.awayLogoUrl)
          .attr("width", LOGO).attr("height", LOGO)
          .attr("x", innerW / 2 - LOGO / 2).attr("y", xLabelY - LOGO - 4);
      }
      xLabelG.append("text").attr("x", innerW / 2).attr("y", xLabelY + (data.awayLogoUrl ? 4 : 0))
        .attr("text-anchor", "middle").attr("font-size", labelSize).attr("font-weight", "bold")
        .attr("fill", awayColor)
        .text(compact ? data.awayAbbr : `${data.awayFullName} (${data.awayAbbr})`);
      const yLabelG = scoreFrame.append("g")
        .attr("transform", `rotate(-90) translate(${-innerH / 2}, ${-MARGIN.left + (compact ? 30 : 44)})`);
      if (data.homeLogoUrl) {
        yLabelG.append("image").attr("href", data.homeLogoUrl)
          .attr("width", LOGO).attr("height", LOGO).attr("x", -LOGO / 2).attr("y", -LOGO - 6);
      }
      yLabelG.append("text").attr("x", 0).attr("y", 6)
        .attr("text-anchor", "middle").attr("font-size", labelSize).attr("font-weight", "bold")
        .attr("fill", homeColor)
        .text(compact ? data.homeAbbr : `${data.homeFullName} (${data.homeAbbr})`);

      mtFrame.append("text").attr("x", innerW / 2).attr("y", xLabelY - 6)
        .attr("text-anchor", "middle").attr("font-size", labelSize).attr("font-weight", "bold")
        .attr("fill", THEME.axis).text("Total points");
      mtFrame.append("text")
        .attr("transform", `rotate(-90) translate(${-innerH / 2}, ${-MARGIN.left + (compact ? 22 : 34)})`)
        .attr("text-anchor", "middle").attr("font-size", labelSize).attr("font-weight", "bold")
        .attr("fill", homeColor)
        .text(compact ? `${data.homeAbbr} margin` : `${data.homeAbbr} margin (${data.homeAbbr} − ${data.awayAbbr})`);
    }

    // ── Tooltip ────────────────────────────────────────────────────────────
    const tooltip = d3.select("body").append("div").attr("class", "game-chart-tooltip");
    function showTip(event, text) {
      tooltip.style("display", "block").text(text)
        .style("left", (event.pageX + 14) + "px")
        .style("top",  (event.pageY - 28) + "px");
    }
    function hideTip() { tooltip.style("display", "none"); }

    // ── Rotating plot + layers (z-order by insertion) ──────────────────────
    const plotClip = g.append("g").attr("clip-path", "url(#game-chart-plot-clip)");
    const plot = plotClip.append("g").attr("transform", plotTransform(rotated ? ROT : 0));
    const layers = {
      regions:       plot.append("g"),
      quadrants:     plot.append("g"),
      density:       plot.append("g"),
      lineMovement:  plot.append("g"),
      spreadLine:    plot.append("g"),
      ouLine:        plot.append("g"),
      iqrBox:        plot.append("g"),
      ellipse:       plot.append("g"),
      pastMeetings:  plot.append("g"),
      seasonMarkers: plot.append("g"),
      avgMarker:     plot.append("g"),
      modelMarkers:  plot.append("g"),
      impliedMarker: plot.append("g"),
      residuals:     plot.append("g"),
      resultMarker:  plot.append("g"),
    };
    // Rugs are stationary chrome, one set per view; the legend toggles both.
    const rugScore = scoreFrame.append("g"), rugMt = mtFrame.append("g");
    layers.marginals = { attr: (name, v) => { rugScore.attr(name, v); rugMt.attr(name, v); return layers.marginals; } };

    const crosshair = g.append("g").attr("pointer-events", "none").style("display", "none");
    const labelHalo = sel => sel.attr("paint-order", "stroke").attr("stroke", SURFACE)
      .attr("stroke-width", 3).attr("stroke-linejoin", "round");
    /** Child group that counter-rotates so its contents stay upright in either view. */
    const upright = parent => parent.append("g").attr("class", "gc-upright")
      .attr("transform", `rotate(${rotated ? -ROT : 0})`);
    /** Positioned group at a score-space point (rotates with the plot). */
    const at = (parent, a, h) => parent.append("g").attr("transform", `translate(${xScale(a)},${yScale(h)})`);

    // ── Crosshair (hover guide lines to both axes, in the current view) ────
    crosshair.append("line").attr("class", "ch-x").attr("stroke", THEME.axis)
      .attr("stroke-width", 1).attr("stroke-dasharray", "3,3").attr("stroke-opacity", 0.8);
    crosshair.append("line").attr("class", "ch-y").attr("stroke", THEME.axis)
      .attr("stroke-width", 1).attr("stroke-dasharray", "3,3").attr("stroke-opacity", 0.8);
    const chLabelX = crosshair.append("g");
    chLabelX.append("rect").attr("rx", 3).attr("fill", THEME.axis);
    chLabelX.append("text").attr("font-size", "10px").attr("font-weight", "600")
      .attr("fill", SURFACE).attr("text-anchor", "middle");
    const chLabelY = crosshair.append("g");
    chLabelY.append("rect").attr("rx", 3).attr("fill", THEME.axis);
    chLabelY.append("text").attr("font-size", "10px").attr("font-weight", "600")
      .attr("fill", SURFACE).attr("text-anchor", "middle");

    const fmtNum = v => Number.isInteger(v) ? String(v) : v.toFixed(1);
    function showCrosshair(a, h) {
      const [px, py] = proj(a, h);
      const xv = rotated ? fmtNum(a + h) : fmtNum(a);
      const yv = rotated ? (h - a > 0 ? "+" : "") + fmtNum(h - a) : fmtNum(h);
      crosshair.style("display", null);
      crosshair.select(".ch-x").attr("x1", px).attr("x2", px).attr("y1", py).attr("y2", innerH);
      crosshair.select(".ch-y").attr("x1", 0).attr("x2", px).attr("y1", py).attr("y2", py);
      chLabelX.attr("transform", `translate(${px},${innerH + 2})`);
      chLabelX.select("rect").attr("x", -16).attr("y", 0).attr("width", 32).attr("height", 14);
      chLabelX.select("text").attr("x", 0).attr("y", 10.5).text(xv);
      chLabelY.attr("transform", `translate(-2,${py})`);
      chLabelY.select("rect").attr("x", -32).attr("y", -7).attr("width", 32).attr("height", 14);
      chLabelY.select("text").attr("x", -16).attr("y", 3.5).text(yv);
    }
    function hideCrosshair() { crosshair.style("display", "none"); }

    // ── Entrance helpers ───────────────────────────────────────────────────
    function fadeIn(sel, targetOpacity, t) {
      if (!anim) { sel.attr("opacity", targetOpacity); return; }
      sel.attr("opacity", 0).transition().delay(t.delay).duration(t.dur).attr("opacity", targetOpacity);
    }
    /** Wrap in a <g> at score point (a,h); scales from 0 → 1 on entrance. Children draw relative to (0,0). */
    function popGroup(parent, a, h, t, ease) {
      const px = xScale(a), py = yScale(h);
      const wrap = parent.append("g").attr("transform", `translate(${px},${py}) scale(${anim ? 0 : 1})`);
      if (anim) wrap.transition().delay(t.delay).duration(t.dur).ease(ease || d3.easeBackOut)
        .attr("transform", `translate(${px},${py}) scale(1)`);
      return wrap;
    }

    // ── Clip a data-space segment to a square [lo,hi]² (Liang–Barsky) ──────
    function clipLine(x1, y1, x2, y2, lo, hi) {
      lo = lo == null ? LO : lo; hi = hi == null ? HI : hi;
      const dx = x2 - x1, dy = y2 - y1;
      const p = [-dx, dx, -dy, dy];
      const q = [x1 - lo, hi - x1, y1 - lo, hi - y1];
      let t0 = 0, t1 = 1;
      for (let i = 0; i < 4; i++) {
        if (p[i] === 0) { if (q[i] < 0) return null; }
        else {
          const t = q[i] / p[i];
          if (p[i] < 0) { if (t > t1) return null; if (t > t0) t0 = t; }
          else          { if (t < t0) return null; if (t < t1) t1 = t; }
        }
      }
      return { x1: x1 + t0 * dx, y1: y1 + t0 * dy, x2: x1 + t1 * dx, y2: y1 + t1 * dy };
    }
    /** A line across the whole (oversized) plane; the plot clip trims it in either view. */
    function drawDataLine(layer, x1, y1, x2, y2, color, width, dash, opacity) {
      const seg = clipLine(x1, y1, x2, y2, LO - SPAN, HI + SPAN);
      if (!seg) return null;
      return layer.append("line")
        .attr("x1", xScale(seg.x1)).attr("y1", yScale(seg.y1))
        .attr("x2", xScale(seg.x2)).attr("y2", yScale(seg.y2))
        .attr("stroke", color).attr("stroke-width", width)
        .attr("stroke-dasharray", dash || null).attr("stroke-opacity", opacity == null ? 1 : opacity);
    }
    // Labels sit 18% along the visible (score-view) segment, clear of the
    // implied-score cluster that usually sits mid-line.
    function lineLabel(layer, x1, y1, x2, y2, text, color) {
      const seg = clipLine(x1, y1, x2, y2);
      if (!seg) return;
      const tt = 0.18;
      const mx = seg.x1 + (seg.x2 - seg.x1) * tt, my = seg.y1 + (seg.y2 - seg.y1) * tt;
      const w = Math.max(44, text.length * 6.5 + 12);
      const lbl = upright(at(layer, mx, my));
      lbl.append("rect").attr("x", -w / 2).attr("y", -10)
        .attr("width", w).attr("height", 18).attr("rx", 4)
        .attr("fill", SURFACE).attr("opacity", 0.92);
      lbl.append("text").attr("x", 0).attr("y", 4)
        .attr("text-anchor", "middle").attr("font-size", "11px").attr("font-weight", "600")
        .attr("fill", color).text(text);
    }
    const fmtSigned = v => (v > 0 ? "+" : "") + v.toFixed(1);
    const fmtSpreadHandicap = s => (s > 0 ? "+" : "") + s.toFixed(1);
    const fmtPct = p => Math.round(p * 100) + "%";
    const Phi = z => 0.5 * (1 + erf(z / Math.SQRT2));
    function erf(x) { // Abramowitz–Stegun 7.1.26, |err| < 1.5e-7
      const s = x < 0 ? -1 : 1; x = Math.abs(x);
      const t = 1 / (1 + 0.3275911 * x);
      const y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x);
      return s * y;
    }

    // ── 1. Win regions: tint each side of the diagonal ─────────────────────
    {
      const homeSide = clipHalfPlane(BIG, -1, 1, 0);   // h ≥ a
      const awaySide = clipHalfPlane(BIG,  1, -1, 0);  // h ≤ a
      const tint = layers.regions.append("g");
      tint.append("path").attr("d", toPath(homeSide)).attr("fill", homeColor).attr("fill-opacity", 0.06);
      tint.append("path").attr("d", toPath(awaySide)).attr("fill", awayColor).attr("fill-opacity", 0.06);
      fadeIn(tint, 1, T(0, 600));
      const diag = drawDataLine(layers.regions, LO - SPAN, LO - SPAN, HI + SPAN, HI + SPAN, THEME.neutral, 1.25, null, 0.7);
      if (diag) fadeIn(diag, 1, T(100, 500));
      if (!compact) {
        // Corner labels in score-space; they ride the rotation but stay upright.
        const pad = 14 / PX;
        const lblH = upright(at(layers.regions, LO + pad, HI - pad - 10 / PX));
        lblH.append("text").attr("font-size", "11px").attr("font-weight", "700")
          .attr("letter-spacing", "0.08em").attr("fill", homeColor).attr("fill-opacity", 0.75)
          .text(`${data.homeAbbr.toUpperCase()} WINS`);
        const lblA = upright(at(layers.regions, HI - pad, LO + pad));
        lblA.append("text").attr("text-anchor", "end").attr("font-size", "11px").attr("font-weight", "700")
          .attr("letter-spacing", "0.08em").attr("fill", awayColor).attr("fill-opacity", 0.75)
          .text(`${data.awayAbbr.toUpperCase()} WINS`);
        fadeIn(lblH, 1, T(300, 500)); fadeIn(lblA, 1, T(300, 500));
      }
    }

    // ── 2. Betting quadrants: wedges between the spread and total lines ────
    if (hasBook) {
      const sp = data.spread, ou = data.overUnder;
      const homeCovers = [-1, 1, sp];       // h − a + spread ≥ 0
      const awayCovers = [1, -1, -sp];
      const over  = [1, 1, -ou];            // a + h − OU ≥ 0
      const under = [-1, -1, ou];
      const wedges = [
        { key: "hc-over",  hp: [homeCovers, over],  text: `${data.homeAbbr} covers · Over` },
        { key: "hc-under", hp: [homeCovers, under], text: `${data.homeAbbr} covers · Under` },
        { key: "ac-over",  hp: [awayCovers, over],  text: `${data.awayAbbr} covers · Over` },
        { key: "ac-under", hp: [awayCovers, under], text: `${data.awayAbbr} covers · Under` },
      ];
      const inWedge = (x, y, hps) => hps.every(h => h[0] * x + h[1] * y + h[2] >= 0);
      wedges.forEach(w => {
        let poly = RECT, hover = BIG;
        w.hp.forEach(h => { poly = clipHalfPlane(poly, h[0], h[1], h[2]); hover = clipHalfPlane(hover, h[0], h[1], h[2]); });
        if (hover.length < 3) return;
        const hCount = homeGames.filter(gm => inWedge(gm.opponentScore, gm.teamScore, w.hp)).length;
        const aCount = awayGames.filter(gm => inWedge(gm.teamScore, gm.opponentScore, w.hp)).length;
        const grp = layers.quadrants.append("g").style("cursor", "default");
        grp.append("path").attr("d", toPath(hover)).attr("fill", "transparent").attr("pointer-events", "all");
        if (!compact && poly.length >= 3) {
          const cen = d3.polygonCentroid(poly);
          const lbl = upright(at(grp, cen[0], cen[1])).attr("class", "gc-upright gc-quadrant-label").attr("opacity", 0.6);
          lbl.append("text").attr("text-anchor", "middle").attr("font-size", "11px").attr("font-weight", "700")
            .attr("fill", THEME.axis).text(w.text).call(labelHalo);
          if (homeGames.length || awayGames.length) {
            lbl.append("text").attr("class", "gc-quadrant-count").attr("text-anchor", "middle").attr("y", 14).attr("font-size", "10px")
              .attr("fill", THEME.axis).call(labelHalo)
              .text(`${data.homeAbbr} ${hCount}/${homeGames.length} · ${data.awayAbbr} ${aCount}/${awayGames.length}`)
              .datum(w.hp);
          }
        }
        grp.on("mouseover", (e) => {
          grp.select(".gc-quadrant-label").attr("opacity", 1);
          showTip(e, `${w.text}\nThis season's games landing here: ` +
            `${data.homeAbbr} ${hCount} of ${homeGames.length}, ${data.awayAbbr} ${aCount} of ${awayGames.length}`);
        }).on("mousemove", (e) => tooltip.style("left", (e.pageX + 14) + "px").style("top", (e.pageY - 28) + "px"))
          .on("mouseout", () => { grp.select(".gc-quadrant-label").attr("opacity", 0.6); hideTip(); });
      });
      fadeIn(layers.quadrants, 1, T(500, 500));
    }

    // ── 3. Outcome density: bivariate normal in (margin, total) space ──────
    {
      const src = densitySources.find(s => s.key === densityKey);
      if (src) {
        const cHome = (src.total + src.margin) / 2, cAway = (src.total - src.margin) / 2;
        const wrap = popGroup(layers.density, cAway, cHome, T(200, 700), d3.easeCubicOut);
        [95, 75, 50, 25].forEach(level => {
          const k = Math.sqrt(CHI2[level]);
          const pts = d3.range(97).map(i => {
            const th = (i / 96) * 2 * Math.PI;
            const m = sigmaM * k * Math.cos(th), t = sigmaT * k * Math.sin(th);
            const h = (t + m) / 2, a = (t - m) / 2;
            return [xScale(cAway + a) - xScale(cAway), yScale(cHome + h) - yScale(cHome)];
          });
          wrap.append("path").attr("d", "M" + pts.map(p => p.join(",")).join("L") + "Z")
            .attr("fill", src.color).attr("fill-opacity", 0.09)
            .attr("stroke", src.color).attr("stroke-opacity", 0.25).attr("stroke-width", 0.75);
        });
      }
      renderReadout(src || null);
    }
    function renderReadout(src) {
      if (!readoutEl) return;
      readoutEl.textContent = "";
      if (!src) return;
      const pWin   = src.winProb != null ? src.winProb : Phi(src.margin / sigmaM);
      const pCover = hasBook ? Phi((src.margin + data.spread) / sigmaM) : null;
      const pOver  = hasBook ? Phi((src.total - data.overUnder) / sigmaT) : null;
      const title = document.createElement("div");
      title.className = "game-chart-readout__title";
      const sw = document.createElement("span");
      sw.className = "game-chart-readout__swatch"; sw.style.backgroundColor = src.color;
      title.appendChild(sw);
      title.appendChild(document.createTextNode(`${src.label} outcome density`));
      readoutEl.appendChild(title);
      const rows = [
        [`${data.homeAbbr} ${src.margin >= 0 ? "-" : "+"}${Math.abs(src.margin).toFixed(1)}`, `total ${src.total.toFixed(1)}`],
        [`${data.homeAbbr} wins`, fmtPct(pWin)],
      ];
      if (pCover != null) rows.push([`${data.homeAbbr} covers ${fmtSpreadHandicap(data.spread)}`, fmtPct(pCover)]);
      if (pOver != null)  rows.push([`Over ${data.overUnder.toFixed(1)}`, fmtPct(pOver)]);
      rows.forEach(([k, v]) => {
        const row = document.createElement("div"); row.className = "game-chart-readout__row";
        const kk = document.createElement("span"); kk.textContent = k;
        const vv = document.createElement("strong"); vv.textContent = v;
        row.appendChild(kk); row.appendChild(vv); readoutEl.appendChild(row);
      });
      const note = document.createElement("div"); note.className = "game-chart-readout__note";
      note.textContent = `Bands: 25 / 50 / 75 / 95%, σ margin ${sigmaM.toFixed(1)}, σ total ${sigmaT.toFixed(1)}`;
      readoutEl.appendChild(note);
    }

    // ── 4. Line movement: opening lines as ghosts + arrow to current implied ─
    if (hasOpening) {
      const os = data.openingSpread, oo = data.openingOverUnder;
      const ghost = layers.lineMovement.append("g");
      drawDataLine(ghost, LO - SPAN, LO - SPAN - os, HI + SPAN, HI + SPAN - os, BOOK_COLOR, 1.25, "5,5", 0.35);
      drawDataLine(ghost, LO - SPAN, oo - (LO - SPAN), HI + SPAN, oo - (HI + SPAN), BOOK_COLOR, 1.25, "5,5,2,5", 0.35);
      if (lineMoved && implied) {
        const x1 = xScale(openingImplied.away), y1 = yScale(openingImplied.home);
        const x2 = xScale(implied.away), y2 = yScale(implied.home);
        const dx = x2 - x1, dy = y2 - y1, len = Math.hypot(dx, dy) || 1;
        const trim = Math.min(9, len / 2);
        const tipText = `Line movement\nOpened ${data.homeAbbr} ${fmtSpreadHandicap(os)} / O/U ${oo.toFixed(1)}` +
          `\nNow ${data.homeAbbr} ${fmtSpreadHandicap(data.spread)} / O/U ${data.overUnder.toFixed(1)}`;
        ghost.append("line")
          .attr("x1", x1).attr("y1", y1)
          .attr("x2", x2 - dx / len * trim).attr("y2", y2 - dy / len * trim)
          .attr("stroke", BOOK_COLOR).attr("stroke-width", 2).attr("stroke-opacity", 0.85)
          .attr("marker-end", arrowFor(BOOK_COLOR)).style("cursor", "default")
          .on("mouseover", e => showTip(e, tipText)).on("mouseout", hideTip);
        ghost.append("circle").attr("cx", x1).attr("cy", y1).attr("r", 4)
          .attr("fill", SURFACE).attr("stroke", BOOK_COLOR).attr("stroke-width", 1.5).attr("stroke-opacity", 0.7)
          .on("mouseover", e => showTip(e, tipText)).on("mouseout", hideTip);
      }
      fadeIn(ghost, 1, T(400, 500));
    }

    // ── 5. Spread + O/U lines ──────────────────────────────────────────────
    if (data.spread != null) {
      const grp = layers.spreadLine.append("g");
      drawDataLine(grp, LO - SPAN, LO - SPAN - data.spread, HI + SPAN, HI + SPAN - data.spread, BOOK_COLOR, 2, "6,5", 0.85);
      lineLabel(grp, LO, LO - data.spread, HI, HI - data.spread, `${data.homeAbbr} ${fmtSpreadHandicap(data.spread)}`, BOOK_COLOR);
      fadeIn(grp, 1, T(150, 500));
    }
    if (data.overUnder != null) {
      const grp = layers.ouLine.append("g");
      drawDataLine(grp, LO - SPAN, data.overUnder - (LO - SPAN), HI + SPAN, data.overUnder - (HI + SPAN), BOOK_COLOR, 2, "6,5,2,5", 0.85);
      lineLabel(grp, LO, data.overUnder - LO, HI, data.overUnder - HI, `O/U ${data.overUnder.toFixed(1)}`, BOOK_COLOR);
      fadeIn(grp, 1, T(200, 500));
    }

    // ── 6. Rugs (per-game marginal ticks, linked to markers) ───────────────
    // Score view: home scored (left) / allowed (bottom); away scored (top) /
    // allowed (right). Margin/Total view: margins on left/right, totals on
    // bottom/top, in this chart's home-margin orientation.
    const RUG_LEN = compact ? 8 : 11, RUG_GAP = 2;
    function gamePoint(gm, side) {   // score-space (away, home) of a season game
      return side === "home" ? [gm.opponentScore, gm.teamScore] : [gm.teamScore, gm.opponentScore];
    }
    function drawRug(parent, games, side, orient, value, scale, color) {
      const [d0, d1] = scale.domain();
      const ticks = games.filter(gm => { const v = value(gm); return v >= Math.min(d0, d1) && v <= Math.max(d0, d1); });
      parent.selectAll(null).data(ticks).enter().append("line")
        .attr("class", "gc-rug")
        .attr("data-game-id", gm => gm.gameId)
        .attr("data-day", gm => isoToDay(gm.date))
        .each(function (gm) {
          const s = value(gm);
          let x1, y1, x2, y2;
          if (orient === "left")        { y1 = y2 = scale(s); x1 = -RUG_GAP;         x2 = -RUG_GAP - RUG_LEN; }
          else if (orient === "right")  { y1 = y2 = scale(s); x1 = innerW + RUG_GAP; x2 = innerW + RUG_GAP + RUG_LEN; }
          else if (orient === "top")    { x1 = x2 = scale(s); y1 = -RUG_GAP;         y2 = -RUG_GAP - RUG_LEN; }
          else                          { x1 = x2 = scale(s); y1 = innerH + RUG_GAP; y2 = innerH + RUG_GAP + RUG_LEN; }
          d3.select(this).attr("x1", x1).attr("y1", y1).attr("x2", x2).attr("y2", y2);
        })
        .attr("stroke", color).attr("stroke-width", 2.25)
        .attr("stroke-opacity", 0.45).attr("stroke-linecap", "round")
        .style("cursor", "pointer")
        .on("mouseover", (e, gm) => {
          const [a, h] = gamePoint(gm, side);
          highlightGame(gm.gameId, true); showCrosshair(a, h); showTip(e, gameTipText(gm, side));
        })
        .on("mouseout", (e, gm) => { highlightGame(gm.gameId, false); hideCrosshair(); hideTip(); })
        .on("click", (e, gm) => { window.location.href = "/games/" + gm.gameId; });
    }
    const RUG_LBL_PAD = 5, RUG_LBL_NUDGE = 26;
    function drawRugLabel(parent, text, orient, color) {
      const lbl = parent.append("text")
        .attr("font-size", "10px").attr("font-weight", "600")
        .attr("fill", color).attr("fill-opacity", 0.85).text(text);
      if (orient === "left")
        lbl.attr("text-anchor", "start").attr("transform", `translate(${RUG_LBL_PAD + 9}, ${innerH - RUG_LBL_PAD - RUG_LBL_NUDGE}) rotate(-90)`);
      else if (orient === "bottom")
        lbl.attr("text-anchor", "start").attr("x", RUG_LBL_PAD + RUG_LBL_NUDGE).attr("y", innerH - RUG_LBL_PAD);
      else if (orient === "right")
        lbl.attr("text-anchor", "start").attr("transform", `translate(${innerW - RUG_LBL_PAD - 9}, ${RUG_LBL_PAD + RUG_LBL_NUDGE}) rotate(90)`);
      else
        lbl.attr("text-anchor", "end").attr("x", innerW - RUG_LBL_PAD - RUG_LBL_NUDGE).attr("y", RUG_LBL_PAD + 10);
    }
    if (homeGames.length) {
      drawRug(rugScore, homeGames, "home", "left",   gm => gm.teamScore,     yScale, homeColor);
      drawRug(rugScore, homeGames, "home", "bottom", gm => gm.opponentScore, xScale, homeColor);
      drawRug(rugMt, homeGames, "home", "left",   gm => gm.teamScore - gm.opponentScore, marginScale, homeColor);
      drawRug(rugMt, homeGames, "home", "bottom", gm => gm.teamScore + gm.opponentScore, totalScale,  homeColor);
      if (!compact) {
        drawRugLabel(rugScore, `${data.homeAbbr} scored`,  "left",   homeColor);
        drawRugLabel(rugScore, `${data.homeAbbr} allowed`, "bottom", homeColor);
        drawRugLabel(rugMt, `${data.homeAbbr} margin`, "left",   homeColor);
        drawRugLabel(rugMt, `${data.homeAbbr} totals`, "bottom", homeColor);
      }
    }
    if (awayGames.length) {
      drawRug(rugScore, awayGames, "away", "top",   gm => gm.teamScore,     xScale, awayColor);
      drawRug(rugScore, awayGames, "away", "right", gm => gm.opponentScore, yScale, awayColor);
      drawRug(rugMt, awayGames, "away", "top",   gm => gm.teamScore + gm.opponentScore, totalScale,  awayColor);
      drawRug(rugMt, awayGames, "away", "right", gm => gm.opponentScore - gm.teamScore, marginScale, awayColor);
      if (!compact) {
        drawRugLabel(rugScore, `${data.awayAbbr} scored`,  "top",   awayColor);
        drawRugLabel(rugScore, `${data.awayAbbr} allowed`, "right", awayColor);
        drawRugLabel(rugMt, `${data.awayAbbr} totals`,  "top",   awayColor);
        drawRugLabel(rugMt, `vs ${data.awayAbbr} margin`, "right", awayColor);
      }
    }
    fadeIn(rugScore, 1, T(350, 600));

    // ── Season-form geometry helpers (shared by first draw and the scrubber) ─
    /** [Q1, Q3] with the same index rule as the server (n/4, 3n/4), or null under 4 values. */
    function iqrOf(values) {
      if (values.length < 4) return null;
      const s = values.slice().sort((a, b) => a - b);
      return [s[Math.floor(s.length / 4)], s[Math.floor(s.length * 3 / 4)]];
    }
    const meanOf = values => values.length ? d3.mean(values) : null;
    /** IQR box in score space {cAway, cHome, w, h} for a team's game list, or null. */
    function iqrBoxOf(games, side) {
      const forQ = iqrOf(games.map(gm => gm.teamScore)), agQ = iqrOf(games.map(gm => gm.opponentScore));
      if (!forQ || !agQ) return null;
      const [xa, xb] = side === "home" ? agQ : forQ, [ya, yb] = side === "home" ? forQ : agQ;
      return { cAway: (xa + xb) / 2, cHome: (ya + yb) / 2,
               w: Math.abs(xScale(xb) - xScale(xa)), h: Math.abs(yScale(yb) - yScale(ya)) };
    }
    /** Average marker point {a, h} for a team's game list, or null. */
    function avgOf(games, side) {
      const f = meanOf(games.map(gm => gm.teamScore)), ag = meanOf(games.map(gm => gm.opponentScore));
      if (f == null || ag == null) return null;
      return side === "home" ? { a: ag, h: f, forVal: f, againstVal: ag } : { a: f, h: ag, forVal: f, againstVal: ag };
    }
    function eigen2x2(a, b, d) {
      const tr = a + d, det = a * d - b * b;
      const disc = Math.sqrt(Math.max(0, (tr / 2) ** 2 - det));
      const l1 = tr / 2 + disc, l2 = tr / 2 - disc;
      let v1x, v1y;
      if (Math.abs(b) > 1e-10) { v1x = l1 - d; v1y = b; } else { v1x = 1; v1y = 0; }
      const n = Math.sqrt(v1x * v1x + v1y * v1y);
      return { l1, l2, v1x: v1x / n, v1y: v1y / n };
    }
    /** Ellipse geometry {cAway, cHome, d95, d50} (paths relative to centre), or null. */
    function ellipseOf(snap, isHome) {
      if (!snap || snap.sdFor == null || snap.sdAgainst == null || snap.corr == null ||
          snap.meanFor == null || snap.meanAgainst == null) return null;
      const cov = snap.corr * snap.sdFor * snap.sdAgainst;
      const { l1, l2, v1x, v1y } = eigen2x2(snap.sdFor * snap.sdFor, cov, snap.sdAgainst * snap.sdAgainst);
      const theta = Math.atan2(v1y, v1x);
      const cAway = isHome ? snap.meanAgainst : snap.meanFor, cHome = isHome ? snap.meanFor : snap.meanAgainst;
      const path = level => {
        const a = Math.sqrt(CHI2[level] * l1), b = Math.sqrt(CHI2[level] * Math.max(0, l2));
        const pts = d3.range(121).map(i => {
          const tt = (i / 120) * 2 * Math.PI;
          const ex = a * Math.cos(tt), ey = b * Math.sin(tt);
          const rx = ex * Math.cos(theta) - ey * Math.sin(theta);
          const ry = ex * Math.sin(theta) + ey * Math.cos(theta);
          const dAway = isHome ? ry : rx, dHome = isHome ? rx : ry;
          return [xScale(cAway + dAway) - xScale(cAway), yScale(cHome + dHome) - yScale(cHome)];
        });
        return "M" + pts.map(p => p.join(",")).join("L") + "Z";
      };
      return { cAway, cHome, d95: path(95), d50: path(50) };
    }
    const preGameSnap = isHome => isHome
      ? { meanFor: data.homeMeanFor, sdFor: data.homeSdFor, meanAgainst: data.homeMeanAgainst, sdAgainst: data.homeSdAgainst, corr: data.homeCorr }
      : { meanFor: data.awayMeanFor, sdFor: data.awaySdFor, meanAgainst: data.awayMeanAgainst, sdAgainst: data.awaySdAgainst, corr: data.awayCorr };
    /** Latest snapshot in a series on or before a day, or null. */
    function snapAt(series, day) {
      let best = null;
      for (const sp of series) { if (isoToDay(sp.date) <= day) best = sp; else break; }
      return best;
    }

    // ── 7. IQR boxes ───────────────────────────────────────────────────────
    const iqrRefs = {};
    function drawIqrBox(side, color, t) {
      const games = side === "home" ? homeGames : awayGames;
      const box = iqrBoxOf(games, side);
      const wrap = popGroup(layers.iqrBox, box ? box.cAway : MID, box ? box.cHome : MID, t, d3.easeCubicOut);
      const rect = wrap.append("rect")
        .attr("x", box ? -box.w / 2 : 0).attr("y", box ? -box.h / 2 : 0)
        .attr("width", box ? box.w : 0).attr("height", box ? box.h : 0)
        .attr("fill", "none").attr("stroke", color).attr("stroke-width", 2).attr("stroke-opacity", 0.4);
      if (!box) wrap.attr("display", "none");
      iqrRefs[side] = { wrap, rect };
    }
    drawIqrBox("home", homeColor, T(250, 600));
    drawIqrBox("away", awayColor, T(300, 600));

    // ── 8. Season form ellipses (95% + inner 50%) ──────────────────────────
    const ellipseRefs = {};
    function drawEllipses(side, color, t) {
      const isHome = side === "home";
      const geo = ellipseOf(preGameSnap(isHome), isHome);
      const wrap = popGroup(layers.ellipse, geo ? geo.cAway : MID, geo ? geo.cHome : MID, t, d3.easeCubicOut);
      const p95 = wrap.append("path").attr("d", geo ? geo.d95 : null)
        .attr("fill", color).attr("fill-opacity", 0.05)
        .attr("stroke", color).attr("stroke-width", 2.5).attr("stroke-opacity", 0.5);
      const p50 = wrap.append("path").attr("d", geo ? geo.d50 : null)
        .attr("fill", "none")
        .attr("stroke", color).attr("stroke-width", 1.25).attr("stroke-opacity", 0.6)
        .attr("stroke-dasharray", "4,3");
      if (!geo) wrap.attr("display", "none");
      ellipseRefs[side] = { wrap, p95, p50 };
    }
    drawEllipses("home", homeColor, T(300, 700));
    drawEllipses("away", awayColor, T(350, 700));

    // ── 9. Past meetings (stars, oriented to this game's home/away) ────────
    if (meetings.length) {
      const star = d3.symbol().type(d3.symbolStar).size(compact ? 70 : 110);
      meetings.forEach((m, i) => {
        const wrap = upright(popGroup(layers.pastMeetings, m.awayTeamScore, m.homeTeamScore, T(650 + i * 40, 500)))
          .attr("class", "gc-upright gc-meeting").attr("data-day", isoToDay(m.date));
        const yr = m.date.slice(0, 4);
        const where = m.neutral ? "neutral site" : `at ${m.venueAbbr}`;
        const tip = `Past meeting · ${fmtDate(m.date)} (${where})\n${data.homeAbbr} ${m.homeTeamScore}, ${data.awayAbbr} ${m.awayTeamScore}`;
        wrap.append("path").attr("d", star()).attr("fill", THEME.neutral).attr("fill-opacity", 0.85)
          .attr("stroke", SURFACE).attr("stroke-width", 1.25).style("cursor", "pointer")
          .on("mouseover", e => { showCrosshair(m.awayTeamScore, m.homeTeamScore); showTip(e, tip); })
          .on("mouseout", () => { hideCrosshair(); hideTip(); })
          .on("click", () => { window.location.href = "/games/" + m.gameId; });
        if (!compact) wrap.append("text").attr("x", 8).attr("y", 3.5)
          .attr("font-size", "9px").attr("font-weight", "600").attr("fill", THEME.axis)
          .call(labelHalo).text(yr);
      });
    }

    // ── 10. Season game markers (recency-faded) ────────────────────────────
    function recencyOpacity(rankFromNewest, n) {
      if (rankFromNewest < 5) return 1;
      return Math.max(0.3, 1 - (rankFromNewest - 4) * (0.7 / Math.max(1, n - 5)));
    }
    function gameTipText(gm, side) {
      const me = side === "home" ? data.homeAbbr : data.awayAbbr;
      const res = gm.win ? "W" : "L";
      const conf = gm.conferenceGame ? " · conf" : "";
      return `${fmtDate(gm.date)} · ${res}${conf}\n${me} ${gm.teamScore}, ${gm.opponentAbbr} ${gm.opponentScore}` +
        `\nmargin ${fmtSigned(gm.teamScore - gm.opponentScore).replace(".0", "")}, total ${gm.teamScore + gm.opponentScore}`;
    }
    function drawSeasonMarkers(games, side, color, baseDelay) {
      const n = games.length;
      const R = compact ? 3.5 : 4.5;
      layers.seasonMarkers.selectAll(null).data(games).enter().append("circle")
        .attr("class", "gc-game")
        .attr("data-game-id", gm => gm.gameId)
        .attr("data-day", gm => isoToDay(gm.date))
        .attr("cx", gm => xScale(gamePoint(gm, side)[0]))
        .attr("cy", gm => yScale(gamePoint(gm, side)[1]))
        .attr("r", anim ? 0 : R)
        .attr("data-r", R)
        .attr("fill", gm => gm.win ? color : SURFACE).attr("fill-opacity", gm => gm.win ? 0.75 : 0.9)
        .attr("stroke", color).attr("stroke-width", 1.5)
        .attr("opacity", (gm, i) => recencyOpacity(n - 1 - i, n))
        .style("cursor", "pointer")
        .on("mouseover", (e, gm) => {
          const [a, h] = gamePoint(gm, side);
          highlightGame(gm.gameId, true); showCrosshair(a, h); showTip(e, gameTipText(gm, side));
        })
        .on("mouseout", (e, gm) => { highlightGame(gm.gameId, false); hideCrosshair(); hideTip(); })
        .on("click", (e, gm) => { window.location.href = "/games/" + gm.gameId; });
      if (anim) layers.seasonMarkers.selectAll("circle").filter(gm => games.includes(gm))
        .transition().delay((gm, i) => baseDelay + i * 18).duration(350).ease(d3.easeBackOut).attr("r", R);
    }
    drawSeasonMarkers(homeGames, "home", homeColor, 450);
    drawSeasonMarkers(awayGames, "away", awayColor, 500);

    // Per-game DOM nodes, indexed once so the scrubber never queries the DOM
    // per tick. Visibility is driven through style.opacity with a CSS fade
    // (cheap; no D3 transition objects per element per tick).
    const gameNodes = new Map();
    const nodeEntry = id => { let e = gameNodes.get(id); if (!e) { e = { markers: [], rugs: [] }; gameNodes.set(id, e); } return e; };
    layers.seasonMarkers.selectAll(".gc-game").each(function (gm) {
      this.style.transition = REDUCED_MOTION ? "" : "opacity 150ms linear";
      nodeEntry(gm.gameId).markers.push(this);
    });
    g.selectAll(".gc-rug").each(function (gm) {
      this.style.transition = REDUCED_MOTION ? "" : "opacity 150ms linear";
      nodeEntry(gm.gameId).rugs.push(this);
    });
    const meetingNodes = [];
    layers.pastMeetings.selectAll(".gc-meeting").each(function () {
      this.style.transition = REDUCED_MOTION ? "" : "opacity 150ms linear";
      meetingNodes.push({ node: this, day: +this.getAttribute("data-day") });
    });

    function highlightGame(gameId, on) {
      // Visibility/recency live in style.opacity (the scrubber drives them), so
      // the hover highlight goes through style too and restores the scrub value.
      layers.seasonMarkers.selectAll(`.gc-game[data-game-id="${gameId}"]`)
        .attr("r", function () { const r = +this.getAttribute("data-r"); return on ? r * 1.8 : r; })
        .attr("stroke-width", on ? 2.5 : 1.5)
        .each(function () {
          if (on) { this.dataset.prevOpacity = this.style.opacity; this.style.opacity = 1; this.parentNode.appendChild(this); }
          else { this.style.opacity = this.dataset.prevOpacity || ""; }
        });
      g.selectAll(`.gc-rug[data-game-id="${gameId}"]`)
        .attr("stroke-opacity", on ? 1 : 0.45).attr("stroke-width", on ? 3.5 : 2.25);
    }

    // ── 11. Average score markers (team logos) ─────────────────────────────
    const avgRefs = {};
    function drawAvgMarker(side, ax, hy, logoUrl, color, abbr, forVal, againstVal, t) {
      const wrap = popGroup(layers.avgMarker, ax, hy, t);
      avgRefs[side] = { wrap, tip: { forVal, againstVal }, a: ax, h: hy };
      const R = compact ? 10 : 13;
      wrap.append("circle").attr("r", R).attr("fill", SURFACE).attr("stroke", color).attr("stroke-width", 2.5);
      const up = upright(wrap);
      if (logoUrl) {
        const cid = "gc-logo-clip-" + abbr.replace(/[^a-z0-9]/gi, "");
        defs.append("clipPath").attr("id", cid).append("circle").attr("r", R - 2);
        up.append("image").attr("href", logoUrl).attr("x", -(R - 2)).attr("y", -(R - 2))
          .attr("width", 2 * (R - 2)).attr("height", 2 * (R - 2)).attr("clip-path", `url(#${cid})`);
      } else {
        up.append("rect").attr("x", -5).attr("y", -5).attr("width", 10).attr("height", 10).attr("fill", color);
      }
      wrap.append("circle").attr("r", R).attr("fill", "transparent").style("cursor", "default")
        .on("mouseover", e => { const r = avgRefs[side]; showCrosshair(r.a, r.h);
          showTip(e, `${abbr} season average\nscores ${r.tip.forVal.toFixed(1)}, allows ${r.tip.againstVal.toFixed(1)}`); })
        .on("mouseout", () => { hideCrosshair(); hideTip(); });
    }
    if (data.homeAvgFor > 0 && data.homeAvgAgainst > 0)
      drawAvgMarker("home", data.homeAvgAgainst, data.homeAvgFor, data.homeLogoUrl, homeColor, data.homeAbbr, data.homeAvgFor, data.homeAvgAgainst, T(800, 500));
    if (data.awayAvgFor > 0 && data.awayAvgAgainst > 0)
      drawAvgMarker("away", data.awayAvgFor, data.awayAvgAgainst, data.awayLogoUrl, awayColor, data.awayAbbr, data.awayAvgFor, data.awayAvgAgainst, T(850, 500));

    // ── 12. Model prediction markers (triangles with short labels) ─────────
    modelPoints.forEach((p, i) => {
      const wrap = upright(popGroup(layers.modelMarkers, p.away, p.home, T(900 + i * 60, 500)));
      const tri = d3.symbol().type(d3.symbolTriangle).size(compact ? 80 : 120);
      const tip = `${p.label}\n${data.homeAbbr} ${p.home.toFixed(1)}, ${data.awayAbbr} ${p.away.toFixed(1)}` +
        `\n${data.homeAbbr} ${p.margin >= 0 ? "-" : "+"}${Math.abs(p.margin).toFixed(1)} · total ${p.total.toFixed(1)}` +
        (p.winProb != null ? `\n${data.homeAbbr} win ${fmtPct(p.winProb)}` : "");
      wrap.append("path").attr("d", tri()).attr("fill", p.color).attr("fill-opacity", 0.9)
        .attr("stroke", SURFACE).attr("stroke-width", 1.5).style("cursor", "default")
        .on("mouseover", e => { showCrosshair(p.away, p.home); showTip(e, tip); })
        .on("mouseout", () => { hideCrosshair(); hideTip(); });
      const left = i % 2 === 1;   // alternate sides so neighbouring labels don't collide
      if (!compact) wrap.append("text").attr("x", left ? -9 : 9).attr("y", left ? -6 : 3.5)
        .attr("text-anchor", left ? "end" : "start")
        .attr("font-size", "9px").attr("font-weight", "700").attr("fill", p.color)
        .call(labelHalo).text(p.short);
    });

    // ── 13. Book implied score ─────────────────────────────────────────────
    if (implied) {
      const wrap = upright(popGroup(layers.impliedMarker, implied.away, implied.home, T(850, 500)));
      wrap.append("circle").attr("r", compact ? 5 : 6.5)
        .attr("fill", BOOK_COLOR).attr("fill-opacity", 0.9).attr("stroke", SURFACE).attr("stroke-width", 1.5)
        .style("cursor", "default")
        .on("mouseover", e => { showCrosshair(implied.away, implied.home);
          showTip(e, `Book implied score\n${data.homeAbbr} ${implied.home.toFixed(1)}, ${data.awayAbbr} ${implied.away.toFixed(1)}` +
            `\n${data.homeAbbr} ${fmtSpreadHandicap(data.spread)} · O/U ${data.overUnder.toFixed(1)}`); })
        .on("mouseout", () => { hideCrosshair(); hideTip(); });
      if (!compact) wrap.append("text").attr("x", -10).attr("y", -6).attr("text-anchor", "end")
        .attr("font-size", "9px").attr("font-weight", "700").attr("fill", BOOK_COLOR)
        .call(labelHalo).text("BOOK");
    }

    // ── 14. Prediction misses (arrows from each prediction to the result) ──
    if (isFinal) {
      const rx = xScale(data.actualAwayScore), ry = yScale(data.actualHomeScore);
      const actualMargin = data.actualHomeScore - data.actualAwayScore;
      const actualTotal  = data.actualHomeScore + data.actualAwayScore;
      const sources = [];
      if (implied) sources.push({ label: "Book", color: BOOK_COLOR, away: implied.away, home: implied.home, margin: -data.spread, total: data.overUnder });
      modelPoints.forEach(p => sources.push({ label: p.label, color: p.color, away: p.away, home: p.home, margin: p.margin, total: p.total }));
      sources.forEach((s, i) => {
        const x1 = xScale(s.away), y1 = yScale(s.home);
        const dx = rx - x1, dy = ry - y1, len = Math.hypot(dx, dy);
        if (len < 14) return;
        const trimStart = 8, trimEnd = 13;
        const ln = layers.residuals.append("line")
          .attr("x1", x1 + dx / len * trimStart).attr("y1", y1 + dy / len * trimStart)
          .attr("x2", rx - dx / len * trimEnd).attr("y2", ry - dy / len * trimEnd)
          .attr("stroke", s.color).attr("stroke-width", 1.75).attr("stroke-opacity", 0.75)
          .attr("marker-end", arrowFor(s.color)).style("cursor", "default")
          .on("mouseover", e => showTip(e, `${s.label} miss\nmargin ${fmtSigned(actualMargin - s.margin)}, total ${fmtSigned(actualTotal - s.total)}` +
            `\n(actual − predicted, ${data.homeAbbr} perspective)`))
          .on("mouseout", hideTip);
        if (anim) {
          const L = len - trimStart - trimEnd;
          ln.attr("stroke-dasharray", `${L} ${L}`).attr("stroke-dashoffset", L)
            .transition().delay(1250 + i * 80).duration(450).ease(d3.easeCubicOut)
            .attr("stroke-dashoffset", 0)
            .on("end", function () { d3.select(this).attr("stroke-dasharray", null); });
        }
      });
    }

    // ── 15. Result marker ──────────────────────────────────────────────────
    if (isFinal) {
      const r = compact ? 8 : 10;
      const wrap = popGroup(layers.resultMarker, data.actualAwayScore, data.actualHomeScore, T(1150, 650),
        d3.easeElasticOut.amplitude(1).period(0.45));
      wrap.append("circle").attr("r", r + 4).attr("fill", SURFACE).attr("fill-opacity", 0.9);
      upright(wrap).append("path").attr("d", `M0,${-r} L${r},0 L0,${r} L${-r},0 Z`)
        .attr("fill", TEXT).attr("fill-opacity", 0.95).attr("stroke", SURFACE).attr("stroke-width", 2)
        .style("cursor", "default")
        .on("mouseover", e => { showCrosshair(data.actualAwayScore, data.actualHomeScore);
          showTip(e, `Final\n${data.homeAbbr} ${data.actualHomeScore}, ${data.awayAbbr} ${data.actualAwayScore}` +
            `\nmargin ${fmtSigned(data.actualHomeScore - data.actualAwayScore).replace(".0", "")}, total ${data.actualHomeScore + data.actualAwayScore}`); })
        .on("mouseout", () => { hideCrosshair(); hideTip(); });
    }

    // ── Visibility ─────────────────────────────────────────────────────────
    Object.entries(state).forEach(([key, s]) => {
      const layer = layers[key];
      if (layer) layer.attr("display", s.visible ? null : "none");
    });

    // ── Season scrubber: re-render season form as of a day ─────────────────
    // day = null restores the pre-game state (all games, server-side stats,
    // result + misses shown). Otherwise everything derived from season games is
    // recomputed from the games on/before that day and the snapshot for it.
    let lastScrubKey = null;
    applyScrub = function (day) {
      scrubDay = day;
      const atEnd = day == null;
      // Games are date-sorted, so the visible set is always a prefix
      const prefix = games => { let n = 0; if (atEnd) return games.length; while (n < games.length && isoToDay(games[n].date) <= day) n++; return n; };
      const hN = prefix(homeGames), aN = prefix(awayGames);
      const hSnap = atEnd ? null : snapAt(homeSeries, day), aSnap = atEnd ? null : snapAt(awaySeries, day);
      const mN = atEnd ? meetingNodes.length : meetingNodes.filter(m => m.day <= day).length;

      if (scrubDate && timeline) scrubDate.textContent = atEnd ? `Game day · ${fmtDate(data.gameDate)}` : fmtDate(dayToIso(day));
      if (scrubRange && timeline) scrubRange.value = atEnd ? (timeline.end - timeline.start) : (day - timeline.start);

      // Nothing derived changes on a day without a game for either team
      const key = `${atEnd}:${hN}:${aN}:${mN}:${hSnap ? hSnap.date : ""}:${aSnap ? aSnap.date : ""}`;
      if (key === lastScrubKey) return;
      lastScrubKey = key;

      const hVis = homeGames.slice(0, hN), aVis = awayGames.slice(0, aN);
      const dur = REDUCED_MOTION ? 0 : 160;
      const tr = sel => sel.transition("scrub").duration(dur).ease(d3.easeLinear);

      // Game markers + rugs: visibility and recency relative to the day
      [[homeGames, hN], [awayGames, aN]].forEach(([games, n]) => {
        games.forEach((gm, i) => {
          const on = i < n;
          const entry = gameNodes.get(gm.gameId);
          if (!entry) return;
          const op = on ? recencyOpacity(n - 1 - i, n) : 0;
          entry.markers.forEach(nd => { nd.style.opacity = op; nd.style.pointerEvents = on ? "" : "none"; });
          entry.rugs.forEach(nd => { nd.style.opacity = on ? 1 : 0; nd.style.pointerEvents = on ? "" : "none"; });
        });
      });
      // This-season past meetings only once they have happened
      meetingNodes.forEach(m => {
        const on = atEnd || m.day <= day;
        m.node.style.opacity = on ? 1 : 0; m.node.style.pointerEvents = on ? "" : "none";
      });

      // Average markers
      [["home", hVis], ["away", aVis]].forEach(([side, visible]) => {
        const ref = avgRefs[side]; if (!ref) return;
        const full = side === "home"
          ? { a: data.homeAvgAgainst, h: data.homeAvgFor, forVal: data.homeAvgFor, againstVal: data.homeAvgAgainst }
          : { a: data.awayAvgFor, h: data.awayAvgAgainst, forVal: data.awayAvgFor, againstVal: data.awayAvgAgainst };
        const pt = atEnd ? full : avgOf(visible, side);
        if (pt) {
          ref.a = pt.a; ref.h = pt.h; ref.tip = { forVal: pt.forVal, againstVal: pt.againstVal };
          tr(ref.wrap).attr("transform", `translate(${xScale(pt.a)},${yScale(pt.h)}) scale(1)`);
        } else {
          tr(ref.wrap).attr("transform", `translate(${xScale(ref.a)},${yScale(ref.h)}) scale(0)`);
        }
      });

      // IQR boxes
      [["home", hVis], ["away", aVis]].forEach(([side, visible]) => {
        const ref = iqrRefs[side]; if (!ref) return;
        const box = iqrBoxOf(atEnd ? (side === "home" ? homeGames : awayGames) : visible, side);
        if (box) {
          ref.wrap.attr("display", null);
          tr(ref.wrap).attr("transform", `translate(${xScale(box.cAway)},${yScale(box.cHome)}) scale(1)`);
          tr(ref.rect).attr("x", -box.w / 2).attr("y", -box.h / 2).attr("width", box.w).attr("height", box.h);
        } else {
          tr(ref.wrap).attr("transform", ref.wrap.attr("transform").replace(/scale\([^)]*\)/, "scale(0)"));
        }
      });

      // Ellipses from the snapshot series (paths swap directly; only the
      // centre glides — interpolating 121-point paths is what made this lag)
      [["home", hSnap], ["away", aSnap]].forEach(([side, snap]) => {
        const ref = ellipseRefs[side]; if (!ref) return;
        const isHome = side === "home";
        const geo = ellipseOf(atEnd ? preGameSnap(isHome) : snap, isHome);
        if (geo) {
          ref.wrap.attr("display", null);
          tr(ref.wrap).attr("transform", `translate(${xScale(geo.cAway)},${yScale(geo.cHome)}) scale(1)`);
          ref.p95.attr("d", geo.d95);
          ref.p50.attr("d", geo.d50);
        } else {
          tr(ref.wrap).attr("transform", ref.wrap.attr("transform").replace(/scale\([^)]*\)/, "scale(0)"));
        }
      });

      // Quadrant landing counts
      const inW = (x, y, hps) => hps.every(hp => hp[0] * x + hp[1] * y + hp[2] >= 0);
      layers.quadrants.selectAll(".gc-quadrant-count").each(function (hps) {
        const hc = hVis.filter(gm => inW(gm.opponentScore, gm.teamScore, hps)).length;
        const ac = aVis.filter(gm => inW(gm.teamScore, gm.opponentScore, hps)).length;
        d3.select(this).text(`${data.homeAbbr} ${hc}/${hVis.length} · ${data.awayAbbr} ${ac}/${aVis.length}`);
      });

      // The result and misses only exist once we're back at game day
      layers.resultMarker.attr("display", atEnd && state.resultMarker.visible ? null : "none");
      layers.residuals.attr("display", atEnd && state.residuals.visible ? null : "none");
    };

    // ── View rotation ──────────────────────────────────────────────────────
    setView = function (toRotated) {
      if (rotated === toRotated) return;
      rotated = toRotated;
      hideCrosshair(); hideTip();
      const dur = REDUCED_MOTION ? 0 : 900;
      const ease = d3.easeCubicInOut;
      plot.transition("view").duration(dur).ease(ease).attr("transform", plotTransform(rotated ? ROT : 0));
      plot.selectAll(".gc-upright").transition("view").duration(dur).ease(ease)
        .attr("transform", `rotate(${rotated ? -ROT : 0})`);
      const outF = rotated ? scoreFrame : mtFrame, inF = rotated ? mtFrame : scoreFrame;
      outF.attr("pointer-events", "none").transition("view").duration(dur * 0.4).attr("opacity", 0);
      inF.attr("pointer-events", null).transition("view").delay(dur * 0.5).duration(dur * 0.5).attr("opacity", 1);
      updateViewButton();
    };

    // ── Legend (grouped) ───────────────────────────────────────────────────
    const legendColors = {
      regions: THEME.neutral, quadrants: THEME.axis, spreadLine: BOOK_COLOR, ouLine: BOOK_COLOR,
      lineMovement: BOOK_COLOR, impliedMarker: BOOK_COLOR, modelMarkers: THEME.series[0],
      density: (densitySources.find(s => s.key === densityKey) || {}).color || THEME.neutral,
      residuals: THEME.axis, resultMarker: TEXT,
      seasonMarkers: homeColor, pastMeetings: THEME.neutral, avgMarker: homeColor,
      iqrBox: homeColor, ellipse: homeColor, marginals: homeColor,
    };
    let currentGroup = null;
    Object.entries(state).forEach(([key, s]) => {
      if (s.group !== currentGroup) {
        currentGroup = s.group;
        const h = document.createElement("div");
        h.className = "game-detail-chart-legend__group"; h.textContent = s.group;
        legendEl.appendChild(h);
      }
      const item = document.createElement("div");
      item.className = "game-detail-chart-legend__item" + (s.visible ? "" : " game-detail-chart-legend__item--hidden");
      item.dataset.key = key;
      const swatch = document.createElement("div");
      swatch.className = "game-detail-chart-legend__swatch game-detail-chart-legend__swatch--" + s.kind;
      swatch.style.backgroundColor = legendColors[key];
      const label = document.createElement("span");
      label.textContent = s.label;
      item.appendChild(swatch); item.appendChild(label);
      if (warnings[key]) {
        const warn = document.createElement("span");
        warn.className = "game-detail-chart-legend__warning";
        warn.textContent = "⚠"; warn.title = warnings[key];
        item.appendChild(warn);
      }
      legendEl.appendChild(item);
      item.addEventListener("click", () => {
        s.visible = !s.visible;
        item.classList.toggle("game-detail-chart-legend__item--hidden", !s.visible);
        const layer = layers[key];
        const suppressed = (key === "resultMarker" || key === "residuals") && scrubDay != null;
        if (layer) layer.attr("display", s.visible && !suppressed ? null : "none");
        if (key === "density") initChart();   // domain refits around the band
      });
    });
    if (readoutEl) readoutEl.style.display = state.density.visible ? "" : "none";

    // Density source selector: full redraw (no animation) so the domain refits
    if (densitySelect) {
      densitySelect.onchange = () => {
        densityKey = densitySelect.value || null;
        initChart();
      };
    }

    return tooltip;
  }

  function fmtDate(iso) {
    const d = new Date(iso + "T00:00:00");
    if (isNaN(d)) return iso;
    return d.toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" });
  }

  function updateViewButton() {
    if (!viewToggleBtn) return;
    viewToggleBtn.classList.toggle("active", rotated);
    viewToggleBtn.textContent = rotated ? "Score View" : "Margin / Total View";
    viewToggleBtn.title = rotated
      ? "Rotate back to home score vs away score"
      : "Rotate the plane 45° so the axes become total points and home margin";
  }

  // ── Density select population (once) ─────────────────────────────────────
  if (densitySelect) {
    densitySelect.textContent = "";
    const none = document.createElement("option");
    none.value = ""; none.textContent = "None";
    densitySelect.appendChild(none);
    densitySources.forEach(s => {
      const o = document.createElement("option");
      o.value = s.key; o.textContent = s.label;
      densitySelect.appendChild(o);
    });
    densitySelect.value = densityKey || "";
    densitySelect.parentElement.style.display = densitySources.length ? "" : "none";
  }

  // ── Init with error handling ──────────────────────────────────────────────
  function initChart() {
    if (loadingEl) loadingEl.style.display = "none";
    if (errorEl)   errorEl.style.display   = "none";
    d3.selectAll(".game-chart-tooltip").remove();
    if (container) { while (container.firstChild) container.removeChild(container.firstChild); }
    if (legendEl)  { while (legendEl.firstChild)  legendEl.removeChild(legendEl.firstChild); }
    if (readoutEl) readoutEl.textContent = "";
    stopPlayback();
    scrubDay = null;
    try {
      drawChart(firstDraw);
      firstDraw = false;
      updateViewButton();
      if (applyScrub && timeline && scrubDate) scrubDate.textContent = `Game day · ${fmtDate(data.gameDate)}`;
      if (scrubRange && timeline) scrubRange.value = timeline.end - timeline.start;
    } catch (err) {
      console.error("Game chart error:", err);
      if (container) { while (container.firstChild) container.removeChild(container.firstChild); }
      if (legendEl)  { while (legendEl.firstChild)  legendEl.removeChild(legendEl.firstChild); }
      if (errorEl)   errorEl.style.display = "block";
    }
  }

  if (retryBtn) retryBtn.addEventListener("click", initChart);

  // ── Season scrubber controls ──────────────────────────────────────────────
  const DAY_MS = 70;   // playback speed: one calendar day per tick
  function stopPlayback() {
    if (playTimer) { clearTimeout(playTimer); playTimer = null; }
    if (scrubPlay) { scrubPlay.textContent = "\u25B6 Replay season"; scrubPlay.classList.remove("active"); }
  }
  function scrubTo(day) {
    if (!applyScrub || !timeline) return;
    if (day >= timeline.end) applyScrub(null); else applyScrub(Math.max(timeline.start, day));
  }
  function startPlayback() {
    if (!timeline || !applyScrub) return;
    stopPlayback();
    let day = timeline.start - 1;   // first tick shows the empty plane
    scrubTo(day);
    if (scrubPlay) { scrubPlay.textContent = "\u23F8 Pause"; scrubPlay.classList.add("active"); }
    const tick = () => {
      day += 1;
      scrubTo(day);
      if (day >= timeline.end) { stopPlayback(); return; }
      playTimer = setTimeout(tick, REDUCED_MOTION ? 0 : DAY_MS);
    };
    playTimer = setTimeout(tick, REDUCED_MOTION ? 0 : DAY_MS);
  }
  if (scrubEl && timeline && scrubRange && scrubPlay) {
    scrubEl.hidden = false;
    scrubRange.max = timeline.end - timeline.start;
    scrubRange.value = scrubRange.max;
    scrubRange.addEventListener("input", () => { stopPlayback(); scrubTo(timeline.start + +scrubRange.value); });
    scrubPlay.addEventListener("click", () => { if (playTimer) stopPlayback(); else startPlayback(); });
  }

  // ── Color override toggle ─────────────────────────────────────────────────
  if (colorToggleBtn) {
    colorToggleBtn.addEventListener("click", () => {
      useTeamColors = !useTeamColors;
      homeColor = useTeamColors ? TEAM_HOME_COLOR : STD_HOME_COLOR;
      awayColor = useTeamColors ? TEAM_AWAY_COLOR : STD_AWAY_COLOR;
      colorToggleBtn.classList.toggle("active", !useTeamColors);
      colorToggleBtn.textContent = useTeamColors ? "Swap Colors" : "Use Team Colors";
      initChart();
    });
  }

  // ── View toggle (score ↔ margin/total) ────────────────────────────────────
  if (viewToggleBtn) {
    viewToggleBtn.addEventListener("click", () => { if (setView) setView(!rotated); });
  }

  // Re-layout when the container crosses the compact-width threshold
  let lastCompact = container.clientWidth > 0 && container.clientWidth < 500;
  let resizeTimer = null;
  window.addEventListener("resize", () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => {
      const nowCompact = container.clientWidth > 0 && container.clientWidth < 500;
      if (nowCompact !== lastCompact) { lastCompact = nowCompact; initChart(); }
    }, 200);
  });

  initChart();

})();
