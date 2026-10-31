---
title: Respuestas automáticas
description: Un bot de chat que responde solo a las preguntas habituales de los jugadores, en el idioma de cada uno, y que solo ofrece lo que tu servidor tiene de verdad.
---

Los jugadores nuevos preguntan lo mismo una y otra vez: cómo gano dinero, cómo protejo mi terreno, dónde está mi compra. El bot de respuestas automáticas contesta por ti. Cuando un mensaje parece una pregunta conocida, el bot responde un momento después con la respuesta que escribiste, en el idioma del jugador.

Entiende cómo escribe la gente de verdad. "komo gano din3ro!!!" y "cómo gano dinero" llevan a la misma respuesta. Y sus respuestas se adaptan a tu servidor: una respuesta sobre dinero solo menciona `/jobs` si `/jobs` existe, y solo habla del mercado si tu mercado está activo.

Chatalyst trae 44 respuestas listas en inglés y español (dinero, saldo, mensajes privados, correo, canales, reportes, Discord, rangos, casas, warps, protección de terreno, kits, votos, PvP, lag y más). Edítalas, bórralas o añade las tuyas en `bot.yml`.

## Activarlo

Viene activado. Los ajustes generales están al principio de la sección `Auto_Responses` de `bot.yml`:

```yaml
Auto_Responses:
  Enabled: true
  Bypass_Permission: "chatalyst.bypass.autoresponse"
  Auto_Language: true
  Language: "en"
  Private_Questions: true
  Player_Cooldown_Seconds: 60
  Global_Cooldown_Seconds: 10
  Delay_Ticks: 15
```

| Opción | Por defecto | Qué hace |
| --- | --- | --- |
| `Enabled` | `true` | Enciende o apaga todo el bot |
| `Bypass_Permission` | `chatalyst.bypass.autoresponse` | Los mensajes de quien tenga este permiso nunca activan el bot (op por defecto, para que el staff no lo dispare) |
| `Auto_Language` | `true` | Cada jugador recibe la respuesta en el idioma de su cliente (inglés o español) |
| `Language` | `en` | Idioma fijo (`en` o `es`) cuando `Auto_Language` es `false` |
| `Private_Questions` | `true` | Una pregunta reconocida no sale al chat público. Solo quien pregunta ve su pregunta y la respuesta, y el chat queda limpio |
| `Player_Cooldown_Seconds` | `60` | Espera por defecto antes de que el mismo jugador reciba otra vez la misma respuesta |
| `Global_Cooldown_Seconds` | `10` | Espera por defecto antes de dar la misma respuesta a cualquiera |
| `Delay_Ticks` | `15` | Pausa corta antes de responder, para que parezca natural (20 ticks = 1 segundo) |

## Escribir una respuesta

Cada entrada de `Responses` necesita al menos una forma de detectar la pregunta y una respuesta. Esta sale del archivo real:

```yaml
    mail:
      Keywords:
        - "correo|mail|inbox|buzon|carta|offline|desconect"
        - "mand|envi|escrib|dej|send|write|leave|como|komo|how"
      Triggers:
        - "how do i send mail"
        - "offline message"
        - "regex:\\bc[oó]mo (mando|envio|env[ií]o) (correo|un mensaje a alguien offline)\\b"
      Reply:
        - "<#7B68A0>[<#B56CFF>Bot<#7B68A0>] <#C0B3D9>Open your mailbox with <#E0AAFF>/inbox<#C0B3D9>: you can write to players even when they are offline."
      Reply_es:
        - "<#7B68A0>[<#B56CFF>Bot<#7B68A0>] <#C0B3D9>Abre tu buzon con <#E0AAFF>/inbox<#C0B3D9>: puedes escribir a jugadores aunque esten desconectados."
      To: player
```

| Clave | Qué hace |
| --- | --- |
| `Triggers` | Texto que debe contener el mensaje, o una expresión regular completa con el prefijo `regex:`. Escribe los dos idiomas |
| `Keywords` | Detección flexible. Cada línea es un grupo de palabras separadas por una barra vertical; la respuesta salta cuando el mensaje contiene una palabra de **cada** grupo |
| `Reply` / `Reply_es` | Las líneas de respuesta para clientes en inglés y en español. Si falta una, se usa la otra. `{player}` es el nombre de quien pregunta |
| `If_Plugin` | Respuestas alternativas que solo se usan si hay cierto plugin instalado |
| `Smart` | Una respuesta hecha de partes que dependen de lo que tiene tu servidor (ver abajo) |
| `To` | `player` responde solo a quien pregunta; `everyone` lo envía a todo el servidor, cada uno en su idioma |
| `Player_Cooldown_Seconds`, `Global_Cooldown_Seconds`, `Delay_Ticks` | Sustituyen los valores generales para esta respuesta |

Una respuesta salta si **cualquiera** de sus detecciones coincide. Antes de comparar, el mensaje se normaliza: minúsculas, sin tildes, sin leet (`din3r0` pasa a `dinero`), letras estiradas acortadas (`holaaaa` pasa a `hola`) y la puntuación convertida en espacios.

> [!TIP]
> En `Keywords`, usa raíces cortas como `gan`, `consig` o `earn`. Una raíz atrapa todas las formas de la palabra: `gano`, `ganar`, `ganando`.

## Respuestas inteligentes

Una respuesta inteligente se arma con partes, y cada parte solo se dice si lo que indica `Needs` está disponible en ese momento. Si no aplica ninguna parte, se usa la línea `Unavailable`. Una parte sin `Needs` se dice siempre.

```yaml
    how-to-money:
      Keywords:
        - "dinero|plata|money|coin|coins|lana|feria|billete|cash|oro|eco|bucks|dosh|moolah|dough|rich"
        - "gan|consig|hag|hac|sac|junt|farm|earn|make|get|neces|need|wanna|gimme|grind|start"
      Smart:
        Intro: "<#7B68A0>[<#B56CFF>Bot<#7B68A0>] <#C0B3D9>Hi <#FBF2FF>{player}<#C0B3D9>, ways to earn money here:"
        Intro_es: "<#7B68A0>[<#B56CFF>Bot<#7B68A0>] <#C0B3D9>Hola <#FBF2FF>{player}<#C0B3D9>, formas de ganar dinero aqui:"
        Parts:
          - Needs: "command:jobs"
            Text: "<#C0B3D9>• Work a job with <#E0AAFF>/jobs<#C0B3D9>."
            Text_es: "<#C0B3D9>• Trabaja un empleo con <#E0AAFF>/jobs<#C0B3D9>."
          - Needs: "market"
            Text: "<#C0B3D9>• Sell your own items on <#E0AAFF>/market<#C0B3D9>."
            Text_es: "<#C0B3D9>• Vende tus propios items en <#E0AAFF>/market<#C0B3D9>."
        Unavailable: "<#7B68A0>[<#B56CFF>Bot<#7B68A0>] <#C0B3D9>There's no money system enabled on this server yet."
        Unavailable_es: "<#7B68A0>[<#B56CFF>Bot<#7B68A0>] <#C0B3D9>Aun no hay un sistema de dinero activo en este servidor."
      To: player
```

Lo que puede necesitar una parte:

| Needs | Disponible cuando |
| --- | --- |
| `command:<nombre>` | Ese comando existe en el servidor, por ejemplo `command:jobs` |
| `plugin:<Nombre>` | Ese plugin está instalado y activo (nombre como sale en `/plugins`) |
| `pm` | Los mensajes privados están activos |
| `mail` | El correo está activo |
| `market` / `trade` | El mercado / el comercio directo están activos |
| `channels` | Los canales de chat están activos |
| `announcements` | El anunciador está activo |
| `economy` / `baltop` | Hay un plugin de economía / el ranking de dinero está activo |
| `store` / `ranks` | Las entregas de objetos / la tienda de rangos están activas |
| `rent` | El alquiler de ventajas está activo, con economía y al menos una oferta |
| `discordlink` | La vinculación con Discord se puede usar de verdad |

En los textos, `{ranks_command}` se sustituye por el nombre actual del comando de tu tienda (ver [tienda](store)). La disponibilidad se comprueba cada vez que el bot responde, así que encender o apagar una función se nota al momento.

## Cómo encaja con el resto del chat

El bot solo responde a mensajes que han pasado: si el [motor de reglas](rules-engine) o el [antispam](chat-filter#antispam) bloquearon antes el mensaje, el bot no dice nada. El filtro de groserías y los registros se siguen aplicando a la pregunta como siempre.

## Límites

- Las respuestas están solo en inglés y español. Los jugadores con otro idioma en el cliente reciben la respuesta en inglés.
- El bot responde preguntas en el chat; no mantiene una conversación ni recuerda lo que un jugador preguntó antes.
- La detección se basa en las palabras y patrones que escribes. Si los jugadores preguntan de una forma que ningún disparador cubre, añade un disparador o una palabra clave.
