// K2Go School: the marks of the teacher on the lesson page (M2). The box keeps the list
// (class.ts); this file turns a mark into rectangles on the page, and a tap or a text selection of
// the teacher into a mark. A mark uses the blocks of anchor.js: block index, print, characters.
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
  // A picture gets a frame, not a fill: a fill hides a dark picture.
  var FRAME = 'box-shadow: inset 0 0 0 4px rgb(255, 200, 0), 0 0 0 1px rgba(0, 0, 0, 0.6);';
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
  // null. A phrase wins over the block around it; then the smallest mark.
  function hitTest(drawn, x, y) {
    var best = null;
    drawn.forEach(function (d) {
      d.rects.forEach(function (r) {
        if (x < r.left || x > r.right || y < r.top || y > r.bottom) { return; }
        var area = (r.right - r.left) * (r.bottom - r.top);
        var rank = d.k === 'b' ? 1 : 0;
        if (!best || rank < best.rank || (rank === best.rank && area < best.area)) {
          best = { id: d.id, rank: rank, area: area };
        }
      });
    });
    return best ? best.id : null;
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

  // The client rects of a mark on this page, or null when its blocks are not on this page.
  function clientRects(doc, blocks, m) {
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
      var rects = clientRects(doc, blocks, m);
      if (!rects || !rects.length) { return; }
      var el = blocks.list[sameBlock(blocks.prints, m.b, m.t)];
      var media = m.k === 'b' && /^(IMG|VIDEO)$/.test(el.tagName);
      var fill = isLight(win.getComputedStyle(el).color) ? DARK_FILL : FILL;
      var local = rects.map(function (r) {
        var box = clamp({ left: r.left - origin.left, top: r.top - origin.top, right: r.right - origin.left,
          bottom: r.bottom - origin.top }, pageW, pageH);
        var d = doc.createElement('div');
        d.setAttribute('style', TOP + 'left: ' + box.left + 'px; top: ' +
          box.top + 'px; width: ' + (box.right - box.left) + 'px; height: ' + (box.bottom - box.top) + 'px; ' +
          (media ? FRAME : fill));
        layer.appendChild(d);
        return box;
      });
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

  // A phrase mark for the text selection in win: { mark }, { error: 'outside' }, or null
  // (no selection). An end of the selection outside the blocks (between two paragraphs) moves to
  // the first or last block that the selection holds.
  function phraseMark(win) {
    var doc = win.document;
    var sel = win.getSelection ? win.getSelection() : null;
    if (!sel || sel.isCollapsed || !sel.rangeCount) { return null; }
    var range = sel.getRangeAt(0);
    var blocks = Anchor.blocksOf(doc);
    var b = blockOf(blocks, range.startContainer);
    var eb = blockOf(blocks, range.endContainer);
    var s = b >= 0 ? charsBefore(doc, blocks.list[b], range.startContainer, range.startOffset) : 0;
    var e = eb >= 0 ? charsBefore(doc, blocks.list[eb], range.endContainer, range.endOffset) : 0;
    if (b < 0 || eb < 0) {
      var inside = [];
      blocks.list.forEach(function (el, i) { if (range.intersectsNode(el)) { inside.push(i); } });
      if (!inside.length) { return { error: 'outside' }; }
      if (b < 0) { b = inside[0]; s = 0; }
      if (eb < 0) { eb = inside[inside.length - 1]; e = blocks.list[eb].textContent.length; }
    }
    var p = trimEnds(function (i) { return blocks.list[i].textContent; }, b, s, eb, e);
    if (!p) { return { error: 'outside' }; }
    return { mark: { k: 'p', b: p.b, t: blocks.prints[p.b], s: p.s, eb: p.eb, et: blocks.prints[p.eb], e: p.e } };
  }

  return {
    mergeLines: mergeLines,
    hitTest: hitTest,
    trimEnds: trimEnds,
    isLight: isLight,
    clamp: clamp,
    draw: draw,
    blockMark: blockMark,
    phraseMark: phraseMark
  };
});
