---
title: Placeholders
description: Todos los {placeholders} que puedes usar en formatos y mensajes de Chatalyst, tus variables propias y los %chatalyst_...% para PlaceholderAPI y TAB.
---

Los placeholders son palabras entre llaves que Chatalyst cambia por datos en vivo: `{player_nick}` se convierte en el apodo del jugador y `{ping_ms}` en su latencia coloreada. Se usan en los [formatos de chat](formats), el tabulador, los [nametags](nametags), los mensajes de entrada y casi cualquier otro texto.

Chatalyst también funciona al revés: publica placeholders `%chatalyst_...%` para que PlaceholderAPI, TAB, los scoreboards y otros plugins enseñen datos de Chatalyst.

## Cómo se activa

Los placeholders internos `{...}` funcionan siempre. Para usar en Chatalyst los `%...%` de otros plugins, o los `%chatalyst_...%` en otros plugins, instala [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/). Chatalyst registra su expansión solo; no hay nada que descargar.

## Configuración

### Placeholders del jugador

| Placeholder | Valor |
|---|---|
| `{player_name}` / `{player}` | El nombre de la cuenta. |
| `{player_nick}` | El apodo con sus colores, o el nombre si no tiene. |
| `{rank_nick}` | El apodo con el color de apodo del jugador, o con el color del rango si no eligió ninguno. |
| `{nick_color}` | El color del apodo como etiqueta MiniMessage, o vacío. |
| `{player_display}` | El display name de Bukkit como texto plano. |
| `{player_prefix}` / `{player_suffix}` | Prefijo y sufijo del rango, de LuckPerms o, si no está, de Vault. |
| `{player_group}` | El id interno del grupo principal (por ejemplo `mvpplusplus`). |
| `{rank_tag}` | El prefijo del rango, o el nombre del grupo si el grupo no tiene prefijo. |
| `{rank_color}` | El primer color del prefijo del rango. |
| `{rank_gradient}` | Un degradado neón hecho a partir del color del rango. Siempre abre una etiqueta `<gradient>`. |
| `{world}` | El nombre del mundo. |
| `{ping}` | La latencia en milisegundos, como número. |
| `{ping_ms}` | La latencia coloreada según la calidad (`23ms` en verde, `320ms` en rojo). |
| `{ping_quality}` | Excellent, Good, Fair, Slow o Laggy. |
| `{ping_bars}` | Barras de señal de colores. |
| `{party}` / `{party_tag}` | El nombre de la party de Parties y la etiqueta decorada de `Chat.Party_Tag`. Vacíos sin party. |
| `{afk}` / `{afk_tag}` | `yes`/`no`, y el sufijo de AFK o vacío. |
| `{tag_prefix}` / `{tag_suffix}` | El [título](tags) equipado o el tag personal, en su lado. |
| `{nametag_title}` | El título del [nametag](nametags) flotante. |
| `{nametag_icon_left}` / `{nametag_icon_right}` | Los iconos laterales del nametag. |
| `{married_to}` / `{married_tag}` | El nombre de la pareja y la línea de hover de `Marriage.Tag`. Vacíos si está soltero. Ver [Matrimonios](marriage). |
| `{msg_command}` | Lo que se sugiere al pulsar el nombre: `/inbox send <nombre>` o `/msg <nombre>`. |
| `{ranks_command}` | El comando de la tienda de rangos con su nombre actual. |

### Placeholders del mensaje

| Placeholder | Dónde | Valor |
|---|---|---|
| `{message}` | Formatos de chat | El mensaje del jugador. Se inserta de forma segura: nadie puede colar formato a través de él. |
| `{message_uuid}` | Formatos de chat | Id que usa el botón de borrar. |
| `{sender_name}` / `{receiver_name}` | Formatos de mensajes privados | Los dos lados de un [mensaje privado](private-messages). |
| `{$etiqueta}` | Cualquier formato | Una etiqueta traducida de los archivos de idioma, por ejemplo `{$rank}` o `{$ping}`. Cada lector la ve en su idioma. |

### Variables propias

Define un texto reutilizable una vez en `integrations.yml` y úsalo en cualquier sitio como `{nombre}`:

```yaml
Variables:
  server_name: "<gradient:#240046:#9D4EDD:#E0AAFF>YourServer</gradient>"
  discord_link: "discord.gg/example"
  # SCRIPT variables (JavaScript). Start the value with "js:". You get 'player'
  # (the player) and 'playerName'. Examples:
  #   {vip_tag}  ->  shows [VIP] only if the player has the permission.
  vip_tag: "js: player.hasPermission('chatalyst.vip') ? '<#D8B4FE>[VIP] ' : ''"
  #   {name_len} ->  the length of the player's name.
  name_len: "js: player.getName().length()"
```

Un valor que empieza por `js:` es una pequeña expresión de JavaScript. Solo recibe `player` y `playerName`, y las clases peligrosas están bloqueadas. Las variables con script necesitan `Scripting.Enabled: true` (lo de fábrica) y la librería Nashorn, que Paper descarga al arrancar.

### Placeholders de PlaceholderAPI

Con PlaceholderAPI instalado, otros plugins pueden leer:

| Placeholder | Valor |
|---|---|
| `%chatalyst_nick%` | El apodo, o el nombre. |
| `%chatalyst_nickcolor%` | El color principal del apodo como código legacy (`&c` o `&#RRGGBB`). |
| `%chatalyst_channel%` | El canal en el que escribe el jugador. |
| `%chatalyst_chatcolor%` | La etiqueta del color de chat elegido. |
| `%chatalyst_muted%` / `%chatalyst_mute_reason%` | `yes`/`no` y el motivo del silencio. |
| `%chatalyst_warnings%` | Puntos de advertencia. |
| `%chatalyst_afk%` | `yes`/`no`. |
| `%chatalyst_prefix%` / `%chatalyst_suffix%` / `%chatalyst_group%` | Prefijo, sufijo y grupo del rango. En [modo anónimo](anonymous) enseñan el disfraz. |
| `%chatalyst_anonymous%` | `yes`/`no`. |
| `%chatalyst_partner%` / `%chatalyst_married%` | Nombre de la pareja y `yes`/`no`. |
| `%chatalyst_fx_<nombre>%` / `%chatalyst_fx_auto%` | Fotograma actual de un tag de rango animado. Ver [Tags, títulos y efectos](tags). |
| `%chatalyst_title_id%`, `_name`, `_text`, `_prefix`, `_suffix`, `_rarity`, `_category`, `_position`, `_season`, `_points` | El título equipado. |
| `%chatalyst_achievement_points%`, `%chatalyst_achievements_earned%`, `_total`, `_percent` | Progreso de logros. |
| `%chatalyst_collections_completed%` / `%chatalyst_collections_total%` | Progreso de colecciones. |

> [!TIP]
> En TAB y en los scoreboards, usa `%chatalyst_prefix%` en vez de `%luckperms_prefix%`: no delata el rango real de quien está en modo anónimo.

### Placeholders de TAB

Con el plugin TAB instalado, Chatalyst registra `%chatalyst_nick%`, `%chatalyst_nick_plain%`, `%chatalyst_channel%`, `%chatalyst_muted%`, `%chatalyst_warnings%` y `%chatalyst_chatcolor%` directamente en TAB, sin PlaceholderAPI. Ver [Nametags flotantes](nametags).

## Comandos y permisos

Los placeholders no tienen comandos ni permisos propios.

## Límites

- Un `{token}` desconocido se deja tal cual, así que las erratas se ven enseguida.
- Los placeholders se sustituyen en una sola pasada. Un valor que contenga `{...}` (por ejemplo un apodo) no se vuelve a sustituir, así que nadie puede colar placeholders.
- En [modo anónimo](anonymous), los placeholders de rango, tags, party, matrimonio y nametag se cambian por el disfraz o quedan vacíos, y `{player_name}` pasa a ser el alias.
