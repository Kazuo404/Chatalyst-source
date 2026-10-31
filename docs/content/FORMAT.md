# Wiki page format (contract for everyone who writes pages)

Every page is two files: `docs/content/en/<slug>.md` and `docs/content/es/<slug>.md`, with the
slugs from `nav.json`. Spanish is a full page of its own, written naturally (not a word-by-word
translation) and with exactly the same sections as the English page.

## Front matter (required)

```
---
title: Channels
description: One sentence, max ~160 chars. Used as the page lead, the <meta> description and in search.
---
```

`title` must match the title in `nav.json` for that language.

## Body

- Start with one or two short paragraphs that say what the feature is FOR (the reader is a
  server owner deciding whether to use it). Then how to turn it on, then how to configure it.
- Headings: `##` and `###` only (the title is the h1). They become the "On this page" menu.
- Fenced code blocks ALWAYS carry a language: ```yaml, ```text, ```java, ```properties.
  YAML examples must be copied from the real file in `src/main/resources/` (same keys, same
  defaults). Never invent an option.
- Tables for option lists: `| Option | Default | What it does |`.
- Commands and permission nodes in inline code: `/channel join <name>`, `chatalyst.channel.join`.
- Callouts (the only "special" syntax):

```
> [!NOTE]
> Text.

> [!TIP]
> Text.

> [!WARNING]
> Text.

> [!DANGER]
> Text.
```

  The build translates the label (Note/Nota, Tip/Consejo, Warning/Aviso, Danger/Peligro).
- Links to other wiki pages: `[Channels](channels)` — just the slug, no extension, no language.
  Anchors work: `[the filter](chat-filter#anti-spam)`. Heading ids are the heading text in
  lowercase with spaces → `-` and accents/punctuation removed.
- No raw HTML. No images (for now).

## Tone

Professional product documentation: second person ("you"), present tense, short sentences,
concrete. No internal jargon, no code class names, no history of how it was built, no author
notes. Explain limits honestly (what it does NOT do). Default values must be the real ones.

## Sources of truth (read before writing)

1. The code in `src/main/java/dev/chatalyst/<module>/` and the config files in
   `src/main/resources/` (they have inline comments). The code wins over any old doc.
2. The previous site `docs/index.html` (the `<article id="...">` for your topic) and
   `docs/wiki/*.md` — good starting material, but check every claim against the code.
3. Commands/permissions: `src/main/resources/plugin.yml` plus commands registered at runtime
   (grep for `register(` / `Command_Priority` in the module).

The pages `commands` and `permissions` are generated from plugin.yml by the build; do not
write them. Link to them instead: `[all commands](commands)`.
