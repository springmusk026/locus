// Injected into pages the agent drives. Builds a compact, ref-based view of the page and performs
// actions on elements by ref. Stays idempotent: running it again on the same page is a no-op.
(function () {
  if (window.__locus) return;
  var L = window.__locus = { next: 1, lastMutation: Date.now(), pending: 0 };

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
    '[role=button],[role=link],[role=checkbox],[role=radio],[role=tab],[role=menuitem],[role=option],[role=switch],' +
    '[role=combobox],[role=textbox],[role=searchbox],[role=slider],[onclick],[tabindex]:not([tabindex="-1"])';
  var SENSITIVE_AUTOCOMPLETE = /cc-|one-time-code|current-password|new-password/;

  function visible(el) {
    var r = el.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) return false;
    var s = getComputedStyle(el);
    return s.visibility !== 'hidden' && s.display !== 'none' && parseFloat(s.opacity || '1') > 0.05;
  }

  function nearViewport(el, all) {
    if (all) return true;
    var r = el.getBoundingClientRect(), h = window.innerHeight;
    return r.bottom > -h * 0.5 && r.top < h * 1.5;
  }

  function role(el) {
    var explicit = el.getAttribute('role');
    if (explicit) return explicit;
    var tag = el.tagName.toLowerCase();
    if (tag === 'a') return 'link';
    if (tag === 'select') return 'combobox';
    if (tag === 'textarea') return 'textbox';
    if (tag === 'summary') return 'button';
    if (tag === 'input') {
      var t = (el.type || 'text').toLowerCase();
      if (t === 'checkbox' || t === 'radio') return t;
      if (t === 'submit' || t === 'button' || t === 'reset' || t === 'image') return 'button';
      if (t === 'search') return 'searchbox';
      return 'textbox';
    }
    if (el.isContentEditable) return 'textbox';
    return tag;
  }

  function clean(s, n) {
    s = (s || '').replace(/\s+/g, ' ').trim();
    return s.length > n ? s.slice(0, n - 1) + '…' : s;
  }

  function name(el) {
    var label = el.getAttribute('aria-label');
    if (label) return clean(label, 80);
    var by = el.getAttribute('aria-labelledby');
    if (by) {
      var t = by.split(/\s+/).map(function (id) { var x = document.getElementById(id); return x ? x.innerText : ''; }).join(' ');
      if (t.trim()) return clean(t, 80);
    }
    if (el.labels && el.labels.length) return clean(el.labels[0].innerText, 80);
    var text = el.innerText;
    if (text && text.trim()) return clean(text, 80);
    return clean(el.getAttribute('placeholder') || el.getAttribute('title') || el.getAttribute('alt') ||
      (el.querySelector && el.querySelector('img[alt]') ? el.querySelector('img[alt]').alt : '') || el.getAttribute('name') || '', 80);
  }

  function sensitive(el) {
    return el.type === 'password' || SENSITIVE_AUTOCOMPLETE.test(el.getAttribute('autocomplete') || '');
  }

  function describe(el, ref, isNew) {
    var r = role(el), parts = [(isNew ? '*' : '') + '[' + ref + ']', r];
    var n = name(el);
    if (n) parts.push(JSON.stringify(n));
    var tag = el.tagName.toLowerCase();
    if (tag === 'input' || tag === 'textarea') {
      if (el.type && tag === 'input' && ['text', 'search', 'checkbox', 'radio', 'submit', 'button'].indexOf(el.type) < 0) parts.push('type=' + el.type);
      if (sensitive(el)) parts.push(el.value ? 'value=<hidden, user must enter>' : 'sensitive');
      else if (el.value && r !== 'button') parts.push('value=' + JSON.stringify(clean(el.value, 60)));
      if (el.checked) parts.push('checked');
    }
    if (tag === 'select' && el.selectedOptions && el.selectedOptions[0]) parts.push('selected=' + JSON.stringify(clean(el.selectedOptions[0].text, 40)));
    if (el.disabled || el.getAttribute('aria-disabled') === 'true') parts.push('disabled');
    if (el.getAttribute('aria-expanded')) parts.push('expanded=' + el.getAttribute('aria-expanded'));
    if (tag === 'a') {
      var href = el.getAttribute('href') || '';
      if (href && href.indexOf('javascript:') !== 0 && href !== '#') parts.push('href=' + clean(href, 80));
    }
    return parts.join(' ');
  }

  function roots() {
    var out = [document];
    (function walk(root) {
      root.querySelectorAll('*').forEach(function (el) {
        if (el.shadowRoot) { out.push(el.shadowRoot); walk(el.shadowRoot); }
      });
    })(document);
    document.querySelectorAll('iframe').forEach(function (f) {
      try { if (f.contentDocument) out.push(f.contentDocument); } catch (e) {}
    });
    return out;
  }

  L.find = function (ref) {
    var rs = roots();
    for (var i = 0; i < rs.length; i++) {
      var el = rs[i].querySelector('[data-locus-ref="' + ref + '"]');
      if (el) return el;
    }
    return null;
  };

  L.snapshot = function (all) {
    var lines = [], count = 0, max = all ? 600 : 250;
    var scrollable = document.documentElement.scrollHeight - window.innerHeight;
    lines.push('Page: ' + clean(document.title, 120));
    lines.push('URL: ' + location.href);
    lines.push('Scroll: ' + (scrollable > 0 ? Math.round(100 * window.scrollY / scrollable) + '% of page' : 'whole page visible') +
      (all ? '' : ' (showing elements near the screen; use mode "full" for all)'));
    var dialog = document.querySelector('dialog[open],[role=dialog],[aria-modal=true]');
    if (dialog && visible(dialog)) lines.push('A dialog is open: ' + JSON.stringify(clean(dialog.innerText, 120)));
    lines.push('');
    roots().forEach(function (root) {
      root.querySelectorAll('h1,h2,h3,' + INTERACTIVE).forEach(function (el) {
        if (count >= max || !visible(el) || !nearViewport(el, all)) return;
        var tag = el.tagName.toLowerCase();
        if (/^h[1-3]$/.test(tag)) { lines.push('#'.repeat(+tag[1]) + ' ' + clean(el.innerText, 100)); return; }
        var ref = el.getAttribute('data-locus-ref'), isNew = false;
        if (!ref) { ref = 'e' + (L.next++); el.setAttribute('data-locus-ref', ref); isNew = true; }
        lines.push(describe(el, ref, isNew));
        count++;
      });
    });
    if (count >= max) lines.push('…more elements not shown; scroll or use mode "full".');
    return lines.join('\n');
  };

  function target(ref) {
    var el = L.find(ref);
    if (!el) return { error: 'No element ' + ref + ' on this page. Take a new snapshot.' };
    el.scrollIntoView({ block: 'center', inline: 'center' });
    return { el: el };
  }

  L.rect = function (ref) {
    var t = target(ref);
    if (t.error) return t;
    var r = t.el.getBoundingClientRect();
    var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
    var top = document.elementFromPoint(cx, cy);
    var covered = top && top !== t.el && !t.el.contains(top) && !top.contains(t.el);
    return { x: cx, y: cy, covered: covered ? clean(top.tagName.toLowerCase() + ' ' + (top.innerText || ''), 60) : null,
      label: name(t.el), role: role(t.el), form: !!(t.el.form || t.el.closest('form')) };
  };

  L.click = function (ref) {
    var t = target(ref);
    if (t.error) return t;
    var el = t.el;
    ['pointerdown', 'mousedown', 'pointerup', 'mouseup'].forEach(function (type) {
      el.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, view: window }));
    });
    el.click();
    return { ok: true };
  };

  L.type = function (ref, text, submit) {
    var t = target(ref);
    if (t.error) return t;
    var el = t.el;
    el.focus();
    var tag = el.tagName.toLowerCase();
    if (tag === 'input' || tag === 'textarea') {
      var proto = tag === 'input' ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype;
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, text);
      el.dispatchEvent(new InputEvent('input', { bubbles: true, data: text, inputType: 'insertText' }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
    } else if (el.isContentEditable) {
      document.execCommand('selectAll', false, null);
      document.execCommand('insertText', false, text);
    } else {
      return { error: ref + ' is not a text field.' };
    }
    if (submit) {
      ['keydown', 'keypress', 'keyup'].forEach(function (type) {
        el.dispatchEvent(new KeyboardEvent(type, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
      });
      var form = el.form || el.closest('form');
      if (form) { if (form.requestSubmit) form.requestSubmit(); else form.submit(); }
    }
    return { ok: true };
  };

  L.select = function (ref, value) {
    var t = target(ref);
    if (t.error) return t;
    var el = t.el;
    if (el.tagName.toLowerCase() !== 'select') return { error: ref + ' is not a dropdown; click it and pick an option instead.' };
    var want = String(value).toLowerCase();
    for (var i = 0; i < el.options.length; i++) {
      var o = el.options[i];
      if (o.value.toLowerCase() === want || o.text.trim().toLowerCase() === want) {
        el.value = o.value;
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
        return { ok: true };
      }
    }
    return { error: 'No option "' + value + '". Options: ' + Array.prototype.map.call(el.options, function (o) { return o.text.trim(); }).join(' | ') };
  };

  L.key = function (key) {
    var el = document.activeElement || document.body;
    ['keydown', 'keypress', 'keyup'].forEach(function (type) {
      el.dispatchEvent(new KeyboardEvent(type, { key: key, bubbles: true }));
    });
    return { ok: true };
  };

  L.scroll = function (direction, ref) {
    var dy = (direction === 'up' ? -1 : 1) * window.innerHeight * 0.8;
    if (ref) {
      var el = L.find(ref);
      if (!el) return { error: 'No element ' + ref + '.' };
      el.scrollBy({ top: dy });
    } else if (direction === 'top') {
      window.scrollTo(0, 0);
    } else if (direction === 'bottom') {
      window.scrollTo(0, document.documentElement.scrollHeight);
    } else {
      window.scrollBy(0, dy);
    }
    return { ok: true };
  };
})();
