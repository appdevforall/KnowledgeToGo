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

  // 'page' (a Kiwix page, block anchor), 'map', 'pdf', 'epub', or null: a page that only the
  // teacher sees (the libraries, the box start page, and the apps that the class does not follow:
  // a list of what follows, so that a new app on the box start page does not go to the class).
  function kindOf(path) {
    var p = String(path || '').split(/[?#]/)[0];
    if (/^\/maps\/(index\.html)?$/.test(p)) { return 'map'; }
    var book = /^\/books\/read\/\d+\/(pdf|epub)$/.exec(p);
    if (book) { return book[1]; }
    if (/^\/kiwix\//.test(p) && !isLibrary(p)) { return 'page'; }
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

  // A page of an app (the class font and the marks are for Kiwix pages only).
  function isApp(kind) { return kind !== null && kind !== 'page'; }

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

  // The place on the screen, or null while the app is not ready.
  function read(win, kind) {
    var app = appOf(win, kind);
    if (!app) { return null; }
    if (kind === 'epub') { return { app: 'epub', cfi: app.location.start.cfi }; }
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
  function apply(win, kind, a) {
    if (!valid(a, kind)) { return false; }
    var app = appOf(win, kind);
    if (!app) { return null; }
    var now = read(win, kind);
    if (kind === 'epub') {
      // The page on the screen holds the CFI, or the reader shows the page that holds it. The page
      // height differs between phones, so the page can start some lines before the teacher's.
      var loc = app.location;
      var CFI = win.ePub && win.ePub.CFI;
      if (CFI) {
        var cmp = new CFI();
        try {
          if (cmp.compare(loc.start.cfi, a.cfi) <= 0 && cmp.compare(a.cfi, loc.end.cfi) <= 0) { return true; }
        } catch (e) { /* a CFI of another book: display() refuses it */ }
      }
      app.display(a.cfi).catch(function () { /* not in this book */ });
      return true;
    }
    if (kind === 'pdf') {
      if (pdfAt(now, a)) { return true; }
      var v = app.pdfViewer;
      if (String(now.z) !== String(a.z)) { v.currentScaleValue = pdfScaleValue(a.z); }
      v.scrollPageIntoView({ pageNumber: Math.min(a.page, v.pagesCount), destArray: [null, { name: 'XYZ' }, a.left, a.top, null], allowNegativeOffset: true });
      return true;
    }
    if (!mapAt(now, a)) { app.jumpTo({ center: [a.lon, a.lat], zoom: a.z, bearing: a.b, pitch: a.p }); }
    return true;
  }

  // Calls onMove(byUser) after the view of the app moves. byUser: the student or teacher touched,
  // clicked, scrolled or typed in the last USER_MS (a fling or a map ease goes on after the
  // touch). Waits for the app object while the page in the frame is the same: in a hidden page (the
  // screen is off) the app can come much later. After WAIT_MS it looks less often.
  var USER_MS = 3000;
  var WAIT_MS = 20000;
  function watch(win, kind, onMove) {
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
      onMove(false);   // the app is ready: not a move, so an early touch does not count
    })();
  }

  return {
    kindOf: kindOf,
    isLibrary: isLibrary,
    mapPlace: mapPlace,
    mapAt: mapAt,
    pdfScaleValue: pdfScaleValue,
    pdfAt: pdfAt,
    valid: valid,
    openUrl: openUrl,
    isApp: isApp,
    read: read,
    apply: apply,
    watch: watch
  };
});
