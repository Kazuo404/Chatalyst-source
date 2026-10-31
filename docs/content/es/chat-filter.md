---
title: Filtro, antispam y antibot
description: Mantén el chat limpio de forma automática. Un filtro de groserías que ve a través de los trucos, antispam con muteos que escalan, espera antibot, protección contra caracteres ocultos, bloqueador de comandos y cooldowns.
---

Estas herramientas protegen tu chat sin que el staff tenga que vigilarlo todo el día. El filtro de groserías atrapa los insultos aunque los disfracen. El antispam frena las avalanchas y los mensajes repetidos, y mutea cada vez más tiempo a quien reincide. El antibot para a las cuentas que entran y hacen spam en el mismo segundo. Y el staff tiene controles rápidos para cuando se desmadra: bloquear el chat, ralentizarlo o limpiarlo.

La mayoría vienen activados y están en `moderation.yml`; el filtro de groserías tiene su propio archivo, `swear.yml`. Para patrones propios (publicidad, enlaces…) mira el [motor de reglas](rules-engine).

## Filtro de groserías

El filtro busca palabras completas, así que no marca palabras normales que contienen una mala ("disputa", "class", "cultura"). Y ve a través de los trucos de siempre:

| Truco | Ejemplo que sigue atrapando |
| --- | --- |
| Letras repetidas | `putooooo` |
| Separadores | `p.u.t.o`, `p u t o`, `p-u-t-o` |
| Leet | `put0`, `@`, `4`, `3`, `1`, `$` |
| Tildes | `pútà` |
| Letras que se parecen | Letras cirílicas que parecen latinas |
| Cambios por sonido | `kabron`, `pvto` |
| CamelCase | `LaPutaDeTuMadre` |

La lista cubre español (Latinoamérica y España), inglés y portugués. Ajustes en `swear.yml`:

```yaml
Enabled: true
Action: CENSOR
Censor_Char: "*"
Points: 1
Use_Player_Language: true
Warn: "<#FF4D6D>Watch your language."
Warn_es: "<#FF4D6D>Cuida tu lenguaje."
Bypass_Permission: chatalyst.bypass.swear

Name_Alert:
  Enabled: true

Normalize:
  Collapse_Repeats: true
  Strip_Separators: true
  Leet: true
  Camel_Boundaries: true
```

| Opción | Por defecto | Qué hace |
| --- | --- | --- |
| `Action` | `CENSOR` | `CENSOR` tapa las letras y envía el mensaje; `DENY` lo cancela |
| `Censor_Char` | `*` | Carácter con el que se tapa |
| `Points` | `1` | [Puntos de advertencia](punishments#puntos-de-advertencia) por cada infracción |
| `Warn` / `Warn_es` | ver arriba | Aviso privado al jugador |
| `Bypass_Permission` | `chatalyst.bypass.swear` | Quien lo tenga no se filtra (op por defecto) |
| `Name_Alert.Enabled` | `true` | Avisa al staff (`chatalyst.notify.security`) cuando alguien entra con un nombre de cuenta ofensivo. Nunca expulsa por sí solo |
| `Normalize.*` | `true` | Cada técnica antievasión se puede apagar por separado |

El archivo tiene tres listas:

- `Words`: palabras prohibidas escritas normal. El filtro calcula solo las variantes disfrazadas. Los plurales y formas habituales van aparte.
- `Contains`: frases que se buscan en cualquier parte de una palabra, para insultos pegados (`laputadetumadre`, `fuckyou`). Añade solo fragmentos largos y sin ambigüedad: `puta` aquí marcaría `computadora`.
- `Whitelist`: palabras reales que nunca deben tocarse en tu comunidad.

Los mensajes que llegan desde Discord también pasan por el filtro antes de entrar al juego.

## Antispam

Impide mandar mensajes demasiado rápido o repetir el mismo mensaje.

```yaml
Anti_Spam:
  Enabled: true
  Delay_Seconds: 1.5
  Similarity: 0.9
  Repeat_Seconds: 30
  Min_Repeat_Length: 5
  Bypass_Permission: chatalyst.bypass.spam
  Auto_Mute:
    Enabled: true
    Warnings_Before_Mute: 4
    Escalation:
      - "15m"
      - "30m"
      - "1h"
      - "5h"
      - "12h"
      - "1d"
    Warning_Reset: 60
    Escalation_Reset: "6h"
    Delete_Messages: true
    Delete_Count: 10
    Clear_Chat: false
```

| Opción | Por defecto | Qué hace |
| --- | --- | --- |
| `Delay_Seconds` | `1.5` | Tiempo mínimo entre dos mensajes del mismo jugador |
| `Similarity` | `0.9` | Cuánto se tiene que parecer un mensaje al anterior para contar como repetido (1 = solo idénticos) |
| `Repeat_Seconds` | `30` | Cuánto se recuerda el mensaje anterior para comparar. `0` = toda la sesión |
| `Min_Repeat_Length` | `5` | Los mensajes más cortos ("ok", "gg", "hola") nunca cuentan como repetidos |
| `Auto_Mute.Warnings_Before_Mute` | `4` | Avisos de spam antes de un muteo automático |
| `Auto_Mute.Escalation` | de 15m a 1d | Duración del muteo en la 1.ª, 2.ª, 3.ª… vez. El último valor se repite |
| `Auto_Mute.Warning_Reset` | `60` | Segundos sin spam para reiniciar los avisos |
| `Auto_Mute.Escalation_Reset` | `6h` | Tiempo sin muteos automáticos para bajar un nivel |
| `Auto_Mute.Delete_Messages` | `true` | Al mutear, borra del chat de todos los mensajes recientes del spammer |
| `Auto_Mute.Delete_Count` | `10` | Cuántos mensajes recientes se borran |
| `Auto_Mute.Clear_Chat` | `false` | Además empuja el chat de todos con líneas en blanco (salvo al staff). Una herramienta bruta, apagada por defecto |

El retraso por rango se pone en `config.yml` con `Groups.List.<grupo>.Chat_Delay_Seconds` (ver [configuración](configuration#ajustes-por-grupo)).

> [!NOTE]
> Borrar un mensaje de la pantalla de los demás solo funciona con líneas que Minecraft firmó. Casi todo el chat normal va firmado; las líneas enviadas como mensaje del sistema (por ejemplo, el chat traducido) no se pueden retirar.

## Antibot

Obliga a los recién conectados a esperar unos segundos antes de chatear o usar comandos, lo que frena a los bots que entran y hacen spam al instante. Viene apagado.

```yaml
Anti_Bot:
  Enabled: false
  Chat_Delay_After_Join: 3
  Command_Delay_After_Join: 2
  Bypass_Permission: chatalyst.bypass.antibot
  Allowed_Commands:
    - "/login"
    - "/register"
    - "/l"
    - "/reg"
```

Los comandos de login de `Allowed_Commands` siempre funcionan, así que los jugadores pueden iniciar sesión durante la espera.

## Seguridad del chat

Limpia cada mensaje antes de que actúe cualquier otro filtro. Quita caracteres invisibles y de control (con los que se cuelan insultos escondiendo caracteres de ancho cero entre las letras, o se invierte la dirección del texto) y limita la longitud del mensaje. Déjala activada.

```yaml
Chat_Security:
  Enabled: true
  Max_Length: 256
  Strip_Control_Chars: true
  Strip_Invisible: true
  Notify_Staff: true
  Bypass_Permission: chatalyst.bypass.security
```

Con `Notify_Staff: true`, el staff con `chatalyst.notify.security` recibe un aviso cuando alguien usa caracteres ocultos para esquivar el filtro. El permiso para saltársela no lo tiene nadie por defecto, ni siquiera los operadores.

## Bloqueador de comandos

Bloquea comandos por completo, sea cual sea el plugin que los registró. Cada entrada es un comando literal (`/op`) o una expresión regular (`regex:...`). Por defecto oculta información del servidor: `/plugins`, `/version`, `/tps`, `/timings`, `/seed` y sus variantes, incluidas las formas con prefijo como `/bukkit:plugins`.

```yaml
Command_Blocker:
  Enabled: true
  Bypass_Permission: chatalyst.bypass.blocker
  Blocked:
    - "regex:(?i)/(\\w+:)?(plugins|pl|version|ver|about|icanhasbukkit|help|\\?)(\\s.*)?"
    - "regex:(?i)/(\\w+:)?(timings|tps|mspt|lag|gc|memory|entities|spark|health|sysinfo)(\\s.*)?"
    - "regex:(?i)/(\\w+:)?(seed|datapack)(\\s.*)?"
```

Un comando bloqueado responde con un mensaje corto "Ese comando está bloqueado" (`blocker.blocked` en los archivos de idioma). Los operadores nunca quedan bloqueados. El autocompletado oculta el mismo tipo de comandos (`Tab_Complete` en `chat.yml`), así que los jugadores ni siquiera los ven. Con [PacketEvents](integrations#packetevents), los comandos bloqueados se paran aún antes.

## Cooldowns de comandos

Obliga a esperar entre usos de ciertos comandos, como `/kit`, `/home` o `/rtp`, sea del plugin que sea.

```yaml
Command_Cooldowns:
  Bypass_Permission: chatalyst.bypass.cooldown
  Commands: {}
```

Añade los comandos con su espera en segundos:

```yaml
Command_Cooldowns:
  Bypass_Permission: chatalyst.bypass.cooldown
  Commands:
    "/kit": 60
    "/home": 5
```

## Controles de chat del staff

| Comando | Permiso | Qué hace |
| --- | --- | --- |
| `/chatlock` (`/mutechat`) | `chatalyst.command.chatlock` | Bloquea el chat global |
| `/chatunlock` (`/unmutechat`) | `chatalyst.command.chatlock` | Lo desbloquea |
| `/slowmode <segundos>` / `/slowmode off` | `chatalyst.command.slowmode` | Obliga a todos a esperar entre mensajes |
| `/clearchat` (`/cc`) | `chatalyst.command.clearchat` | Saca de la vista el chat de todos con `Chat.Clear_Lines` líneas en blanco (150) |
| `/chatalyst purge <jugador>` | `chatalyst.admin` | Borra del chat de todos los mensajes recientes de un jugador, incluso hasta 5 minutos después de irse |
| La **X** junto a un mensaje | `chatalyst.deletemessages` | Borra ese mensaje para todos |

Todos son op por defecto. `chatalyst.chat.bypass` deja hablar a un jugador con el chat bloqueado o en modo lento, y `chatalyst.command.clearchat.bypass` conserva el chat de un miembro del staff cuando alguien lo limpia, para que pueda revisar lo que se dijo.

## Permisos para saltarse filtros

| Permiso | Se salta | Por defecto |
| --- | --- | --- |
| `chatalyst.bypass.swear` | Filtro de groserías | op |
| `chatalyst.bypass.spam` | Antispam y la regla de letras repetidas | op |
| `chatalyst.bypass.antibot` | Espera antibot | op |
| `chatalyst.bypass.security` | Limpieza de seguridad del chat | nadie |
| `chatalyst.bypass.blocker` | Bloqueador de comandos | op |
| `chatalyst.bypass.cooldown` | Cooldowns de comandos | op |
| `chatalyst.bypass.caps` | Antimayúsculas | op |
| `chatalyst.bypass.ads` | Reglas antipublicidad | op |

## Límites

- Ningún filtro es perfecto. El de groserías está ajustado para evitar falsos positivos, así que una escritura muy creativa todavía puede colarse. Añádela a `Words` o `Contains` cuando la veas.
- El bloqueador bloquea por nombre. No puede frenar a un plugin que dé la misma información con otro nombre de comando; añade ese nombre también.
- El antibot solo retrasa el chat y los comandos. No impide que entren cuentas bot; para eso usa un plugin antibot específico o tu proxy.
