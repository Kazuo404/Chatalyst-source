# Third-party notices

Chatalyst is proprietary software. It includes or uses the open-source components below, each
under its own license. Nothing here changes the terms of those components.

## Included in the Chatalyst jar

| Component | Version | License |
|---|---|---|
| JDA (Java Discord API) | 6.4.2 | Apache-2.0 |
| OkHttp | 5.3.2 | Apache-2.0 |
| Okio | 3.16.4 | Apache-2.0 |
| Jackson Core, Databind, Annotations | 2.22 | Apache-2.0 |
| nv-websocket-client | 2.14 | Apache-2.0 |
| Apache Commons Collections | 4.5.0 | Apache-2.0 |
| Tink | 1.18.0 | Apache-2.0 |
| Gson | 2.10.1 | Apache-2.0 |
| Error Prone Annotations | 2.22.0 | Apache-2.0 |
| Kotlin Standard Library | 2.2.21 | Apache-2.0 |
| JetBrains Annotations | 13.0 | Apache-2.0 |
| JSR-305 annotations | 3.0.2 | BSD-3-Clause |
| Protocol Buffers for Java | 4.28.2 | BSD-3-Clause |
| SLF4J API | 2.0.18 | MIT |

The license and notice files shipped by these projects are kept under `META-INF/`.

## Downloaded by the server at startup, not included in the jar

Paper downloads these from Maven Central, as listed under `libraries` in `plugin.yml`. They stay
separate files that the server owner can replace.

| Component | Version | License |
|---|---|---|
| GNU Trove | 3.1.0 | LGPL-2.1-or-later |
| Nashorn | 15.4 | GPL-2.0 with Classpath Exception |
| SQLite JDBC | 3.46.1.3 | Apache-2.0 |
| MySQL Connector/J | 8.4.0 | GPL-2.0 with Universal FOSS Exception |

## Optional integrations

Chatalyst compiles against the public APIs of these plugins, but does not include them. Each one
is installed separately by the server owner, under its own terms: Paper, PlaceholderAPI,
LuckPerms, Vault, ItemsAdder, Nexo, WorldGuard, Parties, TAB, EssentialsX, PacketEvents, dynmap,
Simple Voice Chat, Plasmo Voice and LibsDisguises. Their names belong to their owners.
