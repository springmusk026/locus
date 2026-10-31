// Injected into pages the agent drives. Describes the page as structured data (formatted into text
// by Snapshot.kt) and performs actions on elements by ref. Idempotent: running it again on the same
// page is a no-op.
//
// Refs live in a WeakMap (element -> ref) and a Map of WeakRefs (ref -> element), so the page's DOM is
// never modified and a ref stays the same for as long as its element exists.
(function () {
  if (window.__locus && window.__locus.v === 2) return;
  var L = window.__locus = {
    v: 2, next: 1, lastMutation: Date.now(), pending: 0, snapshots: 0,
    ids: new WeakMap(), refs: new Map(), jobs: {}
  };

  try {
    new MutationObserver(function () { L.lastMutation = Date.now(); })
      .observe(document.documentElement || document, { subtree: true, childList: true, attributes: true, characterData: true });
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

  L.settled = function () {
    return document.readyState === 'complete' && Date.now() - L.lastMutation > 500 && L.pending <= 0;
  };

  var INTERACTIVE = 'a[href],button,input:not([type=hidden]),textarea,select,summary,[contenteditable=""],[contenteditable=true],' +
    '[role=button],[role=link],[role=checkbox],[role=radio],[role=tab],[role=menuitem],[role=menuitemcheckbox],[role=menuitemradio],' +
    '[role=option],[role=switch],[role=combobox],[role=textbox],[role=searchbox],[role=slider],[role=spinbutton],[role=treeitem],' +
    '[onclick],[tabindex]:not([tabindex="-1"])';
  var CONTAINS_ACTION = 'a[href],button,[role=button],[role=link]';
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

  L.find = function (ref) {
    var w = L.refs.get(ref), el = w && w.deref();
    return el && el.isConnected ? el : null;
  };

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
      if (sensitive(el)) { o.sensitive = true; o.hasValue = !!el.value; }
      else if (t === 'file') { if (el.files && el.files.length) o.value = Array.prototype.map.call(el.files, function (f) { return f.name; }).join(', '); }
      else if (el.value && r !== 'button' && t !== 'checkbox' && t !== 'radio') o.value = clean(el.value, 60);
      if (el.checked) o.checked = true;
    } else if (el.isContentEditable && el.innerText.trim()) {
      o.value = clean(el.innerText, 60);
      o.name = clean(el.getAttribute('aria-label') || el.getAttribute('placeholder') || el.getAttribute('aria-placeholder') || '', 80);
    }
    if (el.getAttribute('aria-checked') === 'true' || el.getAttribute('aria-selected') === 'true' || el.getAttribute('aria-pressed') === 'true') o.checked = true;
    if (tag === 'select' && el.selectedOptions && el.selectedOptions[0]) o.selected = clean(el.selectedOptions[0].text, 40);
    if (el.disabled || el.getAttribute('aria-disabled') === 'true') o.disabled = true;
    if (el.getAttribute('aria-expanded')) o.expanded = el.getAttribute('aria-expanded');
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
      if (visit(el) === false) return false;
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

  L.snapshot = function (all) {
    prune();
    var first = L.snapshots++ === 0;
    var items = [], count = 0, max = all ? 600 : 250, truncated = false, frames = [];
    var login = false, captcha = CAPTCHA.test(document.title) || /just a moment|verify you are human|are you a robot/i.test(document.title);
    var modal = modalRoot();
    var start = modal || document.documentElement;
    walk(start, function (el) {
      var tag = el.tagName.toLowerCase();
      if (tag === 'input' && el.type === 'password' && shown(el)) login = true;
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
      var a = assign(el);
      items.push(describe(el, a.ref, a.isNew && !first));
      count++;
    }, frames);
    var scrollable = document.documentElement.scrollHeight - window.innerHeight;
    return {
      title: document.title, url: location.href, full: !!all,
      scroll: scrollable > 4 ? Math.round(100 * window.scrollY / scrollable) : null,
      modal: modal ? clean(modal.getAttribute('aria-label') || modal.innerText, 120) : null,
      login: login, captcha: captcha, frames: frames, truncated: truncated, items: items
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
    if (!el || el === document.body) return { label: '', role: 'page', tag: 'body', submits: false, inForm: false, searchLike: false };
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

  // Scrolls the element into view and reports where to tap, and whether something covers it.
  L.rect = function (ref) {
    var t = target(ref);
    if (t.error) return t;
    var el = t.el, hit = hitTarget(el);
    if (el.disabled || el.getAttribute('aria-disabled') === 'true') return { error: ref + ' is disabled. Fill in the required fields first, or look for what enables it.' };
    if (!shown(hit)) return { error: ref + ' is not visible right now. Take a new snapshot; it may need a menu opened or the page scrolled.' };
    var r = rectOf(el);
    var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
    var local = hit.getBoundingClientRect();
    var doc = hit.ownerDocument;
    var top = doc.elementFromPoint(local.left + local.width / 2, local.top + local.height / 2);
    while (top && top.shadowRoot) {
      var inner = top.shadowRoot.elementFromPoint(local.left + local.width / 2, local.top + local.height / 2);
      if (!inner || inner === top) break;
      top = inner;
    }
    var covered = top && top !== hit && !hit.contains(top) && !top.contains(hit) && !(el.labels && Array.prototype.indexOf.call(el.labels, top) >= 0) &&
      !(top.getRootNode().host && hit.contains(top.getRootNode().host));
    var coverer = null;
    if (covered) { assign(top); coverer = describeShort(top.closest(INTERACTIVE) || top); }
    var outside = cy < 0 || cy > window.innerHeight || cx < 0 || cx > window.innerWidth;
    return { x: cx, y: cy, w: r.width, h: r.height, vw: window.innerWidth, covered: coverer, outside: outside };
  };

  L.click = function (ref) {
    var t = target(ref);
    if (t.error) return t;
    var el = hitTarget(t.el);
    var r = el.getBoundingClientRect(), init = { bubbles: true, cancelable: true, composed: true, view: el.ownerDocument.defaultView,
      clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, button: 0, isPrimary: true, pointerType: 'touch' };
    try { el.dispatchEvent(new PointerEvent('pointerdown', init)); } catch (e) {}
    el.dispatchEvent(new MouseEvent('mousedown', init));
    if (el.focus) el.focus({ preventScroll: true });
    try { el.dispatchEvent(new PointerEvent('pointerup', init)); } catch (e) {}
    el.dispatchEvent(new MouseEvent('mouseup', init));
    el.click();
    return { ok: true };
  };

  function setValue(el, text) {
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
    el.focus();
    setValue(el, text);
    if (submit) pressKey(el, 'Enter');
    return { ok: true };
  };

  L.select = function (ref, value) {
    var t = target(ref);
    if (t.error) return t;
    var el = t.el;
    if (el.tagName.toLowerCase() !== 'select') return { error: ref + ' is not a native dropdown. Click it to open it, then click the option in the new snapshot.' };
    return choose(el, value, ref);
  };

  function choose(el, value, ref) {
    var want = String(value).trim().toLowerCase();
    for (var i = 0; i < el.options.length; i++) {
      var o = el.options[i];
      if (o.value.toLowerCase() === want || o.text.trim().toLowerCase() === want) {
        el.value = o.value;
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
        return { ok: true };
      }
    }
    return { error: 'No option "' + value + '" in ' + ref + '. Options: ' + Array.prototype.map.call(el.options, function (o) { return o.text.trim(); }).slice(0, 40).join(' | ') };
  }

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
})();
