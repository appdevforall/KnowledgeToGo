// K2Go School: the marks of the teacher on the box map (MapLibre on the maps page). A mark holds
// map places, not screen places, so it stays on the same place of the map when the view pans,
// zooms, turns or tilts, on the teacher phone and on each student phone.
//   a: an arrow from the first place to the second one (the head is at the second place). The
//      marker tool of a text page draws an arrow on the map.
//   r: a box (o: 1, an oval) through the four corners that the teacher drew (top left, top right,
//      bottom right, bottom left of the drawn rectangle). On a map that turns or tilts later the
//      four corners show as a shape with four sides; the oval fills that shape.
// geo: the places as whole numbers, longitude and latitude in 1/1000000 of a degree (about 0.1 m),
// in pairs: [lon, lat, lon, lat, ...]. Numbers only, as all marks (no text of the teacher).
// The marks show in one SVG layer over the map canvas (in the map container, in the shadow root of
// <maps-black>), drawn again on each move of the map. The head of an arrow and the lines keep one
// size on the screen at every zoom. The pure functions have unit tests (school/marks.test.ts).
'use strict';

(function (root, factory) {
  if (typeof module === 'object' && module.exports) { module.exports = factory(); }
  else { root.SchoolMapMarks = factory(); }
})(this, function () {
  var E6 = 1000000;
  var MAX_LAT = 85.051129;   // the edge of the web map (Mercator)
  var LAYER_CLASS = 'k2go-map-marks';
  // The shortest arrow and the smallest box (screen px): a shorter drag is a tap.
  var MIN_PX = 12;
  // The head of an arrow (screen px): its length and its half width.
  var HEAD_LEN = 22;
  var HEAD_HALF = 13;
  var OVAL_STEPS = 48;
  var SVG_NS = 'http://www.w3.org/2000/svg';
  // The yellow of the marker (marks.js) with a thin dark edge, so the mark shows on light and dark
  // parts of the map (sea, roads, a satellite picture).
  var YELLOW = 'rgb(255, 200, 0)';
  var EDGE = 'rgba(0, 0, 0, 0.6)';

  function e6(x) { return Math.round(x * E6); }

  // The number of whole turns (360 degrees) to add to longitude lng so it is nearest to the
  // longitude near. A map can show copies of the world side by side: a mark goes on the copy that
  // the view shows.
  function turnsTo(lng, near) { return Math.round((near - lng) / 360) * 360; }

  // The geo of places [{ lng, lat }]. The first place goes into the world from -180 to 180
  // degrees, and the others move with it (a box over the 180 degree line keeps its shape). A place
  // near the horizon of a tilted map can be very far: the others stay within one turn (360
  // degrees) of the first one and within the latitudes of the map, so the box accepts the mark.
  function geoOf(places) {
    var out = [];
    var shift = places.length ? turnsTo(places[0].lng, 0) : 0;
    var lng0 = places.length ? places[0].lng + shift : 0;
    places.forEach(function (p) {
      var lng = Math.max(lng0 - 360, Math.min(lng0 + 360, p.lng + shift));
      out.push(e6(lng), e6(Math.max(-MAX_LAT, Math.min(MAX_LAT, p.lat))));
    });
    return out;
  }

  // The places of a mark: [[lng, lat], ...].
  function placesOf(m) {
    var out = [];
    for (var i = 0; i + 1 < m.geo.length; i += 2) { out.push([m.geo[i] / E6, m.geo[i + 1] / E6]); }
    return out;
  }

  // The arrow from (x0, y0) to (x1, y1) on the screen: the line stops at the base of the head, so
  // the tip stays sharp. head: the three points of the head (tip first). A short arrow gets a
  // smaller head (at most half of its length). Null for no length.
  function arrowShape(x0, y0, x1, y1, len, half) {
    var dx = x1 - x0;
    var dy = y1 - y0;
    var d = Math.sqrt(dx * dx + dy * dy);
    if (!(d >= 1)) { return null; }
    var l = Math.min(len, d / 2);
    var w = half * l / len;
    var ux = dx / d;
    var uy = dy / d;
    var bx = x1 - ux * l;
    var by = y1 - uy * l;
    return {
      line: [x0, y0, bx, by],
      head: [[x1, y1], [bx - uy * w, by + ux * w], [bx + uy * w, by - ux * w]]
    };
  }

  // An oval in the shape with four corners q (top left, top right, bottom right, bottom left): the
  // circle of a unit square, put through the shape. n points.
  function ovalPoints(q, n) {
    var out = [];
    for (var i = 0; i < n; i++) {
      var a = 2 * Math.PI * i / n;
      var u = (1 + Math.cos(a)) / 2;
      var v = (1 + Math.sin(a)) / 2;
      var top = [q[0][0] + (q[1][0] - q[0][0]) * u, q[0][1] + (q[1][1] - q[0][1]) * u];
      var bottom = [q[3][0] + (q[2][0] - q[3][0]) * u, q[3][1] + (q[2][1] - q[3][1]) * u];
      out.push([top[0] + (bottom[0] - top[0]) * v, top[1] + (bottom[1] - top[1]) * v]);
    }
    return out;
  }

  // The distance from point (x, y) to the line from (x0, y0) to (x1, y1).
  function lineDist(x, y, x0, y0, x1, y1) {
    var dx = x1 - x0;
    var dy = y1 - y0;
    var d2 = dx * dx + dy * dy;
    var k = d2 > 0 ? Math.max(0, Math.min(1, ((x - x0) * dx + (y - y0) * dy) / d2)) : 0;
    var px = x0 + k * dx - x;
    var py = y0 + k * dy - y;
    return Math.sqrt(px * px + py * py);
  }

  // The point is in the shape pts ([[x, y], ...]).
  function inside(pts, x, y) {
    var on = false;
    for (var i = 0, j = pts.length - 1; i < pts.length; j = i++) {
      var a = pts[i];
      var b = pts[j];
      if ((a[1] > y) !== (b[1] > y) && x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]) { on = !on; }
    }
    return on;
  }

  // The distance from the point to the edge of the shape pts.
  function edgeDist(pts, x, y) {
    var best = Infinity;
    for (var i = 0, j = pts.length - 1; i < pts.length; j = i++) {
      best = Math.min(best, lineDist(x, y, pts[j][0], pts[j][1], pts[i][0], pts[i][1]));
    }
    return best;
  }

  function area(pts) {
    var s = 0;
    for (var i = 0, j = pts.length - 1; i < pts.length; j = i++) { s += (pts[j][0] + pts[i][0]) * (pts[j][1] - pts[i][1]); }
    return Math.abs(s / 2);
  }

  // shapes: [{ id, k, o, pts }] in screen px (shapesOf). The mark under the point or within pad of
  // it, or null. An arrow wins (it has no inside: a tap must be near its line); then the smallest
  // shape.
  function hit(shapes, x, y, pad) {
    var best = null;
    shapes.forEach(function (s) {
      var rank;
      var size;
      if (s.k === 'a') {
        if (lineDist(x, y, s.pts[0][0], s.pts[0][1], s.pts[1][0], s.pts[1][1]) > pad) { return; }
        rank = 0;
        size = 0;
      } else {
        var outline = s.o === 1 ? ovalPoints(s.pts, OVAL_STEPS) : s.pts;
        if (!inside(outline, x, y) && edgeDist(outline, x, y) > pad) { return; }
        rank = 1;
        size = area(outline);
      }
      if (!best || rank < best.rank || (rank === best.rank && size < best.size)) { best = { id: s.id, rank: rank, size: size }; }
    });
    return best ? best.id : null;
  }

  // The places of mark m on the screen of the map, on the world copy nearest to the center of the
  // view: [[x, y], ...] in px of the map container.
  function screenOf(map, m) {
    var places = placesOf(m);
    var shift = places.length ? turnsTo(places[0][0], map.getCenter().lng) : 0;
    return places.map(function (p) {
      var s = map.project([p[0] + shift, p[1]]);
      return [s.x, s.y];
    });
  }

  // On the globe (a phone can turn it on) a place on the far side has a screen point too: a mark
  // with such a place is not drawn.
  function hidden(map, m) {
    var tr = map.transform;
    if (!tr || typeof tr.isLocationOccluded !== 'function') { return false; }
    return placesOf(m).some(function (p) {
      try { return tr.isLocationOccluded({ lng: p[0], lat: p[1] }); } catch (e) { return false; }
    });
  }

  // The marks of the list on the screen: [{ id, k, o, pts }], pts in px of the map container.
  // A mark of another kind (a Kiwix page, a book) is not here.
  function shapesOf(map, list) {
    var out = [];
    list.forEach(function (m) {
      if (!Array.isArray(m.geo) || (m.k !== 'a' && m.k !== 'r')) { return; }
      if (hidden(map, m)) { return; }
      var pts = screenOf(map, m);
      if (pts.some(function (p) { return !isFinite(p[0]) || !isFinite(p[1]); })) { return; }
      out.push({ id: m.id, k: m.k, o: m.o, pts: pts });
    });
    return out;
  }

  function el(doc, name, attrs) {
    var e = doc.createElementNS(SVG_NS, name);
    Object.keys(attrs).forEach(function (k) { e.setAttribute(k, attrs[k]); });
    return e;
  }

  function pointsAttr(pts) {
    return pts.map(function (p) { return p[0].toFixed(1) + ',' + p[1].toFixed(1); }).join(' ');
  }

  // Draws the shapes in the layer (an <svg>), in place of what it showed.
  function render(layer, shapes) {
    var doc = layer.ownerDocument;
    while (layer.firstChild) { layer.removeChild(layer.firstChild); }
    shapes.forEach(function (s) {
      if (s.k === 'a') {
        var a = arrowShape(s.pts[0][0], s.pts[0][1], s.pts[1][0], s.pts[1][1], HEAD_LEN, HEAD_HALF);
        if (!a) { return; }
        var line = { x1: a.line[0], y1: a.line[1], x2: a.line[2], y2: a.line[3], 'stroke-linecap': 'round', fill: 'none' };
        layer.appendChild(el(doc, 'line', Object.assign({}, line, { stroke: EDGE, 'stroke-width': 8 })));
        layer.appendChild(el(doc, 'line', Object.assign({}, line, { stroke: YELLOW, 'stroke-width': 6 })));
        layer.appendChild(el(doc, 'polygon', { points: pointsAttr(a.head), fill: YELLOW, stroke: EDGE, 'stroke-width': 1,
          'stroke-linejoin': 'round' }));
        return;
      }
      var pts = pointsAttr(s.o === 1 ? ovalPoints(s.pts, OVAL_STEPS) : s.pts);
      layer.appendChild(el(doc, 'polygon', { points: pts, fill: 'none', stroke: EDGE, 'stroke-width': 6, 'stroke-linejoin': 'round' }));
      layer.appendChild(el(doc, 'polygon', { points: pts, fill: 'none', stroke: YELLOW, 'stroke-width': 4, 'stroke-linejoin': 'round' }));
    });
  }

  // The layer of the map: an <svg> over the canvas, made once for each map. It keeps the list that
  // it shows (the box list of the teacher or the student page) and draws it again on each move of
  // the map; it goes with the map container.
  function layerOf(map) {
    var box = map.getContainer();
    var layer = box.querySelector('svg.' + LAYER_CLASS);
    if (!layer) {
      layer = el(box.ownerDocument, 'svg', { class: LAYER_CLASS, 'aria-hidden': 'true' });
      // Over the canvas, under the controls of the map (z-index 2).
      layer.setAttribute('style', 'position: absolute; left: 0; top: 0; width: 100%; height: 100%; z-index: 1; ' +
        'pointer-events: none; overflow: hidden;');
      layer.k2goList = [];
      box.appendChild(layer);
    }
    // The maps page can make a new map in the same container: the layer follows the map of now.
    if (layer.k2goMap !== map) {
      layer.k2goMap = map;
      var again = function () { if (layer.k2goMap === map) { render(layer, shapesOf(map, layer.k2goList)); } };
      map.on('move', again);
      map.on('resize', again);
    }
    return layer;
  }

  // Shows the list on the map (from now on, also after each move). Gives { layer, drawn } as the
  // other mark files; drawn: the shapes now, in px of the layer.
  function draw(map, list) {
    var layer = layerOf(map);
    layer.k2goList = list || [];
    var shapes = shapesOf(map, layer.k2goList);
    render(layer, shapes);
    return { layer: layer, drawn: shapes };
  }

  // The marks of the box (with an id) under a point of the map container, now.
  function markAt(map, x, y, pad) {
    var layer = layerOf(map);
    var withId = layer.k2goList.filter(function (m) { return m.id !== undefined; });
    return hit(shapesOf(map, withId), x, y, pad);
  }

  function place(map, x, y) { return map.unproject([x, y]); }

  // The arrow from point a to point z (px of the map container), or null (a tap).
  function arrowMark(map, a, z) {
    var dx = z.x - a.x;
    var dy = z.y - a.y;
    if (dx * dx + dy * dy < MIN_PX * MIN_PX) { return null; }
    return { k: 'a', geo: geoOf([place(map, a.x, a.y), place(map, z.x, z.y)]) };
  }

  // The box for a rectangle that the teacher drew (px of the map container), or null (a tap).
  function boxMark(map, r) {
    if (r.right - r.left < MIN_PX || r.bottom - r.top < MIN_PX) { return null; }
    return { k: 'r', geo: geoOf([place(map, r.left, r.top), place(map, r.right, r.top),
      place(map, r.right, r.bottom), place(map, r.left, r.bottom)]) };
  }

  // Mark m moved by dx, dy screen px: each place goes with the finger. Keeps its kind and oval.
  function moveMark(map, m, dx, dy) {
    var out = { k: m.k, geo: geoOf(screenOf(map, m).map(function (s) { return place(map, s[0] + dx, s[1] + dy); })) };
    if (m.o === 1) { out.o = 1; }
    return out;
  }

  return {
    E6: E6,
    MIN_PX: MIN_PX,
    turnsTo: turnsTo,
    geoOf: geoOf,
    placesOf: placesOf,
    arrowShape: arrowShape,
    ovalPoints: ovalPoints,
    lineDist: lineDist,
    inside: inside,
    hit: hit,
    shapesOf: shapesOf,
    hidden: hidden,
    draw: draw,
    markAt: markAt,
    arrowMark: arrowMark,
    boxMark: boxMark,
    moveMark: moveMark
  };
});
