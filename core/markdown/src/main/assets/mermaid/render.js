// Locus: renders one Mermaid diagram at a time into a PNG data URL. The app calls locusRender(...)
// through evaluateJavascript and polls locusTake(id); there is no JavaScript bridge into the app.
"use strict";
(function () {
  var current = null;

  function base(dark) {
    return {
      startOnLoad: false,
      securityLevel: "strict",
      theme: dark ? "dark" : "default",
      // SVG <text> labels instead of HTML in <foreignObject>: an <img> of an SVG with
      // foreignObject taints the canvas, and the PNG couldn't be read back.
      htmlLabels: false,
      fontFamily: "sans-serif",
      suppressErrorRendering: true,
      deterministicIds: true,
      maxTextSize: 50000,
      maxEdges: 800
    };
  }

  function finish(id, value) {
    if (current && current.id === id) current.result = JSON.stringify(value);
  }

  function svgSize(svg) {
    var vb = svg.viewBox && svg.viewBox.baseVal;
    var w = vb && vb.width ? vb.width : 0;
    var h = vb && vb.height ? vb.height : 0;
    if (!w || !h) {
      var box = svg.getBBox();
      w = box.width;
      h = box.height;
    }
    return { w: Math.ceil(w), h: Math.ceil(h) };
  }

  window.locusRender = function (id, source, dark, scale, maxSide, maxArea) {
    current = { id: id, result: null };
    var host = document.getElementById("host");
    host.innerHTML = "";
    Promise.resolve()
      .then(function () {
        mermaid.initialize(base(dark));
        return mermaid.render("locus-d" + id, source);
      })
      .then(function (out) {
        host.innerHTML = out.svg;
        var svg = host.querySelector("svg");
        if (!svg) throw new Error("Mermaid returned no SVG");
        var size = svgSize(svg);
        if (size.w < 1 || size.h < 1) throw new Error("Empty diagram");
        svg.setAttribute("width", String(size.w));
        svg.setAttribute("height", String(size.h));
        svg.style.maxWidth = "none";
        var s = scale;
        s = Math.min(s, maxSide / size.w, maxSide / size.h, Math.sqrt(maxArea / (size.w * size.h)));
        var xml = new XMLSerializer().serializeToString(svg);
        host.innerHTML = "";
        return new Promise(function (resolve, reject) {
          var img = new Image();
          img.onload = function () {
            try {
              var canvas = document.createElement("canvas");
              canvas.width = Math.max(1, Math.round(size.w * s));
              canvas.height = Math.max(1, Math.round(size.h * s));
              var ctx = canvas.getContext("2d");
              ctx.scale(s, s);
              ctx.drawImage(img, 0, 0, size.w, size.h);
              var png = canvas.toDataURL("image/png");
              resolve({ png: png.substring(png.indexOf(",") + 1), width: size.w, height: size.h });
            } catch (e) {
              reject(e);
            }
          };
          img.onerror = function () { reject(new Error("The diagram image did not load")); };
          img.src = "data:image/svg+xml;charset=utf-8," + encodeURIComponent(xml);
        });
      })
      .then(function (value) { finish(id, value); })
      .catch(function (e) {
        host.innerHTML = "";
        finish(id, { error: String((e && e.message) || e).substring(0, 500) });
      });
    return "started";
  };

  window.locusTake = function (id) {
    if (!current || current.id !== id || current.result === null) return "";
    var result = current.result;
    current = null;
    return result;
  };

  window.locusReady = typeof mermaid !== "undefined";
})();
