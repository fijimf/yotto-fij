(function () {
  "use strict";

  const data = window.GAME_CHART_DATA;
  const loadingEl = document.getElementById("game-chart-loading");
  const errorEl   = document.getElementById("game-chart-error");
  const retryBtn  = document.getElementById("game-chart-retry");
  const container = document.getElementById("game-chart-container");
  const legendEl  = document.getElementById("game-chart-legend");
  const colorToggleBtn = document.getElementById("game-chart-color-toggle");

  if (!data) {
    if (loadingEl) loadingEl.style.display = "none";
    return;
  }

  // ── Config ────────────────────────────────────────────────────────────────
  const MARGIN = { top: 60, right: 80, bottom: 80, left: 80 };
  const BASE_SIZE = 800;
  const SCORE_MIN = 40;
  const SCORE_MAX = 120;
  const NEUTRAL_COLOR = "#64748b";
  const CHI2_95 = 5.991; // χ² 2-DOF 95%

  const TEAM_HOME_COLOR = data.homeColor ? "#" + data.homeColor : "#3b82f6";
  const TEAM_AWAY_COLOR = data.awayColor ? "#" + data.awayColor : "#ef4444";
  const STD_HOME_COLOR  = "#3b82f6";
  const STD_AWAY_COLOR  = "#ef4444";

  let useTeamColors = true;
  let homeColor = TEAM_HOME_COLOR;
  let awayColor = TEAM_AWAY_COLOR;

  // ── State (visibility) ───────────────────────────────────────────────────
  const state = {
    resultMarker:   { label: "Result",        visible: true  },
    spreadLine:     { label: "Spread Line",   visible: true  },
    ouLine:         { label: "O/U Line",      visible: true  },
    impliedMarker:  { label: "Implied Score", visible: true  },
    seasonMarkers:  { label: "Season Games",  visible: false },
    avgMarker:      { label: "Avg Score",     visible: true  },
    iqrBox:         { label: "IQR Box",       visible: true  },
    ellipse:        { label: "95% Ellipse",   visible: true  },
    marginals:      { label: "Distributions", visible: true  },
  };

  // ── Warning conditions (incomplete data) ─────────────────────────────────
  const warnings = {};
  if (data.homeForQ1 == null || data.awayForQ1 == null)
    warnings.iqrBox = "Insufficient season games for IQR (need ≥4)";
  if (data.homeSdFor == null || data.homeSdAgainst == null ||
      data.awaySdFor == null || data.awaySdAgainst == null ||
      data.homeCorr == null  || data.awayCorr == null)
    warnings.ellipse = "Std dev / correlation stats unavailable";
  if (data.homeMeanFor == null || data.awayMeanFor == null)
    warnings.marginals = "Distribution mean/std dev stats unavailable";
  if (data.spread == null)    warnings.spreadLine = "No spread data";
  if (data.overUnder == null) warnings.ouLine = "No over/under data";
  if (data.spread == null || data.overUnder == null) warnings.impliedMarker = "Requires spread and O/U";
  if (data.actualHomeScore == null) warnings.resultMarker = "Game not yet final";

  // ── Draw function (called on init and on color swap) ─────────────────────
  function drawChart() {
    const totalW = BASE_SIZE;
    const totalH = BASE_SIZE;
    const innerW = totalW - MARGIN.left - MARGIN.right;
    const innerH = totalH - MARGIN.top  - MARGIN.bottom;

    const svg = d3.select(container).append("svg")
      .attr("viewBox", `0 0 ${totalW} ${totalH}`)
      .attr("preserveAspectRatio", "xMidYMid meet");

    // ── Defs (gradients) ────────────────────────────────────────────────────
    const defs = svg.append("defs");
    function makeGrad(id, color, x1, y1, x2, y2) {
      const gr = defs.append("linearGradient")
        .attr("id", id).attr("x1", x1).attr("y1", y1).attr("x2", x2).attr("y2", y2);
      gr.append("stop").attr("offset", "0%").attr("stop-color", color).attr("stop-opacity", 0.2);
      gr.append("stop").attr("offset", "100%").attr("stop-color", color).attr("stop-opacity", 0);
    }
    makeGrad("grad-home-for",     homeColor, "0%",   "0%",   "100%", "0%");
    makeGrad("grad-away-against", awayColor, "100%", "0%",   "0%",   "0%");
    makeGrad("grad-home-against", homeColor, "0%",   "100%", "0%",   "0%");
    makeGrad("grad-away-for",     awayColor, "0%",   "0%",   "0%",   "100%");

    const g = svg.append("g").attr("transform", `translate(${MARGIN.left},${MARGIN.top})`);

    // ── Scales ───────────────────────────────────────────────────────────────
    const xScale = d3.scaleLinear().domain([SCORE_MIN, SCORE_MAX]).range([0, innerW]);
    const yScale = d3.scaleLinear().domain([SCORE_MIN, SCORE_MAX]).range([innerH, 0]);

    // ── Grid (major every 10, minor every 5) ─────────────────────────────────
    const majorTicks = d3.range(SCORE_MIN, SCORE_MAX + 1, 10);
    const minorTicks = d3.range(SCORE_MIN + 5, SCORE_MAX, 10);

    g.append("g")
      .selectAll("line").data(majorTicks).enter().append("line")
      .attr("x1", d => xScale(d)).attr("x2", d => xScale(d))
      .attr("y1", 0).attr("y2", innerH)
      .attr("stroke", "#e2e8f0").attr("stroke-width", 1).attr("stroke-opacity", 0.5);
    g.append("g")
      .selectAll("line").data(majorTicks).enter().append("line")
      .attr("x1", 0).attr("x2", innerW)
      .attr("y1", d => yScale(d)).attr("y2", d => yScale(d))
      .attr("stroke", "#e2e8f0").attr("stroke-width", 1).attr("stroke-opacity", 0.5);

    g.append("g")
      .selectAll("line").data(minorTicks).enter().append("line")
      .attr("x1", d => xScale(d)).attr("x2", d => xScale(d))
      .attr("y1", 0).attr("y2", innerH)
      .attr("stroke", "#e2e8f0").attr("stroke-width", 0.5).attr("stroke-opacity", 0.3);
    g.append("g")
      .selectAll("line").data(minorTicks).enter().append("line")
      .attr("x1", 0).attr("x2", innerW)
      .attr("y1", d => yScale(d)).attr("y2", d => yScale(d))
      .attr("stroke", "#e2e8f0").attr("stroke-width", 0.5).attr("stroke-opacity", 0.3);

    // ── Axes ─────────────────────────────────────────────────────────────────
    g.append("g").attr("transform", `translate(0,${innerH})`)
      .call(d3.axisBottom(xScale).ticks(8));
    g.append("g")
      .call(d3.axisLeft(yScale).ticks(8));

    // ── Axis labels: [logo] team name ─────────────────────────────────────────
    const LOGO_SIZE = 32;

    // X-axis label (away team) — bottom
    const xLabelG = g.append("g");
    const xLabelY = innerH + 55;
    const xLabelX = innerW / 2;
    if (data.awayLogoUrl) {
      xLabelG.append("image")
        .attr("href", data.awayLogoUrl)
        .attr("width", LOGO_SIZE).attr("height", LOGO_SIZE)
        .attr("x", xLabelX - LOGO_SIZE / 2)
        .attr("y", xLabelY - LOGO_SIZE - 4);
    }
    xLabelG.append("text")
      .attr("x", xLabelX).attr("y", xLabelY + (data.awayLogoUrl ? 4 : 0))
      .attr("text-anchor", "middle")
      .attr("font-size", "14px").attr("font-weight", "bold")
      .attr("fill", awayColor)
      .text(`${data.awayFullName} (${data.awayAbbr})`);

    // Y-axis label (home team) — left, rotated
    if (data.homeLogoUrl) {
      const yLabelG = g.append("g")
        .attr("transform", `rotate(-90) translate(${-innerH / 2}, ${-MARGIN.left + 8})`);
      yLabelG.append("image")
        .attr("href", data.homeLogoUrl)
        .attr("width", LOGO_SIZE).attr("height", LOGO_SIZE)
        .attr("x", -LOGO_SIZE / 2)
        .attr("y", -LOGO_SIZE - 6);
      yLabelG.append("text")
        .attr("x", 0).attr("y", 6)
        .attr("text-anchor", "middle")
        .attr("font-size", "14px").attr("font-weight", "bold")
        .attr("fill", homeColor)
        .text(`${data.homeFullName} (${data.homeAbbr})`);
    } else {
      g.append("text")
        .attr("transform", "rotate(-90)")
        .attr("x", -innerH / 2).attr("y", -58)
        .attr("text-anchor", "middle")
        .attr("font-size", "14px").attr("font-weight", "bold")
        .attr("fill", homeColor)
        .text(`${data.homeFullName} (${data.homeAbbr})`);
    }

    // ── Tooltip (plain text, no XSS risk) ────────────────────────────────────
    const tooltip = d3.select("body").append("div")
      .style("position", "absolute")
      .style("background", "#1a1a2e")
      .style("border", "1px solid #2a2a4a")
      .style("color", "#e0e0e0")
      .style("padding", "6px 10px")
      .style("border-radius", "6px")
      .style("font-size", "12px")
      .style("pointer-events", "none")
      .style("display", "none");

    function showTip(event, text) {
      tooltip.style("display", "block").text(text)
        .style("left", (event.pageX + 12) + "px")
        .style("top",  (event.pageY - 28) + "px");
    }
    function hideTip() { tooltip.style("display", "none"); }

    // ── Layer groups (z-order by insertion) ───────────────────────────────────
    const layers = {
      background:    g.append("g"),
      marginals:     g.append("g"),
      iqrBox:        g.append("g"),
      ellipse:       g.append("g"),
      seasonMarkers: g.append("g"),
      avgMarker:     g.append("g"),
      impliedMarker: g.append("g"),
      resultMarker:  g.append("g"),
    };

    // ── Clip segment to [SCORE_MIN, SCORE_MAX]² ───────────────────────────────
    function clipLine(x1, y1, x2, y2) {
      const dx = x2 - x1, dy = y2 - y1;
      let tMin = 0, tMax = 1;
      function clip(n, d) {
        if (d === 0) return n < 0;
        const t = n / d;
        if (d < 0) { if (t > tMax) return true; if (t > tMin) tMin = t; }
        else       { if (t < tMin) return true; if (t < tMax) tMax = t; }
        return false;
      }
      if (clip(SCORE_MIN - x1, dx)) return null;
      if (clip(x1 - SCORE_MAX, -dx)) return null;
      if (clip(SCORE_MIN - y1, dy)) return null;
      if (clip(y1 - SCORE_MAX, -dy)) return null;
      return { x1: x1 + tMin*dx, y1: y1 + tMin*dy, x2: x1 + tMax*dx, y2: y1 + tMax*dy };
    }

    // ── 2. Spread Line ────────────────────────────────────────────────────────
    if (data.spread != null) {
      const seg = clipLine(SCORE_MIN, SCORE_MIN - data.spread, SCORE_MAX, SCORE_MAX - data.spread);
      if (seg) {
        layers.background.append("line")
          .attr("x1", xScale(seg.x1)).attr("y1", yScale(seg.y1))
          .attr("x2", xScale(seg.x2)).attr("y2", yScale(seg.y2))
          .attr("stroke", NEUTRAL_COLOR).attr("stroke-width", 2)
          .attr("stroke-dasharray", "5,5");
        const mx = (seg.x1+seg.x2)/2, my = (seg.y1+seg.y2)/2;
        const lbl = layers.background.append("g");
        lbl.append("rect")
          .attr("x", xScale(mx)-24).attr("y", yScale(my)-11)
          .attr("width", 48).attr("height", 18).attr("rx", 4)
          .attr("fill", "white").attr("opacity", 0.9);
        lbl.append("text")
          .attr("x", xScale(mx)).attr("y", yScale(my)+4)
          .attr("text-anchor", "middle").attr("font-size", "11px").attr("fill", NEUTRAL_COLOR)
          .text(data.spread > 0 ? "+" + data.spread.toFixed(1) : data.spread.toFixed(1));
      }
    }

    // ── 3. O/U Line ───────────────────────────────────────────────────────────
    if (data.overUnder != null) {
      const seg = clipLine(SCORE_MIN, data.overUnder-SCORE_MIN, SCORE_MAX, data.overUnder-SCORE_MAX);
      if (seg) {
        layers.background.append("line")
          .attr("x1", xScale(seg.x1)).attr("y1", yScale(seg.y1))
          .attr("x2", xScale(seg.x2)).attr("y2", yScale(seg.y2))
          .attr("stroke", NEUTRAL_COLOR).attr("stroke-width", 2)
          .attr("stroke-dasharray", "5,5,2,5");
        const mx = (seg.x1+seg.x2)/2, my = (seg.y1+seg.y2)/2;
        const lbl = layers.background.append("g");
        lbl.append("rect")
          .attr("x", xScale(mx)-26).attr("y", yScale(my)-11)
          .attr("width", 52).attr("height", 18).attr("rx", 4)
          .attr("fill", "white").attr("opacity", 0.9);
        lbl.append("text")
          .attr("x", xScale(mx)).attr("y", yScale(my)+4)
          .attr("text-anchor", "middle").attr("font-size", "11px").attr("fill", NEUTRAL_COLOR)
          .text("O/U " + data.overUnder.toFixed(1));
      }
    }

    // ── 4. Implied Score Marker ───────────────────────────────────────────────
    if (data.spread != null && data.overUnder != null) {
      const impliedHome = (data.overUnder - data.spread) / 2;
      const impliedAway = (data.overUnder + data.spread) / 2;
      layers.impliedMarker.append("circle")
        .attr("cx", xScale(impliedAway)).attr("cy", yScale(impliedHome))
        .attr("r", 8)
        .attr("fill", NEUTRAL_COLOR).attr("fill-opacity", 0.8)
        .attr("stroke", d3.color(NEUTRAL_COLOR).darker(1)).attr("stroke-width", 2)
        .on("mouseover", e => showTip(e,
          `Implied: ${data.homeAbbr} ${impliedHome.toFixed(1)}, ${data.awayAbbr} ${impliedAway.toFixed(1)}`))
        .on("mouseout", hideTip);
    }

    // ── 9. Marginal Distributions ─────────────────────────────────────────────
    function normalPdf(x, mu, sigma) {
      return (1/(sigma * Math.sqrt(2 * Math.PI))) * Math.exp(-0.5 * ((x-mu)/sigma)**2);
    }
    function marginalPoints(mu, sigma, n=200) {
      const lo = mu - 3*sigma, hi = mu + 3*sigma;
      return d3.range(n+1).map(i => { const x = lo+(i/n)*(hi-lo); return { x, y: normalPdf(x,mu,sigma) }; });
    }
    const MARG_H = 38;
    function drawMarginal(layerG, pts, mu, sigma, orient, gradId, color) {
      const sc = MARG_H / normalPdf(mu, mu, sigma);
      if (orient === "left") {
        layerG.append("path").datum(pts).attr("fill", `url(#${gradId})`)
          .attr("d", d3.area().x0(0).x1(d => -d.y*sc).y(d => yScale(d.x)));
        layerG.append("path").datum(pts)
          .attr("d", d3.line().x(d => -d.y*sc).y(d => yScale(d.x)))
          .attr("fill","none").attr("stroke",color).attr("stroke-width",1.5).attr("stroke-opacity",0.6);
      } else if (orient === "right") {
        layerG.append("path").datum(pts).attr("fill", `url(#${gradId})`)
          .attr("d", d3.area().x0(innerW).x1(d => innerW+d.y*sc).y(d => yScale(d.x)));
        layerG.append("path").datum(pts)
          .attr("d", d3.line().x(d => innerW+d.y*sc).y(d => yScale(d.x)))
          .attr("fill","none").attr("stroke",color).attr("stroke-width",1.5).attr("stroke-opacity",0.6);
      } else if (orient === "top") {
        layerG.append("path").datum(pts).attr("fill", `url(#${gradId})`)
          .attr("d", d3.area().x(d => xScale(d.x)).y0(0).y1(d => -d.y*sc));
        layerG.append("path").datum(pts)
          .attr("d", d3.line().x(d => xScale(d.x)).y(d => -d.y*sc))
          .attr("fill","none").attr("stroke",color).attr("stroke-width",1.5).attr("stroke-opacity",0.6);
      } else if (orient === "bottom") {
        layerG.append("path").datum(pts).attr("fill", `url(#${gradId})`)
          .attr("d", d3.area().x(d => xScale(d.x)).y0(innerH).y1(d => innerH+d.y*sc));
        layerG.append("path").datum(pts)
          .attr("d", d3.line().x(d => xScale(d.x)).y(d => innerH+d.y*sc))
          .attr("fill","none").attr("stroke",color).attr("stroke-width",1.5).attr("stroke-opacity",0.6);
      }
    }
    const hasMarginals = data.homeMeanFor!=null && data.homeSdFor!=null
                      && data.homeMeanAgainst!=null && data.homeSdAgainst!=null
                      && data.awayMeanFor!=null && data.awaySdFor!=null
                      && data.awayMeanAgainst!=null && data.awaySdAgainst!=null;
    if (hasMarginals) {
      drawMarginal(layers.marginals, marginalPoints(data.homeMeanFor,data.homeSdFor),
        data.homeMeanFor,data.homeSdFor,"left","grad-home-for",homeColor);
      drawMarginal(layers.marginals, marginalPoints(data.awayMeanAgainst,data.awaySdAgainst),
        data.awayMeanAgainst,data.awaySdAgainst,"right","grad-away-against",awayColor);
      drawMarginal(layers.marginals, marginalPoints(data.awayMeanFor,data.awaySdFor),
        data.awayMeanFor,data.awaySdFor,"top","grad-away-for",awayColor);
      drawMarginal(layers.marginals, marginalPoints(data.homeMeanAgainst,data.homeSdAgainst),
        data.homeMeanAgainst,data.homeSdAgainst,"bottom","grad-home-against",homeColor);
    }

    // ── 7. IQR Boxes ──────────────────────────────────────────────────────────
    function drawIqrBox(layerG, x1, x2, y1, y2, color) {
      layerG.append("rect")
        .attr("x", xScale(Math.min(x1,x2))).attr("y", yScale(Math.max(y1,y2)))
        .attr("width", Math.abs(xScale(x2)-xScale(x1))).attr("height", Math.abs(yScale(y2)-yScale(y1)))
        .attr("fill","none").attr("stroke",color).attr("stroke-width",2).attr("stroke-opacity",0.4);
    }
    if (data.homeForQ1!=null && data.homeAgainstQ1!=null)
      drawIqrBox(layers.iqrBox, data.homeAgainstQ1, data.homeAgainstQ3, data.homeForQ1, data.homeForQ3, homeColor);
    if (data.awayForQ1!=null && data.awayAgainstQ1!=null)
      drawIqrBox(layers.iqrBox, data.awayForQ1, data.awayForQ3, data.awayAgainstQ1, data.awayAgainstQ3, awayColor);

    // ── 8. 95% Confidence Ellipses ─────────────────────────────────────────────
    function eigen2x2(a, b, d) {
      const tr = a+d, det = a*d - b*b;
      const disc = Math.sqrt(Math.max(0, (tr/2)**2 - det));
      const l1 = tr/2+disc, l2 = tr/2-disc;
      let v1x, v1y;
      if (Math.abs(b) > 1e-10) { v1x = l1-d; v1y = b; } else { v1x = 1; v1y = 0; }
      const n = Math.sqrt(v1x*v1x + v1y*v1y);
      return { l1, l2, v1x: v1x/n, v1y: v1y/n };
    }
    function drawEllipse(layerG, muFor, muAgainst, sdFor, sdAgainst, corr, isHome, color) {
      if (sdFor==null || sdAgainst==null || corr==null) return;
      const cov = corr * sdFor * sdAgainst;
      const { l1, l2, v1x, v1y } = eigen2x2(sdFor*sdFor, cov, sdAgainst*sdAgainst);
      const a = Math.sqrt(CHI2_95*l1), b = Math.sqrt(CHI2_95*Math.max(0,l2));
      const theta = Math.atan2(v1y, v1x);
      const pts = d3.range(121).map(i => {
        const t = (i/120)*2*Math.PI;
        const ex = a*Math.cos(t), ey = b*Math.sin(t);
        const rx = ex*Math.cos(theta) - ey*Math.sin(theta);
        const ry = ex*Math.sin(theta) + ey*Math.cos(theta);
        const cx = isHome ? (muAgainst+ry) : (muFor+rx);
        const cy = isHome ? (muFor+rx)     : (muAgainst+ry);
        return [xScale(cx), yScale(cy)];
      });
      layerG.append("path")
        .attr("d", "M"+pts.map(p=>p.join(",")).join("L")+"Z")
        .attr("fill","none").attr("stroke",color).attr("stroke-width",2.5).attr("stroke-opacity",0.5);
    }
    drawEllipse(layers.ellipse, data.homeMeanFor, data.homeMeanAgainst,
      data.homeSdFor, data.homeSdAgainst, data.homeCorr, true, homeColor);
    drawEllipse(layers.ellipse, data.awayMeanFor, data.awayMeanAgainst,
      data.awaySdFor, data.awaySdAgainst, data.awayCorr, false, awayColor);

    // ── 5. Season Game Markers ────────────────────────────────────────────────
    function drawSeasonMarkers(layerG, games, isHome, color) {
      layerG.selectAll(null).data(games).enter().append("circle")
        .attr("cx", d => xScale(isHome ? d.opponentScore : d.teamScore))
        .attr("cy", d => yScale(isHome ? d.teamScore : d.opponentScore))
        .attr("r", 6)
        .attr("fill", d => d.win ? color : "none").attr("fill-opacity", 0.5)
        .attr("stroke", color).attr("stroke-width", 1.5)
        .style("cursor","pointer")
        .on("mouseover", (e,d) => showTip(e,
          `${d.date}: ${isHome ? data.homeAbbr : data.awayAbbr} ${d.teamScore}, ${d.opponentAbbr} ${d.opponentScore}`))
        .on("mouseout", hideTip)
        .on("click", (e,d) => { window.location.href = "/games/" + d.gameId; });
    }
    if (data.homeGames && data.homeGames.length)
      drawSeasonMarkers(layers.seasonMarkers, data.homeGames, true, homeColor);
    if (data.awayGames && data.awayGames.length)
      drawSeasonMarkers(layers.seasonMarkers, data.awayGames, false, awayColor);

    // ── 6. Average Score Markers ──────────────────────────────────────────────
    const SQ = 10;
    if (data.homeAvgFor && data.homeAvgAgainst) {
      layers.avgMarker.append("rect")
        .attr("x", xScale(data.homeAvgAgainst)-SQ/2).attr("y", yScale(data.homeAvgFor)-SQ/2)
        .attr("width",SQ).attr("height",SQ)
        .attr("fill",homeColor).attr("fill-opacity",0.7).attr("stroke",homeColor).attr("stroke-width",2)
        .on("mouseover", e => showTip(e,
          `Avg: ${data.homeAbbr} ${data.homeAvgFor.toFixed(1)} \u2013 ${data.homeAvgAgainst.toFixed(1)}`))
        .on("mouseout", hideTip);
    }
    if (data.awayAvgFor && data.awayAvgAgainst) {
      layers.avgMarker.append("rect")
        .attr("x", xScale(data.awayAvgFor)-SQ/2).attr("y", yScale(data.awayAvgAgainst)-SQ/2)
        .attr("width",SQ).attr("height",SQ)
        .attr("fill",awayColor).attr("fill-opacity",0.7).attr("stroke",awayColor).attr("stroke-width",2)
        .on("mouseover", e => showTip(e,
          `Avg: ${data.awayAbbr} ${data.awayAvgFor.toFixed(1)} \u2013 ${data.awayAvgAgainst.toFixed(1)}`))
        .on("mouseout", hideTip);
    }

    // ── 1. Result Marker ──────────────────────────────────────────────────────
    if (data.actualHomeScore != null && data.actualAwayScore != null) {
      const cx = xScale(data.actualAwayScore), cy = yScale(data.actualHomeScore), r = 12;
      layers.resultMarker.append("path")
        .attr("d", `M${cx},${cy-r} L${cx+r},${cy} L${cx},${cy+r} L${cx-r},${cy} Z`)
        .attr("fill",NEUTRAL_COLOR).attr("fill-opacity",0.9)
        .attr("stroke","#000").attr("stroke-width",2)
        .on("mouseover", e => showTip(e,
          `${data.homeAbbr}: ${data.actualHomeScore}, ${data.awayAbbr}: ${data.actualAwayScore}`))
        .on("mouseout", hideTip);
    }

    // ── Initial visibility ────────────────────────────────────────────────────
    Object.entries(state).forEach(([key, s]) => {
      const layer = layers[key];
      if (layer) layer.attr("display", s.visible ? null : "none");
    });

    // ── Legend ────────────────────────────────────────────────────────────────
    const legendColors = {
      resultMarker: "#000", spreadLine: NEUTRAL_COLOR, ouLine: NEUTRAL_COLOR,
      impliedMarker: NEUTRAL_COLOR, seasonMarkers: homeColor,
      avgMarker: homeColor, iqrBox: homeColor, ellipse: homeColor, marginals: homeColor,
    };

    Object.entries(state).forEach(([key, s]) => {
      const item = document.createElement("div");
      item.className = "game-detail-chart-legend__item" +
                       (s.visible ? "" : " game-detail-chart-legend__item--hidden");
      item.dataset.key = key;

      const swatch = document.createElement("div");
      swatch.className = "game-detail-chart-legend__swatch";
      swatch.style.backgroundColor = legendColors[key];
      swatch.style.height = "3px";

      const label = document.createElement("span");
      label.textContent = s.label;

      item.appendChild(swatch);
      item.appendChild(label);

      // Warning icon for incomplete data
      if (warnings[key]) {
        const warn = document.createElement("span");
        warn.className = "game-detail-chart-legend__warning";
        warn.textContent = "\u26A0";
        warn.title = warnings[key];
        item.appendChild(warn);
      }

      legendEl.appendChild(item);

      item.addEventListener("click", () => {
        s.visible = !s.visible;
        item.classList.toggle("game-detail-chart-legend__item--hidden", !s.visible);
        const layer = layers[key];
        if (layer) layer.transition().duration(300).attr("display", s.visible ? null : "none");
      });
    });

    return tooltip;
  }

  // ── Init with error handling ──────────────────────────────────────────────
  function initChart() {
    if (loadingEl) loadingEl.style.display = "none";
    if (errorEl)   errorEl.style.display   = "none";
    // Remove previous tooltip if any
    d3.selectAll(".chart-tooltip-instance").remove();
    if (container) { while (container.firstChild) container.removeChild(container.firstChild); }
    if (legendEl)  { while (legendEl.firstChild)  legendEl.removeChild(legendEl.firstChild); }
    try {
      drawChart();
    } catch (err) {
      console.error("Game chart error:", err);
      if (container) { while (container.firstChild) container.removeChild(container.firstChild); }
      if (legendEl)  { while (legendEl.firstChild)  legendEl.removeChild(legendEl.firstChild); }
      if (errorEl)   errorEl.style.display = "block";
    }
  }

  if (retryBtn) retryBtn.addEventListener("click", initChart);

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

  initChart();

})();
