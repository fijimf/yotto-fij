/* Shared chart palette. Reads the --chart-* design tokens from main.css so
 * every chart (Chart.js and D3 alike) draws from one palette. Loaded globally
 * from the base layout, before any page chart script runs. */
(function () {
    "use strict";

    var cache = null;

    function cssVar(name) {
        return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    }

    /** Lazily-built snapshot of the chart tokens. */
    function theme() {
        if (!cache) {
            cache = {
                series: [1, 2, 3, 4, 5, 6, 7, 8].map(function (i) { return cssVar("--chart-" + i); }),
                mlGreens: ["a", "b", "c", "d"].map(function (k) { return cssVar("--chart-ml-" + k); }),
                win: cssVar("--chart-win"),
                loss: cssVar("--chart-loss"),
                pos: cssVar("--chart-pos"),
                neg: cssVar("--chart-neg"),
                benchmark: cssVar("--chart-benchmark"),
                neutral: cssVar("--chart-neutral"),
                grid: cssVar("--chart-grid"),
                axis: cssVar("--chart-axis")
            };
        }
        return cache;
    }

    /** Nth categorical series color (wraps). */
    function chartColor(i) {
        var s = theme().series;
        return s[((i % s.length) + s.length) % s.length];
    }

    /** Fixed color per prediction-model type; ML:* slugs hash into the greens. */
    function modelColor(type) {
        var t = theme();
        var FIXED = {
            "MASSEY": t.series[0],
            "MASSEY_TOTALS": t.series[3],
            "BRADLEY_TERRY": t.series[1],
            "BRADLEY_TERRY_W": t.series[2],
            "BOOK": t.benchmark
        };
        if (FIXED[type]) return FIXED[type];
        if (type && type.indexOf("ML:") === 0) {
            var h = 0;
            for (var i = 0; i < type.length; i++) h = (h * 31 + type.charCodeAt(i)) >>> 0;
            return t.mlGreens[h % t.mlGreens.length];
        }
        return t.neutral;
    }

    /** "#rrggbb" + alpha in [0,1] → "rgba(r,g,b,a)". Passes through non-hex input. */
    function chartAlpha(hex, alpha) {
        var m = /^#([0-9a-f]{6})$/i.exec(hex);
        if (!m) return hex;
        var n = parseInt(m[1], 16);
        return "rgba(" + ((n >> 16) & 255) + "," + ((n >> 8) & 255) + "," + (n & 255) + "," + alpha + ")";
    }

    window.chartTheme = theme;
    window.chartColor = chartColor;
    window.modelColor = modelColor;
    window.chartAlpha = chartAlpha;
})();
