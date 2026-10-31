---
title: Motor de reglas
description: Escribe tus propias reglas de chat con patrones, condiciones y acciones. Bloquea publicidad, limpia spam, avisa, castiga, alerta al staff o ejecuta comandos, en chat, carteles, libros y más.
---

El motor de reglas te deja decidir qué pasa cuando alguien escribe algo concreto. Cada regla busca un patrón y, cuando coincide, hace lo que elijas: bloquear el mensaje, cambiar una parte, avisar al jugador, sumar puntos de advertencia, avisar al staff, ejecutar un comando, abrir un libro y más.

Las reglas revisan más que el chat. Por defecto también revisan comandos, carteles, libros, nombres de objetos en el yunque y apodos, así que nadie puede saltarse la regla antipublicidad escribiendo la IP de otro servidor en un cartel.

Chatalyst trae reglas listas: anti-IP, anti-dominios, anti-invitaciones de Discord, un limpiador de letras repetidas y antimayúsculas. Todo está en `rules.yml`.

## Activarlo

Viene activado:

```yaml
Enabled: true

Apply_To:
  Commands: true
  Signs: true
  Books: true
  Anvil: true
  Nicks: true
  Ignore_Commands:
    - "/login"
    - "/register"
    - "/l"
    - "/reg"
    - "/changepassword"
```

`Apply_To` decide dónde se aplican las reglas además del chat. `Ignore_Commands` lista comandos que nunca se revisan; deja aquí tus comandos de login y contraseña.

## Anatomía de una regla

Esta es la regla anti-IP del archivo real:

```yaml
Rules:

  anti-ip:
    Match: '\b(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?::\d{1,5})?\b'
    Priority: 100
    Use_Groups: [audited-filter]
    Contexts: [CHAT, COMMAND, SIGN, BOOK, ANVIL, NICK]
    Ignore_Permission: chatalyst.bypass.ads
    Ignore_Contains:
      - "version"
      - "coord"
      - "pos"
      - "warp"
      - " tp "
      - "x:"
      - "y:"
      - "z:"
    Deny: true
    Points: 2
    Warn: "<#FF4D6D>Advertising other servers' IPs is not allowed."
    Warn_es: "<#FF4D6D>No esta permitido anunciar IPs de otros servidores."
    Notify:
      Message: "<#7B68A0>[<#D8B4FE>ADVERT<#7B68A0>] <#C0B3D9>{player}: <#FBF2FF>{message}"
      Message_es: "<#7B68A0>[<#D8B4FE>ANUNCIO<#7B68A0>] <#C0B3D9>{player}: <#FBF2FF>{message}"
    Actions:
      Commands:
        - "warnings {player} clear"
```

### Qué busca

| Clave | Qué hace |
| --- | --- |
| `Match` | La expresión regular que se busca. No distingue mayúsculas salvo con `Case_Sensitive: true` |
| `Priority` | Las más altas se ejecutan antes. Por defecto `0` |
| `Contexts` | Dónde se aplica: `CHAT`, `COMMAND`, `SIGN`, `BOOK`, `ANVIL`, `NICK`. Vacío = en todas partes |
| `Ignore_Permission` | Los jugadores con este permiso se saltan la regla |
| `Ignore_Contains` | Si el mensaje contiene alguno de estos textos, la regla no se aplica |
| `Strip_Accents` | `true` ignora las tildes al buscar, así `pütä` coincide con `puta`. El resto del mensaje no cambia |
| `Stop_After` | `true` deja de revisar más reglas si esta coincide |
| `Begins` / `Expires` | Fechas `yyyy-MM-dd HH:mm` entre las que la regla está activa (reglas de temporada) |
| `Delay_Seconds` / `Player_Delay_Seconds` | Espera entre dos ejecuciones de la regla, para todos / por jugador |

### Qué hace la regla

| Clave | Qué hace |
| --- | --- |
| `Deny` | `true` cancela el mensaje |
| `Deny_Silently` | Lo cancela sin que el autor lo note |
| `Replace` | Sustituye el texto encontrado (`""` lo borra) |
| `Replace_Raw` | `true` permite grupos tipo `$1` en `Replace` |
| `Replace_Prolong` | Un carácter que tapa lo encontrado manteniendo su longitud (`puta` → `****`) |
| `Warn` / `Warn_es` | Un aviso privado al autor |
| `Notify` | `Permission` y `Message` (`Message_es`) que se envían al staff |
| `Points` | [Puntos de advertencia](punishments#puntos-de-advertencia) que se suman al jugador |
| `Kick` | Expulsa al jugador con este mensaje |
| `Fine` | Le cobra esta cantidad de dinero (necesita Vault) |
| `Sound`, `Title`, `Actionbar`, `Bossbar`, `Toast` | Muestran algo al jugador |
| `Actions.Commands` | Comandos de consola |
| `Actions.Player_Commands` | Comandos ejecutados como el jugador, con sus propios permisos |
| `Actions.Proxy_Commands` | `"<servidor> <comando>"`: un comando de consola en otro servidor (ver [red](network#comandos-de-reglas-en-otros-servidores)) |
| `Actions.Rewrite_In_World` | Sustituye el mensaje entero cuando el autor está en cierto mundo |
| `Actions.Set_Data` / `Remove_Data` | Guarda o borra un valor del jugador (ver abajo) |
| `Actions.Open_Book` | Abre un [libro](books) |
| `Actions.Audit` | Escribe una entrada `RULE` en el registro de auditoría |
| `Actions.Discord_Staff` | Envía la coincidencia al canal privado del staff en Discord |

Placeholders en textos y comandos: `{player}`, `{message}`, `{original_message}`, `{rule}`, `{context}`, además de todos los de Chatalyst, tus variables propias y PlaceholderAPI.

## Grupos reutilizables

Un grupo es una plantilla que no hace nada por sí sola. Las reglas la incluyen con `Use_Groups`, así escribes una vez las acciones y condiciones que comparten:

```yaml
Groups:
  audited-filter:
    Notify:
      Permission: chatalyst.notify.rules
      Message: "<#7B68A0>[<#D8B4FE>RULE<#7B68A0>] <#C0B3D9>{player}: <#FBF2FF>{message}"
      Message_es: "<#7B68A0>[<#D8B4FE>REGLA<#7B68A0>] <#C0B3D9>{player}: <#FBF2FF>{message}"
    Actions:
      Audit: true

  survival-only:
    Require:
      Worlds: [world, world_nether, world_the_end]
```

Cuando una regla usa varios grupos, los últimos pisan a los primeros, y lo que escribas en la propia regla gana sobre todos.

## Condiciones

`Require` (todo tiene que cumplirse) e `Ignore` (cualquier coincidencia salta la regla) hacen que una regla dependa de quién escribe y dónde:

```yaml
Require:
  Permission: rank.member
  Worlds: [world]
  Gamemodes: [SURVIVAL, ADVENTURE]
  Channels: [global]
  Regions: [spawn]
  Variables:
    "%vault_eco_balance%": ">=100"
  Data:
    onboarding.step: "equals:pending"
  Scripts:
    - "player.getPing() < 250"
Ignore:
  Worlds: [jail]
  Muted: true
  Uuid: true
```

| Condición | Qué comprueba |
| --- | --- |
| `Permission` | El jugador tiene este permiso |
| `Worlds`, `Gamemodes`, `Channels`, `Regions` | Dónde está el jugador y en qué canal escribe (las regiones necesitan WorldGuard) |
| `Variables` | El valor de un placeholder |
| `Data` | Un valor guardado con `Actions.Set_Data` |
| `Scripts` | Una expresión JavaScript que tiene que devolver verdadero |
| `Muted` (Ignore) | Salta a los jugadores muteados |
| `Uuid` (Ignore) | Oculta los UUID al patrón, para que `/whitelist add <uuid>` no active una regla de números |
| `Discord` | Si el mensaje vino de Discord (solo para jugadores que vincularon su cuenta) |

Los valores de `Variables` y `Data` se pueden comparar con: texto simple (igual), `equals:`, `!=`, `contains:`, `starts:`, `ends:`, `in:a,b,c`, `regex:`, `>`, `>=`, `<` y `<=`.

## Datos guardados del jugador

`Actions.Set_Data` guarda un valor pequeño por jugador, y `Require.Data` / `Ignore.Data` lo vuelven a leer. Así las reglas pueden recordar cosas, por ejemplo "este jugador ya recibió el primer aviso":

```yaml
Actions:
  Set_Data:
    onboarding.step: complete
  Remove_Data: [temporary.flag]
```

Los valores se guardan en tu [almacenamiento](storage) normal (YAML, SQLite o MySQL).

## Antimayúsculas

Limita los mensajes escritos casi todo en mayúsculas. También está en `rules.yml`:

```yaml
Anti_Caps:
  Enabled: true
  Min_Length: 8
  Percentage: 0.7
  To_Lowercase: true
  Bypass_Permission: chatalyst.bypass.caps
  Warn: "<#C0B3D9>Avoid typing in all caps."
  Warn_es: "<#C0B3D9>Evita escribir todo en mayusculas."
```

| Opción | Por defecto | Qué hace |
| --- | --- | --- |
| `Min_Length` | `8` | Los mensajes más cortos nunca se revisan |
| `Percentage` | `0.7` | Proporción de mayúsculas que lo activa (0.7 = 70%) |
| `To_Lowercase` | `true` | Pasa el mensaje a minúsculas. Añade `Deny: true` para bloquearlo en su lugar |

## Compartir reglas entre servidores

Guarda las reglas comunes en archivos aparte junto a `rules.yml` e impórtalos:

```yaml
Import:
  - shared-rules.yml
```

Solo se aceptan nombres de archivo (sin carpetas), las importaciones no son recursivas y, si un nombre de regla aparece dos veces, gana el primero.

## Gestionar las reglas

| Comando | Qué hace |
| --- | --- |
| `/chatalyst rule` | Lista las reglas cargadas |
| `/chatalyst rule toggle <nombre>` | Activa o desactiva una regla sin editar el archivo |
| `/chatalyst reload` | Recarga `rules.yml` y los archivos importados |

Necesitan `chatalyst.admin`. El staff con `chatalyst.notify.rules` (op por defecto) recibe los avisos de `Notify`.

> [!WARNING]
> Si `rules.yml` tiene un error de sintaxis, la recarga conserva tus reglas anteriores en lugar de cargar un conjunto vacío. Mira la consola después de recargar.

## Límites

- Se cargan hasta 512 reglas; las que sobran se omiten con un aviso en consola.
- Cada patrón tiene un tiempo máximo por mensaje. Un patrón mal escrito que tarda demasiado se trata como "no coincide" en lugar de congelar el chat, y `/chatalyst perf` muestra cuántas veces pasó (ver [rendimiento](performance)).
- Los comandos de login y contraseña nunca se escriben en claro en registros ni alertas, aunque una regla coincida con ellos. Aun así, déjalos en `Ignore_Commands`.
- Prueba antes los patrones amplios en un servidor de pruebas: un patrón que coincide con demasiado bloquea la conversación normal.
