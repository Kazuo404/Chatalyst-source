---
title: Recién llegados
description: Da la bienvenida en su primera entrada con un saludo a todo el servidor en el idioma de cada lector y comandos de consola, como un kit de inicio.
---

La primera impresión cuenta. Cuando alguien entra por primera vez en tu servidor, Chatalyst puede anunciarlo a todos («¡Demos la bienvenida a Steve, nuevo en el servidor!») y ejecutar comandos de consola para él, como darle un kit de inicio o un permiso inicial.

Solo ocurre en la primerísima entrada del jugador. A quien vuelve no le afecta.

## Cómo se activa

El módulo de recién llegados viene apagado. En `plugins/Chatalyst/players.yml`, pon `Enabled: true` y ejecuta `/cht reload`:

```yaml
Newcomer:
  Enabled: false
  # true = every online player sees the welcome in their own client language.
  # false = use Welcome_Broadcast exactly as written for everyone.
  Localized_Broadcast: true
  Welcome_Broadcast: "<#FFB627>Let's welcome <#FFC933>{player_name}<#FFB627>, new on the server!"
  Commands: []
```

## Configuración

| Opción | Por defecto | Qué hace |
|---|---|---|
| `Enabled` | `false` | Enciende la bienvenida. |
| `Localized_Broadcast` | `true` | Cada jugador conectado lee la bienvenida en su idioma, sacada de la clave `newcomer.welcome-broadcast` de los archivos de idioma. |
| `Welcome_Broadcast` | "Let's welcome {player_name}, new on the server!" | Se usa tal cual para todos cuando `Localized_Broadcast` es `false`. Déjalo vacío para no mandar bienvenida. |
| `Commands` | `[]` | Comandos de consola que se ejecutan una vez para el jugador nuevo. |

### El mensaje de bienvenida

Con `Localized_Broadcast: true`, el texto sale de `lang/en_US.yml` y `lang/es_ES.yml` (clave `newcomer.welcome-broadcast`), así que se edita allí. Admite `{player_name}`, `{world}` y `{ping}`. Ver [Idiomas y mensajes](localization).

Con `Localized_Broadcast: false`, `Welcome_Broadcast` se manda tal cual y admite cualquier [placeholder](placeholders), incluidos los de PlaceholderAPI.

### Comandos

Los comandos se ejecutan desde la consola, uno tras otro, y admiten los mismos placeholders. Por ejemplo:

```yaml
  Commands:
    - "kit starter {player_name}"
    - "lp user {player_name} permission set essentials.home true"
```

### MOTD de la primera entrada

El mensaje privado que ve un jugador al entrar es otra función. Su variante de primera entrada es `Motd.First_Time` en `messages.yml`, y cualquiera puede volver a verla con `/motd first`. Ver [Idiomas y mensajes](localization).

## Comandos y permisos

Este módulo no tiene comandos propios. `/motd [first]` (permiso `chatalyst.command.motd`, por defecto `true`) vuelve a enseñar el MOTD de entrada.

## Límites

- «Primera entrada» significa que Minecraft nunca ha guardado datos de ese jugador en este servidor. En una red, cada servidor lo decide por su cuenta, así que un jugador puede recibir la bienvenida una vez en cada servidor.
- Con `Localized_Broadcast: true`, el texto de `Welcome_Broadcast` no se enseña; basta con que no esté vacío para que la bienvenida siga activa.
