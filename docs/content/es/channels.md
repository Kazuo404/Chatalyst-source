---
title: Canales
description: Salas de chat separadas para global, comercio, staff, radio, región y party, con historial y un enrutador que saca las ventas del chat global.
---

Los canales dividen el chat en salas. Cada jugador escribe en un canal a la vez y lee los canales que tiene permitido escuchar. En un survival puedes dejar las ventas en `trade`, al staff en `staff` y las charlas cercanas en una frecuencia de radio, y el chat global sigue siendo legible.

Los canales vienen activados. La configuración de fábrica trae `global`, `local` (radio), `staff`, `region` y `party` listos para usar, y `trade` y `auction` preparados pero apagados.

## Tipos de canal

| Tipo | Quién recibe el mensaje |
|---|---|
| `GLOBAL` | Todo el servidor. Con `Proxy: true`, toda la red. |
| `WORLD` | Los jugadores del mismo mundo. |
| `FREQUENCY` | Los jugadores sintonizados en la misma frecuencia de radio, a cualquier distancia. |
| `REGION` | Los jugadores que están en la misma región de chat (regiones de Chatalyst o de WorldGuard). |
| `PARTY` | Los miembros de la misma party (plugin Parties). |

> [!NOTE]
> Sin WorldGuard o sin Parties, los canales `REGION` y `PARTY` funcionan como un canal normal, así que no se rompe nada.

## Cómo se activa

Todo está en `plugins/Chatalyst/chat.yml`, dentro de `Channels`. El módulo viene encendido:

```yaml
Channels:
  Enabled: true
  Default: global
  # Channel players WRITE in when they join the server:
  #   default = everyone starts back in the default channel (the general chat)
  #   last    = remember the channel they were using when they left
  On_Join: default
  Exclusive: true
```

Tras editar, ejecuta `/cht reload`.

## Configuración

### Opciones generales

| Opción | Por defecto | Qué hace |
|---|---|---|
| `Enabled` | `true` | Interruptor general del sistema de canales. |
| `Default` | `global` | El chat principal. Los jugadores nuevos escriben aquí. |
| `On_Join` | `default` | `default` devuelve a todos al canal principal al entrar. `last` recuerda el canal en el que salieron. |
| `Exclusive` | `true` | Los canales son salas separadas (ver abajo). Con `false` vuelven a solaparse. |
| `History.Enabled` | `true` | Guarda un historial corto en memoria por cada canal `GLOBAL`. |
| `History.Size` | `20` | Mensajes que se guardan por canal. |
| `History.Show_On_Switch` | `10` | Mensajes que se repiten al entrar en un canal. `0` = solo a petición. |
| `History.Retention_Minutes` | `30` | Los mensajes más antiguos se descartan. |
| `Frequency.Default` | `""` | Frecuencia con la que empiezan los jugadores nuevos. Vacío = radio apagada hasta que sintonicen. |

### Definir un canal

Cada canal es una entrada dentro de `Channels.List`. Así vienen `global` y `staff`:

```yaml
  List:
    global:
      Aliases: [g, global]
      Type: GLOBAL
      Permission: ""
      Format: chat
      Auto_Listen: true
      # Proxy: true forwards this channel across the whole network (needs Proxy.Enabled below).
      Proxy: false
    staff:
      Aliases: [s, sc, staff]
      Type: GLOBAL
      Permission: chatalyst.channel.staff
      Format: staff
      Auto_Listen: true
      # Staff must never miss staff messages, even with Channels.Exclusive on.
      Force_Listen: true
      # Required for support escalations and staff chat across Velocity/Bungee.
      Proxy: true
```

| Campo | Qué hace |
|---|---|
| `Enabled` | `false` oculta el canal de comandos, alias, autocompletado, enrutador y menú. |
| `Aliases` | Nombres cortos para `/channel <alias>`. |
| `Type` | Uno de los cinco tipos de arriba. |
| `Permission` | Vacío = cualquiera. Si no, hace falta para leer y escribir. |
| `Format` | El archivo de `formats/` que usa el canal. Ver [Formatos de chat](formats). |
| `Auto_Listen` | `true` = se oye sin estar dentro (solo con `Exclusive` apagado). |
| `Force_Listen` | Siempre se oye, incluso con `Exclusive` activo. Lo usan `staff` y `local` de fábrica. |
| `Proxy` | Lleva el canal a toda la red Velocity/BungeeCord/Redis. Ver [Redes con proxy y Redis](network). |
| `Auto_Enable_With_Market_Plugins` | Deja que un `trade` o `auction` apagado aparezca al detectar un plugin de mercado compatible. |

### Canales exclusivos

Con `Exclusive: true` (lo de fábrica) cada canal es una sala propia. Siempre oyes el canal principal (salvo que lo silencies) y el canal en el que escribes. Estar en `global` oculta `trade`, `auction` y los demás. Excepciones:

- los canales con `Force_Listen: true`;
- los canales a los que te suscribes con `/channel listen <nombre>`;
- los jugadores con `chatalyst.channel.seeall`.

Al cambiar de canal se repiten los últimos mensajes, marcados como ya enviados, para que te pongas al día al momento.

### Frecuencia de radio (chat local)

El canal `local` es una radio, no un chat por cercanía. Cada jugador sintoniza una frecuencia entre `10.00` y `99.99` y solo oye a quien está en la misma, a cualquier distancia. Con `Frequency.Default` vacío nadie está en la radio hasta que sintoniza. La frecuencia elegida se guarda.

- `/frequency` abre el sintonizador, `/frequency 42.50` sintoniza directamente y `/frequency off` apaga la radio.
- `/f <mensaje>` habla una vez por tu frecuencia sin salir del canal en el que estás.

Los mensajes de radio pasan por silencios, reglas, antispam, formato y registros como cualquier otro. El staff con `chatalyst.bypass.range` oye todas las frecuencias.

### Canales de comercio y subastas

`trade` y `auction` vienen con `Enabled: false`. Con `Auto_Enable_With_Market_Plugins: true`, Chatalyst los enciende en marcha al detectar un plugin de subastas o tiendas compatible (AuctionHouse, zAuctionHouse, CrazyAuctions, PlayerAuctions, AuctionMaster, QuickShop, ChestShop, ShopGUI+, EconomyShopGUI, DynamicShop, BossShopPro). Pon `Enabled: true` para que uno se vea siempre. El mercado integrado también publica aquí: ver [Mercado e intercambios](market).

### Regiones de chat sin WorldGuard

Los canales `REGION` funcionan sin WorldGuard. Ponte en una esquina y ejecuta `/cht region pos1`, ve a la esquina opuesta y ejecuta `/cht region pos2`, y luego `/cht region add <nombre> [canal]`. Las regiones se guardan en `regions.yml`. Si le das un canal a la región, los jugadores cambian a él al entrar y vuelven al salir. Con WorldGuard instalado, sus regiones también cuentan.

### Enrutador inteligente

El enrutador mantiene el chat global libre de spam de ventas. Los mensajes escritos en el canal principal que encajan con un patrón se mueven solos al canal de destino (o se bloquean con una pista).

```yaml
Channel_Router:
  Enabled: true
  Action: move           # move = deliver in the target channel · block = cancel + hint
  Notify: true           # tell the player their message was moved (at most once a minute)
  # One-off shortcuts: starting a message with the prefix sends it to that channel without
  # switching (player's explicit choice; the prefix is stripped). Example below.
  Shortcut_Prefixes: {}
  # Shortcut_Prefixes:
  #   "!": trade
  #   "?": auction
```

| Opción | Por defecto | Qué hace |
|---|---|---|
| `Enabled` | `true` | Enciende el enrutador. |
| `Action` | `move` | `move` entrega el mensaje en el canal de destino. `block` lo cancela y muestra una pista. |
| `Notify` | `true` | Avisa al jugador de que su mensaje se movió, como mucho una vez por minuto. |
| `Shortcut_Prefixes` | `{}` | Prefijos que mandan un mensaje a un canal sin cambiar de canal. |
| `Rules.<canal>.Patterns` | palabras de venta/subasta en español e inglés | Expresiones regulares de Java, sin distinguir mayúsculas. |

El staff con `chatalyst.bypass.router` nunca se enruta.

### Formato y retardo por grupo

`config.yml` tiene una sección `Groups` indexada por el grupo principal de LuckPerms (o el de Vault). Cada grupo puede tener su propio `Format` (se aplica a los canales que usan el formato base) y `Chat_Delay_Seconds` (sustituye el retardo del antispam; `0` = sin retardo).

## Comandos y permisos

| Comando | Permiso | Qué hace |
|---|---|---|
| `/channel` (`/ch`) | `chatalyst.command.channel` | Abre el menú de canales. |
| `/channel <nombre>` | `chatalyst.command.channel` | Cambia el canal en el que escribes. |
| `/channel list` | `chatalyst.command.channel` | Lista los canales en texto. |
| `/channel mute <nombre>` | `chatalyst.command.channel` | Oculta o muestra un canal. |
| `/channel listen <nombre>` | `chatalyst.command.channel` | Te suscribe a un canal sin escribir en él. |
| `/channel history [nombre]` | `chatalyst.command.channel` | Muestra el historial reciente de un canal. |
| `/channel send <nombre> <msg>` | `chatalyst.channel.send` | Manda un mensaje a un canal sin entrar en él. |
| `/channel sendas <jugador> <nombre> <msg>` | `chatalyst.channel.sendas` | Manda un mensaje como otro jugador conectado (vale desde consola). |
| `/frequency [NN.NN\|off]` | `chatalyst.command.frequency` | Sintoniza la radio. |
| `/f <mensaje>` | `chatalyst.command.frequency` | Habla una vez por tu frecuencia. |
| `/staffchat <msg>` (`/sc`) | `chatalyst.channel.staff` | Manda un mensaje al canal de staff. |
| `/cht region pos1\|pos2\|add\|remove\|list` | `chatalyst.admin` | Gestiona las regiones de chat. |

Otros nodos: `chatalyst.channel.seeall` (oír todos los canales con `Exclusive` activo, por defecto `false`), `chatalyst.bypass.range` (oír todas las frecuencias, por defecto op) y `chatalyst.bypass.router` (nunca se enruta, por defecto op). Consulta [todos los comandos](commands) y [todos los permisos](permissions).

## Límites

- El historial vive solo en memoria y se borra al reiniciar. Solo se guardan los canales `GLOBAL`: repetir la radio, el mundo, la región o la party podría enseñar mensajes que el lector nunca debió ver.
- El chat de radio (`FREQUENCY`) se queda en un servidor. No lo pases por el proxy. Para hablar en toda la red, usa un canal `GLOBAL` aparte.
- El enrutador solo revisa los mensajes de quien escribe en el canal principal.
