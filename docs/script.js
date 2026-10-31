/* Chatalyst documentation — multi-page router, theme, search, mobile sidebar */
(function () {
  'use strict';

  var root = document.documentElement;
  var catalog = window.CHATALYST_I18N || {};
  var navLinks = Array.prototype.slice.call(document.querySelectorAll('#nav a[href^="#"]'));
  var homeLinks = Array.prototype.slice.call(document.querySelectorAll('[data-home-link]'));
  var navGroups = Array.prototype.slice.call(document.querySelectorAll('#nav .nav-group'));
  var globalNavLinks = Array.prototype.slice.call(document.querySelectorAll('.global-nav [data-nav-section]'));
  var pages = Array.prototype.slice.call(document.querySelectorAll('.page'));
  var order = ['home'].concat(navLinks.map(function (a) { return a.getAttribute('href').slice(1); }));
  var pagenav = document.getElementById('pagenav');
  var tocInner = document.getElementById('page-toc-inner');
  var tocLinks = document.getElementById('page-toc-links');
  var themeColorMeta = document.querySelector('meta[name="theme-color"]');
  var themeButtons = Array.prototype.slice.call(document.querySelectorAll('.theme-toggle'));
  var reducedMotionQuery = window.matchMedia('(prefers-reduced-motion: reduce)');
  var tocHeadings = [];
  var tocScrollFrame = null;
  var currentLanguage = root.lang === 'es' ? 'es' : 'en';
  var languageButtons = Array.prototype.slice.call(document.querySelectorAll('[data-language]'));
  var originalPageChrome = {};
  var originalTextNodes = [];
  var searchIndex = [];

  pages.forEach(function (page) {
    originalPageChrome[page.id] = {
      title: page.getAttribute('data-title') || page.id,
      crumb: page.querySelector('.crumb') ? page.querySelector('.crumb').innerHTML : null,
      h1: page.querySelector('h1') ? page.querySelector('h1').innerHTML : null,
      lead: page.querySelector('.lead') ? page.querySelector('.lead').innerHTML : null,
      headings: Array.prototype.slice.call(page.querySelectorAll('h2')).map(function (heading) { return heading.innerHTML; })
    };
  });

  (function captureTranslatableText() {
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    var node;
    while ((node = walker.nextNode())) {
      var parent = node.parentElement;
      if (!parent || !node.nodeValue.trim() || parent.closest('script, style, svg, code, pre, [data-language]')) continue;
      originalTextNodes.push({ node: node, value: node.nodeValue });
    }
  })();

  function ui() {
    return catalog[currentLanguage] || catalog.en || {};
  }

  function localized(en, es) {
    return currentLanguage === 'es' ? es : en;
  }

  function restartMotion(element, className, timeout) {
    if (!element || reducedMotionQuery.matches) return;
    var timerKey = '__' + className + 'Timer';
    if (element[timerKey]) window.clearTimeout(element[timerKey]);
    element.classList.remove(className);
    void element.offsetWidth;
    element.classList.add(className);
    element[timerKey] = window.setTimeout(function () {
      element.classList.remove(className);
      element[timerKey] = null;
    }, timeout);
  }

  function restorePageChrome() {
    pages.forEach(function (page) {
      var original = originalPageChrome[page.id];
      if (!original) return;
      page.setAttribute('data-title', original.title);
      var crumb = page.querySelector('.crumb');
      var h1 = page.querySelector('h1');
      var lead = page.querySelector('.lead');
      if (crumb && original.crumb !== null) crumb.innerHTML = original.crumb;
      if (h1 && original.h1 !== null) h1.innerHTML = original.h1;
      if (lead && original.lead !== null) lead.innerHTML = original.lead;
      Array.prototype.slice.call(page.querySelectorAll('h2')).forEach(function (heading, index) {
        if (original.headings[index] !== undefined) heading.innerHTML = original.headings[index];
      });
    });
  }

  function applyExactTranslations() {
    var translations = currentLanguage === 'es' ? (catalog.exactEs || {}) : {};
    originalTextNodes.forEach(function (entry) {
      if (!entry.node.isConnected) return;
      entry.node.nodeValue = entry.value;
      if (currentLanguage !== 'es') return;
      var raw = entry.value.trim();
      if (Object.prototype.hasOwnProperty.call(translations, raw)) {
        entry.node.nodeValue = entry.value.replace(raw, translations[raw]);
      }
    });
  }

  function applyPageTranslations() {
    restorePageChrome();
    if (currentLanguage !== 'es') return;
    var translations = catalog.pagesEs || {};
    pages.forEach(function (page) {
      var translated = translations[page.id];
      if (!translated) return;
      if (translated.title) page.setAttribute('data-title', translated.title);
      var crumb = page.querySelector('.crumb');
      var h1 = page.querySelector('h1');
      var lead = page.querySelector('.lead');
      if (crumb && translated.crumb) crumb.textContent = translated.crumb;
      if (h1 && translated.h1) h1.textContent = translated.h1;
      if (lead && translated.lead) lead.innerHTML = translated.lead;
      if (translated.headings) {
        Array.prototype.slice.call(page.querySelectorAll('h2')).forEach(function (heading, index) {
          if (translated.headings[index]) heading.textContent = translated.headings[index];
        });
      }
    });
  }

  function syncLanguageControls() {
    root.lang = currentLanguage;
    languageButtons.forEach(function (button) {
      var active = button.getAttribute('data-language') === currentLanguage;
      button.classList.toggle('active', active);
      button.setAttribute('aria-pressed', active ? 'true' : 'false');
    });
    var searchInput = document.getElementById('search-input');
    var searchResults = document.getElementById('search-results');
    var tocLabel = document.querySelector('.page-toc-inner > strong');
    var pageToc = document.getElementById('page-toc');
    var languageSwitch = document.querySelector('.language-switch');
    var sideNavigation = document.getElementById('nav');
    var globalNavigation = document.querySelector('.global-nav');
    var description = document.querySelector('meta[name="description"]');
    if (searchInput) {
      searchInput.placeholder = ui().searchPlaceholder;
      searchInput.setAttribute('aria-label', ui().searchLabel);
    }
    if (searchResults) searchResults.setAttribute('aria-label', ui().searchLabel);
    var triggerLabel = document.querySelector('[data-search-trigger-label]');
    if (triggerLabel) triggerLabel.textContent = ui().searchPlaceholder;
    var trigger = document.getElementById('search-trigger');
    if (trigger) trigger.setAttribute('aria-label', ui().searchLabel);
    var hintMap = { navigate: 'searchHintNavigate', select: 'searchHintSelect', close: 'searchHintClose' };
    Object.keys(hintMap).forEach(function (key) {
      var el = document.querySelector('[data-hint="' + key + '"]');
      if (el && ui()[hintMap[key]]) el.textContent = ui()[hintMap[key]];
    });
    if (tocLabel) tocLabel.textContent = ui().onThisPage;
    if (pageToc) pageToc.setAttribute('aria-label', ui().onThisPage);
    if (languageSwitch) languageSwitch.setAttribute('aria-label', localized('Documentation language', 'Idioma de la documentación'));
    if (sideNavigation) sideNavigation.setAttribute('aria-label', localized('Documentation', 'Documentación'));
    if (globalNavigation) globalNavigation.setAttribute('aria-label', localized('Global navigation', 'Navegación global'));
    if (pagenav) pagenav.setAttribute('aria-label', localized('Page navigation', 'Navegación entre páginas'));
    homeLinks.forEach(function (link) { link.setAttribute('aria-label', localized('Chatalyst documentation home', 'Inicio de la documentación de Chatalyst')); });
    document.querySelectorAll('[data-href-en][data-href-es]').forEach(function (link) {
      link.setAttribute('href', link.getAttribute(currentLanguage === 'es' ? 'data-href-es' : 'data-href-en'));
    });
    syncCodeCopyControls();
    if (description) description.setAttribute('content', localized('Documentation for Chatalyst, a modern chat plugin for Paper and Purpur.', 'Documentación de Chatalyst, un plugin moderno de chat para Paper y Purpur.'));
  }

  function syncCodeCopyControls() {
    document.querySelectorAll('[data-code-label-fallback]').forEach(function (label) {
      label.textContent = ui().codeExample;
    });
    document.querySelectorAll('[data-code-copy]').forEach(function (button) {
      if (button.classList.contains('is-copied')) return;
      var label = button.querySelector('span');
      if (label) label.textContent = ui().copyCode;
      button.setAttribute('aria-label', ui().copyCode);
      button.setAttribute('title', ui().copyCode);
    });
  }

  function copyCodeText(text) {
    if (navigator.clipboard && window.isSecureContext) {
      return navigator.clipboard.writeText(text);
    }
    return new Promise(function (resolve, reject) {
      var textarea = document.createElement('textarea');
      textarea.value = text;
      textarea.setAttribute('readonly', '');
      textarea.style.position = 'fixed';
      textarea.style.opacity = '0';
      document.body.appendChild(textarea);
      textarea.select();
      try {
        if (!document.execCommand('copy')) throw new Error('Copy command failed');
        resolve();
      } catch (error) {
        reject(error);
      } finally {
        textarea.remove();
      }
    });
  }

  function setupCodeBlocks() {
    document.querySelectorAll('pre').forEach(function (pre) {
      if (pre.parentElement && pre.parentElement.classList.contains('code-frame')) return;
      var frame = document.createElement('div');
      frame.className = 'code-frame';
      pre.parentNode.insertBefore(frame, pre);
      frame.appendChild(pre);

      var sourceLabel = pre.getAttribute('data-code-label');
      var codeLabel = document.createElement('span');
      codeLabel.className = 'code-label';
      if (sourceLabel) codeLabel.textContent = sourceLabel;
      else codeLabel.setAttribute('data-code-label-fallback', '');
      frame.appendChild(codeLabel);

      var button = document.createElement('button');
      button.type = 'button';
      button.className = 'code-copy';
      button.setAttribute('data-code-copy', '');
      button.innerHTML = '<svg aria-hidden="true" viewBox="0 0 20 20"><rect x="6.5" y="6.5" width="9" height="9" rx="1.5"/><path d="M4.5 13.5h-1V4.7a1.2 1.2 0 0 1 1.2-1.2h8.8v1"/></svg><span></span>';
      button.addEventListener('click', function () {
        var code = pre.querySelector('code');
        var value = (code ? code.innerText : pre.innerText).replace(/\n$/, '');
        copyCodeText(value).then(function () {
          var label = button.querySelector('span');
          button.classList.add('is-copied');
          if (label) label.textContent = ui().copied;
          button.setAttribute('aria-label', ui().copied);
          if (copyStatus) copyStatus.textContent = ui().copySuccess;
          window.setTimeout(function () {
            button.classList.remove('is-copied');
            syncCodeCopyControls();
          }, 1600);
        }).catch(function () {
          if (copyStatus) copyStatus.textContent = ui().copyFailed;
        });
      });
      frame.appendChild(button);
    });
    syncCodeCopyControls();
  }

  function applyLanguage(language, rerender) {
    currentLanguage = language === 'es' ? 'es' : 'en';
    applyExactTranslations();
    applyPageTranslations();
    syncLanguageControls();
    syncThemeControls();
    prepareResponsiveTables();
    try { localStorage.setItem('chatalyst-language', currentLanguage); } catch (e) {}
    if (rerender) show(fromHash());
    buildSearchIndex();
  }

  languageButtons.forEach(function (button) {
    button.addEventListener('click', function () {
      var language = button.getAttribute('data-language');
      if (language !== currentLanguage) applyLanguage(language, true);
    });
  });

  function syncThemeControls() {
    var dark = root.classList.contains('dark');
    themeButtons.forEach(function (btn) {
      btn.setAttribute('aria-pressed', dark ? 'true' : 'false');
      btn.setAttribute('aria-label', dark ? ui().lightTheme : ui().darkTheme);
      btn.setAttribute('title', dark ? ui().lightTheme : ui().darkTheme);
    });
    if (themeColorMeta) themeColorMeta.setAttribute('content', dark ? '#18181b' : '#ffffff');
  }

  /* ---- Theme controls ---- */
  themeButtons.forEach(function (btn) {
    btn.addEventListener('click', function () {
      root.classList.add('theme-switching');
      var dark = root.classList.toggle('dark');
      try { localStorage.setItem('chatalyst-theme', dark ? 'dark' : 'light'); } catch (e) {}
      syncThemeControls();
      requestAnimationFrame(function () {
        requestAnimationFrame(function () { root.classList.remove('theme-switching'); });
      });
    });
  });
  syncThemeControls();

  /* ---- Mobile sidebar ---- */
  var sidebar = document.getElementById('sidebar');
  var scrim = document.getElementById('scrim');
  var menuBtn = document.getElementById('menu-btn');
  var mobileQuery = window.matchMedia('(max-width: 900px)');
  function setMenu(open) {
    var isMobile = mobileQuery.matches;
    var shouldOpen = isMobile && open;
    sidebar.classList.toggle('open', shouldOpen);
    scrim.classList.toggle('show', shouldOpen);
    sidebar.inert = isMobile && !shouldOpen;
    sidebar.setAttribute('aria-hidden', isMobile && !shouldOpen ? 'true' : 'false');
    document.body.classList.toggle('menu-open', shouldOpen);
    if (menuBtn) {
      menuBtn.setAttribute('aria-expanded', shouldOpen ? 'true' : 'false');
      menuBtn.setAttribute('aria-label', shouldOpen ? ui().closeNavigation : ui().openNavigation);
    }
  }
  if (menuBtn) menuBtn.addEventListener('click', function () { setMenu(!sidebar.classList.contains('open')); });
  if (scrim) scrim.addEventListener('click', function () { setMenu(false); });
  document.addEventListener('keydown', function (ev) {
    if (ev.key === 'Escape' && sidebar.classList.contains('open')) {
      setMenu(false);
      if (menuBtn) menuBtn.focus();
    }
  });
  function syncMenuForViewport() {
    setMenu(false);
    var activeGroup = navGroups.filter(function (group) { return group.classList.contains('is-current'); })[0] || null;
    syncCollapsedNavigation(activeGroup);
  }
  if (mobileQuery.addEventListener) mobileQuery.addEventListener('change', syncMenuForViewport);
  else mobileQuery.addListener(syncMenuForViewport);
  setMenu(false);

  navGroups.forEach(function (group, index) {
    var title = group.querySelector('.nav-group-title');
    var links = group.querySelector('.nav-links');
    if (!title || !links) return;
    if (!links.id) links.id = 'nav-group-links-' + index;
    var toggle = document.createElement('button');
    toggle.type = 'button';
    toggle.className = 'nav-group-toggle';
    toggle.setAttribute('aria-controls', links.id);
    toggle.setAttribute('aria-expanded', 'true');
    toggle.innerHTML = '<svg aria-hidden="true" viewBox="0 0 20 20"><path d="m6.5 8 3.5 3.5L13.5 8"/></svg>';
    group.insertBefore(toggle, links);
    toggle.addEventListener('click', function () {
      var collapsed = group.classList.toggle('is-collapsed');
      toggle.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
      toggle.setAttribute('aria-label', collapsed ? ui().expandSection : ui().collapseSection);
    });
  });

  function syncCollapsedNavigation(activeGroup) {
    navGroups.forEach(function (group) {
      var toggle = group.querySelector('.nav-group-toggle');
      var collapsed = mobileQuery.matches && group !== activeGroup;
      group.classList.toggle('is-collapsed', collapsed);
      if (toggle) {
        toggle.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
        toggle.setAttribute('aria-label', collapsed ? ui().expandSection : ui().collapseSection);
      }
    });
  }

  /* ---- Router ---- */
  var PAGE_META = {
    compatibility: [['Paper / Purpur', 'Paper / Purpur'], ['Java 21+', 'Java 21+'], ['No ProtocolLib', 'Sin ProtocolLib']],
    install: [['Paper-based server', 'Servidor basado en Paper'], ['Java 21+', 'Java 21+'], ['Full restart', 'Reinicio completo']],
    integrations: [['Optional hooks', 'Hooks opcionales'], ['Auto-detected', 'Detección automática'], ['Full restart', 'Reinicio completo']],
    proxy: [['Network feature', 'Función de red'], ['Velocity / Bungee / Redis', 'Velocity / Bungee / Redis'], ['Every backend', 'Todos los backends']],
    discord: [['Built-in bot', 'Bot integrado'], ['Two-way chat', 'Chat bidireccional'], ['Reloadable', 'Admite recarga']],
    localization: [['English + Spanish', 'Inglés + español'], ['Per-player language', 'Idioma por jugador'], ['English fallback', 'Respaldo en inglés']],
    'player-tools': [['Player feature', 'Función del jugador'], ['Permission-aware', 'Controlada por permisos'], ['Persistent', 'Persistente']],
    titles: [['GUI managed', 'Administrado por GUI'], ['English + Spanish', 'Inglés + español'], ['PacketEvents optional', 'PacketEvents opcional']],
    market: [['Disabled by default', 'Desactivado inicialmente'], ['Per-server data', 'Datos por servidor'], ['Escrow protected', 'Depósito protegido']],
    'private-messages': [['Disabled by default', 'Desactivados inicialmente'], ['Persistent privacy', 'Privacidad persistente'], ['Online delivery', 'Entrega en línea']],
    announcements: [['Enabled by default', 'Activados inicialmente'], ['Personal toggle', 'Preferencia personal'], ['Reloadable', 'Admite recarga']],
    display: [['Disabled by default', 'Desactivado inicialmente'], ['TAB-aware', 'Compatible con TAB'], ['Conflict-safe', 'Sin conflictos']],
    storage: [['YAML / SQLite / MySQL', 'YAML / SQLite / MySQL'], ['Async writes', 'Escritura asíncrona'], ['Network-ready', 'Preparado para redes']],
    'reports-audit': [['Staff feature', 'Función del equipo'], ['Persistent tickets', 'Tickets persistentes'], ['Discord optional', 'Discord opcional']],
    security: [['Server protection', 'Protección del servidor'], ['Granular bypasses', 'Excepciones granulares'], ['PacketEvents optional', 'PacketEvents opcional']],
    developer: [['API v1', 'API v1'], ['Java 21+', 'Java 21+'], ['Paper / Purpur / Folia', 'Paper / Purpur / Folia']]
  };

  var RELATED = {
    install: ['compatibility', 'integrations', 'upgrading'],
    integrations: ['discord', 'proxy', 'display'],
    channels: ['formats', 'market', 'proxy'],
    formats: ['channels', 'nicknames', 'variables'],
    nicknames: ['player-tools', 'display', 'formats'],
    'player-tools': ['nicknames', 'titles', 'market'],
    titles: ['player-tools', 'display', 'storage'],
    market: ['channels', 'player-tools', 'storage'],
    'private-messages': ['mail', 'spying', 'reports-audit'],
    mail: ['private-messages', 'storage', 'newcomer'],
    messages: ['announcements', 'display', 'newcomer'],
    announcements: ['messages', 'mentions', 'aliases'],
    display: ['nicknames', 'integrations', 'troubleshooting'],
    storage: ['proxy', 'performance', 'reports-audit'],
    'reports-audit': ['spying', 'security', 'discord'],
    security: ['rules', 'tabcomplete', 'reports-audit'],
    troubleshooting: ['configuration', 'integrations', 'security'],
    developer: ['titles', 'integrations', 'storage']
  };

  function titleOf(id) {
    var p = document.getElementById(id);
    return p ? (p.getAttribute('data-title') || id) : id;
  }

  function buildPageNav(id) {
    var i = order.indexOf(id);
    var prev = i > 0 ? order[i - 1] : null;
    var next = i >= 0 && i < order.length - 1 ? order[i + 1] : null;
    var html = '';
    if (prev) html += '<a href="#' + prev + '"><small>' + esc(ui().previous) + '</small>' + esc(titleOf(prev)) + '</a>';
    if (next) html += '<a class="nx" href="#' + next + '"><small>' + esc(ui().next) + '</small>' + esc(titleOf(next)) + '</a>';
    pagenav.innerHTML = html;
  }

  function buildPageMeta(id) {
    pages.forEach(function (page) {
      var old = page.querySelector('.page-meta');
      if (old) old.remove();
    });
    var page = document.getElementById(id);
    var items = PAGE_META[id];
    if (!page || !items || !items.length) return;
    var meta = document.createElement('div');
    meta.className = 'page-meta';
    meta.setAttribute('aria-label', localized('Page facts', 'Datos de la página'));
    meta.innerHTML = items.map(function (item) {
      return '<span><i aria-hidden="true"></i>' + esc(localized(item[0], item[1])) + '</span>';
    }).join('');
    var lead = page.querySelector('.lead');
    if (lead) lead.insertAdjacentElement('afterend', meta);
    else page.insertBefore(meta, page.firstChild);
  }

  function buildRelatedGuides(id) {
    pages.forEach(function (page) {
      var old = page.querySelector('.related-guides');
      if (old) old.remove();
    });
    var page = document.getElementById(id);
    var relatedIds = RELATED[id];
    if (!page || !relatedIds || !relatedIds.length) return;
    var section = document.createElement('section');
    section.className = 'related-guides';
    section.setAttribute('aria-label', ui().related);
    section.innerHTML = '<h2>' + esc(ui().related) + '</h2><div>' + relatedIds.map(function (relatedId) {
      return '<a href="#' + relatedId + '">' + esc(titleOf(relatedId)) + '<svg aria-hidden="true" viewBox="0 0 20 20"><path d="M5 10h10m-4-4 4 4-4 4"/></svg></a>';
    }).join('') + '</div>';
    page.appendChild(section);
  }

  function buildMobileTableOfContents(id) {
    pages.forEach(function (page) {
      var old = page.querySelector('.mobile-toc');
      if (old) old.remove();
    });
    var page = document.getElementById(id);
    if (!page) return;
    var headings = Array.prototype.slice.call(page.querySelectorAll(':scope > h2'));
    if (headings.length < 2) return;
    headings.forEach(function (heading, index) {
      if (!heading.id) heading.id = 'toc-' + id + '-' + index;
    });
    var details = document.createElement('details');
    details.className = 'mobile-toc';
    details.innerHTML = '<summary>' + esc(ui().onThisPage) + '<svg aria-hidden="true" viewBox="0 0 20 20"><path d="m6.5 8 3.5 3.5L13.5 8"/></svg></summary><nav>' + headings.map(function (heading) {
      return '<button type="button" data-mobile-toc-target="' + heading.id + '">' + esc(heading.textContent.trim()) + '</button>';
    }).join('') + '</nav>';
    var anchor = page.querySelector('.page-meta') || page.querySelector('.lead') || page.querySelector('h1');
    if (anchor) anchor.insertAdjacentElement('afterend', details);
  }

  function prepareResponsiveTables() {
    document.querySelectorAll('.tablewrap table').forEach(function (table) {
      var headers = Array.prototype.slice.call(table.querySelectorAll('thead th')).map(function (header) { return header.textContent.trim(); });
      table.querySelectorAll('tbody tr').forEach(function (row) {
        Array.prototype.slice.call(row.children).forEach(function (cell, index) {
          cell.setAttribute('data-label', headers[index] || '');
          var content = cell.querySelector(':scope > .table-cell-content');
          if (!content) {
            content = document.createElement('span');
            content.className = 'table-cell-content';
            while (cell.firstChild) content.appendChild(cell.firstChild);
            cell.appendChild(content);
          }
        });
      });
    });
  }

  function setActiveToc(targetId) {
    if (tocLinks) Array.prototype.slice.call(tocLinks.querySelectorAll('[data-toc-target]')).forEach(function (button) {
      var active = button.getAttribute('data-toc-target') === targetId;
      button.classList.toggle('active', active);
      if (active) button.setAttribute('aria-current', 'location');
      else button.removeAttribute('aria-current');
    });
    document.querySelectorAll('[data-mobile-toc-target]').forEach(function (button) {
      var active = button.getAttribute('data-mobile-toc-target') === targetId;
      button.classList.toggle('active', active);
      if (active) button.setAttribute('aria-current', 'location');
      else button.removeAttribute('aria-current');
    });
  }

  function syncActiveToc() {
    tocScrollFrame = null;
    if (!tocHeadings.length) return;
    var headerOffset = parseFloat(getComputedStyle(root).getPropertyValue('--header-height')) || 68;
    var activeHeading = tocHeadings[0];
    tocHeadings.forEach(function (heading) {
      if (heading.getBoundingClientRect().top <= headerOffset + 42) activeHeading = heading;
    });
    setActiveToc(activeHeading.id);
  }

  function scheduleTocSync() {
    if (tocScrollFrame !== null) return;
    tocScrollFrame = requestAnimationFrame(syncActiveToc);
  }

  function buildTableOfContents(id) {
    if (!tocInner || !tocLinks) return;
    var page = document.getElementById(id);
    var headings = page ? Array.prototype.slice.call(page.querySelectorAll(':scope > h2')) : [];
    if (!headings.length) {
      tocHeadings = [];
      tocInner.hidden = true;
      tocLinks.innerHTML = '';
      return;
    }

    headings.forEach(function (heading, index) {
      if (!heading.id) heading.id = 'toc-' + id + '-' + index;
    });
    tocLinks.innerHTML = headings.map(function (heading) {
      return '<button type="button" data-toc-target="' + heading.id + '">' +
        esc(heading.textContent.trim()) + '</button>';
    }).join('');
    tocHeadings = headings;
    setActiveToc(headings[0].id);
    tocInner.hidden = false;
  }

  function globalSectionFor(id) {
    if (id === 'developer-home' || id === 'developer') return 'developer';
    if (id === 'home' || id === 'about-home' || id === 'overview' || id === 'license') return 'general';
    return 'guides';
  }

  function syncGlobalNavigation(id) {
    var currentSection = globalSectionFor(id);
    globalNavLinks.forEach(function (link) {
      var active = link.getAttribute('data-nav-section') === currentSection;
      link.classList.toggle('active', active);
      if (active) link.setAttribute('aria-current', 'page');
      else if (!link.hasAttribute('data-home-link') || id !== 'home') link.removeAttribute('aria-current');
    });
  }

  // When a search result navigates to another page we scroll to the matched line, not the top.
  // These let goTo() suppress this one reset and run its scroll right after the page is shown.
  var skipScrollReset = false;
  var pendingReveal = null;

  function resetPageScroll() {
    if (skipScrollReset) { skipScrollReset = false; return; }
    window.scrollTo(0, 0);
    requestAnimationFrame(function () { window.scrollTo(0, 0); });
    setTimeout(function () { window.scrollTo(0, 0); }, 0);
  }

  function show(id) {
    if (order.indexOf(id) === -1) id = order[0];
    pages.forEach(function (p) { p.classList.toggle('active', p.id === id); });
    homeLinks.forEach(function (a) {
      var active = id === 'home';
      a.classList.toggle('active', active);
      if (active) a.setAttribute('aria-current', 'page');
      else a.removeAttribute('aria-current');
    });
    var activeNavLink = null;
    navLinks.forEach(function (a) {
      var active = a.getAttribute('href').slice(1) === id;
      a.classList.toggle('active', active);
      if (active) {
        a.setAttribute('aria-current', 'page');
        activeNavLink = a;
      }
      else a.removeAttribute('aria-current');
    });
    var activeNavGroup = null;
    navGroups.forEach(function (group) {
      var active = !!group.querySelector('a.active');
      group.classList.toggle('is-current', active);
      if (active) activeNavGroup = group;
    });
    restartMotion(activeNavLink, 'is-selecting', 340);
    restartMotion(activeNavGroup, 'is-selecting', 340);
    syncGlobalNavigation(id);
    syncCollapsedNavigation(activeNavGroup);
    buildPageMeta(id);
    buildMobileTableOfContents(id);
    buildRelatedGuides(id);
    buildPageNav(id);
    buildTableOfContents(id);
    prepareResponsiveTables();
    document.title = id === 'home' ? ui().pageSuffix : titleOf(id) + ' — ' + ui().pageSuffix;
    if (window.innerWidth < 901) setMenu(false);
    resetPageScroll();
    var main = document.querySelector('.content');
    if (main) main.scrollTop = 0;
  }

  function fromHash() {
    return (location.hash || '').replace('#', '') || order[0];
  }

  if (tocLinks) {
    tocLinks.addEventListener('click', function (ev) {
      var target = ev.target.closest('[data-toc-target]');
      if (!target) return;
      var heading = document.getElementById(target.getAttribute('data-toc-target'));
      if (!heading) return;
      var reduced = reducedMotionQuery.matches;
      setActiveToc(heading.id);
      heading.scrollIntoView({ behavior: reduced ? 'auto' : 'smooth', block: 'start' });
    });
  }

  document.addEventListener('click', function (ev) {
    var target = ev.target.closest('[data-mobile-toc-target]');
    if (!target) return;
    var heading = document.getElementById(target.getAttribute('data-mobile-toc-target'));
    if (!heading) return;
    setActiveToc(heading.id);
    heading.scrollIntoView({ behavior: reducedMotionQuery.matches ? 'auto' : 'smooth', block: 'start' });
    var details = target.closest('details');
    if (details) details.open = false;
  });

  /* ---- Search (command palette) ---- */
  var cmdk = document.getElementById('cmdk');
  var searchTrigger = document.getElementById('search-trigger');
  var searchInput = document.getElementById('search-input');
  var resultsEl = document.getElementById('search-results');
  var currentItems = [];
  var activeIndex = -1;
  var lastFocus = null;
  var lastQuery = '';

  var ICON_PAGE = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M14 3v4a1 1 0 0 0 1 1h4"/><path d="M6 3h8l5 5v11a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z"/></svg>';
  var ICON_SECTION = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M9 4 7 20M17 4l-2 16M4 9h16M3 15h16"/></svg>';

  function esc(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  function escRegex(s) {
    return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  }

  // Text that belongs to one h2/h3 section: the heading's following siblings up to the next
  // heading. Used both to index a section and (via sectionSiblings) to locate a match inside it.
  function sectionSiblings(heading) {
    var els = [];
    var node = heading.nextElementSibling;
    while (node && !/^H[23]$/.test(node.tagName)) {
      els.push(node);
      node = node.nextElementSibling;
    }
    return els;
  }

  function sectionText(heading) {
    return sectionSiblings(heading).map(function (el) { return el.textContent; })
      .join(' ').replace(/\s+/g, ' ').trim();
  }

  // Everything shown before the first h2: the intro (h1, lead, intro lists/notes/tables).
  // Without this, content that lives above the first heading is unreachable from search.
  function introText(page) {
    var parts = [];
    var kids = page.children;
    for (var i = 0; i < kids.length; i++) {
      if (/^H2$/.test(kids[i].tagName)) break;
      if (kids[i].classList.contains('crumb')) continue;
      parts.push(kids[i].textContent);
    }
    return parts.join(' ').replace(/\s+/g, ' ').trim();
  }

  // Smallest block element within `scope` whose text contains the query — so opening a result
  // lands on the exact line, not just the section. Prefers a full-phrase hit, then most terms.
  function matchElement(scope, terms, phrase) {
    if (!terms.length) return null;
    var pool = [];
    scope.forEach(function (el) {
      pool.push(el);
      var kids = el.querySelectorAll ? el.querySelectorAll('li, p, td, th, .row, dd, dt') : [];
      for (var i = 0; i < kids.length; i++) pool.push(kids[i]);
    });
    var phraseHit = null;
    for (var i = 0; i < pool.length; i++) {
      if (phrase && pool[i].textContent.toLowerCase().indexOf(phrase) !== -1) phraseHit = pool[i];
    }
    if (phraseHit) return phraseHit;
    var best = null, bestCount = 0;
    for (var j = 0; j < pool.length; j++) {
      var text = pool[j].textContent.toLowerCase(), count = 0;
      for (var t = 0; t < terms.length; t++) if (text.indexOf(terms[t]) !== -1) count++;
      if (count > bestCount) { bestCount = count; best = pool[j]; }
    }
    return bestCount > 0 ? best : null;
  }

  function buildSearchIndex() {
    var index = [];
    pages.forEach(function (page) {
      var crumbEl = page.querySelector('.crumb');
      var category = crumbEl ? crumbEl.textContent.trim() : '';
      var pageTitle = page.getAttribute('data-title') || page.id;
      index.push({
        pageId: page.id,
        headingId: null,
        title: pageTitle,
        category: category,
        path: category,
        text: introText(page)
      });
      var h2s = Array.prototype.slice.call(page.querySelectorAll(':scope > h2'));
      h2s.forEach(function (heading, i) {
        if (!heading.id) heading.id = 'toc-' + page.id + '-' + i;
        index.push({
          pageId: page.id,
          headingId: heading.id,
          title: heading.textContent.trim(),
          category: category,
          path: pageTitle,
          text: sectionText(heading)
        });
      });
      var h3s = Array.prototype.slice.call(page.querySelectorAll(':scope > h3'));
      h3s.forEach(function (heading, i) {
        if (!heading.id) heading.id = 'sec-' + page.id + '-h3-' + i;
        index.push({
          pageId: page.id,
          headingId: heading.id,
          title: heading.textContent.trim(),
          category: category,
          path: pageTitle,
          text: sectionText(heading)
        });
      });
    });
    searchIndex = index;
  }

  function tokenize(q) {
    // Word tokens only, punctuation stripped, internal hyphens/slashes kept ("mid-write").
    var m = q.toLowerCase().match(/[a-z0-9]+(?:[-_/][a-z0-9]+)*/g);
    return m || [];
  }

  function fuzzy(needle, hay) {
    if (needle.length < 3) return false;
    var i = 0;
    for (var j = 0; j < hay.length && i < needle.length; j++) {
      if (hay[j] === needle[i]) i++;
    }
    return i === needle.length;
  }

  function scoreEntry(entry, terms) {
    var title = entry.title.toLowerCase();
    var section = (entry.path || '').toLowerCase();
    var body = entry.text.toLowerCase();
    var matched = 0, quality = 0;
    for (var t = 0; t < terms.length; t++) {
      var term = terms[t], s = 0;
      if (title.indexOf(term) === 0) s = 100;
      else if (title.indexOf(term) !== -1) s = 72;
      else if (section.indexOf(term) !== -1) s = 44;
      else if (body.indexOf(term) !== -1) s = 26;
      else if (fuzzy(term, title)) s = 14;
      if (s > 0) { matched++; quality += s; }
    }
    // Short queries must match every term; longer phrases tolerate a few misses so a pasted
    // sentence still surfaces its page instead of returning nothing.
    var need = terms.length <= 2 ? terms.length : Math.ceil(terms.length * 0.6);
    if (matched < need) return -1;
    // matched-count dominates the score; a page beats its own sections on an equal match count.
    return matched * 1000 + quality + (matched === terms.length ? 40 : 0) + (entry.headingId ? 0 : 6);
  }

  function mark(text, terms) {
    if (!terms.length) return esc(text);
    var pattern = terms.map(escRegex).filter(Boolean).join('|');
    if (!pattern) return esc(text);
    var re = new RegExp(pattern, 'ig');
    var out = '', last = 0, m;
    while ((m = re.exec(text))) {
      if (m.index === re.lastIndex) { re.lastIndex++; continue; }
      out += esc(text.slice(last, m.index)) + '<mark>' + esc(m[0]) + '</mark>';
      last = m.index + m[0].length;
    }
    return out + esc(text.slice(last));
  }

  function snippet(text, terms) {
    if (!text) return '';
    if (!terms.length) return esc(text.slice(0, 120));
    var lower = text.toLowerCase();
    var pos = -1;
    for (var i = 0; i < terms.length; i++) {
      var p = lower.indexOf(terms[i]);
      if (p !== -1 && (pos === -1 || p < pos)) pos = p;
    }
    if (pos === -1) return mark(text.slice(0, 120), terms);
    var start = Math.max(0, pos - 42);
    return (start > 0 ? '…' : '') + mark(text.slice(start, start + 150), terms);
  }

  function defaultEntries() {
    var seen = {}, picks = [];
    for (var i = 0; i < searchIndex.length && picks.length < 6; i++) {
      var e = searchIndex[i];
      if (e.headingId || seen[e.pageId] || e.pageId === 'home') continue;
      seen[e.pageId] = true;
      picks.push(e);
    }
    return picks;
  }

  function itemHTML(entry, n, terms) {
    var icon = entry.headingId ? ICON_SECTION : ICON_PAGE;
    var snip = snippet(entry.text, terms);
    return '<a class="cmdk-item" role="option" id="cmdk-opt-' + n + '" data-i="' + n +
      '" href="#' + entry.pageId + '" aria-selected="false">' +
      '<span class="cmdk-item-icon">' + icon + '</span>' +
      '<span class="cmdk-item-main">' +
        '<span class="cmdk-item-title">' + mark(entry.title, terms) + '</span>' +
        (snip ? '<span class="cmdk-item-snippet">' + snip + '</span>' : '') +
      '</span>' +
      (entry.path ? '<span class="cmdk-item-path">' + esc(entry.path) + '</span>' : '') +
      '</a>';
  }

  function render(q) {
    lastQuery = q;
    var terms = tokenize(q);
    var data;
    if (!terms.length) {
      data = defaultEntries();
    } else {
      var hits = [];
      for (var i = 0; i < searchIndex.length; i++) {
        var sc = scoreEntry(searchIndex[i], terms);
        if (sc > 0) hits.push({ entry: searchIndex[i], score: sc, order: i });
      }
      hits.sort(function (a, b) { return b.score - a.score || a.order - b.order; });
      data = hits.slice(0, 14).map(function (h) { return h.entry; });
    }

    currentItems = data;
    if (!data.length) {
      resultsEl.innerHTML = '<div class="cmdk-empty">' + esc(ui().noResults) +
        ' <strong>&ldquo;' + esc(q.trim()) + '&rdquo;</strong></div>';
      activeIndex = -1;
      searchInput.removeAttribute('aria-activedescendant');
      return;
    }

    var groupDefault = ui().searchSuggested || 'Suggested';
    var html = '', lastCat = null, n = 0;
    data.forEach(function (entry) {
      var cat = terms.length ? (entry.category || groupDefault) : groupDefault;
      if (cat !== lastCat) {
        if (lastCat !== null) html += '</div>';
        html += '<div class="cmdk-group"><div class="cmdk-group-label">' + esc(cat) + '</div>';
        lastCat = cat;
      }
      html += itemHTML(entry, n, terms);
      n++;
    });
    if (lastCat !== null) html += '</div>';
    resultsEl.innerHTML = html;
    setActive(0);
  }

  function setActive(idx) {
    var items = resultsEl.querySelectorAll('.cmdk-item');
    if (!items.length) { activeIndex = -1; searchInput.removeAttribute('aria-activedescendant'); return; }
    if (idx < 0) idx = items.length - 1;
    if (idx >= items.length) idx = 0;
    activeIndex = idx;
    for (var i = 0; i < items.length; i++) {
      var on = i === idx;
      items[i].classList.toggle('is-active', on);
      items[i].setAttribute('aria-selected', on ? 'true' : 'false');
      if (on) {
        searchInput.setAttribute('aria-activedescendant', items[i].id);
        items[i].scrollIntoView({ block: 'nearest' });
      }
    }
  }

  var FLASH_MS = 7000; // keep in sync with the .doc-flash animation duration in styles.css
  var flashTimer = null;
  function flashTarget(el) {
    if (!el) return;
    // Clear any previous highlight so consecutive searches never leave two lines marked.
    var prev = document.querySelectorAll('.doc-flash');
    for (var i = 0; i < prev.length; i++) prev[i].classList.remove('doc-flash');
    if (flashTimer) window.clearTimeout(flashTimer);
    void el.offsetWidth; // restart the animation if the same target is reopened
    el.classList.add('doc-flash');
    flashTimer = window.setTimeout(function () {
      el.classList.remove('doc-flash');
      flashTimer = null;
    }, FLASH_MS);
  }

  function goTo(entry) {
    closePalette();
    var terms = tokenize(lastQuery);
    var phrase = lastQuery.trim().toLowerCase();
    var needNav = fromHash() !== entry.pageId;
    var reveal = function () {
      var page = document.getElementById(entry.pageId);
      var heading = entry.headingId ? document.getElementById(entry.headingId) : null;
      if (heading) { try { setActiveToc(entry.headingId); } catch (e) {} }
      // Land on the exact line that matched; fall back to the heading, then the page title.
      var scope = heading ? [heading].concat(sectionSiblings(heading)) : (page ? [page] : []);
      var target = matchElement(scope, terms, phrase) || heading || (page ? page.querySelector('h1') : null);
      if (!target) return;
      flashTarget(target);
      // Anchor the line to a fixed spot just below the fixed header. Exact-offset scrolling is
      // deterministic and, with the reset suppressed, is never fought or landed mid-screen.
      var header = document.querySelector('.site-header');
      var margin = (header ? header.offsetHeight : 68) + 24;
      var y = Math.max(0, target.getBoundingClientRect().top + window.pageYOffset - margin);
      window.scrollTo({ top: y, behavior: reducedMotionQuery.matches ? 'auto' : 'smooth' });
    };
    if (needNav) {
      // Suppress this page's scroll-to-top reset and run reveal() right after it renders,
      // hooked to the hashchange rather than a timing guess.
      skipScrollReset = true;
      pendingReveal = reveal;
      location.hash = '#' + entry.pageId;
    } else {
      reveal();
    }
  }

  function openPalette() {
    if (!cmdk || !cmdk.hidden) return;
    lastFocus = document.activeElement;
    cmdk.hidden = false;
    document.body.classList.add('cmdk-open');
    searchInput.value = '';
    searchInput.setAttribute('aria-expanded', 'true');
    render('');
    window.setTimeout(function () { searchInput.focus(); }, 0);
  }

  function closePalette() {
    if (!cmdk || cmdk.hidden) return;
    cmdk.hidden = true;
    document.body.classList.remove('cmdk-open');
    searchInput.setAttribute('aria-expanded', 'false');
    searchInput.removeAttribute('aria-activedescendant');
    var restore = (lastFocus && lastFocus.focus && lastFocus !== document.body) ? lastFocus : searchTrigger;
    if (restore && restore.focus) restore.focus();
  }

  if (searchTrigger) searchTrigger.addEventListener('click', openPalette);

  if (cmdk && searchInput && resultsEl) {
    searchInput.addEventListener('input', function () { render(searchInput.value); });

    searchInput.addEventListener('keydown', function (ev) {
      if (ev.key === 'ArrowDown') { ev.preventDefault(); setActive(activeIndex + 1); }
      else if (ev.key === 'ArrowUp') { ev.preventDefault(); setActive(activeIndex - 1); }
      else if (ev.key === 'Home') { ev.preventDefault(); setActive(0); }
      else if (ev.key === 'End') { ev.preventDefault(); setActive(currentItems.length - 1); }
      else if (ev.key === 'Tab') { ev.preventDefault(); }
      else if (ev.key === 'Enter') {
        ev.preventDefault();
        if (activeIndex >= 0 && currentItems[activeIndex]) goTo(currentItems[activeIndex]);
      } else if (ev.key === 'Escape') {
        ev.preventDefault();
        closePalette();
      }
    });

    resultsEl.addEventListener('mousemove', function (ev) {
      var item = ev.target.closest('.cmdk-item');
      if (item) {
        var i = parseInt(item.getAttribute('data-i'), 10);
        if (i !== activeIndex) setActive(i);
      }
    });

    resultsEl.addEventListener('click', function (ev) {
      var item = ev.target.closest('.cmdk-item');
      if (!item) return;
      ev.preventDefault();
      var i = parseInt(item.getAttribute('data-i'), 10);
      if (currentItems[i]) goTo(currentItems[i]);
    });

    Array.prototype.slice.call(cmdk.querySelectorAll('[data-cmdk-close]')).forEach(function (el) {
      el.addEventListener('click', closePalette);
    });

    document.addEventListener('keydown', function (ev) {
      var typing = /^(INPUT|TEXTAREA|SELECT)$/.test((document.activeElement || {}).tagName || '');
      if ((ev.ctrlKey || ev.metaKey) && ev.key.toLowerCase() === 'k') {
        ev.preventDefault();
        if (cmdk.hidden) openPalette(); else closePalette();
      } else if (ev.key === '/' && !typing && cmdk.hidden) {
        ev.preventDefault();
        openPalette();
      }
    });
  }

  document.addEventListener('click', function (ev) {
    if (ev.defaultPrevented || ev.button !== 0 || ev.metaKey || ev.ctrlKey || ev.shiftKey || ev.altKey) return;
    var link = ev.target.closest('a[href^="#"]');
    if (!link) return;
    var route = (link.getAttribute('href') || '').slice(1);
    if (order.indexOf(route) === -1) return;
    ev.preventDefault();
    if (fromHash() !== route) history.pushState(null, '', '#' + route);
    show(route);
  });

  window.addEventListener('hashchange', function () {
    show(fromHash());
    if (pendingReveal) { var run = pendingReveal; pendingReveal = null; run(); }
  });
  window.addEventListener('scroll', scheduleTocSync, { passive: true });
  var copyStatus = document.createElement('span');
  copyStatus.className = 'sr-only';
  copyStatus.setAttribute('aria-live', 'polite');
  document.body.appendChild(copyStatus);
  setupCodeBlocks();
  var initialRoute = fromHash();
  applyLanguage(currentLanguage, false);
  show(initialRoute);
  function settleInitialRoute() {
    window.requestAnimationFrame(function () {
      window.requestAnimationFrame(function () {
        if (fromHash() === initialRoute) window.scrollTo(0, 0);
      });
    });
  }
  if (document.readyState === 'complete') settleInitialRoute();
  else window.addEventListener('load', settleInitialRoute, { once: true });
})();
