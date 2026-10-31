# Release audit — 2026-07-22

This document closes the documentation-to-diff review for the current Chatalyst 1.0.0 delivery.
It covers the complete pending worktree rather than one isolated feature. It is evidence of code,
configuration and deterministic test review; it does not replace a live Paper/Purpur/Folia and
third-party-plugin compatibility matrix.

## Delivery map

| Area | Operator documentation | Architecture / release evidence |
|---|---|---|
| Market, auctions and direct trade | [Market and Direct Trade](market.md) | [ADR 0009](../adr/0009-market-contract-ui-and-durable-drafts.md) |
| Unified premium GUI, categories and pages | [Palette](paleta.md) | [ADR 0010](../adr/0010-unified-purple-space-gui-system.md) |
| Floating/TAB/chat nametag animation and announce frames | [Floating Nametags](nametags.md), [Announcements](announcements.md) | Changelog and animation regression tests |
| Anonymous mode, inventory sharing and container audit | [Container Log, Inventory Sharing and Anonymous Mode](spy-anonymous.md) | Session-identity, audit and bounded-token tests |
| Marriage and ceremony | [Marriage](marriage.md) | State, ceremony and storage regression tests |
| Rules, logging and command credential safety | [Rules Engine](rules-engine.md) | [ADR 0011](../adr/0011-sensitive-command-redaction-boundary.md), projected-text regressions |
| Storage reload and network hardening | [Storage and Database](database.md), [Proxy and Redis](proxy-redis.md), [Smoke Test](smoke-test.md) | [ADR 0012](../adr/0012-targeted-rule-console-protocol.md), codec and operation-gate tests |
| Staff moderation and premium punishment screens | [Staff Moderation Suite](staff-suite.md) | Language/template preservation and command-catalog tests |

Every player-visible runtime string added by this delivery lives in `lang/en_US.yml` and
`lang/es_ES.yml`; Java key coverage and exact language-key parity are build gates. Configuration
version 30 migrates factory GUI colours and templates idempotently while preserving customized
owner text and player-selectable palettes.

## Security and reliability result

The cumulative whole-plugin review closed 25 defects in this delivery. The reviewed surfaces
include lifecycle/reload, YAML/SQLite/MySQL durability, Redis and proxy codecs, HTTP consumers,
Folia region ownership, market escrow, GUI authorization, anonymity boundaries, audit logging and
the rules engine.

The highest-impact final finding was a credential disclosure path in rule output. A command such
as `/login secret.example` could match a broad advertising/IP rule and then reach a `RULE` log,
staff Discord alert or `Notify` template through `{original_message}`. The shared sensitive-command
boundary now preserves only `/login <redacted>` for external observers. Mandatory labels remain
protected even when configurable lists are replaced, and tab-separated arguments cannot bypass
the boundary. The sender-only warning remains unchanged.

Other release-wide guarantees verified during the review include:

- bounded rule counts, names, regex work, report/mail text, HTTP response bodies and network
  buffers;
- atomic or durable state transitions for reload, market drafts, escrow, claims, confirmations
  and player-scoped storage;
- fail-closed authentication and capacity limits for proxy/Redis traffic;
- per-viewer identity and localization without leaking anonymous real names;
- stable, clamped pagination and permission rechecks at GUI action boundaries.

The final rules v2.1 verification found six additional integration defects after the initial
feature draft: accent/UUID preprocessing could mutate delivered text; remote console ignored its
target and duplicated in dual-route mode; authenticated peers had no local command authorization;
the Discord rule context was not wired; malformed/duplicate imports could fail silently or run a
rule twice; and `@prolong` truncated supplementary Unicode. Matching now projects onto original
text, imports are strict/first-wins, linked online Discord authors use the entity scheduler, and
remote console uses a targeted, allowlisted, deduplicated and audited packet.

## Verification baseline

- Clean Gradle build and complete automated suite: 551 tests, zero failures.
- YAML parsing, language parity, Java language-key coverage and plugin descriptor checks.
- Ed25519 signature/integrity verification of the release jar.
- Staged update jar hash compared after deployment.
- `git diff --check` and a repository scan for private keys, webhook tokens and common provider
  credentials before commit.

The remaining operational risk is the real-server matrix: run the bilingual [smoke-test
checklist](smoke-test.md) on the exact Paper/Purpur/Folia and integration-plugin versions used in
production before restarting a live network.
