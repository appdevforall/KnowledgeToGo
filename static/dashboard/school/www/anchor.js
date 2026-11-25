// K2Go School: the block anchor. The teacher sends the place in the page as "this block, this
// character of it at the top edge", never as pixels: pixel offsets differ by thousands of px
// between phones (fonts, text size, late images). The pure functions have unit tests (school/anchor.test.ts); the DOM
// part is thin.
'use strict';

(function (root, factory) {
  if (typeof module === 'object' && module.exports) { module.exports = factory(); }
  else { root.SchoolAnchor = factory(); }
})(this, function () {
  // A block is a leaf text container (it holds no other text container), or a picture that is
  // not inside a text container. Leaves only, so that a list item with a paragraph, or a table
  // row in an infobox, is one place and not two nested ones.
  var TEXT = 'h1,h2,h3,h4,h5,h6,p,li,dt,dd,pre,blockquote,figcaption,caption,tr';
  var MEDIA = 'img,video';
  var PRINT_CHARS = 48;

  // FNV-1a, 32 bit, base 36: a short print of the start of a block, to find the same block when
  // the two pages are not the same (other language, other version, a block more or less).
  function hash(s) {
    var h = 0x811c9dc5;
    for (var i = 0; i < s.length; i++) {
      h ^= s.charCodeAt(i);
      h = Math.imul(h, 0x01000193) >>> 0;
    }
    return h.toString(36);
  }

  function printOf(text) {
    return hash(String(text || '').replace(/\s+/g, ' ').trim().slice(0, PRINT_CHARS));
  }

  // rects: the blocks in document order, { top, bottom } relative to the top edge of the view.
  // The anchor is the first block that is not fully above the edge:
  //   b: its index, n: the number of blocks,
  //   o: the part of the block above the edge (0..1), when the edge cuts the block,
  //   d: px from the top of the block down to the edge (negative: the block starts below it).
  // anchorOf() adds t (the print of the block), and c, l (the character at the edge, see
  // targetY) when the edge cuts a text block.
  function pickAnchor(rects) {
    for (var i = 0; i < rects.length; i++) {
      var r = rects[i];
      var h = r.bottom - r.top;
      if (h > 0 && r.bottom > 0) {
        var d = -r.top;
        return { b: i, n: rects.length, o: d > 0 ? Math.round(d / h * 10000) / 10000 : 0, d: Math.round(d) };
      }
    }
    return { b: -1, n: rects.length, o: 0, d: 0 };
  }

  // prints: the prints of the student blocks. Finds the block of the anchor:
  //   exact: same print at the same index, or the nearest block with the same print;
  //   else the block at the same relative place in the page (the page is not the same).
  function resolveBlock(prints, a) {
    var n = prints.length;
    if (!a || a.b < 0 || n === 0) { return { i: -1, exact: false }; }
    if (a.t === undefined) { return { i: Math.min(a.b, n - 1), exact: a.b < n }; }
    if (prints[a.b] === a.t) { return { i: a.b, exact: true }; }
    for (var k = 1; k < n; k++) {
      if (a.b - k >= 0 && prints[a.b - k] === a.t) { return { i: a.b - k, exact: true }; }
      if (a.b + k < n && prints[a.b + k] === a.t) { return { i: a.b + k, exact: true }; }
      if (a.b - k < 0 && a.b + k >= n) { break; }
    }
    var total = a.n > 1 ? a.n - 1 : 1;
    return { i: Math.min(n - 1, Math.round(a.b / total * (n - 1))), exact: false };
  }

  // top, height: the student block in page coordinates. Gives the page scroll position.
  // Best: the line of the character at the top edge of the teacher (a.c), with the part of that
  // line above the edge (a.l); then the student shows the same line, also when its lines wrap in
  // another place (text size). charTop, charHeight: that character on the student, if found.
  // Else, inside the block the part of the block counts; in the gap above a block the pixels
  // count (gaps are small).
  function targetY(top, height, a, charTop, charHeight) {
    var y;
    if (a.c !== undefined && charTop !== undefined && charTop !== null) { y = charTop + (a.l || 0) * charHeight; }
    else if (a.d > 0 || a.o > 0) { y = top + a.o * height; }
    else { y = top + a.d; }
    return Math.max(0, Math.round(y));
  }

  // The first character that is not a space, at or after c. A space at the end of a line has no
  // clear place on the screen.
  function nextVisible(text, c) {
    var i = c;
    while (i < text.length && /\s/.test(text.charAt(i))) { i++; }
    return i < text.length ? i : c;
  }

  // lengths: the lengths of the text nodes of a block, in order. Gives the node and the offset
  // of character c of the block text.
  function locate(lengths, c) {
    if (c < 0) { return null; }
    var left = c;
    for (var k = 0; k < lengths.length; k++) {
      if (left < lengths[k]) { return { k: k, off: left }; }
      left -= lengths[k];
    }
    return null;
  }

  // DOM part: the blocks of a document. Kept only when the document is fully loaded: a list made
  // while the page loads has only the first blocks (the teacher can send during the load).
  function blocksOf(doc) {
    var cached = doc.__k2goBlocks;
    // A page script can replace blocks after the load: then make the list again.
    if (cached && (!cached.list.length || cached.list[0].isConnected)) { return cached; }
    var list = [];
    if (doc.body) {
      var all = doc.body.querySelectorAll(TEXT + ',' + MEDIA);
      for (var i = 0; i < all.length; i++) {
        var el = all[i];
        var media = el.matches(MEDIA);
        if (media ? (el.parentElement && el.parentElement.closest(TEXT)) : el.querySelector(TEXT)) { continue; }
        list.push(el);
      }
    }
    var prints = list.map(function (el) {
      return el.matches(MEDIA) ? printOf(String(el.getAttribute('src') || '').split('/').pop()) : printOf(el.textContent);
    });
    var blocks = { list: list, prints: prints };
    if (doc.readyState === 'complete') { doc.__k2goBlocks = blocks; }
    return blocks;
  }

  function textNodes(doc, el) {
    var list = [];
    var walker = doc.createTreeWalker(el, 4);   // NodeFilter.SHOW_TEXT
    for (var n = walker.nextNode(); n; n = walker.nextNode()) { list.push(n); }
    return list;
  }

  // The box of character c of a block, or null (no such character, or it shows no box).
  function charRect(doc, el, c) {
    var nodes = textNodes(doc, el);
    var at = locate(nodes.map(function (n) { return n.length; }), c);
    if (!at) { return null; }
    var range = doc.createRange();
    range.setStart(nodes[at.k], at.off);
    range.setEnd(nodes[at.k], at.off + 1);
    var rects = range.getClientRects();
    return rects.length && rects[0].height > 0 ? rects[0] : null;
  }

  // The character of the block at the top edge (x: left side of the block), and the part of its
  // line above the edge. No character when its line is not at the edge (for example the first
  // cell of a tall table row), or when the engine has no caretRangeFromPoint.
  function topChar(doc, el, r) {
    if (!doc.caretRangeFromPoint) { return null; }
    var caret = doc.caretRangeFromPoint(r.left + 2, 1);
    if (!caret || !el.contains(caret.startContainer)) { return null; }
    var before = doc.createRange();
    before.setStart(el, 0);
    before.setEnd(caret.startContainer, caret.startOffset);
    var c = nextVisible(el.textContent, before.toString().length);
    var box = charRect(doc, el, c);
    if (!box || box.bottom < -box.height / 2 || box.top > box.height / 2) { return null; }
    return { c: c, l: Math.max(0, Math.min(1, Math.round(-box.top / box.height * 1000) / 1000)) };
  }

  function anchorOf(win) {
    var doc = win.document;
    var blocks = blocksOf(doc);
    var rects = blocks.list.map(function (el) { return el.getBoundingClientRect(); });
    var a = pickAnchor(rects);
    if (a.b >= 0) {
      a.t = blocks.prints[a.b];
      var top = a.d > 0 ? topChar(doc, blocks.list[a.b], rects[a.b]) : null;
      if (top) { a.c = top.c; a.l = top.l; }
    }
    return a;
  }

  // Returns true when the student shows the same block as the teacher, false when the page is
  // not the same and the place is only near.
  function applyAnchor(win, a) {
    var doc = win.document;
    var blocks = blocksOf(doc);
    var found = resolveBlock(blocks.prints, a);
    if (found.i < 0) { win.scrollTo(0, 0); return !a || a.b < 0; }
    var el = blocks.list[found.i];
    // A block in a closed <details> has no box: open it, as the teacher sees it.
    for (var p = el.parentElement; p; p = p.parentElement) {
      if (p.tagName === 'DETAILS' && !p.open) { p.open = true; }
    }
    var r = el.getBoundingClientRect();
    // The character only counts in the same block: in another page it can be another text.
    var box = found.exact && a.c !== undefined ? charRect(doc, el, a.c) : null;
    win.scrollTo(0, targetY(r.top + win.scrollY, r.height, a,
      box ? box.top + win.scrollY : undefined, box ? box.height : undefined));
    return found.exact;
  }

  return {
    printOf: printOf,
    pickAnchor: pickAnchor,
    resolveBlock: resolveBlock,
    targetY: targetY,
    nextVisible: nextVisible,
    locate: locate,
    blocksOf: blocksOf,
    anchorOf: anchorOf,
    applyAnchor: applyAnchor
  };
});
