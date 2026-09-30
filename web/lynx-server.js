/*
 * LYNXgwas Server: shared browser code for every page.
 *
 * - Wraps fetch() so same-origin requests carry the session cookie and, for anything that changes
 *   state, the per-session CSRF token the server checks (X-Lynx-CSRF).
 * - Shows the server's own error messages (sign-in needed, read-only dataset, too busy...).
 * - Account bar + dialogs: sign in, register, verify email code, forgot/reset password, delete account.
 * - Retention countdown bar helper (green -> red) for project cards.
 *
 * Everything user-supplied is inserted with textContent, never innerHTML.
 */
(function () {
  'use strict';

  var state = { me: null };
  var origFetch = window.fetch.bind(window);
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
    var send = function () {
      if (method !== 'GET' && method !== 'HEAD' && state.me && state.me.csrf) {
        var h = new Headers(init.headers || {});
        h.set('X-Lynx-CSRF', state.me.csrf);
        init.headers = h;
      }
      return origFetch(input, init).then(function (resp) {
        if (resp.status === 401 || resp.status === 403 || resp.status === 413 || resp.status === 429) {
          resp.clone().json().then(function (d) {
            if (d && d.error) toast(d.error, 'error');
            if (resp.status === 401) openAuth('login');
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

  // ── Session ──────────────────────────────────────────────────────────
  async function refreshMe() {
    try {
      var r = await origFetch('/api/auth/me', { credentials: 'same-origin' });
      state.me = await r.json();
    } catch (e) {
      state.me = { signed_in: false };
    }
    renderAccountBar();
    document.dispatchEvent(new CustomEvent('lynx:auth', { detail: state.me }));
    return state.me;
  }

  // ── Account bar ──────────────────────────────────────────────────────
  function renderAccountBar() {
    var bar = document.getElementById('lx-account');
    if (!bar) {
      bar = el('div', { id: 'lx-account' });
      var header = document.querySelector('header');
      if (header) header.appendChild(bar); else { bar.classList.add('floating'); document.body.appendChild(bar); }
    }
    bar.textContent = '';
    if (state.me && state.me.signed_in) {
      bar.appendChild(el('span', { className: 'lx-email', title: 'Signed in', text: state.me.email }));
      bar.appendChild(el('button', { className: 'lx-link', onclick: function () { openAuth('account'); }, text: 'Account' }));
      bar.appendChild(el('button', { className: 'lx-btn', onclick: logout, text: 'Sign out' }));
    } else {
      bar.appendChild(el('button', { className: 'lx-link', onclick: function () { openAuth('register'); }, text: 'Create account' }));
      bar.appendChild(el('button', { className: 'lx-btn', onclick: function () { openAuth('login'); }, text: 'Sign in' }));
    }
  }

  async function logout() {
    await postJson('/api/auth/logout', {});
    await refreshMe();
    toast('Signed out.');
    if (window.LYNX_onAuthChange) window.LYNX_onAuthChange();
  }

  // ── Auth dialog ──────────────────────────────────────────────────────
  var dlg = { mode: 'login', email: '' };

  function closeAuth() {
    var o = document.getElementById('lx-auth');
    if (o) o.remove();
  }

  function field(label, type, name, attrs) {
    var input = el('input', Object.assign({ type: type, name: name, required: 'required' }, attrs || {}));
    return el('label', { className: 'lx-field' }, [el('span', { text: label }), input]);
  }

  function openAuth(mode) {
    closeAuth();
    dlg.mode = mode;
    var titleText = {
      login: 'Sign in', register: 'Create an account', verify: 'Check your email',
      forgot: 'Reset your password', reset: 'Choose a new password', account: 'Your account'
    }[mode];
    var form = el('form', { className: 'lx-form', novalidate: 'novalidate' });
    var msg = el('div', { className: 'lx-msg', role: 'alert' });
    var extra = el('div', { className: 'lx-extra' });

    if (mode === 'login') {
      form.appendChild(field('Email', 'email', 'email', { autocomplete: 'email', value: dlg.email }));
      form.appendChild(field('Password', 'password', 'password', { autocomplete: 'current-password' }));
      form.appendChild(el('button', { className: 'lx-btn primary', type: 'submit', text: 'Sign in' }));
      extra.appendChild(el('button', { type: 'button', className: 'lx-link', onclick: function () { openAuth('forgot'); }, text: 'Forgot password?' }));
      extra.appendChild(el('button', { type: 'button', className: 'lx-link', onclick: function () { openAuth('register'); }, text: 'Create an account' }));
    } else if (mode === 'register') {
      form.appendChild(el('p', { className: 'lx-note', text: 'Your projects are private to you and are deleted automatically '
        + ((state.me && state.me.retention_days) || 15) + ' days after you create them. Public datasets need no account.' }));
      form.appendChild(field('Email', 'email', 'email', { autocomplete: 'email', value: dlg.email }));
      form.appendChild(field('Password (at least 10 characters)', 'password', 'password', { autocomplete: 'new-password', minlength: '10' }));
      form.appendChild(field('Repeat password', 'password', 'password2', { autocomplete: 'new-password' }));
      form.appendChild(el('button', { className: 'lx-btn primary', type: 'submit', text: 'Create account' }));
      extra.appendChild(el('button', { type: 'button', className: 'lx-link', onclick: function () { openAuth('login'); }, text: 'I already have an account' }));
    } else if (mode === 'verify') {
      form.appendChild(el('p', { className: 'lx-note', text: 'We sent a 6-digit code to ' + dlg.email + '. Enter it below. It expires in 15 minutes.' }));
      form.appendChild(field('Code', 'text', 'code', { inputmode: 'numeric', autocomplete: 'one-time-code', pattern: '[0-9]{6}', maxlength: '6' }));
      form.appendChild(el('button', { className: 'lx-btn primary', type: 'submit', text: 'Verify email' }));
      extra.appendChild(el('button', { type: 'button', className: 'lx-link', onclick: function () { resend('verify', msg); }, text: 'Send a new code' }));
    } else if (mode === 'forgot') {
      form.appendChild(el('p', { className: 'lx-note', text: 'Enter your email and we will send you a code to choose a new password.' }));
      form.appendChild(field('Email', 'email', 'email', { autocomplete: 'email', value: dlg.email }));
      form.appendChild(el('button', { className: 'lx-btn primary', type: 'submit', text: 'Send code' }));
    } else if (mode === 'reset') {
      form.appendChild(el('p', { className: 'lx-note', text: 'Enter the code sent to ' + dlg.email + ' and your new password.' }));
      form.appendChild(field('Code', 'text', 'code', { inputmode: 'numeric', autocomplete: 'one-time-code', maxlength: '6' }));
      form.appendChild(field('New password (at least 10 characters)', 'password', 'password', { autocomplete: 'new-password' }));
      form.appendChild(field('Repeat new password', 'password', 'password2', { autocomplete: 'new-password' }));
      form.appendChild(el('button', { className: 'lx-btn primary', type: 'submit', text: 'Change password' }));
      extra.appendChild(el('button', { type: 'button', className: 'lx-link', onclick: function () { resend('reset', msg); }, text: 'Send a new code' }));
    } else if (mode === 'account') {
      form.appendChild(el('p', { className: 'lx-note', text: 'Signed in as ' + state.me.email + '.' }));
      form.appendChild(el('p', { className: 'lx-note', text: 'Deleting your account removes it and every one of your projects immediately. This cannot be undone.' }));
      form.appendChild(field('Password (to confirm deletion)', 'password', 'password', { autocomplete: 'current-password' }));
      form.appendChild(el('button', { className: 'lx-btn danger', type: 'submit', text: 'Delete my account and data' }));
      extra.appendChild(el('button', { type: 'button', className: 'lx-link', onclick: function () { openAuth('forgot'); }, text: 'Change password' }));
    }

    form.addEventListener('submit', function (e) { e.preventDefault(); submitAuth(form, msg); });
    var box = el('div', { className: 'lx-dialog', role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'lx-auth-title' }, [
      el('div', { className: 'lx-dialog-head' }, [
        el('h3', { id: 'lx-auth-title', text: titleText }),
        el('button', { type: 'button', className: 'lx-close', 'aria-label': 'Close', onclick: closeAuth, text: '×' })
      ]),
      msg, form, extra
    ]);
    var overlay = el('div', { id: 'lx-auth', className: 'lx-overlay', onclick: function (e) { if (e.target === overlay) closeAuth(); } }, [box]);
    document.body.appendChild(overlay);
    var first = form.querySelector('input');
    if (first) first.focus();
  }

  function showMsg(msg, text, kind) {
    msg.textContent = text || '';
    msg.className = 'lx-msg ' + (kind || '');
  }

  async function resend(purpose, msg) {
    var r = await postJson('/api/auth/resend', { email: dlg.email, purpose: purpose });
    showMsg(msg, r.data.message || r.data.error || 'If a request is pending, a new code has been sent.', r.ok ? 'ok' : 'error');
  }

  async function submitAuth(form, msg) {
    var v = {};
    Array.prototype.forEach.call(form.elements, function (i) { if (i.name) v[i.name] = i.value; });
    var btn = form.querySelector('button[type=submit]');
    btn.disabled = true;
    try {
      if ((dlg.mode === 'register' || dlg.mode === 'reset') && v.password !== v.password2) {
        showMsg(msg, 'The two passwords do not match.', 'error'); return;
      }
      var r;
      if (dlg.mode === 'login') {
        dlg.email = v.email;
        r = await postJson('/api/auth/login', { email: v.email, password: v.password });
        if (r.ok) { closeAuth(); await afterSignIn('Signed in.'); return; }
      } else if (dlg.mode === 'register') {
        dlg.email = v.email;
        r = await postJson('/api/auth/register', { email: v.email, password: v.password });
        if (r.ok) { openAuth('verify'); return; }
      } else if (dlg.mode === 'verify') {
        r = await postJson('/api/auth/verify', { email: dlg.email, code: (v.code || '').trim() });
        if (r.ok) { closeAuth(); await afterSignIn('Email verified. Welcome!'); return; }
      } else if (dlg.mode === 'forgot') {
        dlg.email = v.email;
        r = await postJson('/api/auth/forgot', { email: v.email });
        if (r.ok) { openAuth('reset'); return; }
      } else if (dlg.mode === 'reset') {
        r = await postJson('/api/auth/reset', { email: dlg.email, code: (v.code || '').trim(), password: v.password });
        if (r.ok) { closeAuth(); await afterSignIn('Password changed.'); return; }
      } else if (dlg.mode === 'account') {
        if (!confirm('Delete your account and all of your projects now? This cannot be undone.')) return;
        r = await postJson('/api/my/delete-account', { password: v.password });
        if (r.ok) { closeAuth(); await refreshMe(); toast('Your account and data were deleted.'); if (window.LYNX_onAuthChange) window.LYNX_onAuthChange(); return; }
      }
      showMsg(msg, (r && (r.data.message || r.data.error)) || 'Something went wrong. Try again.', 'error');
    } finally {
      btn.disabled = false;
    }
  }

  async function afterSignIn(text) {
    await refreshMe();
    toast(text, 'ok');
    if (window.LYNX_onAuthChange) window.LYNX_onAuthChange();
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
    signedIn: function () { return !!(state.me && state.me.signed_in); },
    requireSignIn: function (why) {
      if (state.me && state.me.signed_in) return true;
      toast(why || 'Please sign in first.', 'info');
      openAuth('login');
      return false;
    },
    openAuth: openAuth,
    toast: toast,
    refresh: refreshMe,
    countdown: countdown
  };

  function start() { refreshMe().then(function () { readyResolve(); }); }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
})();
