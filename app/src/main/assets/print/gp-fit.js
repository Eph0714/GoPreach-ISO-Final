/*
 * gp-fit.js - one fit-to-page rule for every printed GoPreach report, the SAME file on the website and in the Android app (app/src/main/assets/print/gp-fit.js).
 *
 * The printed copy is the report as it looks in GoPreach (nothing is redrawn): this script only decides, for the chosen paper, HOW LARGE the page's own tables are laid out, so that
 *   - nothing is cut off at the right edge and no column is squeezed to nothing;
 *   - the table uses the whole width of the paper (columns widen; no wide empty strip beside it);
 *   - text stays readable: columns wrap first, and only a table whose unbreakable columns are still too wide is scaled down (never below MIN_SCALE);
 *   - the orientation that needs the least shrinking is chosen (upright when the table fits upright, across otherwise);
 *   - a block that fits on one page stays whole, a longer one flows over pages with its header repeated and no row split.
 *
 * Mark the table that must fit with class "gp-fit" and each block that should stay together (title + table) with class "gp-sheet".
 *
 *   GPFit.layout({ paper: 'A4' | 'LETTER', orientation: 'auto' | 'portrait' | 'landscape', marginMm: 8 })
 *     -> { paper, landscape, scale, pageWidthPx, pageHeightPx, pageWidthMm, pageHeightMm, marginMm, fits }
 *   GPFit.reset()
 */
(function (g) {
    'use strict';
    var MM = 96 / 25.4, MIN_SCALE = 0.55, PAPER = { A4: [210, 297], LETTER: [215.9, 279.4], LEGAL: [215.9, 355.6] };

    function tables(root) { return [].slice.call((root || document).querySelectorAll('.gp-fit')); }
    function sheets(root) { return [].slice.call((root || document).querySelectorAll('.gp-sheet')); }

    function reset(root) {
        tables(root).forEach(function (t) { t.style.zoom = ''; t.style.width = ''; t.style.tableLayout = ''; });
        sheets(root).forEach(function (s) { s.style.breakInside = ''; s.style.pageBreakInside = ''; });
    }

    // How much a table must be shrunk to fit [availPx]: laid out at the page width first (columns wrap), then measured. 1 = it fits as it is.
    function scaleFor(t, availPx) {
        t.style.zoom = ''; t.style.width = availPx + 'px';
        var w = Math.max(t.offsetWidth, t.scrollWidth);
        return w <= availPx + 0.5 ? 1 : Math.max(MIN_SCALE, availPx / w);
    }

    function layout(o) {
        o = o || {};
        var paper = PAPER[String(o.paper || 'A4').toUpperCase()] ? String(o.paper || 'A4').toUpperCase() : 'A4';
        var margin = o.marginMm == null ? 8 : +o.marginMm, dims = PAPER[paper], list = tables();
        var availFor = function (landscape) { return ((landscape ? dims[1] : dims[0]) - 2 * margin) * MM; };
        var pageHFor = function (landscape) { return ((landscape ? dims[0] : dims[1]) - 2 * margin) * MM; };
        // lay every table out for one orientation: how much it had to shrink, and how many pages the blocks then take
        var applyFor = function (landscape) {
            var avail = availFor(landscape), scale = 1;
            list.forEach(function (t) {
                var s = scaleFor(t, avail);
                if (s < 1) { t.style.zoom = String(s); t.style.width = (avail / s) + 'px'; }   // zoom shrinks the table; the width is widened to match so it still ends at the right margin
                scale = Math.min(scale, s);
            });
            // the whole printed document (heading, blocks, summary): from the top of the body to the lowest thing in it, not the height of the window around it
            var top = document.body.getBoundingClientRect().top, bottom = top;
            [].forEach.call(document.body.children, function (c) { if (c.tagName !== 'SCRIPT' && c.tagName !== 'STYLE') bottom = Math.max(bottom, c.getBoundingClientRect().bottom); });
            var height = Math.max(bottom - top, 1);
            return { scale: scale, pages: height / pageHFor(landscape) };
        };
        var landscape, res;
        if (o.orientation === 'portrait') landscape = false;
        else if (o.orientation === 'landscape') landscape = true;
        else if (!list.length) landscape = false;
        else {
            // upright when it fits well; across when upright would shrink the text or take clearly more pages (narrow columns make tall rows)
            var p = applyFor(false), l = applyFor(true);
            landscape = (p.scale < 0.82 && l.scale > p.scale) || (l.scale >= 0.82 && Math.ceil(l.pages - 0.02) < Math.ceil(p.pages - 0.02));   // fewer sheets of paper wins; a tie stays upright
        }
        reset();
        res = applyFor(landscape);
        var availPx = availFor(landscape), pageH = pageHFor(landscape);
        // a block that fits on one page is not split by a page break; a longer one flows
        sheets().forEach(function (s) {
            var h = s.getBoundingClientRect().height;
            if (h > 0 && h <= pageH * 0.98) { s.style.breakInside = 'avoid'; s.style.pageBreakInside = 'avoid'; }
        });
        var overflow = list.some(function (t) { return t.offsetWidth * (parseFloat(t.style.zoom) || 1) > availPx + 1; });
        // From here the size follows the page itself: the print dialog may still change the paper or the orientation, and a table that spans exactly the width it is given fits whatever sheet is used
        // (the shrink factor stays; its width is the whole page width in any case).
        list.forEach(function (t) { var z = parseFloat(t.style.zoom) || 1; t.style.width = z < 1 ? 'calc(100% / ' + z + ')' : '100%'; });
        return {
            paper: paper, landscape: landscape, scale: res.scale, pages: Math.ceil(res.pages - 0.01), marginMm: margin, fits: !overflow,
            pageWidthPx: availPx, pageHeightPx: pageH,
            pageWidthMm: landscape ? dims[1] : dims[0], pageHeightMm: landscape ? dims[0] : dims[1],
        };
    }

    g.GPFit = { layout: layout, reset: reset, MIN_SCALE: MIN_SCALE };
})(window);
