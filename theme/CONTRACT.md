# Theme contract (design ↔ build)

The build (`docs/build/build.mjs`) turns `docs/content/<lang>/*.md` into
static pages `docs/<lang>/<slug>.html`. The design owns ONLY these files:

- `docs/theme/page.html`  — the HTML shell of every page (placeholders below)
- `docs/theme/home.en.html`, `docs/theme/home.es.html` — body of the landing page per language
  (inserted as `{{content}}` of page.html with `{{pageClass}}` = `is-home`)
- `docs/theme/theme.css`  — all styles
- `docs/theme/theme.js`   — all behaviour (no framework)
- optional `docs/theme/assets/*` (svg/png/webp, favicon, social card 1200×630)

## Placeholders in page.html (the build replaces them literally)

| Placeholder | Value |
|---|---|
| `{{lang}}` | `en` or `es` (use in `<html lang>`) |
| `{{title}}` | page title (plain text, already escaped) |
| `{{description}}` | one-sentence description (escaped) |
| `{{siteName}}` | `Chatalyst Docs` / `Docs de Chatalyst` |
| `{{root}}` | relative path to the docs root, e.g. `../` — prefix EVERY asset url with it: `{{root}}theme/theme.css` |
| `{{canonical}}` | absolute canonical URL of this page |
| `{{altLang}}` / `{{altLangUrl}}` | the other language code and the URL of this same page in it |
| `{{hreflang}}` | ready-made `<link rel="alternate" hreflang=...>` tags |
| `{{nav}}` | sidebar HTML (structure below) |
| `{{crumb}}` | group name of the page (e.g. "Chat") — empty on home |
| `{{content}}` | page body HTML (structure below) |
| `{{toc}}` | "On this page" list HTML — may be empty |
| `{{pager}}` | previous/next HTML — may be empty |
| `{{pageClass}}` | `is-home` or `is-doc` (put on `<body>`) |
| `{{i18n}}` | a `<script>` that defines `window.WIKI = {lang, root, strings:{...}}` — include it before theme.js |
| `{{year}}` | current year |

Header must contain: brand (link to `{{root}}{{lang}}/`), search button (opens a Ctrl/⌘+K
dialog), EN/ES switch (link to `{{altLangUrl}}`), light/dark toggle, links to Discord
(`https://discord.gg/`… placeholder href `#discord`) and the store. Mobile: sidebar becomes a
drawer. Footer: small, © {{year}} DevKazuo.

## HTML the build generates (style these exact classes)

Sidebar `{{nav}}`:
```html
<nav class="wiki-nav" aria-label="...">
  <div class="nav-group">
    <p class="nav-group-title">Chat</p>
    <a class="nav-link" href="../en/channels.html">Channels</a>
    <a class="nav-link is-active" aria-current="page" href="...">Formats</a>
  </div>
</nav>
```

Body `{{content}}` on doc pages:
```html
<header class="doc-header">
  <p class="crumb">Chat</p>
  <h1>Channels</h1>
  <p class="lead">description</p>
</header>
<article class="prose">
  <h2 id="setup"><a class="anchor" href="#setup">#</a>Setup</h2>
  <p>… <code>inline</code> … <a href="channels.html">link</a></p>
  <div class="callout callout-note"><p class="callout-title">Note</p><p>…</p></div>
     (also callout-tip, callout-warning, callout-danger)
  <div class="code-block"><div class="code-head"><span class="code-lang">yaml</span>
     <button class="copy-btn" type="button">Copy</button></div>
     <pre><code class="language-yaml">…escaped…</code></pre></div>
  <div class="table-wrap"><table>…</table></div>
  <ul>, <ol>, <blockquote>, <hr>, <strong>, <em>
</article>
```
Generated reference pages (commands, permissions) additionally use:
```html
<input class="ref-filter" type="search" placeholder="Filter…">
<div class="table-wrap"><table class="ref-table">…<code class="perm">node</code>… <span class="badge badge-op">op</span> <span class="badge badge-true">everyone</span> <span class="badge badge-false">nobody</span></table></div>
```
(theme.js: typing in `.ref-filter` hides `.ref-table tbody tr` rows that don't match.)

`{{toc}}`:
```html
<ul class="toc"><li class="toc-h2"><a href="#setup">Setup</a></li><li class="toc-h3">…</li></ul>
```
`{{pager}}`:
```html
<nav class="pager"><a class="pager-prev" href="…"><span>Previous</span><strong>Title</strong></a>
<a class="pager-next" href="…"><span>Next</span><strong>Title</strong></a></nav>
```

## Search

The build writes `{{root}}search/<lang>.json`:
`[{ "url": "channels.html", "title": "...", "group": "Chat", "description": "...", "headings": ["..."], "text": "first ~1500 chars of plain text" }]`
(`url` is relative to `{{root}}<lang>/`). theme.js loads it lazily when the dialog opens,
ranks title > headings > description > text, keyboard navigation (↑ ↓ Enter Esc).

## Other behaviour for theme.js

Theme toggle persisted in localStorage (wrapped in try/catch; default = system preference),
copy buttons, scroll-spy on `.toc`, mobile drawer, syntax highlighting (highlight.js from
cdnjs is fine, or none). Everything must work with JavaScript disabled except search.

## Strings

`window.WIKI.strings` provides: search, searchPlaceholder, noResults, copy, copied, onThisPage,
previous, next, menu, close, themeLight, themeDark. Don't hardcode UI text in theme.js.

## Constraints

External resources only from cdnjs.cloudflare.com, cdn.jsdelivr.net and Google Fonts.
Accessible (contrast AA, focus rings, skip link, aria labels), responsive down to 360px, no
horizontal scroll, works when opened from GitHub Pages under a sub-path (hence `{{root}}`).
