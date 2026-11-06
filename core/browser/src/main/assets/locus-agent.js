// Injected into pages the agent drives. Describes the page as structured data (formatted into text
// by Snapshot.kt) and performs actions on elements by ref. Idempotent: running it again on the same
// page is a no-op.
//
// Refs live in a WeakMap (element -> ref) and a Map of WeakRefs (ref -> element), so the page's DOM is
// never modified and a ref stays the same for as long as its element exists.
//
// Trust: AgentBrowser registers this file with addDocumentStartJavaScript, with the SECRET placeholder below
// replaced by a random per-tab secret, so it runs before the page's own scripts. It also evaluates the
// file again before each call, with the secret left empty, as a fallback for WebViews that can't run
// it at document start. Every answer to Kotlin goes through L.seal, which carries the secret. Only the
// copy that ran at document start knows it, so an answer without it (a page that defined its own
// __locus first, or a helper that only got in after the page's scripts) is treated as unverified and
// the sensitive-action gate asks the user. See docs/design/browser.md, "Trusting the page helper".
(function () {
  if (window.__locus && window.__locus.v === 2) return;
  var SECRET = '__LOCUS_SECRET__';
  // Natives captured before page scripts can replace them (when this runs at document start).
  var nativeStringify = JSON.stringify, nativeKeys = Object.keys, isArray = Array.isArray;
  // For saved logins (see "Saved logins" below): the form's real action even when a field is named
  // "action", and the page's prompt(), the one channel that reaches Kotlin synchronously.
  var formActionGet = (Object.getOwnPropertyDescriptor(HTMLFormElement.prototype, 'action') || {}).get;
  var nativePrompt = window.prompt;
  // Fields Locus filled from a saved login. Private to this closure, so the page can't clear it.
  var filled = new WeakSet();
  var L = window.__locus = {
    v: 2, next: 1, lastMutation: Date.now(), pending: 0, snapshots: 0,
    ids: new WeakMap(), refs: new Map(), jobs: {},
    // Element nodes added to the page (portals, menus) with when; what an element looked like when it got
    // its ref (to find it again after a re-render); panels seen as open popups; the last element acted on.
    added: [], fp: new Map(), panels: [], lastTarget: null, lastActionAt: 0, sel: null, hovered: null,
    // This document's identity: refs and facts belong to it, and an action checks it is still the same.
    doc: Math.random().toString(36).slice(2) + Date.now().toString(36)
  };

  // JSON without toJSON or other hooks a page could add to Object.prototype after this ran.
  function ser(v) {
    if (v === null || v === undefined) return 'null';
    var t = typeof v;
    if (t === 'string') return nativeStringify(v);
    if (t === 'number') return isFinite(v) ? String(v) : 'null';
    if (t === 'boolean') return v ? 'true' : 'false';
    if (t !== 'object') return 'null';
    var parts = [], i;
    if (isArray(v)) {
      for (i = 0; i < v.length; i++) parts.push(ser(v[i]));
      return '[' + parts.join(',') + ']';
    }
    var keys = nativeKeys(v);
    for (i = 0; i < keys.length; i++) {
      var x = v[keys[i]];
      if (x === undefined || typeof x === 'function') continue;
      parts.push(nativeStringify(keys[i]) + ':' + ser(x));
    }
    return '{' + parts.join(',') + '}';
  }

  // The envelope Kotlin reads (PageReply): the secret, this document's id, and the result.
  L.seal = function (r) { return '{"k":' + nativeStringify(SECRET) + ',"d":' + nativeStringify(L.doc) + ',"r":' + ser(r) + '}'; };

  try {
    new MutationObserver(function (records) {
      var now = L.lastMutation = Date.now();
      for (var i = 0; i < records.length; i++) {
        var nodes = records[i].addedNodes;
        for (var j = 0; j < nodes.length && j < 50; j++) if (nodes[j].nodeType === 1) L.added.push({ w: new WeakRef(nodes[j]), t: now });
      }
      if (L.added.length > 300) L.added.splice(0, L.added.length - 200);
    }).observe(document.documentElement || document, { subtree: true, childList: true, attributes: true, characterData: true });
  } catch (e) {}
  try {
    var origFetch = window.fetch;
    if (origFetch) window.fetch = function () {
      L.pending++;
      return origFetch.apply(this, arguments).finally(function () { L.pending--; });
    };
    var origSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.send = function () {
      L.pending++;
      this.addEventListener('loadend', function () { L.pending--; });
      return origSend.apply(this, arguments);
    };
  } catch (e) {}

  // A short CSS transition or animation still running (a menu sliding open). Endless ones (spinners) and
  // long ones (carousels) don't count, so they can't hold an action up.
  function animating() {
    try {
      var list = document.getAnimations ? document.getAnimations() : [];
      for (var i = 0; i < list.length && i < 60; i++) {
        if (list[i].playState !== 'running' || !list[i].effect || !list[i].effect.getComputedTiming) continue;
        var end = list[i].effect.getComputedTiming().endTime;
        if (isFinite(end) && end <= 2000) return true;
      }
    } catch (e) {}
    return false;
  }

  // Text fields a person typed into: trusted input events come from the keyboard, never from a script.
  // Their values stay out of snapshots until the agent types into the field itself (docs/design/browser.md,
  // "Take over and hand back"; Operator doesn't collect what the user enters while they have control).
  // execCommand's input events are trusted too, so the agent's own typing is excluded with agentTyping.
  var entered = new WeakSet(), agentTyping = false;
  try {
    window.addEventListener('input', function (e) {
      if (!e.isTrusted || agentTyping) return;
      var t = e.composedPath ? e.composedPath()[0] : e.target;
      if (t && t.nodeType === 1) entered.add(t);
    }, true);
  } catch (e) {}
  L.entered = function (el) { return entered.has(el); };
  // A same-origin frame has its own copy of this helper, which saw the typing there.
  function typedByUser(el) {
    if (entered.has(el)) return true;
    try {
      var w = el.ownerDocument.defaultView;
      return !!(w && w !== window && w.__locus && w.__locus.entered && w.__locus.entered(el));
    } catch (e) { return false; }
  }

  L.settled = function () {
    return document.readyState === 'complete' && Date.now() - L.lastMutation > 500 && L.pending <= 0 && !animating();
  };

  var INTERACTIVE = 'a[href],button,input:not([type=hidden]),textarea,select,summary,[contenteditable=""],[contenteditable=true],' +
    '[role=button],[role=link],[role=checkbox],[role=radio],[role=tab],[role=menuitem],[role=menuitemcheckbox],[role=menuitemradio],' +
    '[role=option],[role=switch],[role=combobox],[role=textbox],[role=searchbox],[role=slider],[role=spinbutton],[role=treeitem],' +
    '[onclick],[tabindex]:not([tabindex="-1"])';
  var CONTAINS_ACTION = 'a[href],button,[role=button],[role=link]';
  // Choices inside an open popup. Grid cells count there (a date picker's days), not on the page.
  var OPTION_LIKE = '[role=option],[role=menuitem],[role=menuitemradio],[role=menuitemcheckbox],[role=treeitem],[role=gridcell]';
  var POPUP_ROLES = '[role=listbox],[role=menu],[role=tree],[role=grid]';
  var MAX_POPUP_ITEMS = 50;
  var SENSITIVE_AUTOCOMPLETE = /cc-|one-time-code|current-password|new-password/;
  var PAYMENT = /cc-|card.?number|cardnum|cvv|cvc|security.?code|expir/i;
  var SEARCHY = /search|query|^q$|^s$|keyword|find|suche|recherche|buscar/i;
  var CAPTCHA = /recaptcha|hcaptcha|challenges\.cloudflare\.com|turnstile|arkoselabs|funcaptcha|captcha/i;

  // ---- Elements -----------------------------------------------------------------------------------

  function refOf(el) { return L.ids.get(el) || null; }

  function assign(el) {
    var r = L.ids.get(el);
    if (r) return { ref: r, isNew: false };
    r = 'e' + (L.next++);
    L.ids.set(el, r);
    L.refs.set(r, new WeakRef(el));
    return { ref: r, isNew: true };
  }

  // The element behind a ref. When it's gone because the page re-rendered it (menus and lists built by
  // frameworks often replace their nodes), the one element that now has the same role and name takes
  // its place, as a Playwright locator would find it again; with none or several, the ref is gone.
  L.find = function (ref) {
    var w = L.refs.get(ref), el = w && w.deref();
    return el && el.isConnected ? el : rebind(ref);
  };

  function rebind(ref) {
    var fp = L.fp.get(ref);
    if (!fp || !fp.name) return null;
    var found = [];
    walk(document.documentElement, function (x) {
      if (!(x.matches(INTERACTIVE) || x.matches(OPTION_LIKE) || x.__locusClickable)) return;
      if (role(x) === fp.role && name(x) === fp.name && visible(x)) { found.push(x); if (found.length > 1) return false; }
    });
    if (found.length !== 1) return null;
    var el = found[0];
    if (!L.ids.get(el)) L.ids.set(el, ref);
    L.refs.set(ref, new WeakRef(el));
    return el;
  }

  function remember(ref, o) {
    L.fp.set(ref, { role: o.role, name: o.name });
    if (L.fp.size > 4000) { var n = 0; L.fp.forEach(function (v, k) { if (n++ < 1000) L.fp.delete(k); }); }
  }

  function prune() {
    L.refs.forEach(function (w, r) { var el = w.deref(); if (!el || !el.isConnected) L.refs.delete(r); });
  }

  function styleOf(el) { return el.ownerDocument.defaultView.getComputedStyle(el); }

  // A visually hidden checkbox or radio (styled through its label) is reached through the label.
  function hitTarget(el) {
    var tag = el.tagName.toLowerCase();
    if (tag === 'input' && (el.type === 'checkbox' || el.type === 'radio' || el.type === 'file') && !shown(el) && el.labels && el.labels.length) {
      for (var i = 0; i < el.labels.length; i++) if (shown(el.labels[i])) return el.labels[i];
    }
    return el;
  }

  function shown(el) {
    if (el.checkVisibility && !el.checkVisibility({ opacityProperty: true, visibilityProperty: true, contentVisibilityAuto: true })) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) return false;
    if (!el.checkVisibility) {
      var s = styleOf(el);
      if (s.visibility === 'hidden' || s.display === 'none' || parseFloat(s.opacity || '1') < 0.05) return false;
    }
    return !el.closest('[aria-hidden=true],[inert]');
  }

  function visible(el) { return shown(hitTarget(el)); }

  // Offset of an element's frame inside the top-level viewport (same-origin iframes).
  function frameOffset(el) {
    var x = 0, y = 0, w = el.ownerDocument.defaultView;
    while (w && w !== window) {
      var f = w.frameElement;
      if (!f) break;
      var r = f.getBoundingClientRect();
      x += r.left + f.clientLeft; y += r.top + f.clientTop;
      w = f.ownerDocument.defaultView;
    }
    return { x: x, y: y };
  }

  function rectOf(el) {
    var r = hitTarget(el).getBoundingClientRect(), o = frameOffset(el);
    return { left: r.left + o.x, top: r.top + o.y, width: r.width, height: r.height };
  }

  function nearViewport(el, all) {
    if (all) return true;
    var r = rectOf(el), h = window.innerHeight;
    return r.top + r.height > -h * 0.5 && r.top < h * 1.5;
  }

  function role(el) {
    var explicit = el.getAttribute('role');
    if (explicit) return explicit.split(' ')[0];
    var tag = el.tagName.toLowerCase();
    if (tag === 'a') return 'link';
    if (tag === 'select') return el.multiple ? 'listbox' : 'combobox';
    if (tag === 'textarea') return 'textbox';
    if (tag === 'summary' || tag === 'button') return 'button';
    if (tag === 'input') {
      var t = (el.type || 'text').toLowerCase();
      if (t === 'checkbox' || t === 'radio') return t;
      if (t === 'submit' || t === 'button' || t === 'reset' || t === 'image') return 'button';
      if (t === 'search') return 'searchbox';
      if (t === 'range') return 'slider';
      if (t === 'number') return 'spinbutton';
      if (t === 'file') return 'file-upload';
      return 'textbox';
    }
    if (el.isContentEditable) return 'textbox';
    if (el.hasAttribute('onclick') || el.__locusClickable) return 'clickable';
    return tag;
  }

  function clean(s, n) {
    s = (s || '').replace(/\s+/g, ' ').trim();
    return s.length > n ? s.slice(0, n - 1) + '…' : s;
  }

  // The element with id `id` in el's document or shadow root, or the top document.
  function byId(el, id) {
    var root = el.getRootNode ? el.getRootNode() : document;
    return (root.getElementById ? root.getElementById(id) : null) || el.ownerDocument.getElementById(id);
  }

  function idsOf(el, attr) { return (el.getAttribute(attr) || '').split(/\s+/).filter(Boolean); }

  // Native <select> and <datalist> options as text: "Group: Option", disabled ones marked.
  function optionText(x) {
    var t = clean(x.label || x.text || x.value, 50);
    var g = x.parentElement && x.parentElement.tagName === 'OPTGROUP' ? x.parentElement : null;
    if (g && g.label) t = clean(g.label, 30) + ': ' + t;
    return t + (x.disabled || (g && g.disabled) ? ' (disabled)' : '');
  }

  function optionTexts(options, n) {
    var out = [];
    for (var i = 0; i < options.length && out.length < n; i++) out.push(optionText(options[i]));
    return out;
  }

  // Lower case, single spaces, no accents: how option text is compared with what the model asked for.
  function norm(s) {
    s = String(s == null ? '' : s);
    try { s = s.normalize('NFD').replace(/[̀-ͯ]/g, ''); } catch (e) {}
    return s.replace(/\s+/g, ' ').trim().toLowerCase();
  }

  function name(el) {
    var label = el.getAttribute('aria-label');
    if (label && label.trim()) return clean(label, 80);
    var by = el.getAttribute('aria-labelledby');
    if (by) {
      var root = el.getRootNode();
      var t = by.split(/\s+/).map(function (id) { var x = root.getElementById ? root.getElementById(id) : null; return x ? x.innerText : ''; }).join(' ');
      if (t.trim()) return clean(t, 80);
    }
    var tag = el.tagName.toLowerCase();
    if ((tag === 'input' || tag === 'textarea' || tag === 'select') && el.labels && el.labels.length) {
      var lt = clean(el.labels[0].innerText, 80);
      if (lt) return lt;
    }
    if (tag === 'input' && (el.type === 'submit' || el.type === 'button' || el.type === 'reset') && el.value) return clean(el.value, 80);
    if (tag !== 'input' && tag !== 'textarea' && tag !== 'select') {
      var text = el.innerText;
      if (text && text.trim()) return clean(text, 80);
    }
    var img = el.querySelector && el.querySelector('img[alt]');
    return clean(el.getAttribute('placeholder') || el.getAttribute('title') || el.getAttribute('alt') ||
      (img ? img.alt : '') || el.getAttribute('name') || '', 80);
  }

  function sensitive(el) {
    if (el.tagName.toLowerCase() !== 'input') return false;
    var ac = el.getAttribute('autocomplete') || '';
    return el.type === 'password' || SENSITIVE_AUTOCOMPLETE.test(ac) || PAYMENT.test((el.name || '') + ' ' + (el.id || '') + ' ' + ac);
  }

  function describe(el, ref, isNew) {
    var tag = el.tagName.toLowerCase(), r = role(el);
    var o = { ref: ref, role: r, name: name(el) };
    if (isNew) o['new'] = true;
    if (tag === 'input' || tag === 'textarea') {
      var t = (el.type || 'text').toLowerCase();
      if (tag === 'input' && ['text', 'search', 'checkbox', 'radio', 'submit', 'button', 'file', 'range', 'number'].indexOf(t) < 0) o.type = t;
      // A field filled from a saved login never shows its value, whatever kind of field it is.
      if (filled.has(el) && el.value) { o.filled = true; }
      else if (sensitive(el)) { o.sensitive = true; o.hasValue = !!el.value; }
      else if (t === 'file') { if (el.files && el.files.length) o.value = Array.prototype.map.call(el.files, function (f) { return f.name; }).join(', '); }
      else if (r !== 'button' && t !== 'checkbox' && t !== 'radio' && typedByUser(el)) { o.entered = true; o.hasValue = !!el.value; }
      else if (el.value && r !== 'button' && t !== 'checkbox' && t !== 'radio') o.value = clean(el.value, 60);
      if (el.checked) o.checked = true;
    } else if (el.isContentEditable && el.innerText.trim()) {
      if (typedByUser(el)) { o.entered = true; o.hasValue = true; } else o.value = clean(el.innerText, 60);
      o.name = clean(el.getAttribute('aria-label') || el.getAttribute('placeholder') || el.getAttribute('aria-placeholder') || '', 80);
    }
    if (el.getAttribute('aria-checked') === 'true' || el.getAttribute('aria-selected') === 'true' || el.getAttribute('aria-pressed') === 'true') o.checked = true;
    if (tag === 'select') {
      var chosen = Array.prototype.filter.call(el.options, function (x) { return x.selected; });
      if (chosen.length) o.selected = chosen.slice(0, 5).map(function (x) { return clean(x.text, 40); }).join(' | ') + (chosen.length > 5 ? ' | …' : '');
      if (el.multiple) o.multiple = true;
      o.options = optionTexts(el.options, 8);
      o.optionCount = el.options.length;
    } else if (tag === 'input' && el.list && el.list.options) {
      o.options = optionTexts(el.list.options, 8);
      o.optionCount = el.list.options.length;
    }
    if (el.disabled || el.getAttribute('aria-disabled') === 'true') o.disabled = true;
    if (el.getAttribute('aria-expanded')) o.expanded = el.getAttribute('aria-expanded');
    var popup = el.getAttribute('aria-haspopup');
    if (popup && popup !== 'false') o.haspopup = popup === 'true' ? 'menu' : popup;
    var activeId = el.getAttribute('aria-activedescendant');
    if (activeId) {
      var act = byId(el, activeId);
      if (act && shown(act)) o.active = assign(act).ref;
    }
    if (tag === 'a') {
      var href = el.getAttribute('href') || '';
      if (href && href.indexOf('javascript:') !== 0 && href !== '#') o.href = clean(href, 80);
    }
    return o;
  }

  // An element styled as clickable (cursor: pointer) that isn't a control: common in apps built
  // from divs. Only the outermost such element counts, so one card isn't listed five times.
  function looksClickable(el) {
    var tag = el.tagName;
    if (tag !== 'DIV' && tag !== 'SPAN' && tag !== 'LI' && tag !== 'IMG' && tag !== 'svg' && tag !== 'TD') return false;
    if (styleOf(el).cursor !== 'pointer') return false;
    var p = el.parentElement;
    if (p && styleOf(p).cursor === 'pointer') return false;
    if (!el.innerText && tag !== 'IMG' && tag !== 'svg' && !el.getAttribute('aria-label')) return false;
    return true;
  }

  function modalRoot() {
    var c = document.querySelectorAll('dialog[open],[role=dialog],[role=alertdialog],[aria-modal=true]');
    for (var i = c.length - 1; i >= 0; i--) {
      var d = c[i];
      var modal = (d.tagName === 'DIALOG' && d.matches(':modal')) || d.getAttribute('aria-modal') === 'true' || d.getAttribute('role') === 'alertdialog';
      if (modal && shown(d)) return d;
    }
    return null;
  }

  // Walks the page in document order, entering open shadow roots and same-origin iframes.
  function walk(root, visit, frames) {
    var kids = root.children || [];
    for (var i = 0; i < kids.length; i++) {
      var el = kids[i];
      if (!el.shadowRoot && el.getClientRects().length === 0 && styleOf(el).display === 'none') continue; // hides the whole subtree
      var v = visit(el);
      if (v === false) return false;
      if (v === 'skip') continue; // the visitor handled this subtree itself
      if (el.shadowRoot && walk(el.shadowRoot, visit, frames) === false) return false;
      if (el.tagName === 'IFRAME' || el.tagName === 'FRAME') {
        var doc = null;
        try { doc = el.contentDocument; } catch (e) {}
        if (doc && doc.documentElement) { if (walk(doc.documentElement, visit, frames) === false) return false; }
        else if (frames && shown(el)) {
          var src = el.getAttribute('src') || '';
          try { src = new URL(src, location.href).host; } catch (e) {}
          if (src) frames.push(src);
        }
        continue;
      }
      if (walk(el, visit, frames) === false) return false;
    }
  }

  // ---- Popups -------------------------------------------------------------------------------------
  // Menus, listboxes and suggestion lists often live far from the control that opened them: appended to
  // <body> (a "portal") with position: fixed and a high z-index, outside an open dialog, or after the
  // page's 250th element. They are found wherever they are and listed under their control.

  // Whether b is a or inside it, across shadow roots and same-origin frames.
  function composedContains(a, b) {
    for (var x = b; x; ) {
      if (x === a) return true;
      var up = x.parentNode || x.host || null;
      if (!up && x.nodeType === 9) { try { up = x.defaultView && x.defaultView.frameElement; } catch (e) { up = null; } }
      x = up;
    }
    return false;
  }

  // Floating: it or one of its three nearest ancestors is position fixed or absolute.
  function positioned(el) {
    for (var i = 0, x = el; x && x.nodeType === 1 && i < 4; i++, x = x.parentElement) {
      var p = styleOf(x).position;
      if (p === 'fixed' || p === 'absolute') return true;
    }
    return false;
  }

  function popupRole(el) {
    var r = (el.getAttribute('role') || '').split(' ')[0];
    return /^(listbox|menu|tree|grid|dialog)$/.test(r) ? r : '';
  }

  function opensPopup(t) {
    var h = t.getAttribute('aria-haspopup');
    return t.getAttribute('role') === 'combobox' || (!!h && h !== 'false');
  }

  // id -> the control that names it in aria-controls or aria-owns (APG combobox pattern).
  function triggerMap() {
    var map = {}, list = document.querySelectorAll('[aria-expanded=true],[role=combobox],[aria-haspopup],[aria-activedescendant]');
    for (var i = 0; i < list.length; i++) {
      var t = list[i];
      idsOf(t, 'aria-controls').concat(idsOf(t, 'aria-owns')).forEach(function (id) { if (!map[id]) map[id] = t; });
    }
    return map;
  }

  // The control that opened `el`: the one naming it (or its wrapper or inner list), else by timing, the
  // control the agent last acted on or the focused field (`guess`).
  function ownerOf(el, map) {
    for (var i = 0, x = el; x && x.nodeType === 1 && i < 4; i++, x = x.parentElement) if (x.id && map[x.id]) return { el: map[x.id], guess: false };
    var inner = el.querySelectorAll ? el.querySelectorAll('[id]') : [];
    for (var j = 0; j < inner.length && j < 200; j++) if (map[inner[j].id] && !composedContains(el, map[inner[j].id])) return { el: map[inner[j].id], guess: false };
    var last = L.lastTarget && L.lastTarget.deref();
    if (last && last.isConnected && Date.now() - L.lastActionAt < 60000 && !composedContains(el, last)) return { el: last, guess: true };
    var f = deepActive();
    if (f && f !== document.body && !composedContains(el, f) && (isEditable(f) || f.getAttribute('role') === 'combobox')) return { el: f, guess: true };
    return { el: null, guess: false };
  }

  // A floating panel with something to choose in it, at or just inside an element the page added.
  function floatingPanel(n) {
    var cands = [n];
    for (var i = 0; n.children && i < n.children.length && i < 5; i++) cands.push(n.children[i]);
    for (var k = 0; k < cands.length; k++) {
      var c = cands[k], pos = styleOf(c).position;
      if (pos !== 'fixed' && pos !== 'absolute') continue;
      if (c.matches('dialog,[aria-modal=true],[role=alertdialog]') || !shown(c) || !c.querySelector(INTERACTIVE + ',' + OPTION_LIKE)) continue;
      var r = c.getBoundingClientRect();
      if (r.width * r.height > window.innerWidth * window.innerHeight * 0.8) continue; // a full-screen layer is a dialog, not a menu
      return c;
    }
    return null;
  }

  // The open popups on the page: lists a control names (aria-controls/aria-owns), floating listboxes,
  // menus, trees and grids, open popovers, the floating menu right after an expanded toggle that names
  // none (Bootstrap), and floating panels that appeared after the agent's last action.
  function findPopups() {
    var map = triggerMap(), out = [];
    function add(el, trigger) {
      if (!el || el.nodeType !== 1 || !el.isConnected || !shown(el) || el.getAttribute('aria-modal') === 'true') return null;
      for (var i = 0; i < out.length; i++) {
        if (composedContains(out[i].el, el)) return out[i];
        if (composedContains(el, out[i].el)) { trigger = trigger || out[i].trigger; out.splice(i, 1); i--; }
      }
      var o = trigger ? { el: trigger, guess: false } : ownerOf(el, map);
      var p = { el: el, trigger: o.el, guess: o.guess };
      out.push(p);
      return p;
    }
    Object.keys(map).forEach(function (id) {
      var t = map[id], el = byId(t, id);
      if (el && el !== t && !composedContains(el, t) && (popupRole(el) || opensPopup(t))) add(el, t);
    });
    var roles = document.querySelectorAll(POPUP_ROLES);
    for (var i = 0; i < roles.length; i++) if (positioned(roles[i])) add(roles[i], null);
    try {
      var pops = document.querySelectorAll('[popover]');
      for (var j = 0; j < pops.length; j++) if (pops[j].matches(':popover-open')) add(pops[j], null);
    } catch (e) {}
    var toggles = document.querySelectorAll('[aria-expanded=true]');
    for (var k = 0; k < toggles.length; k++) {
      var t = toggles[k], sib = t.nextElementSibling;
      if (t.getAttribute('aria-controls') || t.getAttribute('aria-owns') || !sib) continue;
      var pos = styleOf(sib).position;
      if ((pos === 'absolute' || pos === 'fixed') && sib.querySelector(INTERACTIVE)) add(sib, t);
    }
    L.panels = L.panels.filter(function (w) { var x = w.deref(); return x && x.isConnected; });
    L.panels.forEach(function (w) { add(w.deref(), null); });
    if (L.lastActionAt) {
      for (var a = L.added.length - 1; a >= 0 && L.added[a].t >= L.lastActionAt - 50; a--) {
        var n = L.added[a].w.deref();
        if (!n || !n.isConnected || L.added[a].t > L.lastActionAt + 30000) continue;
        var panel = floatingPanel(n);
        if (panel && add(panel, null) && !L.panels.some(function (w) { return w.deref() === panel; })) L.panels.push(new WeakRef(panel));
      }
    }
    return out;
  }

  function scrollerOf(el) {
    var isScroller = function (x) {
      if (x.scrollHeight <= x.clientHeight + 2) return false;
      var o = styleOf(x).overflowY;
      return o === 'auto' || o === 'scroll' || o === 'overlay';
    };
    for (var i = 0, x = el; x && x.nodeType === 1 && i < 4; i++, x = x.parentElement) if (isScroller(x)) return x;
    var inner = el.querySelectorAll ? el.querySelectorAll('*') : [];
    for (var j = 0; j < inner.length && j < 300; j++) if (isScroller(inner[j])) return inner[j];
    return null;
  }

  function popupName(p) {
    var el = p.el, label = el.getAttribute('aria-label');
    if (label && label.trim()) return clean(label, 60);
    var by = idsOf(el, 'aria-labelledby').map(function (id) { var x = byId(el, id); return x ? x.innerText : ''; }).join(' ');
    if (by.trim()) return clean(by, 60);
    return p.trigger ? clean(name(p.trigger), 60) : '';
  }

  // A popup as a header record followed by its elements (at most MAX_POPUP_ITEMS).
  function popupBlock(p, first) {
    var items = [], total = 0, setsize = 0;
    walk(p.el, function (el) {
      var interactive = el.matches(INTERACTIVE) || (el.matches(OPTION_LIKE) && !el.querySelector(INTERACTIVE));
      if (!interactive && looksClickable(el) && !el.parentElement.closest(INTERACTIVE)) { el.__locusClickable = true; interactive = true; }
      if (!interactive) return;
      if (el.parentElement && el.parentElement.closest(CONTAINS_ACTION) && !el.matches('input,select,textarea')) return;
      if (!visible(el)) return;
      total++;
      var size = parseInt(el.getAttribute('aria-setsize'), 10);
      if (size > setsize) setsize = size;
      if (items.length >= MAX_POPUP_ITEMS) return;
      var a = assign(el), o = describe(el, a.ref, a.isNew && !first);
      o['in'] = p.id;
      remember(a.ref, o);
      items.push(o);
    });
    var head = { popup: p.id, role: popupRole(p.el) || 'panel', name: popupName(p), count: Math.max(total, setsize), listed: items.length };
    if (p.trigger) { head.from = assign(p.trigger).ref; if (p.guess) head.guess = true; }
    if (scrollerOf(p.el)) head.scrolls = true;
    if (!items.length) head.text = clean(p.el.innerText, 120);
    return [head].concat(items);
  }

  L.snapshot = function (all) {
    prune();
    var first = L.snapshots++ === 0;
    var items = [], count = 0, max = all ? 600 : 250, truncated = false, frames = [];
    var login = false, otp = false, captcha = CAPTCHA.test(document.title) || /just a moment|verify you are human|are you a robot/i.test(document.title);
    var modal = modalRoot();
    var pops = findPopups(), popOf = new Map();
    pops.forEach(function (p, i) { p.id = 'p' + (i + 1); popOf.set(p.el, p); });
    walk(document.documentElement, function (el) {
      var pop = popOf.get(el);
      // Floating lists inside shadow roots and frames, which findPopups' selectors can't reach.
      if (!pop && el.getRootNode() !== document && el.hasAttribute('role') && el.matches(POPUP_ROLES) && positioned(el) && shown(el)) {
        var owner = ownerOf(el, {});
        pop = { el: el, trigger: owner.el, guess: owner.guess, id: 'p' + (pops.length + 1) };
        pops.push(pop);
        popOf.set(el, pop);
      }
      if (pop) return 'skip';
      // With a modal dialog open only its elements are listed (popups above are listed wherever they are).
      if (modal && !composedContains(modal, el)) return composedContains(el, modal) ? undefined : 'skip';
      var tag = el.tagName.toLowerCase();
      if (tag === 'input' && el.type === 'password' && shown(el)) login = true;
      if (tag === 'input' && !otp && loginKind(el) === 'code' && shown(el)) otp = true;
      if ((tag === 'iframe' && CAPTCHA.test(el.getAttribute('src') || '')) || el.matches('.g-recaptcha,.h-captcha,.cf-turnstile,#challenge-form')) captcha = true;
      if (count >= max) { truncated = true; return; }
      if (/^h[1-4]$/.test(tag)) {
        if (shown(el) && nearViewport(el, all)) {
          var ht = clean(el.innerText, 100);
          if (ht) items.push({ h: ht, lvl: +tag[1] });
        }
        return;
      }
      var interactive = el.matches(INTERACTIVE);
      if (!interactive && looksClickable(el) && !el.parentElement.closest(INTERACTIVE)) { el.__locusClickable = true; interactive = true; }
      if (!interactive) return;
      if (el.parentElement && el.parentElement.closest(CONTAINS_ACTION) && !el.matches('input,select,textarea')) return;
      if (!visible(el) || !nearViewport(el, all)) return;
      var a = assign(el), o = describe(el, a.ref, a.isNew && !first);
      remember(a.ref, o);
      items.push(o);
      count++;
    }, frames);
    // Each popup goes right after the control that opened it, or first when that control isn't listed.
    var listed = {}, under = {}, loose = [], out = [];
    items.forEach(function (it) { if (it.ref) listed[it.ref] = true; });
    pops.forEach(function (p) {
      var block = popupBlock(p, first), from = block[0].from;
      if (from && listed[from]) (under[from] = under[from] || []).push(block); else loose.push(block);
    });
    loose.forEach(function (b) { out.push.apply(out, b); });
    items.forEach(function (it) {
      out.push(it);
      if (it.ref && under[it.ref]) under[it.ref].forEach(function (b) { out.push.apply(out, b); });
    });
    var scrollable = document.documentElement.scrollHeight - window.innerHeight;
    return {
      title: document.title, url: location.href, full: !!all,
      scroll: scrollable > 4 ? Math.round(100 * window.scrollY / scrollable) : null,
      modal: modal ? clean(modal.getAttribute('aria-label') || modal.innerText, 120) : null,
      login: login, otp: otp, captcha: captcha, frames: frames, truncated: truncated, items: out
    };
  };

  // Passages of visible page text containing `query`, for snapshot's find.
  L.findText = function (query, max) {
    var q = String(query).toLowerCase(), out = [];
    var text = (document.body && document.body.innerText) || '';
    var lower = text.toLowerCase(), i = lower.indexOf(q);
    while (i >= 0 && out.length < (max || 10)) {
      out.push(text.slice(Math.max(0, i - 80), i + q.length + 80).replace(/\s+/g, ' ').trim());
      i = lower.indexOf(q, i + q.length + 80);
    }
    return out;
  };

  // ---- Facts for approvals ------------------------------------------------------------------------

  function formOf(el) { return el.form || (el.closest && el.closest('form')); }

  function fieldsOf(form) {
    return Array.prototype.filter.call(form.querySelectorAll('input,textarea,select'), function (f) {
      return f.type !== 'hidden' && f.type !== 'submit' && f.type !== 'button' && f.type !== 'reset' && f.type !== 'image';
    });
  }

  function isSearchField(f) {
    if (!f) return false;
    if (f.type === 'search' || f.getAttribute('role') === 'searchbox' || f.closest('[role=search]')) return true;
    return SEARCHY.test([f.name, f.id, f.getAttribute('aria-label'), f.getAttribute('placeholder'), f.title].join(' '));
  }

  function formFacts(form, field) {
    if (!form) return { inForm: false, searchLike: isSearchField(field), hasPassword: false, hasPayment: false };
    var fields = fieldsOf(form);
    var texty = fields.filter(function (f) { return f.tagName !== 'SELECT' && f.type !== 'checkbox' && f.type !== 'radio'; });
    var hasPassword = fields.some(function (f) { return f.type === 'password'; });
    var hasPayment = fields.some(function (f) { return PAYMENT.test((f.name || '') + ' ' + (f.id || '') + ' ' + (f.getAttribute('autocomplete') || '')); });
    var searchLike = form.getAttribute('role') === 'search' || !!form.closest('[role=search]') || fields.some(function (f) { return f.type === 'search'; }) ||
      (texty.length === 1 && isSearchField(texty[0]) && (form.method || 'get').toLowerCase() === 'get');
    return { inForm: true, searchLike: searchLike, hasPassword: hasPassword, hasPayment: hasPayment };
  }

  function submits(el) {
    var tag = el.tagName.toLowerCase();
    if (tag === 'button') return !!formOf(el) && (el.getAttribute('type') || 'submit').toLowerCase() === 'submit';
    if (tag === 'input') return el.type === 'submit' || el.type === 'image';
    return false;
  }

  function pageHasPassword() {
    var found = false;
    walk(document.documentElement, function (el) { if (el.tagName === 'INPUT' && el.type === 'password' && shown(el)) { found = true; return false; } });
    return found;
  }

  // Everything the Kotlin side needs to decide whether an action must be confirmed.
  L.inspect = function (ref) {
    var el = L.find(ref);
    if (!el) return { error: missing(ref) };
    var f = formFacts(formOf(el), el);
    return {
      label: name(el), role: role(el), tag: el.tagName.toLowerCase(), inputType: el.type || null,
      sensitive: sensitive(el), submits: submits(el), inForm: f.inForm, searchLike: f.searchLike,
      formHasPassword: f.hasPassword, formHasPayment: f.hasPayment, pageHasPassword: pageHasPassword(),
      editable: isEditable(el)
    };
  };

  // Facts for the focused element (pressing Enter in it).
  L.inspectFocused = function () {
    var el = deepActive();
    if (!el || el === document.body) {
      return { label: '', role: 'page', tag: 'body', inputType: null, sensitive: false, submits: false, inForm: false, searchLike: false,
        formHasPassword: false, formHasPayment: false, pageHasPassword: pageHasPassword(), editable: false };
    }
    var a = assign(el);
    var o = L.inspect(a.ref);
    o.ref = a.ref;
    return o;
  };

  function deepActive() {
    var el = document.activeElement;
    while (el) {
      if (el.shadowRoot && el.shadowRoot.activeElement) { el = el.shadowRoot.activeElement; continue; }
      if (el.tagName === 'IFRAME') { try { var d = el.contentDocument; if (d && d.activeElement) { el = d.activeElement; continue; } } catch (e) {} }
      break;
    }
    return el;
  }

  function isEditable(el) {
    var tag = el.tagName.toLowerCase();
    return (tag === 'input' && ['checkbox', 'radio', 'submit', 'button', 'reset', 'image', 'file', 'range', 'color'].indexOf(el.type) < 0) ||
      tag === 'textarea' || el.isContentEditable;
  }

  // ---- Actions ------------------------------------------------------------------------------------

  var SENSITIVE_FIELD = 'This is a password, card or one-time-code field, which you never type into. For a sign-in, use browser_login if the snapshot says a saved login is available; otherwise hand over with browser_ask_user.';

  function missing(ref) { return 'No element ' + ref + ' on this page (refs change after navigation). Take a new snapshot.'; }

  function target(ref) {
    var el = L.find(ref);
    if (!el) return { error: missing(ref) };
    hitTarget(el).scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
    return { el: el };
  }

  function describeShort(el) {
    var n = name(el);
    return el.tagName.toLowerCase() + (n ? ' "' + clean(n, 50) + '"' : '') + (refOf(el) ? ' [' + refOf(el) + ']' : '');
  }

  // The deepest element at (x, y) in doc, through open shadow roots.
  function deepAt(doc, x, y) {
    var top = doc.elementFromPoint(x, y);
    while (top && top.shadowRoot) {
      var inner = top.shadowRoot.elementFromPoint(x, y);
      if (!inner || inner === top) break;
      top = inner;
    }
    return top;
  }

  // Whether a tap landing on `top` reaches `el` (Playwright's "receives events" check).
  function receives(el, hit, top) {
    if (!top) return false;
    return top === hit || hit.contains(top) || top.contains(hit) || composedContains(hit, top) ||
      !!(el.labels && Array.prototype.indexOf.call(el.labels, top) >= 0);
  }

  // Where to tap `el`: its centre, or another point inside it when something covers the centre (a sticky
  // header over a list, a badge over a button). Coordinates are in el's own document; `covered` is what
  // sits on top when no point is free.
  function hitPoint(el) {
    var hit = hitTarget(el), r = hit.getBoundingClientRect(), doc = hit.ownerDocument;
    var spots = [[0.5, 0.5], [0.5, 0.25], [0.5, 0.75], [0.25, 0.5], [0.75, 0.5]], first = null;
    for (var i = 0; i < spots.length; i++) {
      var x = r.left + r.width * spots[i][0], y = r.top + r.height * spots[i][1];
      var top = deepAt(doc, x, y);
      if (receives(el, hit, top)) return { x: x, y: y, at: top, hit: hit };
      if (!first) first = top;
    }
    return { x: r.left + r.width / 2, y: r.top + r.height / 2, at: null, hit: hit, covered: first };
  }

  // Whether `x` is part of an open popup or a full-screen layer (a menu's backdrop).
  function inPopupLayer(x) {
    var pops = findPopups();
    for (var i = 0; i < pops.length; i++) if (composedContains(pops[i].el, x)) return pops[i];
    var r = x.getBoundingClientRect();
    return r.width >= window.innerWidth * 0.9 && r.height >= window.innerHeight * 0.9 && styleOf(x).position === 'fixed' ? { el: x } : null;
  }

  function noteTarget(el) { L.lastTarget = new WeakRef(el); L.lastActionAt = Date.now(); }

  // Scrolls the element into view and reports where to tap, and whether something covers it.
  L.rect = function (ref) {
    var t = target(ref);
    if (t.error) return t;
    var el = t.el, hit = hitTarget(el);
    if (el.disabled || el.getAttribute('aria-disabled') === 'true' || el.closest('[inert]')) return { error: ref + ' is disabled. Fill in the required fields first, or look for what enables it.' };
    if (!shown(hit)) return { error: ref + ' is not visible right now. Take a new snapshot; it may need a menu opened or the page scrolled.' };
    var p = hitPoint(el), o = frameOffset(el), r = rectOf(el);
    var cx = p.x + o.x, cy = p.y + o.y;
    var out = { x: cx, y: cy, w: r.width, h: r.height, vw: window.innerWidth, covered: null, outside: cy < 0 || cy > window.innerHeight || cx < 0 || cx > window.innerWidth };
    if (p.covered) {
      assign(p.covered);
      out.covered = describeShort(p.covered.closest(INTERACTIVE) || p.covered);
      var layer = inPopupLayer(p.covered);
      if (layer && !composedContains(layer.el, el)) out.layer = layer.trigger && refOf(layer.trigger) ? refOf(layer.trigger) : 'page';
    } else {
      noteTarget(el);
    }
    return out;
  };

  function focusableAncestor(x) {
    for (; x && x.nodeType === 1; x = x.parentElement || (x.getRootNode().host || null)) {
      if (x.disabled) return null;
      if (x.tabIndex >= 0 || x.hasAttribute('tabindex') || x.isContentEditable || /^(INPUT|SELECT|TEXTAREA|BUTTON)$/.test(x.tagName) || (x.tagName === 'A' && x.hasAttribute('href'))) return x;
    }
    return null;
  }

  // The parent element, through shadow roots; null at the top of a document.
  function parentOf(x) {
    var p = x.parentNode;
    if (!p) return null;
    if (p.nodeType === 11) return p.host || null;
    return p.nodeType === 1 ? p : null;
  }

  function fire(target, type, Kind, init) {
    var ev;
    try { ev = new Kind(type, init); } catch (e) { ev = new MouseEvent(type, init); }
    return target.dispatchEvent(ev);
  }

  // A tap from script, in the order a phone's browser sends one (Pointer Events and Touch Events specs):
  // pointerover/enter, pointerdown, touchstart, pointerup, touchend, then the compatibility mouse events
  // mouseover/enter, mousemove, mousedown, focus, mouseup, and click. All at the hit-tested point, on the
  // element actually there, so menus that open on mousedown or pointerdown (MUI, react-select, Select2,
  // Radix) and options that select on mouseup or click both work. Like a browser, a cancelled pointerdown
  // drops the mouse events but not the click, a cancelled touchend drops both, and a cancelled mousedown
  // keeps focus where it is (menus do that so the field keeps focus and the list stays open).
  L.click = function (ref) {
    var t = target(ref);
    if (t.error) return t;
    var p = hitPoint(t.el), el = p.at || p.hit, view = el.ownerDocument.defaultView;
    noteTarget(t.el);
    var base = { bubbles: true, cancelable: true, composed: true, view: view, clientX: p.x, clientY: p.y, screenX: p.x, screenY: p.y, button: 0, buttons: 1, detail: 1 };
    var ptr = { bubbles: true, cancelable: true, composed: true, view: view, clientX: p.x, clientY: p.y, button: 0, buttons: 1,
      pointerId: 1, pointerType: 'touch', isPrimary: true, width: 1, height: 1, pressure: 0.5 };
    var enter = function (o) { var c = {}; for (var k in o) c[k] = o[k]; c.bubbles = false; c.cancelable = false; return c; };
    // The pointer moves from where the last tap was: leave events for what it leaves, enter events
    // (which don't bubble) for every element it enters, so menus that open on hover of a wrapper open.
    var before = L.hovered && L.hovered.deref(), left = [], entered = [];
    var still = before && before.isConnected;
    for (var b = still ? before : null; b && !composedContains(b, el); b = parentOf(b)) left.push(b);
    for (var e = el; e && !(still && composedContains(e, before)); e = parentOf(e)) entered.unshift(e);
    if (before && before.isConnected && before !== el) {
      fire(before, 'pointerout', view.PointerEvent || MouseEvent, ptr);
      left.forEach(function (x) { fire(x, 'pointerleave', view.PointerEvent || MouseEvent, enter(ptr)); });
      fire(before, 'mouseout', MouseEvent, base);
      left.forEach(function (x) { fire(x, 'mouseleave', MouseEvent, enter(base)); });
    }
    L.hovered = new WeakRef(el);
    fire(el, 'pointerover', view.PointerEvent || MouseEvent, ptr);
    entered.forEach(function (x) { fire(x, 'pointerenter', view.PointerEvent || MouseEvent, enter(ptr)); });
    var mouse = fire(el, 'pointerdown', view.PointerEvent || MouseEvent, ptr), touch = true, touchObj = null;
    try {
      touchObj = new view.Touch({ identifier: 1, target: el, clientX: p.x, clientY: p.y, screenX: p.x, screenY: p.y, pageX: p.x + view.scrollX, pageY: p.y + view.scrollY });
      fire(el, 'touchstart', view.TouchEvent, { bubbles: true, cancelable: true, composed: true, view: view, touches: [touchObj], targetTouches: [touchObj], changedTouches: [touchObj] });
    } catch (e) { touchObj = null; }
    var up = {}; for (var k in ptr) up[k] = ptr[k]; up.buttons = 0; up.pressure = 0;
    fire(el, 'pointerup', view.PointerEvent || MouseEvent, up);
    if (touchObj) touch = fire(el, 'touchend', view.TouchEvent, { bubbles: true, cancelable: true, composed: true, view: view, touches: [], targetTouches: [], changedTouches: [touchObj] });
    if (!touch) return { ok: true };
    var focusMoves = true;
    if (mouse) {
      fire(el, 'mouseover', MouseEvent, base);
      entered.forEach(function (x) { fire(x, 'mouseenter', MouseEvent, enter(base)); });
      fire(el, 'mousemove', MouseEvent, base);
      focusMoves = fire(el, 'mousedown', MouseEvent, base);
    }
    if (focusMoves && el.isConnected) {
      var f = focusableAncestor(el), active = deepActive();
      if (f && f !== active) f.focus({ preventScroll: true });
      else if (!f && active && active !== el.ownerDocument.body && active.blur && !composedContains(active, el)) active.blur();
    }
    var upInit = {}; for (var m in base) upInit[m] = base[m]; upInit.buttons = 0;
    if (mouse && el.isConnected) fire(el, 'mouseup', MouseEvent, upInit);
    // A page that re-rendered the element on mousedown gets the click on what is at the point now.
    var clickOn = el.isConnected ? el : deepAt(el.ownerDocument, p.x, p.y);
    if (clickOn) fire(clickOn, 'click', MouseEvent, upInit);
    return { ok: true };
  };

  function setValue(el, text) {
    // The agent's own text replaces what the user typed, so the field may be described again.
    entered.delete(el);
    agentTyping = true;
    try { return writeValue(el, text); } finally { agentTyping = false; }
  }

  function writeValue(el, text) {
    var tag = el.tagName.toLowerCase();
    if (tag === 'input' || tag === 'textarea') {
      var proto = tag === 'input' ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype;
      el.dispatchEvent(new InputEvent('beforeinput', { bubbles: true, cancelable: true, data: text, inputType: 'insertText' }));
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, text);
      el.dispatchEvent(new InputEvent('input', { bubbles: true, data: text, inputType: 'insertText' }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
      return true;
    }
    if (el.isContentEditable) {
      el.focus();
      var sel = el.ownerDocument.getSelection(), range = el.ownerDocument.createRange();
      range.selectNodeContents(el); sel.removeAllRanges(); sel.addRange(range);
      if (!el.ownerDocument.execCommand('insertText', false, text)) el.innerText = text;
      return true;
    }
    return false;
  }

  L.type = function (ref, text, submit) {
    var t = target(ref);
    if (t.error) return t;
    var el = t.el;
    if (!isEditable(el)) {
      var inner = el.querySelector && el.querySelector('input:not([type=hidden]),textarea,[contenteditable=""],[contenteditable=true]');
      if (inner && isEditable(inner)) el = inner;
      else return { error: ref + ' (' + role(el) + ') is not a text field. Use browser_click for buttons, browser_select for dropdowns.' };
    }
    if (el.readOnly) return { error: ref + ' is read-only.' };
    // Checked again here, in the same call that types: the page may have changed since the facts check.
    if (sensitive(el)) return { error: SENSITIVE_FIELD };
    el.focus();
    noteTarget(el);
    setValue(el, text);
    if (submit) pressKey(el, 'Enter');
    var problem = dateProblem(el, text);
    if (problem) return { error: ref + ': ' + problem };
    return { ok: true };
  };

  // Date and time inputs take one fixed format and silently drop anything else.
  var DATE_FORMATS = { date: 'YYYY-MM-DD', time: 'HH:MM (24-hour)', 'datetime-local': 'YYYY-MM-DDTHH:MM', month: 'YYYY-MM', week: 'YYYY-Www' };
  function dateProblem(el, text) {
    var fmt = el.tagName === 'INPUT' && DATE_FORMATS[el.type];
    if (!fmt || !String(text).trim() || el.value) return null;
    return 'this ' + el.type + ' field didn\'t take "' + clean(text, 40) + '". Give it as ' + fmt + ', e.g. ' +
      ({ date: '2026-11-06', time: '14:30', 'datetime-local': '2026-11-06T14:30', month: '2026-11', week: '2026-W45' })[el.type] + '.';
  }

  // ---- Dropdowns ----------------------------------------------------------------------------------

  // Options of `list` whose text matches `want`: exact first (value, label or text), then a unique
  // start, then a unique part. Returns { el } or { many: [texts] } or {}.
  function matchOption(list, want, textOf, keysOf) {
    var w = norm(want);
    if (!w) return {};
    var exact = list.filter(function (o) { return keysOf(o).some(function (k) { return norm(k) === w; }); });
    if (exact.length) return { el: exact[0] };
    var starts = list.filter(function (o) { return norm(textOf(o)).indexOf(w) === 0; });
    if (starts.length === 1) return { el: starts[0] };
    var parts = starts.length ? starts : list.filter(function (o) { return norm(textOf(o)).indexOf(w) >= 0; });
    if (parts.length === 1) return { el: parts[0] };
    if (parts.length > 1) return { many: parts.slice(0, 8).map(function (o) { return clean(textOf(o), 50); }) };
    return {};
  }

  function nativeKeys(o) { return [o.value, o.label, o.text]; }
  function nativeText(o) { return o.label || o.text; }
  function optionDisabled(o) { return o.disabled || (o.parentElement && o.parentElement.tagName === 'OPTGROUP' && o.parentElement.disabled); }

  // Sets a native <select> the way Playwright's selectOption does: match by value or label, refuse a
  // disabled option, set `selected`, then fire input and change (bubbling). A multi-select takes several
  // options separated by " | " and ends up with exactly those selected.
  function choose(el, value, ref) {
    if (el.disabled) return { error: ref + ' is disabled. Fill in the required fields first, or look for what enables it.' };
    var opts = Array.prototype.slice.call(el.options), wants = [String(value)];
    var whole = matchOption(opts, value, nativeText, nativeKeys);
    if (el.multiple && !whole.el && String(value).indexOf('|') >= 0) wants = String(value).split('|').map(function (s) { return s.trim(); }).filter(Boolean);
    var picked = [];
    for (var i = 0; i < wants.length; i++) {
      var m = wants.length === 1 ? whole : matchOption(opts, wants[i], nativeText, nativeKeys);
      if (m.many) return { error: 'Several options in ' + ref + ' match "' + wants[i] + '": ' + m.many.join(' | ') + '. Give the exact text.' };
      if (!m.el) {
        return { error: 'No option "' + wants[i] + '" in ' + ref + ' (' + opts.length + ' options). Options: ' +
          optionTexts(opts, 40).join(' | ') + (opts.length > 40 ? ' | …' : '') };
      }
      if (optionDisabled(m.el)) return { error: 'The option "' + clean(nativeText(m.el), 50) + '" in ' + ref + ' is disabled.' };
      picked.push(m.el);
    }
    if (el.multiple) opts.forEach(function (o) { o.selected = picked.indexOf(o) >= 0; });
    else picked[0].selected = true;
    el.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    return { ok: true, selected: picked.map(function (o) { return clean(nativeText(o), 60); }).join(' | ') };
  }

  // The popup `trig` opened: the element its aria-controls/aria-owns names (or an inner one's), the
  // list holding its aria-activedescendant, else the popup findPopups ties to it, else the one that
  // appeared after the agent's last action.
  function popupFor(trig, strict) {
    var named = [trig].concat(Array.prototype.slice.call(trig.querySelectorAll ? trig.querySelectorAll('[aria-controls],[aria-owns]') : [], 0, 5));
    for (var i = 0; i < named.length; i++) {
      var ids = idsOf(named[i], 'aria-controls').concat(idsOf(named[i], 'aria-owns'));
      for (var j = 0; j < ids.length; j++) {
        var el = byId(named[i], ids[j]);
        if (el && shown(el) && !composedContains(el, trig)) {
          var pops = findPopups();
          for (var k = 0; k < pops.length; k++) if (composedContains(pops[k].el, el)) return pops[k].el;
          return el;
        }
      }
    }
    var active = trig.getAttribute('aria-activedescendant');
    var act = active && byId(trig, active), list = act && act.closest(POPUP_ROLES);
    if (list && shown(list)) return list;
    var all = findPopups(), mine = all.filter(function (p) { return p.trigger === trig || (p.trigger && composedContains(trig, p.trigger)); });
    if (mine.length) return mine[mine.length - 1].el;
    if (strict) return null;
    var guessed = all.filter(function (p) { return p.guess; });
    return guessed.length ? guessed[guessed.length - 1].el : null;
  }

  // What can be chosen in a popup: its options (or menu items, tree items, grid cells), else its links
  // and buttons (a Bootstrap menu), else its list items.
  function choicesIn(pop) {
    var pick = function (sel) { return Array.prototype.filter.call(pop.querySelectorAll(sel), function (x) { return shown(x); }); };
    var list = pick(OPTION_LIKE);
    if (!list.length) list = pick(INTERACTIVE).filter(function (x) { return !isEditable(x); });
    if (!list.length) list = pick('li');
    return list;
  }

  function choiceText(x) { return (x.getAttribute('aria-label') || x.innerText || x.textContent || '').replace(/\s+/g, ' ').trim(); }
  function choiceKeys(x) { return [choiceText(x), (x.innerText || '').split('\n')[0], x.getAttribute('data-value'), x.getAttribute('value'), x.getAttribute('title')].filter(Boolean); }

  // The text field that filters the popup's list: the control itself, a field inside it, one in the
  // popup or just around it (Select2 and Choices.js put their search box there), or the focused one.
  function searchField(trig, pop) {
    var ok = function (x) { return x && isEditable(x) && !x.readOnly && !x.disabled && shown(x) ? x : null; };
    var found = ok(trig) || ok(trig.querySelector && trig.querySelector('input:not([type=hidden]),textarea'));
    for (var i = 0, x = pop; !found && x && x.nodeType === 1 && i < 4; i++, x = x.parentElement) {
      found = ok(x.querySelector('input[type=search],input[type=text],input:not([type])'));
    }
    var f = deepActive();
    if (!found && f && ok(f) && (f.getAttribute('role') === 'combobox' || f.hasAttribute('aria-autocomplete') || (pop && composedContains(pop.parentElement || pop, f)))) found = f;
    return found;
  }

  function disabledChoice(x) { return x.getAttribute('aria-disabled') === 'true' || x.disabled || !!x.closest('[aria-disabled=true]'); }

  // browser_select, step 1. A native <select> (or its label) is set right here. An option's own ref is
  // handed back to be clicked. Anything else is a custom dropdown: Kotlin opens it (a real tap when the
  // browser is on screen) and then calls selectFind.
  L.selectStart = function (ref, value) {
    var t = target(ref);
    if (t.error) return t;
    var el = t.el;
    if (el.tagName === 'LABEL' && el.control) el = el.control;
    if (el.tagName === 'SELECT') {
      var r = choose(el, value, ref);
      if (r.ok) r['native'] = true;
      return r;
    }
    if (el.matches(OPTION_LIKE)) return { option: ref, text: clean(choiceText(el), 60) };
    if (el.disabled || el.getAttribute('aria-disabled') === 'true') return { error: ref + ' is disabled. Fill in the required fields first, or look for what enables it.' };
    if (sensitive(el)) return { error: SENSITIVE_FIELD };
    L.sel = null;
    var open = el.getAttribute('aria-expanded') !== 'false' && !!popupFor(el, true);
    return { custom: true, open: open || (isEditable(el) && !el.readOnly), role: role(el) };
  };

  // browser_select, each later step: find the option in the popup `ref` opened. Answers one of
  //   { found: ref, text }  click this option;
  //   { typed: true }       typed the value into the list's search field; let the page filter;
  //   { scrolled: true }    scrolled a long (maybe virtualised) list further; look again;
  //   { wait: true }        no popup yet, or filtering still under way;
  //   { error }             why not, with what the list offers.
  L.selectFind = function (ref, value, fresh) {
    var trig = L.find(ref);
    if (!trig) return { error: missing(ref) };
    if (fresh || !L.sel || L.sel.ref !== ref) L.sel = { ref: ref, typedAt: 0, scrolls: 0, seen: [] };
    var st = L.sel, pop = popupFor(trig);
    if (pop) {
      var list = choicesIn(pop);
      list.forEach(function (x) { var s = clean(choiceText(x), 50); if (s && st.seen.indexOf(s) < 0 && st.seen.length < 200) st.seen.push(s); });
      var m = matchOption(list, value, choiceText, choiceKeys);
      if (m.el) {
        if (disabledChoice(m.el)) return { error: 'The option "' + clean(choiceText(m.el), 50) + '" is disabled.' };
        var a = assign(m.el);
        remember(a.ref, { role: role(m.el), name: name(m.el) });
        return { found: a.ref, text: clean(choiceText(m.el), 60) };
      }
      if (m.many) return { error: 'Several options match "' + value + '": ' + m.many.join(' | ') + '. Give the exact text.' };
    }
    if (st.typedAt && Date.now() - st.typedAt < 2500) return { wait: true };
    if (!st.typedAt) {
      var field = searchField(trig, pop);
      if (field) {
        if (sensitive(field)) return { error: SENSITIVE_FIELD };
        field.focus();
        noteTarget(trig);
        setValue(field, String(value));
        st.typedAt = Date.now();
        return { typed: true };
      }
    }
    if (!pop) return { wait: true, none: true };
    var sc = scrollerOf(pop);
    if (sc && st.scrolls < 60 && sc.scrollTop + sc.clientHeight < sc.scrollHeight - 2) {
      sc.scrollTop = sc.scrollTop + Math.max(40, sc.clientHeight * 0.8);
      // Virtualised lists render rows from their scroll event, which a browser sends only with its next
      // frame (and a WebView off screen may not draw one): send it now. A second one is harmless.
      sc.dispatchEvent(new Event('scroll'));
      st.scrolls++;
      return { scrolled: true };
    }
    var how = (st.typedAt ? ' after typing it into the list\'s search field' : '') + (st.scrolls ? (st.typedAt ? ' and' : '') + ' after scrolling the whole list' : '');
    return { error: 'No option matches "' + value + '" in the list ' + ref + ' opened' + how + '. ' +
      (st.seen.length ? 'It offers: ' + st.seen.slice(0, 40).join(' | ') + (st.seen.length > 40 ? ' | …' : '') + '.' : 'It shows no options.') +
      ' The list is still open (see the snapshot); press Escape to close it.' };
  };

  // browser_select, last step: what the control shows now, to confirm the choice took.
  L.selectCheck = function (ref, text) {
    var trig = L.find(ref);
    if (!trig) return { gone: true };
    var shows = trig.tagName === 'SELECT' ? Array.prototype.map.call(trig.selectedOptions, nativeText).join(', ')
      : isEditable(trig) ? trig.value || '' : (trig.innerText || trig.getAttribute('aria-label') || '');
    // A field's chosen value often sits next to it (react-select's single value, chips): look around it.
    // Not while a list that is still open sits in there: its options would always match.
    var around = trig, pop = popupFor(trig, true);
    for (var i = 0; i < 3 && around.parentElement && norm(around.innerText).indexOf(norm(text)) < 0; i++) {
      var up = around.parentElement;
      if ((up.innerText || '').length > 400 || (pop && composedContains(up, pop))) break;
      around = up;
    }
    var ok = norm(shows).indexOf(norm(text)) >= 0 || norm(around.innerText).indexOf(norm(text)) >= 0;
    return { ok: ok, shows: clean(shows || around.innerText, 80), open: trig.getAttribute('aria-expanded') === 'true' };
  };

  // Fills one field of any kind: text, select, checkbox/radio ("true"/"false"), contenteditable.
  L.fill = function (ref, value) {
    var el = L.find(ref);
    if (!el) return { error: missing(ref) };
    var tag = el.tagName.toLowerCase(), r = role(el);
    if (tag === 'select') return choose(el, value, ref);
    if (r === 'checkbox' || r === 'radio' || r === 'switch') {
      var want = /^(true|yes|on|1|checked)$/i.test(String(value).trim());
      var now = tag === 'input' ? el.checked : el.getAttribute('aria-checked') === 'true';
      if (now !== want) {
        if (r === 'radio' && !want) return { error: ref + ' is a radio button; choose another option in its group instead of unchecking it.' };
        hitTarget(el).click();
      }
      return { ok: true };
    }
    if (tag === 'input' && el.type === 'file') return { error: ref + ' is a file input; use browser_upload.' };
    var t = target(ref);
    if (t.error) return t;
    if (!isEditable(el)) return { error: ref + ' (' + r + ') can\'t be filled. Click it instead.' };
    if (sensitive(el)) return { error: ref + ': ' + SENSITIVE_FIELD };
    el.focus();
    setValue(el, String(value));
    return { ok: true };
  };

  var KEYS = {
    Enter: 13, Escape: 27, Tab: 9, Backspace: 8, Delete: 46, Space: 32, ' ': 32,
    ArrowDown: 40, ArrowUp: 38, ArrowLeft: 37, ArrowRight: 39, PageDown: 34, PageUp: 33, Home: 36, End: 35
  };

  function focusables(doc) {
    return Array.prototype.filter.call(doc.querySelectorAll('a[href],button,input:not([type=hidden]),select,textarea,[tabindex],[contenteditable=""],[contenteditable=true]'),
      function (e) { return e.tabIndex >= 0 && !e.disabled && shown(e); });
  }

  // Synthetic key events have no default action, so the useful ones are emulated: Enter submits the
  // form (implicit submission) or activates a link/button, Tab moves focus, Space toggles.
  function pressKey(el, key) {
    var code = KEYS[key] || (key.length === 1 ? key.toUpperCase().charCodeAt(0) : 0);
    var k = key === 'Space' ? ' ' : key;
    var init = { key: k, code: key.length === 1 ? 'Key' + key.toUpperCase() : (key === 'Space' ? 'Space' : key), keyCode: code, which: code, bubbles: true, cancelable: true, composed: true };
    var go = el.dispatchEvent(new KeyboardEvent('keydown', init));
    if (k.length === 1 || key === 'Enter') el.dispatchEvent(new KeyboardEvent('keypress', init));
    el.dispatchEvent(new KeyboardEvent('keyup', init));
    if (!go) return;
    var tag = el.tagName.toLowerCase();
    if (key === 'Enter') {
      if (tag === 'a' || tag === 'button' || el.getAttribute('role') === 'button') { el.click(); return; }
      if (tag === 'input') {
        var form = formOf(el);
        if (form) {
          var btn = form.querySelector('button:not([type]),button[type=submit],input[type=submit],input[type=image]');
          if (btn && !btn.disabled) btn.click();
          else if (fieldsOf(form).filter(function (f) { return f.tagName === 'INPUT' && f.type !== 'checkbox' && f.type !== 'radio'; }).length <= 1) {
            if (form.requestSubmit) form.requestSubmit(); else form.submit();
          }
        }
      }
    } else if (key === 'Tab') {
      var list = focusables(el.ownerDocument), i = list.indexOf(el);
      var next = list[(i + 1) % Math.max(list.length, 1)];
      if (next) next.focus();
    } else if (key === 'Space' || key === ' ') {
      if (tag === 'button' || el.type === 'checkbox' || el.type === 'radio' || el.getAttribute('role') === 'button' ||
          el.getAttribute('role') === 'checkbox' || el.getAttribute('role') === 'switch') el.click();
    }
  }

  L.key = function (key) {
    var el = deepActive() || document.body;
    pressKey(el, key);
    return { ok: true };
  };

  L.scroll = function (direction, ref) {
    var dy = (direction === 'up' ? -1 : 1) * window.innerHeight * 0.8;
    if (ref) {
      var el = L.find(ref);
      if (!el) return { error: missing(ref) };
      if (direction === 'into_view') { hitTarget(el).scrollIntoView({ block: 'center', behavior: 'instant' }); return { ok: true }; }
      var before = el.scrollTop;
      el.scrollBy({ top: dy, behavior: 'instant' });
      if (el.scrollTop === before && el.scrollHeight <= el.clientHeight) return { error: ref + ' does not scroll. Scroll the page instead (leave out ref).' };
    } else if (direction === 'top') {
      window.scrollTo({ top: 0, behavior: 'instant' });
    } else if (direction === 'bottom') {
      window.scrollTo({ top: document.documentElement.scrollHeight, behavior: 'instant' });
    } else {
      var y = window.scrollY;
      window.scrollBy({ top: dy, behavior: 'instant' });
      if (window.scrollY === y) return { ok: true, note: direction === 'up' ? 'Already at the top.' : 'Already at the bottom of the page.' };
    }
    return { ok: true };
  };

  // ---- Files --------------------------------------------------------------------------------------
  // evaluateJavascript can't await, so async work is started here and its result polled as a job.

  // Reads a blob: URL (only the page that made it can) as base64, fetched in chunks by readChunk.
  L.readBlob = function (url, id) {
    L.jobs[id] = { state: 'running' };
    fetch(url).then(function (r) { return r.blob(); }).then(function (b) {
      var fr = new FileReader();
      fr.onload = function () { var s = String(fr.result); L.jobs[id] = { state: 'done', type: b.type, data: s.slice(s.indexOf(',') + 1) }; };
      fr.onerror = function () { L.jobs[id] = { state: 'error', error: 'could not read the file' }; };
      fr.readAsDataURL(b);
    }).catch(function (e) { L.jobs[id] = { state: 'error', error: String(e && e.message || e) }; });
    return { ok: true };
  };

  L.job = function (id) {
    var j = L.jobs[id];
    if (!j) return { state: 'missing' };
    return { state: j.state, error: j.error, type: j.type, length: j.data ? j.data.length : 0 };
  };

  L.readChunk = function (id, start, size) {
    var j = L.jobs[id];
    return j && j.data ? j.data.substr(start, size) : '';
  };

  L.endJob = function (id) { delete L.jobs[id]; return { ok: true }; };

  // The file input an upload targets: the element itself, one inside it or its label, one in its
  // form, or the page's only one.
  function fileInput(el) {
    var isFile = function (x) { return x && x.tagName === 'INPUT' && x.type === 'file'; };
    if (isFile(el)) return el;
    var inner = el.querySelector && el.querySelector('input[type=file]');
    if (inner) return inner;
    var label = el.closest && el.closest('label');
    if (label && isFile(label.control)) return label.control;
    var form = formOf(el);
    if (form) { var f = form.querySelector('input[type=file]'); if (f) return f; }
    var all = [];
    walk(document.documentElement, function (x) { if (isFile(x)) all.push(x); });
    return all.length === 1 ? all[0] : null;
  }

  L.uploadTarget = function (ref) {
    var el = L.find(ref);
    if (!el) return { error: missing(ref) };
    var input = fileInput(el);
    if (!input) return { none: true };
    if (input.disabled) return { error: 'The file input is disabled.' };
    return { ref: assign(input).ref, multiple: !!input.multiple, accept: input.accept || '' };
  };

  L.fileStart = function (id) { L.jobs[id] = { state: 'upload', parts: [] }; return { ok: true }; };
  L.fileChunk = function (id, part) { L.jobs[id].parts.push(part); return { ok: true }; };

  // Puts the uploaded files into the input, as if the user had picked them, and tells the page.
  L.fileSet = function (ref, files) {
    var input = L.find(ref);
    if (!input) return { error: missing(ref) };
    var dt = new DataTransfer();
    files.forEach(function (f) {
      var b64 = L.jobs[f.id].parts.join(''), bin = atob(b64), bytes = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
      dt.items.add(new File([bytes], f.name, { type: f.type || 'application/octet-stream', lastModified: Date.now() }));
      delete L.jobs[f.id];
    });
    input.files = dt.files;
    input.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
    input.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
    return { ok: true, count: input.files.length };
  };

  // Whether `text` is visible on the page (including open shadow roots and same-origin frames).
  L.hasText = function (text) {
    var q = String(text).toLowerCase(), found = false;
    if (document.body && document.body.innerText.toLowerCase().indexOf(q) >= 0) return true;
    walk(document.documentElement, function (el) {
      if (el.shadowRoot) { var t = el.shadowRoot.textContent || ''; if (t.toLowerCase().indexOf(q) >= 0) { found = true; return false; } }
      if (el.tagName === 'IFRAME') { try { var b = el.contentDocument && el.contentDocument.body; if (b && b.innerText.toLowerCase().indexOf(q) >= 0) { found = true; return false; } } catch (e) {} }
    });
    return found;
  };

  // ---- Saved logins -------------------------------------------------------------------------------
  // docs/design/browser.md, "Saved logins". Kotlin decides; these only report what the sign-in fields
  // are (never their values) and fill what Kotlin planned, checking everything again in the same call.

  var OTP_HINT = /one.?time|\botp\b|totp|2fa|two.?factor|\bmfa\b|verification.?code|auth(?:entication|enticator)?.?code|passcode/i;
  var USER_HINT = /user|e-?mail|login|account|identifier|phone|mobile/i;

  function hintOf(el) {
    var labels = el.labels && el.labels.length ? el.labels[0].innerText : '';
    return [el.name, el.id, el.getAttribute('aria-label'), el.getAttribute('placeholder'), el.getAttribute('autocomplete'), labels].join(' ');
  }

  // "password", "new-password", "username", "code" or null.
  function loginKind(el) {
    if (!el || el.tagName !== 'INPUT') return null;
    var t = (el.type || 'text').toLowerCase(), ac = (el.getAttribute('autocomplete') || '').toLowerCase();
    if (t === 'password') return /new-password/.test(ac) ? 'new-password' : 'password';
    if (['text', 'email', 'tel', 'number'].indexOf(t) < 0) return null;
    if (/one-time-code/.test(ac)) return 'code';
    var hint = hintOf(el);
    if (PAYMENT.test(hint) || isSearchField(el)) return null;
    if (/\b(username|email)\b/.test(ac) || t === 'email') return 'username';
    if (OTP_HINT.test(hint) && !USER_HINT.test(hint)) return 'code';
    if (USER_HINT.test(hint)) return 'username';
    return null;
  }

  function actionsOf(form) {
    if (!form) return [];
    var out = [];
    try { out.push(formActionGet ? formActionGet.call(form) : location.href); } catch (e) { out.push(location.href); }
    Array.prototype.forEach.call(form.querySelectorAll('button[formaction],input[formaction]'), function (b) {
      try { out.push(new URL(b.getAttribute('formaction'), location.href).href); } catch (e) { out.push('invalid:'); }
    });
    return out;
  }

  // The same rule as LoginRules.sameSite in Kotlin.
  function sameSite(url, origin) {
    var a = String(url || '').trim();
    if (!a || /^javascript:/i.test(a)) return true;
    var u;
    try { u = new URL(a); } catch (e) { return false; }
    if (u.protocol + '//' !== origin.split('//')[0] + '//') return false;
    var h1 = u.hostname.toLowerCase(), h2 = new URL(origin).hostname.toLowerCase();
    return h1 === h2 || h1.slice(-(h2.length + 1)) === '.' + h2 || h2.slice(-(h1.length + 1)) === '.' + h1;
  }

  // What the page's sign-in fields are. Fields in frames are listed with topDocument false; Kotlin skips them.
  L.loginForm = function () {
    var forms = [], fields = [];
    walk(document.documentElement, function (el) {
      if (el.tagName !== 'INPUT') return;
      var kind = loginKind(el);
      if (!kind) return;
      var form = formOf(el), i = -1;
      if (form) { i = forms.indexOf(form); if (i < 0) { forms.push(form); i = forms.length - 1; } }
      fields.push({ ref: assign(el).ref, kind: kind, type: (el.type || 'text').toLowerCase(), visible: shown(el),
        topDocument: el.ownerDocument === document, form: i, actions: actionsOf(form) });
    });
    return { top: window.top === window, origin: location.origin, fields: fields };
  };

  // Fills the planned fields, or none: every check runs first, again, in this same call.
  L.fillLogin = function (origin, plan, values) {
    if (window.top !== window) return { error: 'The sign-in form is not in the top frame.' };
    if (location.origin !== origin) return { error: 'The page is now on ' + location.origin + ', not ' + origin + '.' };
    var todo = [], want = [['username', plan.username, values.username], ['password', plan.password, values.password], ['code', plan.code, values.code]];
    for (var i = 0; i < want.length; i++) {
      var kind = want[i][0], ref = want[i][1], value = want[i][2];
      if (!ref) continue;
      if (typeof value !== 'string' || !value) return { error: 'Nothing to fill for the ' + kind + '.' };
      var el = L.find(ref);
      if (!el) return { error: missing(ref) };
      if (el.ownerDocument !== document) return { error: ref + ' is not in the top frame.' };
      if (!shown(el)) return { error: ref + ' is not visible.' };
      if (el.disabled || el.readOnly) return { error: ref + ' can\'t be filled.' };
      if (loginKind(el) !== kind) return { error: ref + ' is no longer a ' + kind + ' field.' };
      if (kind === 'password' && el.type !== 'password') return { error: ref + ' is not a password field.' };
      var acts = actionsOf(formOf(el));
      for (var j = 0; j < acts.length; j++) if (!sameSite(acts[j], origin)) return { error: 'The form sends to another site.' };
      todo.push([el, value]);
    }
    if (!todo.length) return { error: 'Nothing to fill.' };
    todo.forEach(function (t) { t[0].focus(); setValue(t[0], t[1]); filled.add(t[0]); });
    return { ok: true };
  };

  // Offering to save a login the user typed. Only the copy that ran at document start (it knows the tab's
  // secret) and only in the top frame. A password is captured only from the user's own typing (trusted
  // input events) into a password field, and only if the field still holds what they typed; it's handed to
  // Kotlin, which shows "Save this login?" and keeps it in memory only until the user answers.
  if (SECRET && window.top === window) {
    var typed = new WeakMap(), sent = '';
    document.addEventListener('input', function (e) {
      var t = e.composedPath ? e.composedPath()[0] : e.target;
      // execCommand's input events are trusted too: the agent's own typing (agentTyping) is never the user's.
      if (e.isTrusted && !agentTyping && t && t.tagName === 'INPUT' && t.type === 'password') typed.set(t, t.value);
    }, true);
    var offer = function (scope) {
      try {
        var root = scope || document, pass = null, user = '';
        var inputs = root.querySelectorAll ? root.querySelectorAll('input') : [];
        for (var i = 0; i < inputs.length; i++) {
          var el = inputs[i], kind = loginKind(el);
          if (kind === 'password' && !pass && el.value && typed.get(el) === el.value && !filled.has(el) && shown(el)) pass = el;
          if (kind === 'username' && !user && el.value) user = el.value;
        }
        if (!pass) return;
        var key = user + '\n' + pass.value;
        if (key === sent) return;
        sent = key;
        nativePrompt.call(window, '__locus_login__' + SECRET, ser({ u: user, p: pass.value }));
      } catch (e) {}
    };
    document.addEventListener('submit', function (e) { if (e.isTrusted) offer(e.target); }, true);
    document.addEventListener('keydown', function (e) {
      if (e.isTrusted && e.key === 'Enter' && e.target && e.target.tagName === 'INPUT') offer(formOf(e.target) || document);
    }, true);
    document.addEventListener('click', function (e) {
      if (!e.isTrusted || !e.target || !e.target.closest) return;
      var b = e.target.closest('button,input[type=submit],input[type=image],[role=button]');
      if (b) offer(formOf(b) || document);
    }, true);
  }

  // Lock the helper so page scripts can't swap it for one that reports harmless facts about a "Pay now"
  // button (the confirmation gate in SensitiveActions relies on L.inspect). AgentBrowser injects this
  // file at document start, before any page script runs, so the page never gets a chance to define
  // window.__locus first. State fields (next, pending, …) stay writable; the functions don't.
  try {
    Object.keys(L).forEach(function (k) {
      if (typeof L[k] === 'function' || k === 'doc') Object.defineProperty(L, k, { writable: false, configurable: false });
    });
    Object.defineProperty(window, '__locus', { value: L, writable: false, configurable: false, enumerable: false });
  } catch (e) {}
})();
