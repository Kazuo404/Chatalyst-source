/* Progressive enhancement only. Content and navigation are rendered by the build. */
(() => {
  'use strict';
  const wiki = window.WIKI || {};
  const strings = wiki.strings || {};
  const html = document.documentElement;
  const lang = wiki.lang === 'es' ? 'es' : 'en';
  const $ = (selector, parent = document) => parent.querySelector(selector);
  const $$ = (selector, parent = document) => [...parent.querySelectorAll(selector)];
  const text = key => strings[key] || '';
  html.classList.add('has-js');

  $$('[data-i18n]').forEach(element => {
    if (text(element.dataset.i18n)) element.textContent = text(element.dataset.i18n);
  });
  // Extra states live in localized HTML templates; no UI copy is embedded in JS.
  function templateText(id) {
    const template = $(id);
    return template?.content.querySelector(`.${lang}`)?.textContent || '';
  }

  const systemTheme = matchMedia('(prefers-color-scheme: dark)');
  let savedTheme;
  try { savedTheme = localStorage.getItem('chatalyst-theme'); } catch (_) { /* Private storage. */ }
  function applyTheme(theme) {
    html.dataset.theme = theme;
    const radio = $(`#theme-${theme}`);
    if (radio) radio.checked = true;
  }
  const validTheme = value => value === 'dark' || value === 'light';
  applyTheme(validTheme(savedTheme) ? savedTheme : systemTheme.matches ? 'dark' : 'light');
  $$('input[name="theme"]').forEach(radio => radio.addEventListener('change', () => {
    savedTheme = radio.value;
    applyTheme(savedTheme);
    try { localStorage.setItem('chatalyst-theme', savedTheme); } catch (_) { /* Still works for this page. */ }
  }));
  systemTheme.addEventListener('change', () => {
    if (!validTheme(savedTheme)) applyTheme(systemTheme.matches ? 'dark' : 'light');
  });
  window.addEventListener('storage', event => {
    if (event.key === 'chatalyst-theme') {
      savedTheme = event.newValue;
      applyTheme(validTheme(savedTheme) ? savedTheme : systemTheme.matches ? 'dark' : 'light');
    }
  });

  const menu = $('#menu-state');
  const sidebar = $('#sidebar');
  const mobile = matchMedia('(max-width: 900px)');
  const background = $$('.site-header, .main-content, .page-toc');
  if (text('menu')) menu.setAttribute('aria-label', text('menu'));
  function updateMenu(focus = false) {
    const open = mobile.matches && menu.checked;
    menu.setAttribute('aria-expanded', String(open));
    sidebar.inert = mobile.matches && !open;
    background.forEach(element => { element.inert = open; });
    if (open && focus) $('a', sidebar)?.focus();
  }
  function closeMenu(restoreFocus = false) {
    menu.checked = false;
    updateMenu();
    if (restoreFocus) menu.focus();
  }
  menu.addEventListener('change', () => updateMenu(true));
  sidebar.addEventListener('click', event => { if (event.target.closest('a')) closeMenu(); });
  mobile.addEventListener('change', () => { closeMenu(); });
  updateMenu();
  document.addEventListener('keydown', event => {
    if (!mobile.matches || !menu.checked) return;
    if (event.key === 'Escape') { event.preventDefault(); closeMenu(true); }
    if (event.key === 'Tab') {
      const focusable = [menu, ...$$('a[href], button, input', sidebar)];
      const first = focusable[0], last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    }
  });

  const copyStatus = $('#copy-status');
  $$('.copy-btn').forEach(button => {
    const original = text('copy') || button.textContent;
    button.textContent = original;
    let reset;
    button.addEventListener('click', async () => {
      const code = $('pre code', button.closest('.code-block'));
      if (!code) return;
      button.disabled = true;
      try {
        if (navigator.clipboard && window.isSecureContext) await navigator.clipboard.writeText(code.textContent);
        else {
          const area = document.createElement('textarea');
          area.value = code.textContent;
          area.style.cssText = 'position:fixed;left:-9999px;top:0';
          document.body.append(area);
          area.select();
          let copied;
          try { copied = document.execCommand('copy'); } finally { area.remove(); }
          if (!copied) throw new Error('clipboard');
        }
        button.textContent = text('copied') || original;
        button.classList.add('is-copied');
        copyStatus.textContent = text('copied');
        clearTimeout(reset);
        reset = setTimeout(() => { button.textContent = original; button.classList.remove('is-copied'); copyStatus.textContent = ''; }, 1800);
      } catch (_) {
        const range = document.createRange();
        range.selectNodeContents(code);
        const selection = window.getSelection();
        selection.removeAllRanges();
        selection.addRange(range);
        copyStatus.textContent = templateText('#copy-error');
      } finally { button.disabled = false; button.focus({ preventScroll: true }); }
    });
  });

  const normalize = value => String(value || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLocaleLowerCase(lang);
  $$('.ref-filter').forEach(filter => {
    const scope = filter.closest('.prose') || filter.parentElement;
    // One filter can serve a page with multiple reference tables, as emitted by the build.
    const rows = $$('.ref-table tbody tr', scope);
    const searchable = rows.map(row => normalize(row.textContent));
    const status = document.createElement('p');
    status.className = 'ref-status';
    status.setAttribute('role', 'status');
    status.hidden = true;
    filter.insertAdjacentElement('afterend', status);
    if (!filter.labels?.length && !filter.hasAttribute('aria-label')) filter.setAttribute('aria-label', filter.placeholder || text('search'));
    filter.addEventListener('input', () => {
      const query = normalize(filter.value).trim();
      let count = 0;
      rows.forEach((row, index) => { row.hidden = !searchable[index].includes(query); if (!row.hidden) count++; });
      status.textContent = text('noResults');
      status.hidden = count > 0;
    });
  });
  // Scrollable regions remain usable with a keyboard, including wide reference tables.
  $$('.table-wrap, pre').forEach(region => {
    region.tabIndex = 0;
  });

  const tocLinks = $$('.toc a[href^="#"]');
  const sections = tocLinks.map(link => {
    try { return { link, heading: document.getElementById(decodeURIComponent(link.hash.slice(1))) }; }
    catch (_) { return { link, heading: null }; }
  }).filter(item => item.heading);
  let scheduled = false;
  function updateToc() {
    scheduled = false;
    let current = sections[0];
    sections.forEach(item => { if (item.heading.getBoundingClientRect().top <= 160) current = item; });
    sections.forEach(item => {
      const active = item === current;
      item.link.classList.toggle('is-active', active);
      if (active) item.link.setAttribute('aria-current', 'location');
      else item.link.removeAttribute('aria-current');
    });
  }
  if (sections.length) {
    window.addEventListener('scroll', () => { if (!scheduled) { scheduled = true; requestAnimationFrame(updateToc); } }, { passive: true });
    updateToc();
  }

  const dialog = $('#search-dialog');
  const trigger = $('.search-trigger');
  const input = $('#search-input');
  const results = $('#search-results');
  const status = $('.search-status');
  const base = new URL(`${wiki.root || '../'}${lang}/`, document.baseURI);
  let index = null, pending = null, matches = [], selected = -1, opener;
  input.placeholder = text('searchPlaceholder');
  trigger.hidden = false;
  // Cosmetic shortcut hint follows the platform; the command accepts either modifier.
  $('kbd', trigger).textContent = /Mac|iPhone|iPad/.test(navigator.platform) ? '⌘ K' : 'Ctrl K';

  function safeUrl(value) {
    const url = new URL(value, base);
    return url.origin === base.origin && url.pathname.startsWith(base.pathname) && !url.username && !url.password ? url.href : null;
  }
  async function loadIndex() {
    if (index) return index;
    if (pending) return pending;
    status.textContent = templateText('#search-loading');
    input.setAttribute('aria-busy', 'true');
    pending = (async () => {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 12000);
      try {
        const response = await fetch(new URL(`${wiki.root || '../'}search/${lang}.json`, document.baseURI), { signal: controller.signal });
        if (!response.ok) throw new Error(String(response.status));
        const data = await response.json();
        if (!Array.isArray(data)) throw new Error('index');
        index = data.filter(item => item && typeof item.url === 'string' && typeof item.title === 'string').flatMap(item => {
          let url;
          try { url = safeUrl(item.url); } catch (_) { return []; }
          return url ? [{ ...item, url, fields: [normalize(item.title), normalize((item.headings || []).join(' ')), normalize(item.description), normalize(item.text)] }] : [];
        });
        return index;
      } finally { clearTimeout(timeout); input.removeAttribute('aria-busy'); }
    })();
    try { return await pending; } finally { pending = null; }
  }
  function select(indexToSelect) {
    selected = indexToSelect;
    $$('.search-result', results).forEach((item, i) => item.setAttribute('aria-selected', String(i === selected)));
    const item = results.children[selected];
    if (item) { input.setAttribute('aria-activedescendant', item.id); item.scrollIntoView({ block: 'nearest' }); }
    else input.removeAttribute('aria-activedescendant');
  }
  function renderResults() {
    if (!index) return;
    const query = normalize(input.value).trim();
    const terms = query.split(/\s+/).filter(Boolean);
    matches = index.map(item => {
      let score = 0;
      for (const term of terms) {
        const field = item.fields.findIndex(value => value.includes(term));
        if (field === -1) return { item, score: -1 };
        score += [1000, 100, 10, 1][field];
      }
      if (query && item.fields[0] === query) score += 10000;
      return { item, score };
    }).filter(hit => hit.score >= 0).sort((a, b) => b.score - a.score).slice(0, 10).map(hit => hit.item);
    results.replaceChildren();
    matches.forEach((item, i) => {
      const result = document.createElement('li');
      result.className = 'search-result';
      result.id = `search-result-${i}`;
      result.setAttribute('role', 'option');
      result.setAttribute('aria-selected', 'false');
      [['result-group', item.group], ['result-title', item.title], ['result-description', item.description]].forEach(([className, value]) => {
        const span = document.createElement('span');
        span.className = className;
        span.textContent = value || '';
        result.append(span);
      });
      result.addEventListener('pointermove', () => select(i));
      result.addEventListener('click', () => { window.location.assign(item.url); });
      results.append(result);
    });
    input.setAttribute('aria-expanded', String(matches.length > 0));
    status.textContent = matches.length ? '' : text('noResults');
    select(matches.length ? 0 : -1);
  }
  async function openSearch() {
    if (dialog.open) return;
    closeMenu();
    opener = document.activeElement;
    dialog.showModal();
    input.focus();
    try { await loadIndex(); if (dialog.open) renderResults(); }
    catch (_) { status.textContent = templateText('#search-error'); }
  }
  trigger.addEventListener('click', openSearch);
  $('.search-close').addEventListener('click', () => dialog.close());
  dialog.addEventListener('keydown', event => {
    if (event.key === 'Escape') { event.preventDefault(); dialog.close(); }
  });
  dialog.addEventListener('click', event => {
    const rect = dialog.getBoundingClientRect();
    if (event.target === dialog && (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom)) dialog.close();
  });
  dialog.addEventListener('close', () => { input.setAttribute('aria-expanded', 'false'); opener?.focus(); });
  input.addEventListener('input', renderResults);
  input.addEventListener('keydown', event => {
    if ((event.key === 'ArrowDown' || event.key === 'ArrowUp') && matches.length) {
      event.preventDefault();
      select((selected + (event.key === 'ArrowDown' ? 1 : -1) + matches.length) % matches.length);
    } else if (event.key === 'Enter' && matches[selected]) {
      event.preventDefault();
      window.location.assign(matches[selected].url);
    }
  });
  document.addEventListener('keydown', event => {
    if ((event.metaKey || event.ctrlKey) && !event.altKey && event.key.toLowerCase() === 'k') {
      event.preventDefault();
      if (dialog.open) dialog.close(); else openSearch();
    }
  });
})();
