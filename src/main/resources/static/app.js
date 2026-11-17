'use strict';
// Plain JS client, no build step. Server text is always inserted with textContent;
// innerHTML is used only for icon SVGs from icons.js.
const $ = (id) => document.getElementById(id);
const state = { key: '', spaces: [], selected: new Set(), conversationId: null, busy: false };

const SUGGESTIONS = [
  "What's the refund window for annual plans?",
  'What does error NW-4291 mean?',
  'How many days of annual leave do I get?',
];

function store(k, v) { try { v ? localStorage.setItem(k, v) : localStorage.removeItem(k); } catch (e) { /* storage unavailable */ } }
function load(k) { try { return localStorage.getItem(k) || ''; } catch (e) { return ''; } }
function el(tag, cls, text) {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text !== undefined) n.textContent = text;
  return n;
}
function iconEl(name, size) {
  const s = el('span', 'i');
  s.innerHTML = window.icon(name, size);
  return s;
}
function paintIcons() {
  document.querySelectorAll('[data-icon]').forEach((n) => { n.innerHTML = window.icon(n.dataset.icon, 14); });
  $('send').innerHTML = window.icon('send-horizontal', 16);
  $('sign-out').innerHTML = window.icon('log-out', 16);
}

async function api(path, opts = {}) {
  const res = await fetch(path, { ...opts, headers: { 'X-API-Key': state.key, ...(opts.headers || {}) } });
  if (!res.ok) {
    let detail = res.status === 401 ? 'That key was not recognised.' : res.status + ' ' + res.statusText;
    try { const p = await res.json(); if (p.detail) detail = p.detail; } catch (e) { /* not json */ }
    throw new Error(detail);
  }
  return res.status === 204 ? null : res.json();
}

/* ---------- Session ---------- */
async function connect(e) {
  if (e) e.preventDefault();
  const key = $('api-key').value.trim();
  if (!key) return;
  state.key = key;
  try {
    const me = await api('/api/v1/me');
    store('apiKey', key);
    state.spaces = me.spaces.filter((s) => s !== '*').sort();
    state.selected = new Set(state.spaces);
    $('app').dataset.state = 'signed-in';
    $('sign-out').hidden = false;
    $('who').textContent = 'Signed in as ' + me.name;
    $('connect-error').textContent = '';
    renderSpaces();
    refreshDocuments();
    $('question').focus();
  } catch (err) {
    state.key = '';
    $('connect-error').textContent = err.message;
  }
}

function signOut() {
  store('apiKey', '');
  state.key = '';
  state.conversationId = null;
  $('api-key').value = '';
  $('app').dataset.state = 'signed-out';
  $('sign-out').hidden = true;
  resetThread();
}

/* ---------- Spaces and documents ---------- */
function renderSpaces() {
  $('spaces').replaceChildren(...state.spaces.map((s) => {
    const row = el('label', 'space');
    const box = el('input');
    box.type = 'checkbox';
    box.checked = state.selected.has(s);
    box.onchange = () => { box.checked ? state.selected.add(s) : state.selected.delete(s); };
    row.append(box, el('span', '', s));
    return row;
  }));
  $('upload-space').replaceChildren(...state.spaces.map((s) => {
    const o = el('option', '', 'Upload to ' + s);
    o.value = s;
    return o;
  }));
}

let docTimer = null;
async function refreshDocuments() {
  clearTimeout(docTimer);
  const items = [];
  let pending = false;
  try {
    for (const s of state.spaces) {
      for (const d of await api(`/api/v1/spaces/${encodeURIComponent(s)}/documents`)) {
        if (d.status === 'SUPERSEDED') continue;
        pending = pending || d.status === 'QUEUED' || d.status === 'INDEXING';
        const li = el('li', 'doc');
        const name = el('div');
        name.append(el('span', 'doc__name', d.sourceName),
          el('span', 'doc__meta', `${s} · v${d.version}${d.chunkCount ? ' · ' + d.chunkCount + ' chunks' : ''}`));
        name.firstChild.style.display = 'block';
        const status = el('span', 'status', d.status.charAt(0) + d.status.slice(1).toLowerCase());
        status.dataset.s = d.status;
        if (d.error) status.title = d.error;
        li.append(iconEl('file-text', 16), name, status);
        items.push(li);
      }
    }
  } catch (err) {
    items.push(el('li', 'doc-empty error', err.message));
  }
  $('documents').replaceChildren(...(items.length ? items : [el('li', 'doc-empty', 'No documents yet. Upload a PDF, DOCX, Markdown or text file.')]));
  if (pending) docTimer = setTimeout(refreshDocuments, 1000);
}

async function upload() {
  const file = $('upload-file').files[0];
  if (!file) return;
  const body = new FormData();
  body.append('file', file);
  try {
    await api(`/api/v1/spaces/${encodeURIComponent($('upload-space').value)}/documents`, { method: 'POST', body });
  } catch (err) {
    alert('Upload failed: ' + err.message);
  }
  $('upload-file').value = '';
  refreshDocuments();
}

/* ---------- Thread ---------- */
function resetThread() {
  $('thread').replaceChildren($('empty') || buildEmpty());
  renderSources([], null);
}

function buildEmpty() {
  const box = el('div', 'empty');
  box.id = 'empty';
  return box;
}

function renderSuggestions() {
  $('suggestions').replaceChildren(...SUGGESTIONS.map((q) => {
    const b = el('button', 'suggestion', q);
    b.type = 'button';
    b.onclick = () => { $('question').value = q; $('ask-form').requestSubmit(); };
    return b;
  }));
}

function renderAnswerText(container, text) {
  container.replaceChildren();
  for (const part of text.split(/(\[\d{1,3}\])/)) {
    const m = /^\[(\d{1,3})\]$/.exec(part);
    if (m) {
      const c = el('button', 'cite', m[1]);
      c.type = 'button';
      c.title = 'Show source ' + m[1];
      c.onclick = () => flashSource(Number(m[1]));
      container.append(c);
    } else {
      container.append(document.createTextNode(part));
    }
  }
}

function flashSource(n) {
  const li = document.querySelector(`#sources .src[data-n="${n}"]`);
  if (!li) return;
  li.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  li.dataset.flash = '';
  setTimeout(() => delete li.dataset.flash, 1100);
}

/**
 * Highlights, in a cited excerpt, the identifiers and figures the answer used: the same
 * evidence the server's verifier checks numbers against.
 */
function highlighted(text, answer) {
  const plain = (answer || '').replace(/\[\d{1,3}\]/g, ' ');
  const tokens = new Set([
    ...(plain.match(/\b[A-Z][A-Z0-9]*-\d+\b/g) || []),
    ...(plain.match(/\d[\d,]*(?:\.\d+)?%?/g) || []),
  ]);
  const frag = document.createDocumentFragment();
  if (!tokens.size) { frag.append(text); return frag; }
  const escaped = [...tokens].sort((a, b) => b.length - a.length).map((n) => n.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
  const re = new RegExp('(?<![\\w.-])(' + escaped.join('|') + ')(?![\\d])(\\s+(?:days?|weeks?|months?|hours?|USD|per\\s\\w+))?', 'g');
  let last = 0;
  for (const m of text.matchAll(re)) {
    frag.append(text.slice(last, m.index));
    frag.append(el('mark', '', m[0]));
    last = m.index + m[0].length;
  }
  frag.append(text.slice(last));
  return frag;
}

function renderSources(sources, answer) {
  $('sources-empty').hidden = !!(sources && sources.length);
  if (!sources || !sources.length) {
    $('sources-empty').textContent = answer === null
      ? 'The passages an answer uses appear here, with the cited parts highlighted.'
      : 'No passages matched this question, so nothing was sent to the model.';
    $('sources').replaceChildren();
    return;
  }
  $('sources').replaceChildren(...sources.map((s) => {
    const li = el('li', 'src');
    li.dataset.n = s.number;
    if (s.cited) li.dataset.cited = '';
    const head = el('div', 'src__head');
    const name = el('div');
    const file = el('span', 'src__file', s.sourceName);
    file.style.display = 'block';
    name.append(file, el('span', 'src__where', [s.section, s.page ? 'page ' + s.page : null, s.space].filter(Boolean).join(' · ')));
    head.append(el('span', 'src__n', String(s.number)), name);
    const ex = el('p', 'src__excerpt');
    ex.append(s.cited ? highlighted(s.excerpt, answer) : s.excerpt);
    li.append(head, ex);
    return li;
  }));
}

function renderFoot(box, a) {
  const foot = el('div', 'a__foot');
  const v = a.verification;
  if (v && v.refusal) {
    foot.append(el('span', 'pill pill--quiet', 'Not in your documents'));
  } else if (v && v.grounded) {
    const p = el('span', 'pill pill--ok');
    p.append(iconEl('check', 12), document.createTextNode('Verified against sources'));
    foot.append(p);
  } else if (v) {
    const p = el('span', 'pill pill--warn');
    p.append(iconEl('circle-alert', 12), document.createTextNode('Not fully verified'));
    foot.append(p);
  }
  const level = a.confidence.level.toLowerCase();
  const total = Object.values(a.timingsMs || {}).reduce((x, y) => x + y, 0);
  foot.append(el('span', 'pill pill--quiet',
    [level === 'none' ? null : level + ' confidence', a.cached ? 'cached' : null, total + ' ms'].filter(Boolean).join(' · ')));

  const fb = el('div', 'feedback');
  for (const [name, rating, label] of [['thumbs-up', 1, 'Helpful'], ['thumbs-down', -1, 'Not helpful']]) {
    const b = el('button', 'icon-btn');
    b.type = 'button';
    b.title = label;
    b.setAttribute('aria-label', label);
    b.setAttribute('aria-pressed', 'false');
    b.innerHTML = window.icon(name, 14);
    b.onclick = async () => {
      try {
        await api(`/api/v1/answers/${a.answerId}/feedback`, {
          method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ rating }) });
        fb.querySelectorAll('button').forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
      } catch (err) { b.title = err.message; }
    };
    fb.append(b);
  }
  foot.append(fb);
  box.append(foot);

  // Narrow screens hide the Sources panel, so cited files are listed under the answer.
  const cited = (a.sources || []).filter((s) => s.cited);
  if (cited.length) {
    const list = el('ol', 'a__sources');
    for (const s of cited) {
      const li = el('li');
      li.append(el('span', 'src__n', String(s.number)),
        el('span', '', s.sourceName + (s.section ? ' · ' + s.section : '')));
      list.append(li);
    }
    box.append(list);
  }

  if (a.notice && !(v && v.refusal)) {
    const n = el('div', 'a__notice');
    n.append(iconEl('circle-alert', 14), el('span', '', a.notice));
    box.append(n);
  }
}

async function ask(e) {
  e.preventDefault();
  const question = $('question').value.trim();
  if (!question || !state.key || state.busy) return;
  state.busy = true;
  $('send').disabled = true;
  $('question').value = '';
  autosize();
  const empty = $('empty');
  if (empty) empty.remove();

  $('thread').append(el('div', 'q', question));
  const box = el('div', 'a');
  const status = el('div', 'a__status');
  status.append(iconEl('search', 14), el('span', '', 'Searching ' + [...state.selected].join(', ')));
  const text = el('div', 'a__text');
  box.append(status);
  $('thread').append(box);
  $('thread').scrollTop = $('thread').scrollHeight;

  let answer = '';
  try {
    const res = await fetch('/api/v1/chat/stream', {
      method: 'POST',
      headers: { 'X-API-Key': state.key, 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ question, conversationId: state.conversationId, spaces: [...state.selected] }),
    });
    if (!res.ok) {
      let detail = res.status === 429 ? 'Too many questions at once. Wait a moment and ask again.' : res.statusText;
      try { detail = (await res.json()).detail || detail; } catch (err) { /* ignore */ }
      throw new Error(detail);
    }
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let idx;
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const raw = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 2);
        const event = (/^event:(.*)$/m.exec(raw) || [])[1];
        const data = raw.split('\n').filter((l) => l.startsWith('data:')).map((l) => l.slice(5)).join('\n');
        if (!event || !data) continue;
        const payload = JSON.parse(data);
        if (event === 'meta') {
          state.conversationId = payload.conversationId;
          renderSources(payload.sources, '');
        } else if (event === 'token') {
          if (!answer) status.replaceWith(text);
          answer += payload.text;
          renderAnswerText(text, answer);
        } else if (event === 'done') {
          if (!answer) status.replaceWith(text);
          renderAnswerText(text, payload.answer);
          renderSources(payload.sources, payload.answer);
          renderFoot(box, payload);
        } else if (event === 'error') {
          throw new Error(payload.message);
        }
      }
      $('thread').scrollTop = $('thread').scrollHeight;
    }
  } catch (err) {
    box.replaceChildren(el('div', 'error', err.message));
  } finally {
    state.busy = false;
    $('send').disabled = false;
    $('question').focus();
  }
}

function autosize() {
  const t = $('question');
  t.style.height = 'auto';
  t.style.height = Math.min(t.scrollHeight, 160) + 'px';
}

paintIcons();
renderSuggestions();
$('key-form').addEventListener('submit', connect);
$('sign-out').addEventListener('click', signOut);
$('upload-file').addEventListener('change', upload);
$('ask-form').addEventListener('submit', ask);
$('question').addEventListener('input', autosize);
$('question').addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); $('ask-form').requestSubmit(); }
});
const saved = load('apiKey');
if (saved) { $('api-key').value = saved; connect(); }
