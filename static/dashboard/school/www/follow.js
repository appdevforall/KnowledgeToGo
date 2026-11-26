// K2Go School: shared code of the teacher and student pages.
// The page shows the lesson in a same-origin frame at one fixed layout width and with one font
// from the box, then scales it to the screen. The place in the page is a block anchor
// (anchor.js), so a different text size or a late image does not move the student off.
'use strict';

var School = (function () {
  var params = new URLSearchParams(location.search);
  var WIDTH = parseInt(params.get('w'), 10) || 400;
  var PING_MS = 10000;      // the box sends a ping this often (router.ts)
  var DEAD_MS = 25000;      // no event for this long: the channel is dead, open a new one

  function fitFrame(stage, frame) {
    var scale = stage.clientWidth / WIDTH;
    frame.style.width = WIDTH + 'px';
    frame.style.height = Math.ceil(stage.clientHeight / scale) + 'px';
    frame.style.transform = 'scale(' + scale + ')';
  }

  // The stage changes size with the window, and with the header above it (a note, a button that
  // shows or hides, a line that wraps on a small phone).
  function watchFit(stage, frames) {
    var fit = function () { frames.forEach(function (f) { fitFrame(stage, f); }); };
    if (window.ResizeObserver) { new ResizeObserver(fit).observe(stage); }
    else { window.addEventListener('resize', fit); }
    fit();
  }

  // One font for the class frame, served by the box (fonts/OFL.txt), so that the lines wrap the
  // same on every phone. Browsers have different default fonts, and each maker maps
  // "sans-serif" to its own font. Code keeps a monospace font. "?font=native" keeps the page
  // fonts, to compare.
  var FONT = 'K2Go Class';
  function fontFace(file, weight, style) {
    return '@font-face { font-family: "' + FONT + '"; src: url("/school/fonts/' + file +
      '") format("truetype"); font-weight: ' + weight + '; font-style: ' + style +
      '; font-display: block; }\n';
  }
  var CLASS_CSS =
    fontFace('AtkinsonHyperlegible-Regular.ttf', 400, 'normal') +
    fontFace('AtkinsonHyperlegible-Bold.ttf', 700, 'normal') +
    fontFace('AtkinsonHyperlegible-Italic.ttf', 400, 'italic') +
    fontFace('AtkinsonHyperlegible-BoldItalic.ttf', 700, 'italic') +
    'html { font-size: 16px !important; -webkit-text-size-adjust: 100% !important; ' +
    'text-size-adjust: 100% !important; }\n' +
    'body, body * { font-family: "' + FONT + '", sans-serif !important; }\n' +
    'pre, pre *, code, kbd, samp, tt { font-family: monospace !important; }\n';

  function normalize(doc) {
    if (params.get('font') === 'native' || !doc.head) { return; }
    var style = doc.createElement('style');
    style.textContent = CLASS_CSS;
    doc.head.appendChild(style);   // last, so that it wins over the page styles
  }

  // Calls back when the layout of the page in the frame changes: a late image, the class font
  // that arrives, a different text size. At most once per frame.
  function onLayout(win, callback) {
    var doc = win.document;
    var queued = false;
    function fire() {
      if (queued) { return; }
      queued = true;
      win.requestAnimationFrame(function () { queued = false; callback(); });
    }
    if (win.ResizeObserver && doc.body) { new win.ResizeObserver(fire).observe(doc.body); }
    doc.addEventListener('load', fire, true);   // images; load does not bubble, capture does
    if (doc.fonts && doc.fonts.ready) { doc.fonts.ready.then(fire); }
  }

  // The lesson view: two frames on the stage. A new page loads in the hidden frame; the page on
  // the screen stays until the new one has the class font and its first layout, and onReady
  // applied the anchor. Then the new page fades in over the old one: no white or empty frame
  // between two pages. A page that navigates by itself (a form, a link that the page does not
  // open with open()) loads in place, with no fade.
  // onReady(win) runs for each page load, with the new page already on view.win().
  var FONT_WAIT_MS = 1500;    // the longest wait for the class font; then the page shows anyway
  var LOAD_WAIT_MS = 20000;   // a load with no load event: give up the hidden page
  var FADE_MS = 200;          // the CSS transition of the pages (opacity) is shorter
  function view(stage, onReady) {
    var a = document.createElement('iframe');
    var b = document.createElement('iframe');
    [a, b].forEach(function (f) {
      f.title = 'Lesson';
      f.addEventListener('load', function () { loaded(f); });
      stage.appendChild(f);
    });
    // pdf.js in a frame of the same origin sends its "webviewerloaded" event to the parent document
    // (this page), not to its own window, where the Calibre-Web reader sets the PDF file and the
    // worker. Without this, the PDF in the frame does not open.
    document.addEventListener('webviewerloaded', function (ev) {
      var src = ev.detail && ev.detail.source;
      if (src && (src === a.contentWindow || src === b.contentWindow)) {
        src.dispatchEvent(new CustomEvent('webviewerloaded', { detail: ev.detail }));
      }
    });
    var front = a;
    var back = b;
    var asked = {};         // per frame: the URL that open() gave it, and the path where it landed
    var loadingUrl = null;  // the URL that the hidden frame loads
    var gen = 0;            // open() count: a late callback of an older open does nothing
    var loadTimer = null;
    var blankTimer = null;
    watchFit(stage, [a, b]);

    function key(f) { return f === a ? 'a' : 'b'; }

    // The path of the page in a frame, relative to the box origin. No hash: a change of hash only
    // fires no load event in the student frame, and the anchor already gives the place.
    function pathOf(f) {
      try {
        var l = f.contentWindow.location;
        return l.protocol === 'about:' ? null : l.pathname + l.search;
      } catch (e) { return null; }   // another origin: not a class page
    }

    function docOf(f) {
      try { return f.contentDocument; } catch (e) { return null; }
    }

    function whenFontReady(doc, done) {
      var called = false;
      var go = function () { if (!called) { called = true; done(); } };
      setTimeout(go, FONT_WAIT_MS);
      if (params.get('font') === 'native' || !doc.fonts || !doc.fonts.load) { go(); return; }
      doc.fonts.load('16px "' + FONT + '"').then(go, go);
    }

    function show(old) {
      clearTimeout(blankTimer);
      front.className = 'front';
      if (loadingUrl !== null) { return; }   // the old frame loads the next page already
      old.className = 'old';   // stays under the new page until the fade ends
      blankTimer = setTimeout(function () {
        old.className = '';
        try { old.contentWindow.location.replace('about:blank'); } catch (e) { old.src = 'about:blank'; }
      }, FADE_MS);
    }

    // timedOut: no load event came in LOAD_WAIT_MS; take the hidden frame as it is.
    function loaded(f, timedOut) {
      var doc = docOf(f);
      var path = pathOf(f);
      if (f === back) {
        // about:blank of an old page; it can come late, after open() gave the frame a new URL.
        if (loadingUrl === null || (!path && doc && !timedOut)) { return; }
        clearTimeout(loadTimer);
      } else {
        if (!path) { return; }
        asked[key(f)] = null;   // it navigated by itself: no URL of open() for this page
      }
      // The page of an app (a map, a PDF, the EPUB reader) keeps its own font: the class font would
      // move the pdf.js text layer off the glyphs. The text of an EPUB book is in inner frames of
      // the reader: it gets the class font there (place.js bookFont).
      var app = path !== null && SchoolPlace.isApp(SchoolPlace.kindOf(path));
      if (doc && !app) { normalize(doc); }
      var mine = gen;
      var ready = function () {
        if (f === back) {
          if (mine !== gen || loadingUrl === null) { return; }   // a newer open() runs
          asked[key(f)] = { url: loadingUrl, landed: path };
          loadingUrl = null;
          stage.classList.remove('loading');
          var old = front;
          front = back;
          back = old;
          if (path) { onReady(front.contentWindow); }
          // The next frame paints the anchor that onReady applied; then the page shows. A hidden
          // page gets no animation frame: the timer shows it anyway.
          var shown = false;
          var reveal = function () { if (!shown) { shown = true; show(old); } };
          requestAnimationFrame(reveal);
          setTimeout(reveal, 100);
        } else if (f === front) {
          onReady(f.contentWindow);
        }
      };
      if (doc && path && !app) { whenFontReady(doc, ready); } else { ready(); }
    }

    function open(url) {
      gen += 1;
      clearTimeout(blankTimer);
      clearTimeout(loadTimer);
      back.className = '';
      loadingUrl = url;
      asked[key(back)] = null;
      stage.classList.add('loading');
      // replace: a lesson page is no history entry, so Back in the browser leaves the class page.
      // The hidden frame can still hold the same page (a fast page change): a new hash alone then
      // loads nothing and fires no load event, so the page loads again.
      var samePage = pathOf(back) === url.split('#')[0];
      try {
        back.contentWindow.location.replace(url);
        if (samePage) { back.contentWindow.location.reload(); }
      } catch (e) { back.src = url; }
      var mine = gen;
      loadTimer = setTimeout(function () { if (mine === gen) { loaded(back, true); } }, LOAD_WAIT_MS);
    }

    return {
      win: function () { return front.contentWindow; },
      // The path of the page on the screen, or null (no page yet, or not a page of this box).
      path: function () { return pathOf(front); },
      open: open,
      loading: function () { return loadingUrl !== null; },
      // True when the page on the screen is url, or the page where url landed after a redirect
      // (the path is then another one, but this is the page for url: do not load it again).
      isAt: function (url) {
        var p = pathOf(front);
        var o = asked[key(front)];
        return p !== null && (p === url || (o !== null && o !== undefined && o.url === url && o.landed === p));
      }
    };
  }

  // Watchdog of a live channel (EventSource). A channel can die with no error event (M0: after
  // an overload; or the Wi-Fi goes off and on): the box sends a ping with data every PING_MS,
  // and with no event in DEAD_MS the page opens a new channel. When the network or the screen
  // comes back, it checks at once. reopen() must open the channel and call seen() for each
  // event; onClosed() runs when the browser gives up (for example a 401 or a 502).
  function keepAlive(opts) {
    var lastSeen = performance.now();
    function check(now) {
      var live = opts.live();
      if (!live || live.readyState === EventSource.CLOSED) { opts.onClosed(); return; }
      if (now || performance.now() - lastSeen > DEAD_MS) { lastSeen = performance.now(); opts.reopen(); }
    }
    setInterval(function () { check(false); }, 5000);
    window.addEventListener('online', function () { check(true); });
    document.addEventListener('visibilitychange', function () {
      if (document.visibilityState === 'visible') { check(false); }
    });
    return { seen: function () { lastSeen = performance.now(); } };
  }

  return {
    params: params,
    WIDTH: WIDTH,
    view: view,
    normalize: normalize,
    // The CSS of the class font, for an app that puts it in its own pages (the EPUB reader,
    // place.js), or '' with "?font=native".
    classCss: function () { return params.get('font') === 'native' ? '' : CLASS_CSS; },
    onLayout: onLayout,
    keepAlive: keepAlive
  };
})();
