/* Generic scatter/correlation matrix renderer (correlation explorer, spec §5.5).
 * Ported from the rankings page's inline scatter-matrix fragment and
 * parameterized by variable list. Expects the host page to provide the
 * scaffolding elements (#scatter-matrix-root, #scatter-legend,
 * #scatter-tooltip, #scatter-selected-label, #scatter-modal-*) styled by the
 * existing .scatter-* CSS, plus D3 already loaded.
 *
 * Payload: { vars: [{id, label, group, decimals}],
 *            rows: [{teamId, team, conf, values: {varId: number}}] }
 * All user-supplied strings (team/conference names) go through textContent. */
(function () {
    "use strict";

    var CELL = 90;

    var CONF_COLORS = [
        '#1A6FD4','#4BAED4','#A0D4F0','#0C3060','#1D9E75','#5DCAA5','#0F6E56','#7DC232',
        '#3B6D11','#97C459','#6B8E23','#C0DD97','#EFB527','#BA7517','#F5C030','#F07A2A',
        '#C0471A','#E07060','#C0272D','#8B1A1A','#7F77DD','#534AB7','#AFA9EC','#6A2BA0',
        '#D4537E','#B0306A','#ED93B1','#444441','#888780','#B4B2A9','#A0522D','#8B7355','#C8B89A'
    ];

    window.renderScatterMatrix = function (payload) {
        if (!payload || !payload.vars || payload.vars.length < 2 || !payload.rows || !payload.rows.length) return;

        var VARS = payload.vars.map(function (v) {
            return {
                key: v.id,
                label: v.label,
                fmt: (function (dec) { return function (x) { return x.toFixed(dec); }; })(v.decimals != null ? v.decimals : 2)
            };
        });
        var N = VARS.length;

        /* flatten rows: d[varId] = value, plus id/name/conf */
        var raw = payload.rows.map(function (r) {
            var d = { id: r.teamId, name: r.team, conf: r.conf };
            VARS.forEach(function (v) {
                d[v.key] = (r.values && r.values[v.key] != null) ? r.values[v.key] : null;
            });
            return d;
        });

        /* conference color map */
        var confs = Array.from(new Set(raw.map(function (d) { return d.conf; }))).sort();
        var confColorMap = {};
        confs.forEach(function (c, i) { confColorMap[c] = CONF_COLORS[i % CONF_COLORS.length]; });
        function dotColor(d) { return confColorMap[d.conf] || window.chartTheme().neutral; }

        /* Pearson r */
        function pearsonR(data, xKey, yKey) {
            var valid = data.filter(function (d) { return d[xKey] != null && d[yKey] != null; });
            if (valid.length < 2) return null;
            var xs = valid.map(function (d) { return d[xKey]; });
            var ys = valid.map(function (d) { return d[yKey]; });
            var xm = xs.reduce(function (a, b) { return a + b; }, 0) / xs.length;
            var ym = ys.reduce(function (a, b) { return a + b; }, 0) / ys.length;
            var num = 0, dxSq = 0, dySq = 0;
            for (var i = 0; i < xs.length; i++) {
                var dx = xs[i] - xm, dy = ys[i] - ym;
                num += dx * dy; dxSq += dx * dx; dySq += dy * dy;
            }
            var den = Math.sqrt(dxSq * dySq);
            return den === 0 ? 0 : num / den;
        }

        var rMatrix = {};
        for (var ri = 0; ri < N; ri++) {
            for (var ci = 0; ci < N; ci++) {
                if (ri !== ci) rMatrix[ri + ',' + ci] = pearsonR(raw, VARS[ci].key, VARS[ri].key);
            }
        }

        function corrClass(r) {
            if (r === null) return '';
            var abs = Math.abs(r), dir = r > 0 ? 'pos' : 'neg';
            if (abs < 0.3) return '';
            var str = abs < 0.6 ? 'light' : abs < 0.8 ? 'medium' : 'strong';
            return 'scatter-corr--' + dir + '-' + str;
        }

        /* tooltip — all user text via textContent */
        var tooltip = document.getElementById('scatter-tooltip');

        function showDotTooltip(event, d, xVar, yVar) {
            var nameEl = document.createElement('strong');
            nameEl.textContent = d.name;
            var confEl = document.createElement('div');
            confEl.className = 'scatter-tooltip-conf';
            confEl.textContent = d.conf;
            var xEl = document.createElement('div');
            xEl.textContent = xVar.label + ': ' + (d[xVar.key] != null ? xVar.fmt(d[xVar.key]) : '—');
            var yEl = document.createElement('div');
            yEl.textContent = yVar.label + ': ' + (d[yVar.key] != null ? yVar.fmt(d[yVar.key]) : '—');
            tooltip.textContent = '';
            tooltip.append(nameEl, confEl, xEl, yEl);
            tooltip.style.display = 'block';
            moveTooltip(event);
        }

        function showCorrTooltip(event, xVar, yVar, r) {
            var titleEl = document.createElement('strong');
            titleEl.textContent = xVar.label + ' vs ' + yVar.label;
            var rEl = document.createElement('div');
            rEl.textContent = 'Pearson r: ' + (r >= 0 ? '+' : '') + r.toFixed(4);
            var hintEl = document.createElement('div');
            hintEl.className = 'scatter-tooltip-conf';
            hintEl.textContent = 'Click to expand';
            tooltip.textContent = '';
            tooltip.append(titleEl, rEl, hintEl);
            tooltip.style.display = 'block';
            moveTooltip(event);
        }

        function hideTooltip() { tooltip.style.display = 'none'; }
        function moveTooltip(event) {
            tooltip.style.left = (event.clientX + 14) + 'px';
            tooltip.style.top = (event.clientY + 14) + 'px';
        }

        /* highlight state */
        var selectedTeamId = null;
        var allDots = [];

        function applyHighlight(teamId) {
            allDots.forEach(function (dot) {
                var id = +dot.getAttribute('data-team-id');
                var sel = id === teamId;
                dot.classList.toggle('scatter-dot--selected', sel);
                dot.classList.toggle('scatter-dot--dimmed', !sel);
                dot.setAttribute('r', sel ? 5 : 2.5);
                dot.setAttribute('opacity', sel ? 1 : 0.12);
            });
        }

        function clearHighlight() {
            selectedTeamId = null;
            var lbl = document.getElementById('scatter-selected-label');
            if (lbl) { lbl.style.display = 'none'; lbl.textContent = ''; }
            allDots.forEach(function (dot) {
                dot.classList.remove('scatter-dot--selected', 'scatter-dot--dimmed');
                dot.setAttribute('r', 2.5);
                dot.setAttribute('opacity', 0.7);
            });
        }

        function toggleHighlight(teamId, teamName) {
            if (selectedTeamId === teamId) {
                clearHighlight();
            } else {
                selectedTeamId = teamId;
                var lbl = document.getElementById('scatter-selected-label');
                if (lbl) { lbl.textContent = teamName; lbl.style.display = 'block'; }
                applyHighlight(teamId);
            }
        }

        /* modal */
        var overlay = document.getElementById('scatter-modal-overlay');
        var modalBody = document.getElementById('scatter-modal-body');
        var modalTitle = document.getElementById('scatter-modal-title');

        function openModal(xVar, yVar) {
            modalTitle.textContent = xVar.label + ' vs ' + yVar.label;
            modalBody.textContent = '';

            var margin = { top: 20, right: 20, bottom: 50, left: 60 };
            var totalW = 480, totalH = 480;
            var innerW = totalW - margin.left - margin.right;
            var innerH = totalH - margin.top - margin.bottom;

            var valid = raw.filter(function (d) {
                return d[xVar.key] != null && d[yVar.key] != null;
            });

            var xScale = d3.scaleLinear()
                .domain(d3.extent(valid, function (d) { return d[xVar.key]; }))
                .range([0, innerW]).nice();
            var yScale = d3.scaleLinear()
                .domain(d3.extent(valid, function (d) { return d[yVar.key]; }))
                .range([innerH, 0]).nice();

            var svg = d3.select(modalBody).append('svg')
                .attr('width', totalW).attr('height', totalH)
                .append('g')
                .attr('transform', 'translate(' + margin.left + ',' + margin.top + ')');

            svg.append('g')
                .attr('transform', 'translate(0,' + innerH + ')')
                .call(d3.axisBottom(xScale).ticks(5))
                .call(function (g) {
                    g.selectAll('text').style('fill', 'var(--color-text-muted)').style('font-size', '0.72rem');
                    g.select('.domain').style('stroke', 'var(--color-border)');
                    g.selectAll('.tick line').style('stroke', 'var(--color-border)');
                });

            svg.append('text')
                .attr('x', innerW / 2).attr('y', innerH + 42)
                .attr('text-anchor', 'middle')
                .style('fill', 'var(--color-text)').style('font-size', '0.85rem')
                .text(xVar.label);

            svg.append('g')
                .call(d3.axisLeft(yScale).ticks(5))
                .call(function (g) {
                    g.selectAll('text').style('fill', 'var(--color-text-muted)').style('font-size', '0.72rem');
                    g.select('.domain').style('stroke', 'var(--color-border)');
                    g.selectAll('.tick line').style('stroke', 'var(--color-border)');
                });

            svg.append('text')
                .attr('transform', 'rotate(-90)')
                .attr('x', -innerH / 2).attr('y', -48)
                .attr('text-anchor', 'middle')
                .style('fill', 'var(--color-text)').style('font-size', '0.85rem')
                .text(yVar.label);

            svg.selectAll('circle').data(valid).enter().append('circle')
                .attr('cx', function (d) { return xScale(d[xVar.key]); })
                .attr('cy', function (d) { return yScale(d[yVar.key]); })
                .attr('r', 3.5)
                .attr('fill', dotColor)
                .attr('opacity', 0.7)
                .attr('class', 'scatter-dot')
                .on('mouseover', function (event, d) { showDotTooltip(event, d, xVar, yVar); })
                .on('mousemove', moveTooltip)
                .on('mouseout', hideTooltip);

            overlay.style.display = 'flex';
        }

        function closeModal() {
            overlay.style.display = 'none';
            modalBody.textContent = '';
            hideTooltip();
        }

        document.getElementById('scatter-modal-close').addEventListener('click', closeModal);
        overlay.addEventListener('click', function (e) {
            if (e.target === overlay) closeModal();
        });

        document.addEventListener('keydown', function (e) {
            if (e.key === 'Escape') { clearHighlight(); closeModal(); }
        });

        /* cell renderers */
        function renderDiagonal(cell, v) {
            cell.classList.add('scatter-cell--diag');
            var valid = raw.filter(function (d) { return d[v.key] != null; })
                           .map(function (d) { return d[v.key]; });
            var minV = d3.min(valid), maxV = d3.max(valid);
            var labelDiv = document.createElement('div');
            labelDiv.className = 'scatter-diag-label';
            labelDiv.textContent = v.label;
            var rangeDiv = document.createElement('div');
            rangeDiv.className = 'scatter-diag-range';
            rangeDiv.textContent =
                (minV != null ? v.fmt(minV) : '—') + ' – ' +
                (maxV != null ? v.fmt(maxV) : '—');
            cell.append(labelDiv, rangeDiv);
        }

        function renderScatter(cell, xVar, yVar) {
            cell.classList.add('scatter-cell--scatter');
            var valid = raw.filter(function (d) {
                return d[xVar.key] != null && d[yVar.key] != null;
            });
            if (!valid.length) return;
            var pad = 8;
            var xScale = d3.scaleLinear()
                .domain(d3.extent(valid, function (d) { return d[xVar.key]; }))
                .range([pad, CELL - pad]).nice();
            var yScale = d3.scaleLinear()
                .domain(d3.extent(valid, function (d) { return d[yVar.key]; }))
                .range([CELL - pad, pad]).nice();

            /* viewBox keeps internal coord system at CELL×CELL; SVG scales with the cell */
            var svg = d3.select(cell).append('svg')
                .attr('viewBox', '0 0 ' + CELL + ' ' + CELL)
                .attr('width', '100%').attr('height', '100%');

            xScale.ticks(3).forEach(function (t) {
                svg.append('line')
                    .attr('x1', xScale(t)).attr('x2', xScale(t))
                    .attr('y1', CELL - pad - 2).attr('y2', CELL - pad + 2)
                    .attr('stroke', window.chartTheme().axis).attr('stroke-width', 0.5);
            });
            yScale.ticks(3).forEach(function (t) {
                svg.append('line')
                    .attr('x1', pad - 2).attr('x2', pad + 2)
                    .attr('y1', yScale(t)).attr('y2', yScale(t))
                    .attr('stroke', window.chartTheme().axis).attr('stroke-width', 0.5);
            });

            var circles = svg.selectAll('circle').data(valid).enter().append('circle')
                .attr('cx', function (d) { return xScale(d[xVar.key]); })
                .attr('cy', function (d) { return yScale(d[yVar.key]); })
                .attr('r', 2.5)
                .attr('fill', dotColor)
                .attr('opacity', 0.7)
                .attr('class', 'scatter-dot')
                .attr('data-team-id', function (d) { return d.id; })
                .on('mouseover', function (event, d) { showDotTooltip(event, d, xVar, yVar); })
                .on('mousemove', moveTooltip)
                .on('mouseout', hideTooltip)
                .on('click', function (event, d) {
                    event.stopPropagation();
                    toggleHighlight(d.id, d.name);
                });

            allDots.push.apply(allDots, circles.nodes());
            svg.on('click', clearHighlight);
        }

        function renderCorr(cell, row, col, xVar, yVar) {
            cell.classList.add('scatter-cell--corr');
            var r = rMatrix[row + ',' + col];
            var cls = corrClass(r);
            if (cls) cell.classList.add(cls);
            var valDiv = document.createElement('div');
            valDiv.className = 'scatter-corr-val';
            valDiv.textContent = r !== null ? (r >= 0 ? '+' : '') + r.toFixed(2) : '—';
            cell.appendChild(valDiv);
            cell.addEventListener('mouseover', function (event) {
                if (r != null) showCorrTooltip(event, xVar, yVar, r);
            });
            cell.addEventListener('mousemove', moveTooltip);
            cell.addEventListener('mouseout', hideTooltip);
            cell.addEventListener('click', function () { openModal(xVar, yVar); });
        }

        function renderCell(cell) {
            var row = +cell.dataset.row;
            var col = +cell.dataset.col;
            if (row === col)    renderDiagonal(cell, VARS[col]);
            else if (row > col) renderScatter(cell, VARS[col], VARS[row]);
            else                renderCorr(cell, row, col, VARS[col], VARS[row]);
        }

        /* build grid with lazy IntersectionObserver; column count follows N */
        var grid = document.getElementById('scatter-matrix-root');
        if (!grid) return;
        grid.textContent = '';
        grid.style.gridTemplateColumns = 'repeat(' + N + ', minmax(90px, 1fr))';

        var observer = new IntersectionObserver(function (entries) {
            entries.forEach(function (entry) {
                if (entry.isIntersecting) {
                    renderCell(entry.target);
                    observer.unobserve(entry.target);
                }
            });
        }, { rootMargin: '120px' });

        for (var row = 0; row < N; row++) {
            for (var col = 0; col < N; col++) {
                var cellEl = document.createElement('div');
                cellEl.className = 'scatter-cell';
                cellEl.dataset.row = row;
                cellEl.dataset.col = col;
                grid.appendChild(cellEl);
                observer.observe(cellEl);
            }
        }

        /* conference legend — all user strings via textContent */
        var legend = document.getElementById('scatter-legend');
        if (legend) {
            legend.textContent = '';
            confs.forEach(function (conf) {
                var item = document.createElement('div');
                item.className = 'scatter-legend-item';
                var swatch = document.createElement('div');
                swatch.className = 'scatter-legend-swatch';
                swatch.style.background = confColorMap[conf];
                var label = document.createElement('span');
                label.textContent = conf;
                item.append(swatch, label);
                legend.appendChild(item);
            });
        }
    };
})();
