// K2Go School M0 spike: shared code of the teacher and student pages.
// The page shows the lesson in a same-origin frame at one fixed layout width, then scales it
// to the screen. Then the text has the same layout on each phone and a scroll anchor lands on
// the same words.
'use strict';

var School = (function () {
  var params = new URLSearchParams(location.search);
  var WIDTH = parseInt(params.get('w'), 10) || 400;

  var BLOCKS = 'h1,h2,h3,h4,h5,h6,p,li,pre,table,figure,img,blockquote,dt,dd';

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

  function blocks(doc) {
    return doc.body ? doc.body.querySelectorAll(BLOCKS) : [];
  }

  // Anchor = index of the first block that crosses the top edge, plus the part of it that is
  // above the edge. It survives small layout differences (fonts) better than a pixel offset.
  function anchorOf(win) {
    var list = blocks(win.document);
    for (var i = 0; i < list.length; i++) {
      var r = list[i].getBoundingClientRect();
      if (r.height > 0 && r.bottom > 0) {
        return { b: i, o: Math.max(0, -r.top) / r.height };
      }
    }
    return { b: -1, o: 0 };
  }

  function applyAnchor(win, a, y) {
    var el = a && a.b >= 0 ? blocks(win.document)[a.b] : null;
    if (el) {
      var r = el.getBoundingClientRect();
      win.scrollTo(0, Math.round(r.top + win.scrollY + a.o * r.height));
    } else {
      win.scrollTo(0, y || 0);
    }
  }

  // Browsers have different default fonts (Chrome: serif, the system WebView: sans-serif), so
  // a page with no font rule wraps its lines differently. Force one base font in the frame.
  // "?font=native" keeps the browser default, to compare.
  function normalize(doc) {
    if (params.get('font') === 'native' || !doc.head) { return; }
    var style = doc.createElement('style');
    style.textContent = 'html { font-family: sans-serif; font-size: 16px; ' +
      '-webkit-text-size-adjust: 100%; text-size-adjust: 100%; }';
    doc.head.insertBefore(style, doc.head.firstChild);
  }

  // Path of the page in the frame, relative to the box origin. No hash: a change of hash only
  // fires no load event in the student frame, and the scroll anchor already gives the place.
  function framePath(frame) {
    var l = frame.contentWindow.location;
    return l.pathname + l.search;
  }

  return {
    params: params,
    WIDTH: WIDTH,
    watchFit: watchFit,
    anchorOf: anchorOf,
    applyAnchor: applyAnchor,
    normalize: normalize,
    framePath: framePath
  };
})();
