---
title: Anuncios
description: Anuncios automáticos que esperan a que el chat esté vivo, avisos rápidos en chat, título, barra de acción, bossbar o notificación, y un compositor animado.
---

Chatalyst tiene dos tipos de anuncios. El **anunciador** rota tus mensajes solo y, en modo inteligente, solo habla cuando el chat está vivo de verdad, así que un servidor tranquilo no se llena de spam. Para avisos puntuales, el staff usa `/broadcast` o el compositor `/announce`, que añade pantallas, sonidos, estilos y animaciones desde un menú.

Los anuncios automáticos se enseñan en el idioma de cada jugador: los clientes en inglés leen `Messages` y los clientes en español leen `Messages_es`.

## Cómo se activa

El anunciador viene encendido. Está en `plugins/Chatalyst/bot.yml`:

```yaml
Announcer:
  Enabled: true
  Interval: 300          # seconds between announcements
  Random: false          # true = random order
  Smart:
    Enabled: true
    Min_Players: 2         # below this online count, never announce
    Min_Chatters: 3        # fixed minimum of different players chatting
    Chatters_Percent: 10   # ...or this % of online players, whichever is higher
  Prefix: "<#7B68A0>[<#B56CFF>Info<#7B68A0>] <reset>"
  # Title shown on "/broadcast toast <message>" (plain text; toasts don't support colors).
  Toast_Title: "Announcement"
  Toast_Title_es: "Anuncio"
  Messages:
    - "<#C0B3D9>Remember to respect other players."
    - "<#C0B3D9>Switch channels with <#FBF2FF>/channel<#C0B3D9>."
    - "<#C0B3D9>Send mail to anyone with <#FBF2FF>/inbox<#C0B3D9>."
  Messages_es:
    - "<#C0B3D9>Recuerda respetar a los demas jugadores."
    - "<#C0B3D9>Cambia de canal con <#FBF2FF>/channel<#C0B3D9>."
    - "<#C0B3D9>Envia correo a cualquiera con <#FBF2FF>/inbox<#C0B3D9>."
```

Edita los mensajes y ejecuta `/cht reload`.

## Configuración

### Anunciador automático

| Opción | Por defecto | Qué hace |
|---|---|---|
| `Enabled` | `true` | Enciende el anunciador. |
| `Interval` | `300` | Segundos entre anuncios. |
| `Random` | `false` | `true` = orden aleatorio en vez del orden de la lista. |
| `Smart.Enabled` | `true` | Solo anuncia cuando el chat está activo (ver abajo). |
| `Smart.Min_Players` | `2` | Con menos jugadores conectados, nunca anuncia. |
| `Smart.Min_Chatters` | `3` | Mínimo de jugadores distintos que tienen que haber escrito desde el último anuncio. |
| `Smart.Chatters_Percent` | `10` | O este porcentaje de los conectados, lo que sea mayor. |
| `Prefix` | `[Info]` | Texto delante de cada anuncio automático. |
| `Messages` / `Messages_es` | 3 ejemplos | Los anuncios. Las dos listas se emparejan por posición. |

En modo inteligente, con 100 jugadores y `Chatters_Percent: 10`, tienen que haber escrito diez jugadores distintos desde el anuncio anterior. Con 5 jugadores se aplica el mínimo fijo de 3. Si el chat está callado, el anuncio espera a un intervalo posterior; no se pierde nada.

Si falta una línea en español, los jugadores en español ven la inglesa. La consola usa inglés.

### Avisos rápidos

`/broadcast [chat|title|actionbar|bossbar|toast] <mensaje>` manda un mensaje estático a una pantalla. Sin pantalla, va al chat. `Toast_Title` y `Toast_Title_es` fijan el título de los avisos tipo notificación.

### El compositor /announce

`/announce` abre un menú para un anuncio cuidado:

1. Elige la pantalla: `EPIC` (título animado, chat enmarcado y bossbar animada a la vez), chat, título, barra de acción, bossbar o notificación.
2. Elige un sonido, un estilo de la paleta de colores del chat y una animación.
3. Pulsa el objeto del mensaje y escribe el texto en el chat (`cancelar` lo anula).
4. Elige el alcance: este servidor o toda la red.
5. Míralo antes en privado, o envíalo.

Animaciones disponibles: estática, máquina de escribir, expansión, glitch, onda de color, fotogramas, pulso, destello de alerta, marquesina y cuenta atrás. Se aplican al título, la barra de acción y la bossbar; el chat y la notificación son estáticos. Las animaciones duran unos ocho segundos.

La animación de **fotogramas** usa tus propias plantillas de `bot.yml`, donde `{msg}` es el texto compuesto:

```yaml
  Animation:
    Interval_Ticks: 6
    Frames:
      - "<gradient:#240046:#7B2CBF>{msg}</gradient>"
      - "<gradient:#7B2CBF:#9D4EDD>{msg}</gradient>"
      - "<gradient:#9D4EDD:#C77DFF>{msg}</gradient>"
      - "<gradient:#C77DFF:#E0AAFF>{msg}</gradient>"
```

`Interval_Ticks` admite de 2 a 200.

### Anuncios a toda la red

El alcance «toda la red» aparece cuando `Proxy.Enabled` y `Network_Admin.Announcements.Enabled` (en `network.yml`) están encendidos. Cada servidor dibuja el anuncio por su cuenta, con la misma pantalla, estilo, sonido y animación. Ver [Redes con proxy y Redis](network).

## Comandos y permisos

| Comando | Permiso (por defecto) | Qué hace |
|---|---|---|
| `/announce` | `chatalyst.command.announce` (op) | Abre el compositor. |
| `/broadcast [pantalla] <mensaje>` (`/bc`) | `chatalyst.command.broadcast` (op) | Manda un aviso estático. |
| `/toggle announcements` | `chatalyst.command.toggle` (true) | El jugador deja de ver el anunciador automático. |

| Permiso | Por defecto | Qué permite |
|---|---|---|
| `chatalyst.admin.network.announce` | op | Enviar `/announce` a toda la red. |
| `chatalyst.command.broadcast.chat`, `.title`, `.actionbar`, `.bossbar`, `.toast` | false | Una pantalla cada uno (ver Límites). |

## Límites

- `/toggle announcements` solo oculta el anunciador automático. Los avisos del staff y `/announce` siguen llegando a todos.
- El servidor comprueba `chatalyst.command.broadcast` antes de ejecutar `/broadcast`, y ese nodo ya permite todas las pantallas. Así que hoy los nodos por pantalla no sirven por sí solos para dar a alguien una sola pantalla.
- El destello de alerta cambia de color muy rápido. Para avisos de rutina, usa pulso u onda de color.
- Los anuncios automáticos son por servidor. Para un mensaje a toda la red, usa el alcance de red del compositor.
