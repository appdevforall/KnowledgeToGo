// K2Go School: the marks of the teacher in an EPUB book (the Calibre-Web reader, epub.js). A mark
// is a range of the book as an EPUB CFI (epub.js makes it and finds it again), so it stays on the
// same words when the page breaks at another place (another font or screen on each phone).
//   p: the words of the range get the marker.
//   r: a box (o: 1, an oval) as the teacher drew it. The range goes from the first word in it to
//      the last one; each corner holds its place from its word: the top left corner from the first
//      word, the bottom right corner from the last one (x, y, ex, ey: in 1/100 of the font size, so
//      the box grows with the text). When the two words are no longer in one column, or the box
//      from the corners does not hold them (the line breaks between them on this phone), the box
//      goes around the words of the range (one box for each column).
//   b: a frame around a picture.
// The reader shows each section of the book in an inner frame; a "view" here is one of them:
// { win: the window of the inner frame, contents: its epub.js Contents }. The pure functions have
// unit tests (school/marks.test.ts).
'use strict';

(function (root, factory) {
  if (typeof module === 'object' && module.exports) { module.exports = factory(require('./marks.js')); }
  else { root.SchoolEpubMarks = factory(root.SchoolMarks); }
})(this, function (Marks) {
  var PAD = 3;
  var MEDIA = /^(IMG|IMAGE|SVG|VIDEO)$/i;

  // The CFI is in the section with this base (contents.cfiBase). A CFI of another section must not
  // go to this one: its steps can find other nodes here.
  function inSection(cfi, base) {
    return typeof cfi === 'string' && typeof base === 'string' && base !== '' && cfi.indexOf('epubcfi(' + base + '!') === 0;
  }

  // rects: client rects of a range. Groups them by column of the page: the rects of one column
  // overlap across, the columns do not (a gap is between them).
  function columns(rects) {
    var groups = [];
    rects.filter(function (r) { return r.right - r.left > 0.5 && r.bottom - r.top > 0.5; })
      .sort(function (p, q) { return p.left - q.left; })
      .forEach(function (r) {
        var g = groups[groups.length - 1];
        if (g && r.left < g.right) {
          g.right = Math.max(g.right, r.right);
          g.rects.push(r);
        } else {
          groups.push({ right: r.right, rects: [r] });
        }
      });
    return groups.map(function (g) { return g.rects; });
  }

  // The rectangle around rects, pad larger on each side.
  function around(rects, pad) {
    var box = { left: Infinity, top: Infinity, right: -Infinity, bottom: -Infinity };
    rects.forEach(function (r) {
      box.left = Math.min(box.left, r.left);
      box.top = Math.min(box.top, r.top);
      box.right = Math.max(box.right, r.right);
      box.bottom = Math.max(box.bottom, r.bottom);
    });
    return { left: box.left - pad, top: box.top - pad, right: box.right + pad, bottom: box.bottom + pad };
  }

  // An oval through the corners of the box: the words in the box stay inside it.
  function ovalAround(box) {
    var dx = (box.right - box.left) * (Math.SQRT2 - 1) / 2;
    var dy = (box.bottom - box.top) * (Math.SQRT2 - 1) / 2;
    return { left: box.left - dx, top: box.top - dy, right: box.right + dx, bottom: box.bottom + dy };
  }

  // The corners of a drawn box from its words: a, b the rects of the first and the last word, fa,
  // fb their font sizes (px). Gives { x, y, ex, ey }.
  function corners(a, fa, b, fb, box) {
    return {
      x: Math.round((box.left - a.left) / fa * 100),
      y: Math.round((box.top - a.top) / fa * 100),
      ex: Math.round((box.right - b.right) / fb * 100),
      ey: Math.round((box.bottom - b.bottom) / fb * 100)
    };
  }

  // The box of mark m from its words on this page, or null when its corners cross.
  function cornerBox(a, fa, b, fb, m) {
    var box = {
      left: a.left + m.x * fa / 100,
      top: a.top + m.y * fa / 100,
      right: b.right + m.ex * fb / 100,
      bottom: b.bottom + m.ey * fb / 100
    };
    return box.right - box.left >= 1 && box.bottom - box.top >= 1 ? box : null;
  }

  // The box of box mark m as the teacher drew it, from its words on this page, or null: the range
  // is in two columns, or the box does not hold the center of its first and last word (the line
  // breaks between them on this phone). ends: the rects and font sizes of the first and the last
  // word ({ a, fa, b, fb }), or null.
  function fitted(m, rects, ends) {
    if (m.k !== 'r' || typeof m.x !== 'number' || !ends || columns(rects).length !== 1) { return null; }
    var box = cornerBox(ends.a, ends.fa, ends.b, ends.fb, m);
    return box && centerIn(ends.a, box) && centerIn(ends.b, box) ? box : null;
  }

  // The client rects to draw for mark m with the rects of its range (ends: as fitted()).
  function shapes(m, rects, ends) {
    var cols = columns(rects);
    if (m.k === 'p') {
      return cols.reduce(function (all, c) { return all.concat(Marks.mergeLines(c)); }, []);
    }
    var box = fitted(m, rects, ends);
    if (box) { return [box]; }
    return cols.map(function (c) {
      var outline = around(c, PAD);
      return m.o === 1 ? ovalAround(outline) : outline;
    });
  }

  // The range of the mark in this view, or null (another section, or a CFI that does not fit).
  function rangeOf(view, cfi) {
    if (!inSection(cfi, view.contents.cfiBase)) { return null; }
    try {
      var range = view.contents.range(cfi);
      return range && !range.collapsed ? range : null;
    } catch (e) { return null; }
  }

  function fontSize(win, node) {
    var el = node.nodeType === 1 ? node : node.parentElement;
    var size = el ? parseFloat(win.getComputedStyle(el).fontSize) : NaN;
    return size > 0 ? size : 16;
  }

  // The words of a text: [{ start, end }]. Intl.Segmenter where the phone has it (also for
  // languages with no spaces); else the parts between spaces.
  function wordsOf(doc, text) {
    var seg = Marks.segmentsFor(doc.documentElement.lang);
    if (seg) {
      return seg(text).filter(function (w) { return w.word; }).map(function (w) { return { start: w.start, end: w.end }; });
    }
    var out = [];
    var re = /\S+/g;
    for (var w = re.exec(text); w; w = re.exec(text)) { out.push({ start: w.index, end: w.index + w[0].length }); }
    return out;
  }

  // The word (or picture) at an end of a range: the one that starts at (node, off), or with last
  // true the one that ends there. Gives { rect, size }, or null. A word that wraps (a hyphen at the
  // end of a line) gives its first part, or its last part for the last word.
  function endOf(win, node, off, last) {
    var doc = win.document;
    if (node.nodeType !== 3) {
      var el = node.childNodes[last ? off - 1 : off];
      if (el && el.nodeType === 3) { return endOf(win, el, last ? el.length : 0, last); }
      return el && el.getBoundingClientRect ? { rect: el.getBoundingClientRect(), size: fontSize(win, node) } : null;
    }
    var w = Marks.wordAround(node.data, last ? off - 1 : off, Marks.segmentsFor(doc.documentElement.lang));
    if (!w) { return null; }
    var r = doc.createRange();
    r.setStart(node, w.start);
    r.setEnd(node, w.end);
    var rects = r.getClientRects();
    if (!rects.length) { return null; }
    return { rect: rects[last ? rects.length - 1 : 0], size: fontSize(win, node) };
  }

  // The first and the last word of the range, or null.
  function endsOf(win, range) {
    var a = endOf(win, range.startContainer, range.startOffset, false);
    var b = endOf(win, range.endContainer, range.endOffset, true);
    return a && b ? { a: a.rect, fa: a.size, b: b.rect, fb: b.size } : null;
  }

  // Draws the marks of the list that are in this view, in a new layer. As SchoolMarks.draw.
  function draw(view, list) {
    var win = view.win;
    var drawn = [];
    var layer = Marks.newLayer(win.document, list);
    if (!layer) { return { layer: null, drawn: drawn }; }
    list.forEach(function (m) {
      var range = rangeOf(view, m.cfi);
      if (!range) { return; }
      var ends = m.k === 'r' ? endsOf(win, range) : null;
      var all = Array.prototype.slice.call(range.getClientRects());
      var fit = fitted(m, all, ends);
      var rects = fit ? [fit] : shapes(m, all, null);
      if (!rects.length) { return; }
      var at = range.startContainer.nodeType === 1 ? range.startContainer : range.startContainer.parentElement;
      var light = at ? Marks.isLight(win.getComputedStyle(at).color) : false;
      var d = Marks.paint(win, layer, m, rects, m.k !== 'p', light);
      if (d) {
        d.fit = !!fit;   // a box as drawn: the teacher can move it (a box around the words cannot)
        drawn.push(d);
      }
    });
    return { layer: layer, drawn: drawn };
  }

  function cfiMark(view, k, range) {
    try {
      var cfi = view.contents.cfiFromRange(range);
      return inSection(cfi, view.contents.cfiBase) ? { k: k, cfi: cfi } : null;
    } catch (e) { return null; }
  }

  // The word under the point (client coordinates of the view): { node, start, end }, or null.
  function wordAt(view, x, y) {
    var doc = view.win.document;
    if (!doc.caretRangeFromPoint) { return null; }
    var caret = doc.caretRangeFromPoint(x, y);
    if (!caret || caret.startContainer.nodeType !== 3) { return null; }
    var node = caret.startContainer;
    var w = Marks.wordAround(node.data, caret.startOffset, Marks.segmentsFor(doc.documentElement.lang));
    if (!w) { return null; }
    var range = doc.createRange();
    range.setStart(node, w.start);
    range.setEnd(node, w.end);
    var r = range.getBoundingClientRect();
    if (y < r.top - PAD || y > r.bottom + PAD) { return null; }   // the nearest caret of an empty place
    return { node: node, start: w.start, end: w.end };
  }

  // a before z in the book.
  function before(a, z) {
    if (a.node === z.node) { return a.start <= z.start; }
    return !!(a.node.compareDocumentPosition(z.node) & 4);   // DOCUMENT_POSITION_FOLLOWING
  }

  // The phrase mark from word a to word z, in any order, or null.
  function dragMark(view, a, z) {
    var first = before(a, z) ? a : z;
    var last = first === a ? z : a;
    var range = view.win.document.createRange();
    range.setStart(first.node, first.start);
    range.setEnd(last.node, last.end);
    return cfiMark(view, 'p', range);
  }

  function centerIn(r, box) {
    var x = (r.left + r.right) / 2;
    var y = (r.top + r.bottom) / 2;
    return r.right > r.left && x >= box.left && x <= box.right && y >= box.top && y <= box.bottom;
  }

  function meets(r, box) {
    return r.right >= box.left && r.left <= box.right && r.bottom >= box.top && r.top <= box.bottom;
  }

  // The box mark for a rectangle that the teacher drew (client coordinates of the view): the range
  // of the words and pictures with their center in it, and the corners of the rectangle from the
  // first and the last of them. Null: too small (a tap), or nothing in it.
  function boxMark(view, rect) {
    if (rect.right - rect.left < Marks.MIN_BOX || rect.bottom - rect.top < Marks.MIN_BOX) { return null; }
    var win = view.win;
    var doc = win.document;
    if (!doc.body) { return null; }
    var range = doc.createRange();
    var first = null;
    var last = null;
    var hit = function (node, start, end) {
      if (!first) { first = { node: node, start: start }; }
      last = { node: node, end: end };
    };
    var walk = doc.createTreeWalker(doc.body, 5);   // NodeFilter.SHOW_ELEMENT | SHOW_TEXT
    for (var n = walk.nextNode(); n; n = walk.nextNode()) {
      if (n.nodeType === 1) {
        if (MEDIA.test(n.tagName) && centerIn(n.getBoundingClientRect(), rect)) { hit(n, -1, -1); }
        continue;
      }
      range.selectNodeContents(n);
      if (!Array.prototype.some.call(range.getClientRects(), function (r) { return meets(r, rect); })) { continue; }
      wordsOf(doc, n.data).forEach(function (w) {
        range.setStart(n, w.start);
        range.setEnd(n, w.end);
        if (centerIn(range.getBoundingClientRect(), rect)) { hit(n, w.start, w.end); }
      });
    }
    if (!first) { return null; }
    var out = doc.createRange();
    if (first.start < 0) { out.setStartBefore(first.node); } else { out.setStart(first.node, first.start); }
    if (last.end < 0) { out.setEndAfter(last.node); } else { out.setEnd(last.node, last.end); }
    var m = cfiMark(view, 'r', out);
    var ends = m ? endsOf(win, out) : null;
    if (!ends) { return m; }
    var c = corners(ends.a, ends.fa, ends.b, ends.fb, rect);
    m.x = c.x;
    m.y = c.y;
    m.ex = c.ex;
    m.ey = c.ey;
    return m;
  }

  // The mark for a tap on a picture.
  function pictureMark(view, media) {
    var range = view.win.document.createRange();
    range.selectNode(media);
    return cfiMark(view, 'b', range);
  }

  return {
    // The pictures that a pen tap marks (the cover of a book is often an <image> in an <svg>).
    media: 'img, video, svg',
    inSection: inSection,
    columns: columns,
    around: around,
    ovalAround: ovalAround,
    corners: corners,
    cornerBox: cornerBox,
    fitted: fitted,
    shapes: shapes,
    draw: draw,
    wordAt: wordAt,
    dragMark: dragMark,
    boxMark: boxMark,
    pictureMark: pictureMark
  };
});
