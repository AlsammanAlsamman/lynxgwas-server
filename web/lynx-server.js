/*
 * LYNXgwas Server: shared browser code for every page.
 *
 * - Wraps fetch() so same-origin requests carry the workspace cookie and, for anything that
 *   changes state, the per-workspace CSRF token the server checks (X-Lynx-CSRF).
 * - Shows the server's own error messages (read-only dataset, too busy, server full...).
 * - Anonymous private workspace: no account, email or password. Created the first time it is
 *   needed; a workspace bar + panel to copy a link that opens it elsewhere, forget this browser,
 *   or delete it. Opening a page with #workspace=... opens that workspace here.
 * - Retention countdown bar helper (green -> red) for project cards.
 *
 * Everything user-supplied is inserted with textContent, never innerHTML.
 */
(function () {
  'use strict';

  var state = { me: null };

  // ── Serving under a sub-path (e.g. https://host/lynxgwas/) ─────────────
  // Pages call the API as '/api/...'. BASE is the folder the page was loaded from ('' at the site
  // root); same-origin URLs that don't already start with it get it prepended, for fetch, XHR,
  // window.open and link clicks. At the root this changes nothing.
  var BASE = location.pathname.replace(/\/[^\/]*$/, '');
  function withBase(url) {
    if (!BASE || typeof url !== 'string' || url === '') return url;
    try {
      var u = new URL(url, location.href);
      if (u.origin !== location.origin || u.pathname === BASE || u.pathname.indexOf(BASE + '/') === 0) return url;
      return u.origin + BASE + u.pathname + u.search + u.hash;
    } catch (e) { return url; }
  }
  if (BASE) {
    var origOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (method, url) {
      var args = Array.prototype.slice.call(arguments);
      args[1] = withBase(String(url));
      return origOpen.apply(this, args);
    };
    var origWindowOpen = window.open;
    window.open = function (url) {
      var args = Array.prototype.slice.call(arguments);
      if (url) args[0] = withBase(String(url));
      return origWindowOpen.apply(window, args);
    };
    var fixLink = function (e) {
      var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
      if (a && a.getAttribute('href').charAt(0) === '/') a.href = withBase(a.getAttribute('href'));
    };
    document.addEventListener('click', fixLink, true);
    document.addEventListener('auxclick', fixLink, true);
  }

  var origFetch = function (input, init) {
    if (typeof input === 'string') input = withBase(input);
    else if (input && input.url && withBase(input.url) !== input.url) input = new Request(withBase(input.url), input);
    return window.__lynxNativeFetch(input, init);
  };
  window.__lynxNativeFetch = window.fetch.bind(window);
  var readyResolve;
  var ready = new Promise(function (r) { readyResolve = r; });

  function sameOrigin(url) {
    try { return new URL(url, location.href).origin === location.origin; } catch (e) { return false; }
  }

  window.fetch = function (input, init) {
    init = Object.assign({}, init || {});
    var url = typeof input === 'string' ? input : (input && input.url) || '';
    var method = String(init.method || (input && input.method) || 'GET').toUpperCase();
    if (!sameOrigin(url)) return origFetch(input, init);
    init.credentials = 'same-origin';
    var retried = false;
    var send = function () {
      if (method !== 'GET' && method !== 'HEAD' && state.me && state.me.csrf) {
        var h = new Headers(init.headers || {});
        h.set('X-Lynx-CSRF', state.me.csrf);
        init.headers = h;
      }
      return origFetch(input, init).then(function (resp) {
        // An action that needs the workspace, from a browser that has none yet: create it, resend once
        var changesState = method !== 'GET' && method !== 'HEAD';
        if (resp.status === 401 && changesState && !retried && !/\/api\/auth\//.test(url)) {
          retried = true;
          return ensureWorkspace().then(function (ok) { return ok ? send() : resp; });
        }
        if (resp.status === 401 || resp.status === 403 || resp.status === 413 || resp.status === 429 || resp.status === 507) {
          resp.clone().json().then(function (d) {
            if (d && d.error) toast(d.error, 'error');
          }).catch(function () {});
        }
        return resp;
      });
    };
    // State-changing calls wait for the session lookup so the CSRF token is known
    return (method === 'GET' || method === 'HEAD') ? send() : ready.then(send);
  };

  // ── Small DOM helpers ────────────────────────────────────────────────
  function el(tag, attrs, children) {
    var e = document.createElement(tag);
    if (attrs) Object.keys(attrs).forEach(function (k) {
      if (k === 'text') e.textContent = attrs[k];
      else if (k === 'className') e.className = attrs[k];
      else if (k.slice(0, 2) === 'on') e.addEventListener(k.slice(2), attrs[k]);
      else e.setAttribute(k, attrs[k]);
    });
    (children || []).forEach(function (c) { if (c) e.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); });
    return e;
  }

  function toast(msg, kind) {
    var box = document.getElementById('lx-toasts');
    if (!box) { box = el('div', { id: 'lx-toasts' }); document.body.appendChild(box); }
    var t = el('div', { className: 'lx-toast ' + (kind || 'info'), role: 'status', text: msg });
    box.appendChild(t);
    setTimeout(function () { t.classList.add('gone'); setTimeout(function () { t.remove(); }, 400); }, 5000);
  }

  async function postJson(url, body) {
    var r = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) });
    var d = {};
    try { d = await r.json(); } catch (e) {}
    return { ok: r.ok && d.ok !== false, status: r.status, data: d };
  }

  // ── Workspace state ──────────────────────────────────────────────────
  async function refreshMe() {
    try {
      var r = await origFetch('/api/auth/me', { credentials: 'same-origin' });
      state.me = await r.json();
    } catch (e) {
      state.me = { signed_in: false };
    }
    renderWorkspaceBar();
    document.dispatchEvent(new CustomEvent('lynx:auth', { detail: state.me }));
    return state.me;
  }

  function hasWorkspace() { return !!(state.me && state.me.signed_in); }
  function days(key, fallback) { return (state.me && state.me[key]) || fallback; }
  function changed() { if (window.LYNX_onAuthChange) window.LYNX_onAuthChange(); }

  /** Creates this browser's private workspace if it has none. Resolves to true when there is one. */
  async function ensureWorkspace() {
    if (hasWorkspace()) return true;
    var r = await postJson('/api/auth/start', {});
    if (!r.ok) return false;          // the server's message was already shown
    await refreshMe();
    if (hasWorkspace()) toast('Your private workspace is ready. Only this browser can open it.', 'ok');
    return hasWorkspace();
  }

  /**
   * Opens the workspace a link points to (#workspace=...) in this browser, always after asking:
   * someone could send a link to their own workspace and then see whatever the visitor uploads.
   */
  async function openFromLink(link) {
    var warning = 'Open the workspace from this link?\n\n'
      + 'Only continue if YOU made this link. Anyone who has it can see the projects and everything you upload into this workspace.';
    if (hasWorkspace()) warning += '\n\nThis browser will also stop opening its current workspace. Make a link to it first if you still need it.';
    if (!confirm(warning)) { toast('The workspace link was not opened.'); return false; }
    var r = await postJson('/api/auth/open', { link: link });
    if (!r.ok) { toast(r.data.error || 'This workspace link is not valid.', 'error'); return false; }
    await refreshMe();
    toast('Workspace opened in this browser.', 'ok');
    changed();
    return true;
  }

  // ── Workspace bar ────────────────────────────────────────────────────
  function renderWorkspaceBar() {
    var bar = document.getElementById('lx-account');
    if (!bar) {
      bar = el('div', { id: 'lx-account' });
      var header = document.querySelector('header');
      if (header) header.appendChild(bar); else { bar.classList.add('floating'); document.body.appendChild(bar); }
    }
    bar.textContent = '';
    if (hasWorkspace()) {
      bar.appendChild(el('span', { className: 'lx-email', title: 'Projects you create are private to this workspace', text: 'Private workspace' }));
      bar.appendChild(el('button', { className: 'lx-btn', onclick: function () { openPanel('workspace'); }, text: 'Workspace' }));
    } else {
      bar.appendChild(el('button', { className: 'lx-link', onclick: function () { openPanel('open'); }, text: 'I have a workspace link' }));
    }
  }

  // ── Workspace panel ──────────────────────────────────────────────────
  function closePanel() {
    var o = document.getElementById('lx-auth');
    if (o) o.remove();
  }

  function openPanel(mode) {
    closePanel();
    var body = el('div', { className: 'lx-form' });
    var msg = el('div', { className: 'lx-msg', role: 'alert' });
    var title;

    if (mode === 'workspace') {
      title = 'Your private workspace';
      body.appendChild(el('p', { className: 'lx-note', text: 'No account, email or password: your projects belong to this browser. '
        + 'Nobody else can see them. Each project is deleted automatically ' + days('retention_days', 15)
        + ' days after you create it; download what you want to keep.' }));
      body.appendChild(el('p', { className: 'lx-note', text: 'To use the same workspace on another computer or browser, make a link and open it there. '
        + 'Anyone who has the link has full access to your projects, so keep it private and never open a link someone else sent you. '
        + 'Making a new link stops the old one from working.' }));
      var linkBox = el('div', { className: 'lx-extra' });
      body.appendChild(el('button', { type: 'button', className: 'lx-btn primary', onclick: function () { makeLink(linkBox, msg); },
        text: 'Make a link to this workspace' }));
      body.appendChild(linkBox);
      body.appendChild(el('hr'));
      body.appendChild(el('button', { type: 'button', className: 'lx-btn', onclick: forgetBrowser, text: 'Forget this browser' }));
      body.appendChild(el('p', { className: 'lx-note', text: 'This browser stops opening the workspace. It still exists and opens with its link.' }));
      body.appendChild(el('button', { type: 'button', className: 'lx-btn danger', onclick: deleteWorkspace, text: 'Delete workspace and all projects' }));
    } else {
      title = 'Open a workspace';
      body.appendChild(el('p', { className: 'lx-note', text: 'Paste the workspace link you made in another browser. Do not use a link someone else sent you: '
        + 'its owner would see everything you upload.' }));
      var input = el('input', { type: 'text', name: 'link', autocomplete: 'off', spellcheck: 'false', placeholder: location.origin + '/#workspace=...' });
      body.appendChild(el('label', { className: 'lx-field' }, [el('span', { text: 'Workspace link' }), input]));
      body.appendChild(el('button', { type: 'button', className: 'lx-btn primary', text: 'Open workspace', onclick: async function () {
        if (await openFromLink(input.value)) closePanel();
      } }));
    }

    var box = el('div', { className: 'lx-dialog', role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'lx-auth-title' }, [
      el('div', { className: 'lx-dialog-head' }, [
        el('h3', { id: 'lx-auth-title', text: title }),
        el('button', { type: 'button', className: 'lx-close', 'aria-label': 'Close', onclick: closePanel, text: '×' })
      ]),
      msg, body
    ]);
    var overlay = el('div', { id: 'lx-auth', className: 'lx-overlay', onclick: function (e) { if (e.target === overlay) closePanel(); } }, [box]);
    document.body.appendChild(overlay);
    var first = body.querySelector('input, button');
    if (first) first.focus();
  }

  function showMsg(msg, text, kind) {
    msg.textContent = text || '';
    msg.className = 'lx-msg ' + (kind || '');
  }

  async function makeLink(box, msg) {
    var r = await postJson('/api/my/link', {});
    if (!r.ok || !r.data.link) { showMsg(msg, r.data.error || 'Could not make a link. Try again.', 'error'); return; }
    box.textContent = '';
    var out = el('input', { type: 'text', readonly: 'readonly', value: r.data.link, 'aria-label': 'Workspace link' });
    box.appendChild(out);
    box.appendChild(el('button', { type: 'button', className: 'lx-btn', text: 'Copy', onclick: async function () {
      try { await navigator.clipboard.writeText(r.data.link); toast('Link copied. Keep it private.', 'ok'); }
      catch (e) { out.select(); toast('Select the link and copy it.', 'info'); }
    } }));
    out.select();
    showMsg(msg, 'New link made. Any older link no longer works.', 'ok');
  }

  async function forgetBrowser() {
    if (!confirm('Forget this workspace in this browser?\n\nYou can only open it again with its link. Without a link, its projects cannot be reached any more.')) return;
    var r = await postJson('/api/my/forget-browser', {});
    if (!r.ok) return;
    closePanel();
    await refreshMe();
    toast('This browser no longer opens the workspace.');
    changed();
  }

  async function deleteWorkspace() {
    if (!confirm('Delete this workspace and all of its projects now, everywhere? This cannot be undone.')) return;
    var r = await postJson('/api/my/delete-workspace', {});
    if (!r.ok) return;
    closePanel();
    await refreshMe();
    toast('Your workspace and its data were deleted.');
    changed();
  }

  // ── Retention countdown (green -> red) ───────────────────────────────
  function countdown(createdAt, expiresAt) {
    var now = Date.now();
    var total = Math.max(1, expiresAt - createdAt);
    var left = Math.max(0, expiresAt - now);
    var frac = Math.max(0, Math.min(1, left / total));
    var hue = Math.round(120 * frac);                 // 120 = green ... 0 = red
    var days = left >= 3 * 86400000 ? Math.round(left / 86400000) : Math.floor(left / 86400000);
    var hours = Math.floor((left % 86400000) / 3600000);
    var label = left <= 0 ? 'Deleting now' : days >= 1 ? 'Deleted in ' + days + ' day' + (days === 1 ? '' : 's') + (days < 3 ? ' ' + hours + ' h' : '')
      : 'Deleted in ' + Math.max(1, hours) + ' hour' + (hours === 1 ? '' : 's');
    var wrap = el('div', { className: 'lx-countdown', title: 'Deleted automatically on ' + new Date(expiresAt).toLocaleString() });
    var bar = el('div', { className: 'lx-countdown-bar' });
    var fill = el('div', { className: 'lx-countdown-fill' });
    fill.style.width = (frac * 100).toFixed(1) + '%';
    fill.style.background = 'hsl(' + hue + ', 70%, 45%)';
    bar.appendChild(fill);
    wrap.appendChild(bar);
    var txt = el('div', { className: 'lx-countdown-text', text: label });
    txt.style.color = 'hsl(' + hue + ', 70%, 32%)';
    wrap.appendChild(txt);
    return wrap;
  }

  window.LYNX = {
    ready: ready,
    me: function () { return state.me; },
    signedIn: hasWorkspace,
    /**
     * True when this browser already has its workspace. Otherwise creates it in the background,
     * then calls {@code retry} (if given) so the action continues, and returns false now.
     */
    requireSignIn: function (why, retry) {
      if (hasWorkspace()) return true;
      ensureWorkspace().then(function (ok) { if (ok) { changed(); if (retry) retry(); } });
      return false;
    },
    ensureWorkspace: ensureWorkspace,
    openAuth: openPanel,
    toast: toast,
    refresh: refreshMe,
    countdown: countdown
  };

  async function start() {
    var m = /[#&]workspace=([A-Za-z0-9_-]{32,100})/.exec(location.hash);
    if (m) history.replaceState(null, '', location.pathname + location.search);   // the token leaves the address bar first
    await refreshMe();
    readyResolve();   // before opening a link: state-changing requests wait for this
    if (m) await openFromLink(m[1]);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
})();
