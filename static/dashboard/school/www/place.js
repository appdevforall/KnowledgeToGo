// K2Go School: the type of a lesson page, and the place in the apps of the box that the class
// follows (maps, PDF and EPUB in Calibre-Web). A Kiwix page uses the block anchor (anchor.js).
// The place lives in the app (MapLibre, pdf.js, epub.js): this file reads it there each time and
// keeps no copy. Design: k2go-school/notes/follow-content-design.md. The pure functions have unit
// tests (school/place.test.ts); the DOM part is thin.
'use strict';

(function (root, factory) {
  if (typeof module === 'object' && module.exports) { module.exports = factory(); }
  else { root.SchoolPlace = factory(); }
})(this, function () {
  // The Kiwix library, also its page for browsers with no JavaScript.
  function isLibrary(path) { return /^\/kiwix(\/|\/nojs(\/.*)?)?$/.test(path); }

  // The apps of the box that the class can follow: the one list of what follows. The teacher start
  // page shows a card for each app here that the box has (start.html); kindOf() gives the type of
  // a page of an app, or null for a page that the class does not follow (a library, a catalog, a
  // format with no adapter). A new adapter (its kind in read/apply/watch) adds its app here.
  //   id: the app, also the card (start.html) and its words (text.js 'app' + id);
  //   home: the start page of the app on the box.
  var APPS = [
    { id: 'Kiwix', home: '/kiwix/', kindOf: function (p) { return /^\/kiwix\//.test(p) && !isLibrary(p) ? 'page' : null; } },
    { id: 'Maps', home: '/maps/', kindOf: function (p) { return /^\/maps\/(index\.html)?$/.test(p) ? 'map' : null; } },
    { id: 'Books', home: '/books/', kindOf: function (p) {
      var read = /^\/books\/read\/\d+\/(pdf|epub)$/.exec(p);   // the Calibre-Web reader of these two formats
      return read ? read[1] : null;
    } }
  ];

  // 'page' (a Kiwix page, block anchor), 'map', 'pdf', 'epub', or null: a page that only the
  // teacher sees.
  function kindOf(path) {
    var p = String(path || '').split(/[?#]/)[0];
    for (var i = 0; i < APPS.length; i++) {
      var kind = APPS[i].kindOf(p);
      if (kind) { return kind; }
    }
    return null;
  }

  function round(x, digits) {
    var f = Math.pow(10, digits);
    return Math.round(x * f) / f;
  }

  function num(x) { return typeof x === 'number' && isFinite(x); }

  // Bearing in degrees, from -180 to 180.
  function turn(deg) {
    var d = ((deg % 360) + 540) % 360 - 180;
    return d === -180 ? 180 : d;
  }

  // The view of the map that the class follows: center, zoom, bearing, pitch. The style, the
  // globe and the terrain stay as each phone has them (design O2).
  function mapPlace(lat, lon, zoom, bearing, pitch) {
    return { app: 'map', lat: round(lat, 6), lon: round(lon, 6), z: round(zoom, 2), b: round(turn(bearing), 1), p: round(pitch, 1) };
  }

  // The map shows the place (the student applied it, or it was there already).
  function mapAt(now, want) {
    return Math.abs(now.lat - want.lat) < 1e-5 && Math.abs(now.lon - want.lon) < 1e-5 &&
      Math.abs(now.z - want.z) < 0.01 && Math.abs(turn(now.b - want.b)) < 0.5 && Math.abs(now.p - want.p) < 0.5;
  }

  // pdf.js gives the scale as a name ("auto", "page-width") or as a percent.
  function pdfScaleValue(z) {
    return typeof z === 'number' ? String(z / 100) : String(z);
  }

  function pdfAt(now, want) {
    return now.page === want.page && String(now.z) === String(want.z) && Math.abs(now.top - want.top) < 4;
  }

  // A place from the box (the teacher sent it) has the fields of its app.
  function valid(a, kind) {
    if (!a || a.app !== kind) { return false; }
    if (kind === 'map') { return num(a.lat) && num(a.lon) && num(a.z) && num(a.b) && num(a.p); }
    if (kind === 'epub') { return typeof a.cfi === 'string' && a.cfi.indexOf('epubcfi(') === 0; }
    if (kind === 'pdf') { return num(a.page) && a.page >= 1 && num(a.top) && num(a.left) && (num(a.z) || typeof a.z === 'string'); }
    return false;
  }

  // The address to open for a place. A map opens at the place (in its hash): with no hash the maps
  // page first shows the whole world, after the place was applied.
  function openUrl(url, a) {
    if (kindOf(url) !== 'map' || !valid(a, 'map')) { return url; }
    return url.split('#')[0] + '#' + [a.lat, a.lon, a.p, a.b, a.z].join('/');
  }

  // A page of an app (the class font is for Kiwix pages only).
  function isApp(kind) { return kind !== null && kind !== 'page'; }

  // The teacher can mark the page: a Kiwix page (marks.js) or an EPUB book (epubmarks.js).
  function hasMarks(kind) { return kind === 'page' || kind === 'epub'; }

  // The pages of the EPUB book on the screen: [{ win, contents }], one for each inner frame of the
  // reader (epub.js keeps one Contents for each).
  function bookViews(win) {
    try {
      var r = win.reader;
      var list = r && r.rendition ? r.rendition.getContents() : [];
      return list.filter(function (c) { return c && c.window && c.document && c.document.body; })
        .map(function (c) { return { win: c.window, contents: c }; });
    } catch (e) { return []; }
  }

  // What a class does not do in an app page. The map: no download of a region (FQR). The regions
  // are on the box before the class, and the download needs the app (the Library > Maps card), not
  // a class page. The maps page has its own switch (REGION_DOWNLOADER_ENABLED) only when the maps
  // role installs, so the class hides the control of its two buttons ("Select region for download",
  // "Choose region to delete"); the download panel opens only from them. The titles come from the
  // maps page: a new title shows the control again. The map is in the shadow root of <maps-black>,
  // so the style goes there, when the map exists (watch). A deliberate workaround: the owner of the
  // switch is the maps page; a carry patch that turns its downloader off in a class can replace it.
  // A browser with no :has (WebView before 105) uses the second rule: the buttons go, an empty
  // frame of the control can stay.
  var FQR_BUTTONS = 'button[title="Select region for download"], button[title="Choose region to delete"]';
  var APP_CSS = {
    map: '.maplibregl-ctrl-group:has(> button[title="Select region for download"], > button[title="Choose region to delete"]) { display: none !important; }\n' +
      FQR_BUTTONS + ' { display: none !important; }'
  };
  // The EPUB reader: the class font (classCss: the CSS of School.classCss in follow.js) on each
  // page of the book, and one text size, on every phone. The reader has its own font and size
  // setting on each phone (another font breaks the lines at other places, so the pages and the
  // marks fall at other places). The School frame has the same width on every phone (follow.js),
  // so the lines break at the same places; only the page height can differ.
  var BOOK_FONT_ID = 'k2go-class-font';
  function bookFont(rendition, classCss) {
    var put = function (contents) {
      var doc = contents && contents.document;
      if (!doc || !doc.head || doc.getElementById(BOOK_FONT_ID)) { return; }
      var style = doc.createElement('style');
      style.id = BOOK_FONT_ID;
      style.textContent = classCss;
      doc.head.appendChild(style);   // last, so that it wins over the book styles
    };
    rendition.hooks.content.register(put);   // each section that the reader shows later
    rendition.getContents().forEach(put);
    rendition.themes.fontSize('100%');       // over the size that this phone saved in the reader
  }

  function prepare(win, kind, app, classCss) {
    if (kind === 'epub') {
      if (classCss) { bookFont(app, classCss); }
      return;
    }
    var css = APP_CSS[kind];
    if (!css) { return; }
    var root = kind === 'map' ? app.getContainer().getRootNode() : win.document;
    var where = root.head || root;   // a shadow root has no head
    var style = win.document.createElement('style');
    style.textContent = css;
    where.appendChild(style);
  }

  // With a mark tool on, the finger of the teacher marks: the controls of the app that turn the
  // page on a tap go off. The EPUB reader of Calibre-Web has a "previous" and a "next" area over
  // each side of the book (on a phone, 120 px of the 400 px on each side). The arrow keys and the
  // table of contents still turn the page.
  var TOOL_CSS = {
    epub: '#prev, #next { visibility: hidden !important; pointer-events: none !important; }'
  };
  function toolCss(kind) { return TOOL_CSS[kind] || ''; }

  // The app object of the page in the frame, or null while it is not ready.
  function appOf(win, kind) {
    try {
      if (kind === 'map') {
        var el = win.document.querySelector('maps-black');
        return el && el.map ? el.map : null;
      }
      if (kind === 'pdf') {
        var pdf = win.PDFViewerApplication;
        return pdf && pdf.pdfDocument && pdf.pdfViewer && pdf.pdfViewer.pagesCount > 0 && pdf.pdfViewer._location ? pdf : null;
      }
      if (kind === 'epub') {
        // The Calibre-Web reader keeps its reader in a global; the rendition has a location after
        // it showed its first page.
        var r = win.reader;
        return r && r.rendition && r.rendition.location && r.rendition.location.start ? r.rendition : null;
      }
    } catch (e) { /* another origin, or the page goes */ }
    return null;
  }

  // The place in the book at the center of the reader (an EPUB CFI), or null. Each phone breaks the
  // pages at other places: the student page that holds the center of the teacher page shares most
  // of its text. (With the first line of the teacher, the student page could end with that line,
  // and show almost only the text before it.)
  function middleOf(rendition) {
    var box = rendition.manager && rendition.manager.container ? rendition.manager.container.getBoundingClientRect() : null;
    if (!box || box.width <= 0 || box.height <= 0) { return null; }
    var list = rendition.getContents();
    for (var i = 0; i < list.length; i++) {
      var c = list[i];
      var frame = c.window && c.window.frameElement;
      if (!frame || !c.document.caretRangeFromPoint) { continue; }
      var f = frame.getBoundingClientRect();
      var x = box.left + box.width / 2 - f.left;
      if (x < 0 || x > f.width) { continue; }
      // The center can be on a picture or between two paragraphs: there the caret is in a text of
      // spaces only, that the reader cannot show (it goes to the start of the section). Then a
      // point a little higher or lower.
      for (var k = 0; k < MIDDLE_TRIES.length; k++) {
        var y = box.top + box.height * MIDDLE_TRIES[k] - f.top;
        var caret = y >= 0 && y <= f.height ? c.document.caretRangeFromPoint(x, y) : null;
        if (!caret || caret.startContainer.nodeType !== 3 || !/\S/.test(caret.startContainer.data)) { continue; }
        var cfi = c.cfiFromRange(caret);
        return typeof cfi === 'string' && cfi.indexOf('epubcfi(') === 0 ? cfi : null;
      }
    }
    return null;
  }
  var MIDDLE_TRIES = [0.5, 0.4, 0.6, 0.3, 0.7, 0.2, 0.8];

  // The place on the screen, or null while the app is not ready.
  function read(win, kind) {
    var app = appOf(win, kind);
    if (!app) { return null; }
    if (kind === 'epub') {
      var middle = null;
      try { middle = middleOf(app); } catch (e) { /* the first line, as before */ }
      return { app: 'epub', cfi: middle || app.location.start.cfi };
    }
    if (kind === 'pdf') {
      // _location is what pdf.js sends with its "updateviewarea" event. pdf.js 4.5 as Calibre-Web
      // bundles it; a newer pdf.js can rename it, and then the PDF does not follow (null).
      var l = app.pdfViewer._location;
      return { app: 'pdf', page: l.pageNumber, top: Math.round(l.top), left: Math.round(l.left), z: l.scale };
    }
    var c = app.getCenter();
    return mapPlace(c.lat, c.lng, app.getZoom(), app.getBearing(), app.getPitch());
  }

  // Shows the place. true: the view is at the place; null: the app is not ready, try again;
  // false: the place cannot show (not a place of this app).
  // The view shows the place. null: the app is not ready, or the place is not one of this app.
  // EPUB: the page on the screen holds the CFI (the center of the teacher page, middleOf). The
  // page breaks differ between phones, so the page can start or end some lines away from the
  // teacher's.
  function at(win, kind, a) {
    if (!valid(a, kind)) { return null; }
    var app = appOf(win, kind);
    if (!app) { return null; }
    if (kind === 'epub') {
      var loc = app.location;
      var CFI = win.ePub && win.ePub.CFI;
      if (!CFI) { return false; }
      try {
        var cmp = new CFI();
        return cmp.compare(loc.start.cfi, a.cfi) <= 0 && cmp.compare(a.cfi, loc.end.cfi) <= 0;
      } catch (e) { return false; }   // a CFI of another book: display() refuses it
    }
    var now = read(win, kind);
    return kind === 'pdf' ? pdfAt(now, a) : mapAt(now, a);
  }

  function apply(win, kind, a) {
    if (!valid(a, kind)) { return false; }
    var shown = at(win, kind, a);
    if (shown === null) { return null; }
    if (shown) { return true; }
    var app = appOf(win, kind);
    if (kind === 'epub') {
      app.display(a.cfi).catch(function () { /* not in this book */ });
      return true;
    }
    if (kind === 'pdf') {
      var now = read(win, kind);
      var v = app.pdfViewer;
      if (String(now.z) !== String(a.z)) { v.currentScaleValue = pdfScaleValue(a.z); }
      v.scrollPageIntoView({ pageNumber: Math.min(a.page, v.pagesCount), destArray: [null, { name: 'XYZ' }, a.left, a.top, null], allowNegativeOffset: true });
      return true;
    }
    app.jumpTo({ center: [a.lon, a.lat], zoom: a.z, bearing: a.b, pitch: a.p });
    return true;
  }

  // Calls onMove(byUser) after the view of the app moves. byUser: the student or teacher touched,
  // clicked, scrolled or typed in the last USER_MS (a fling or a map ease goes on after the
  // touch). Waits for the app object while the page in the frame is the same: in a hidden page (the
  // screen is off) the app can come much later. After WAIT_MS it looks less often.
  var USER_MS = 3000;
  var WAIT_MS = 20000;
  function watch(win, kind, onMove, classCss) {
    var doc = win.document;
    var lastInput = -Infinity;
    var input = function () { lastInput = Date.now(); };
    ['pointerdown', 'touchstart', 'touchmove', 'wheel', 'keydown'].forEach(function (type) {
      doc.addEventListener(type, input, { capture: true, passive: true });
    });
    var moved = function () { onMove(Date.now() - lastInput < USER_MS); };
    var start = Date.now();
    (function hook() {
      if (win.document !== doc) { return; }   // another page loaded in the frame
      var app = appOf(win, kind);
      if (!app) {
        setTimeout(hook, Date.now() - start < WAIT_MS ? 250 : 2000);
        return;
      }
      if (kind === 'pdf') { app.eventBus.on('updateviewarea', moved); }
      else if (kind === 'epub') {
        // The book pages are in an inner frame: epub.js passes their events on.
        ['touchstart', 'keydown', 'mousedown'].forEach(function (type) { app.on(type, input); });
        app.on('relocated', moved);
      } else { app.on('move', moved); }
      try { prepare(win, kind, app, classCss); } catch (e) { /* the class follows the app also without these changes */ }
      onMove(false);   // the app is ready: not a move, so an early touch does not count
    })();
  }

  return {
    APPS: APPS,
    kindOf: kindOf,
    isLibrary: isLibrary,
    mapPlace: mapPlace,
    mapAt: mapAt,
    pdfScaleValue: pdfScaleValue,
    pdfAt: pdfAt,
    valid: valid,
    openUrl: openUrl,
    isApp: isApp,
    hasMarks: hasMarks,
    bookViews: bookViews,
    toolCss: toolCss,
    middleOf: middleOf,
    prepare: prepare,
    read: read,
    at: at,
    apply: apply,
    watch: watch
  };
});
