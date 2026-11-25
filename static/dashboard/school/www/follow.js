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

  function watchFit(stage, frame) {
    var fit = function () { fitFrame(stage, frame); };
    window.addEventListener('resize', fit);
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

  // Path of the page in the frame, relative to the box origin. No hash: a change of hash only
  // fires no load event in the student frame, and the anchor already gives the place.
  function framePath(frame) {
    var l = frame.contentWindow.location;
    return l.pathname + l.search;
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
    watchFit: watchFit,
    normalize: normalize,
    onLayout: onLayout,
    framePath: framePath,
    keepAlive: keepAlive
  };
})();
