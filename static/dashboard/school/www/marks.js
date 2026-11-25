// K2Go School: the marks of the teacher on the lesson page (M2). The box keeps the list
// (class.ts); this file turns a mark into rectangles on the page, and a tap or a drag of the
// teacher into a mark. A mark uses the blocks of anchor.js: block index, print, characters.
// The marks show in one layer over the page, not in the page text: the text nodes of the page
// stay as they are (the anchor counts characters in them). The pure functions have unit tests
// (school/marks.test.ts).
'use strict';

(function (root, factory) {
  if (typeof module === 'object' && module.exports) { module.exports = factory(require('./anchor.js')); }
  else { root.SchoolMarks = factory(root.SchoolAnchor); }
})(this, function (Anchor) {
  var LAYER_ID = 'k2go-marks';
  // One color (maintainer: one color at first). Multiply keeps the text under it dark and sharp.
  // Each rect is on top of the page (z-index) and blends with it: the layer has no z-index, so it
  // makes no group of its own that the rects would blend into.
  var TOP = 'position: absolute; margin: 0; padding: 0; border: 0; z-index: 2147483647; ';
  var FILL = 'background: rgb(255, 222, 50); mix-blend-mode: multiply; border-radius: 2px;';
  // A page in dark mode (light text on a dark page, for example a Kiwix book in a dark theme):
  // multiply would make the text yellow on an almost black page. A clear yellow band keeps the
  // light text readable on it.
  var DARK_FILL = 'background: rgba(255, 200, 0, 0.35); border-radius: 2px;';
  // A picture and a box get a frame, not a fill: a fill hides a dark picture. The thin dark edge
  // shows the frame on a light page.
  var FRAME = 'box-shadow: inset 0 0 0 4px rgb(255, 200, 0), 0 0 0 1px rgba(0, 0, 0, 0.6);';
  // A box smaller than this (frame px) is a tap, not a box.
  var MIN_BOX = 12;
  var PAD = 2;

  // rects: client rects of a text range ({ left, top, right, bottom }). Gives one rectangle per
  // line: a range gives one rect per text node and per inline box, often overlapping.
  function mergeLines(rects) {
    var lines = [];
    rects.filter(function (r) { return r.right - r.left > 0.5 && r.bottom - r.top > 0.5; })
      .sort(function (p, q) { return p.top - q.top || p.left - q.left; })
      .forEach(function (r) {
        for (var i = 0; i < lines.length; i++) {
          var l = lines[i];
          var overlap = Math.min(l.bottom, r.bottom) - Math.max(l.top, r.top);
          if (overlap >= Math.min(l.bottom - l.top, r.bottom - r.top) / 2) {
            l.left = Math.min(l.left, r.left);
            l.right = Math.max(l.right, r.right);
            l.top = Math.min(l.top, r.top);
            l.bottom = Math.max(l.bottom, r.bottom);
            return;
          }
        }
        lines.push({ left: r.left, top: r.top, right: r.right, bottom: r.bottom });
      });
    return lines;
  }

  // drawn: [{ id, k, rects }] as draw() gives it. The mark under the point (layer coordinates), or
  // null. A phrase wins over the block around it, a block over a box (a box shows only a frame, so
  // a tap inside it can mean the block under it); then the smallest mark.
  function hitTest(drawn, x, y) {
    var best = null;
    drawn.forEach(function (d) {
      d.rects.forEach(function (r) {
        if (x < r.left || x > r.right || y < r.top || y > r.bottom) { return; }
        var area = (r.right - r.left) * (r.bottom - r.top);
        var rank = d.k === 'p' ? 0 : d.k === 'b' ? 1 : 2;
        if (!best || rank < best.rank || (rank === best.rank && area < best.area)) {
          best = { id: d.id, rank: rank, area: area };
        }
      });
    });
    return best ? best.id : null;
  }

  // The box or oval under the point or near it (pad, layer coordinates): { id, rect }, or null. The
  // smallest one wins (a box inside a box).
  function shapeAt(drawn, x, y, pad) {
    var best = null;
    drawn.forEach(function (d) {
      if (d.k !== 'r') { return; }
      d.rects.forEach(function (r) {
        if (x < r.left - pad || x > r.right + pad || y < r.top - pad || y > r.bottom + pad) { return; }
        var area = (r.right - r.left) * (r.bottom - r.top);
        if (!best || area < best.area) { best = { id: d.id, rect: r, area: area }; }
      });
    });
    return best ? { id: best.id, rect: best.rect } : null;
  }

  // The index of the student block for mark block b with print t, or -1. Only the same block
  // counts: on another version of the page a mark at a near place would mark other words.
  function sameBlock(prints, b, t) {
    var r = Anchor.resolveBlock(prints, { b: b, t: t, n: prints.length });
    return r.exact ? r.i : -1;
  }

  // The text node and offset of character c of a block; c may be the end of the text.
  function pointAt(doc, el, c) {
    var nodes = Anchor.textNodes(doc, el);
    var lengths = nodes.map(function (n) { return n.length; });
    var at = Anchor.locate(lengths, c);
    if (at) { return { node: nodes[at.k], off: at.off }; }
    var total = lengths.reduce(function (s, n) { return s + n; }, 0);
    if (c === total && nodes.length) { return { node: nodes[nodes.length - 1], off: lengths[lengths.length - 1] }; }
    return null;
  }

  // The position of a box mark on this page, or null (its edges cross). topR, bottomR: the client
  // rects of the blocks of its top and bottom edge; origin: the client position of the page origin.
  // x holds across the page (the frame width is the same on every phone); each edge holds its place
  // in its block.
  function boxRect(topR, bottomR, origin, m) {
    var top = topR.top + m.y;
    var bottom = bottomR.top + m.ey;
    if (bottom - top < 1) { return null; }
    return { left: origin.left + m.x, top: top, right: origin.left + m.x + m.w, bottom: bottom };
  }

  // The client rects of a mark on this page, or null when its blocks are not on this page.
  function clientRects(doc, blocks, m, origin) {
    var i = sameBlock(blocks.prints, m.b, m.t);
    if (i < 0) { return null; }
    var el = blocks.list[i];
    if (m.k === 'b') {
      if (m.cell !== undefined) {
        el = el.cells && el.cells[m.cell];
        if (!el) { return null; }
      }
      var r = el.getBoundingClientRect();
      if (r.width <= 0 || r.height <= 0) { return null; }   // hidden (a closed <details>, a hidden cell)
      return [{ left: r.left - PAD, top: r.top - PAD, right: r.right + PAD, bottom: r.bottom + PAD }];
    }
    if (m.k === 'p') {
      var j = i + (m.eb - m.b);   // the same shift as the first block
      if (blocks.prints[j] !== m.et) { return null; }
      var start = pointAt(doc, el, m.s);
      var end = pointAt(doc, blocks.list[j], m.e);
      if (!start || !end) { return null; }
      var range = doc.createRange();
      range.setStart(start.node, start.off);
      range.setEnd(end.node, end.off);
      return mergeLines(Array.prototype.slice.call(range.getClientRects()));
    }
    if (m.k === 'r') {
      var k = sameBlock(blocks.prints, m.eb, m.et);
      if (k < 0) { return null; }
      var topR = el.getBoundingClientRect();
      var bottomR = blocks.list[k].getBoundingClientRect();
      if (topR.height <= 0 || bottomR.height <= 0) { return null; }   // hidden, or a picture not loaded yet
      var box = boxRect(topR, bottomR, origin, m);
      return box ? [box] : null;
    }
    return null;
  }

  // True for a light color ("rgb(r, g, b)" or "rgba(...)" as getComputedStyle gives it): light
  // text is on a dark page.
  function isLight(color) {
    var c = /rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)/.exec(String(color || ''));
    if (!c) { return false; }
    return (0.2126 * c[1] + 0.7152 * c[2] + 0.0722 * c[3]) / 255 > 0.5;
  }

  // A rect in page coordinates, cut to the page (w x h).
  function clamp(r, w, h) {
    return { left: Math.max(0, r.left), top: Math.max(0, r.top), right: Math.min(w, r.right), bottom: Math.min(h, r.bottom) };
  }

  // Draws the marks on the page in win, in a new layer. Returns the layer and what it drew, for
  // the hit test of the teacher. A mark that is not on this page is not drawn.
  function draw(win, list) {
    var doc = win.document;
    var old = doc.getElementById(LAYER_ID);
    if (old) { old.parentNode.removeChild(old); }
    var drawn = [];
    if (!list || !list.length || !doc.documentElement) { return { layer: null, drawn: drawn }; }
    // A mark stays inside the page: a rect out of it would make the page wider or longer.
    var pageW = doc.documentElement.clientWidth;
    var pageH = doc.documentElement.scrollHeight;
    var layer = doc.createElement('div');
    layer.id = LAYER_ID;
    layer.setAttribute('aria-hidden', 'true');
    layer.setAttribute('style', 'position: absolute; left: 0; top: 0; width: 0; height: 0; margin: 0; padding: 0; ' +
      'border: 0; pointer-events: none;');
    // Out of the body: the layout watch (School.onLayout) looks at the body only.
    doc.documentElement.appendChild(layer);
    var origin = layer.getBoundingClientRect();
    var blocks = Anchor.blocksOf(doc);
    list.forEach(function (m) {
      var rects = clientRects(doc, blocks, m, origin);
      if (!rects || !rects.length) { return; }
      var el = blocks.list[sameBlock(blocks.prints, m.b, m.t)];
      var frame = m.k === 'r' || (m.k === 'b' && /^(IMG|VIDEO)$/.test(el.tagName));
      var fill = isLight(win.getComputedStyle(el).color) ? DARK_FILL : FILL;
      var local = rects.map(function (r) {
        var box = clamp({ left: r.left - origin.left, top: r.top - origin.top, right: r.right - origin.left,
          bottom: r.bottom - origin.top }, pageW, pageH);
        if (box.right - box.left < 1 || box.bottom - box.top < 1) { return null; }   // off the page
        var d = doc.createElement('div');
        d.setAttribute('style', TOP + 'left: ' + box.left + 'px; top: ' +
          box.top + 'px; width: ' + (box.right - box.left) + 'px; height: ' + (box.bottom - box.top) + 'px; ' +
          (frame ? FRAME : fill) + (m.o === 1 ? ' border-radius: 50%;' : ''));
        layer.appendChild(d);
        return box;
      }).filter(function (box) { return box !== null; });
      if (!local.length) { return; }
      drawn.push({ id: m.id, k: m.k, rects: local });
    });
    return { layer: layer, drawn: drawn };
  }

  // The index of the block that holds node, or -1.
  function blockOf(blocks, node) {
    for (var el = node && node.nodeType === 1 ? node : node && node.parentElement; el; el = el.parentElement) {
      var i = blocks.list.indexOf(el);
      if (i >= 0) { return i; }
    }
    return -1;
  }

  // A block mark for a tap on target, or null (no block there).
  function blockMark(doc, target) {
    var blocks = Anchor.blocksOf(doc);
    var i = blockOf(blocks, target);
    if (i < 0) { return null; }
    var m = { k: 'b', b: i, t: blocks.prints[i] };
    var cell = blocks.list[i].tagName === 'TR' && target.closest ? target.closest('td, th') : null;
    if (cell && cell.parentElement === blocks.list[i]) { m.cell = cell.cellIndex; }
    return m;
  }

  // A box mark for a rectangle that the teacher drew (client coordinates of the page in win), or
  // null (too small: a tap; or no block on the page). Each edge hangs on the block under it.
  function boxMark(win, rect) {
    var w = Math.round(rect.right - rect.left);
    if (w < MIN_BOX || rect.bottom - rect.top < MIN_BOX) { return null; }
    var blocks = Anchor.blocksOf(win.document);
    var rects = blocks.list.map(function (el) { return el.getBoundingClientRect(); });
    var i = blockAtY(rects, rect.top, rect.left, rect.right, true);
    var k = blockAtY(rects, rect.bottom, rect.left, rect.right, false);
    if (i < 0 || k < 0) { return null; }
    return {
      k: 'r', b: i, t: blocks.prints[i], y: Math.round(rect.top - rects[i].top),
      eb: k, et: blocks.prints[k], ey: Math.round(rect.bottom - rects[k].top),
      x: Math.round(rect.left + win.scrollX), w: w
    };
  }

  // rects: the block rects in page order. The block under y: among the blocks that hold y, the
  // first one that overlaps the box across (x0..x1), else the first one. An edge in a gap between
  // two blocks hangs on the block inside the box: the top edge (down) on the nearest block below,
  // the bottom edge on the nearest block above. Else the other one, else -1.
  function blockAtY(rects, y, x0, x1, down) {
    var holds = -1;
    var above = -1;
    var below = -1;
    for (var i = 0; i < rects.length; i++) {
      var r = rects[i];
      if (r.bottom - r.top <= 0) { continue; }
      if (r.top <= y && y < r.bottom) {
        if (r.right > x0 && r.left < x1) { return i; }
        if (holds < 0) { holds = i; }
      } else if (r.bottom <= y && (above < 0 || r.bottom > rects[above].bottom)) {
        above = i;
      } else if (r.top > y && (below < 0 || r.top < rects[below].top)) {
        below = i;
      }
    }
    if (holds >= 0) { return holds; }
    if (down) { return below >= 0 ? below : above; }
    return above >= 0 ? above : below;
  }

  // The characters of block el before (node, off).
  function charsBefore(doc, el, node, off) {
    var r = doc.createRange();
    r.setStart(el, 0);
    r.setEnd(node, off);
    return r.toString().length;
  }

  // text(i): the text of block i. A start with no visible character after it moves to the start of
  // the next block, and an end with no visible character before it to the end of the block before
  // (a picture, an empty block, a selection of a whole paragraph that ends at the start of the next
  // block, a row that starts with a space). Gives { b, s, eb, e }, or null when no text is left.
  function trimEnds(text, b, s, eb, e) {
    while (b <= eb && !/\S/.test(text(b).slice(s))) { b += 1; s = 0; }
    while (eb >= b && !/\S/.test(text(eb).slice(0, e))) {
      eb -= 1;
      e = eb >= b ? text(eb).length : 0;
    }
    if (eb < b || (eb === b && e <= s)) { return null; }
    return { b: b, s: s, eb: eb, e: e };
  }

  // The word of text at character c: { start, end } (end not included), or null when c is not in
  // a word. A caret right after a word (the finger on the right half of its last letter) counts for
  // that word. segments(text), if given, cuts the text into [{ start, end, word }] (Intl.Segmenter:
  // also for languages with no spaces, and with no punctuation in the word); else the words are the
  // parts between spaces.
  function wordAround(text, c, segments) {
    var i = Math.min(Math.max(c, 0), text.length);
    if (segments) {
      var list = segments(text);
      var after = null;
      for (var k = 0; k < list.length; k++) {
        var s = list[k];
        if (!s.word) { continue; }
        if (s.start <= i && i < s.end) { return { start: s.start, end: s.end }; }
        if (s.end === i) { after = { start: s.start, end: s.end }; }
      }
      return after;
    }
    if ((i >= text.length || /\s/.test(text.charAt(i))) && i > 0 && !/\s/.test(text.charAt(i - 1))) { i -= 1; }
    if (i >= text.length || /\s/.test(text.charAt(i))) { return null; }
    var a = i;
    var z = i + 1;
    while (a > 0 && !/\s/.test(text.charAt(a - 1))) { a -= 1; }
    while (z < text.length && !/\s/.test(text.charAt(z))) { z += 1; }
    return { start: a, end: z };
  }

  // text(i), prints: the text and the print of block i. a, z: the words under the finger at the
  // start and at the end of a drag, { b, start, end }, in any order. Gives the phrase mark from the
  // first word to the last one, all words in between included, or null.
  function phraseBetween(text, prints, a, z) {
    var first = a;
    var last = z;
    if (z.b < a.b || (z.b === a.b && z.start < a.start)) { first = z; last = a; }
    var p = trimEnds(text, first.b, first.start, last.b, last.end);
    if (!p) { return null; }
    return { k: 'p', b: p.b, t: prints[p.b], s: p.s, eb: p.eb, et: prints[p.eb], e: p.e };
  }

  // Intl.Segmenter (Chrome 87 and later) as segments() of wordAround, or null (an old WebView).
  var segmenters = {};
  function segmentsFor(lang) {
    if (typeof Intl === 'undefined' || !Intl.Segmenter) { return null; }
    var key = lang || '';
    if (!segmenters[key]) {
      var seg = new Intl.Segmenter(lang || undefined, { granularity: 'word' });
      segmenters[key] = function (text) {
        var out = [];
        var it = seg.segment(text)[Symbol.iterator]();
        for (var n = it.next(); !n.done; n = it.next()) {
          out.push({ start: n.value.index, end: n.value.index + n.value.segment.length, word: !!n.value.isWordLike });
        }
        return out;
      };
    }
    return segmenters[key];
  }

  // The word under the point (client coordinates of the page in win): { b, start, end }, or null
  // (no text there, or the point is out of its block). The word stays in its text node: a line
  // break (<br>) or the next table cell adds no character to the text, so a word must not go over
  // it.
  function wordAt(win, x, y) {
    var doc = win.document;
    if (!doc.caretRangeFromPoint) { return null; }
    var caret = doc.caretRangeFromPoint(x, y);
    if (!caret || caret.startContainer.nodeType !== 3) { return null; }
    var blocks = Anchor.blocksOf(doc);
    var b = blockOf(blocks, caret.startContainer);
    if (b < 0) { return null; }
    var el = blocks.list[b];
    var r = el.getBoundingClientRect();
    if (y < r.top || y > r.bottom) { return null; }   // the nearest caret of a gap between blocks
    var node = caret.startContainer;
    var w = wordAround(node.data, caret.startOffset, segmentsFor(doc.documentElement.lang));
    if (!w) { return null; }
    var base = charsBefore(doc, el, node, 0);
    return { b: b, start: base + w.start, end: base + w.end };
  }

  // The phrase mark for a drag from word a to word z of the page in win, or null.
  function dragMark(win, a, z) {
    var blocks = Anchor.blocksOf(win.document);
    return phraseBetween(function (i) { return blocks.list[i].textContent; }, blocks.prints, a, z);
  }

  return {
    mergeLines: mergeLines,
    hitTest: hitTest,
    shapeAt: shapeAt,
    boxRect: boxRect,
    blockAtY: blockAtY,
    boxMark: boxMark,
    trimEnds: trimEnds,
    isLight: isLight,
    clamp: clamp,
    draw: draw,
    blockMark: blockMark,
    wordAround: wordAround,
    segmentsFor: segmentsFor,
    phraseBetween: phraseBetween,
    wordAt: wordAt,
    dragMark: dragMark
  };
});
