# Motor de reglas v2

Las reglas viven en `rules.yml`. El motor evalúa expresiones regulares ordenadas en chat,
comandos, líneas de carteles, páginas de libros, nombres de yunque, apodos y mensajes vinculados de
del bot de Discord. Una regla puede combinar grupos reutilizables, condiciones de contexto, placeholders,
scripts, estado persistente por jugador y acciones independientes.

## Orden de evaluación

Para cada regla activa, Chatalyst comprueba el contexto, excepciones, ventana de fechas, tiempos de espera y condiciones `Require`/`Ignore` antes de ejecutar una acción. Los valores mayores de `Priority` se procesan primero. `Stop_After: true` detiene la cadena después de esa coincidencia.

La entrada se sanea antes de filtrarla. Los carteles se inspeccionan línea por línea y los libros página por página; así el costo queda acotado y un libro grande no puede ocultar contenido detrás de un recorte arbitrario. Las expresiones inválidas o vacías se rechazan al cargar el archivo.

## Grupos reutilizables

Los grupos son plantillas y nunca se ejecutan por sí solos. Una regla puede componer varios mediante `Use_Groups`. Los grupos posteriores sustituyen valores de los anteriores y lo escrito directamente en la regla tiene prioridad final.

```yaml
Groups:
  audited:
    Notify:
      Permission: chatalyst.notify.rules
      Message_es: "<gold>{player} activó {rule}: <white>{message}"
    Actions:
      Audit: true

  solo-survival:
    Require:
      Worlds: [world, world_nether, world_the_end]

Rules:
  enlace-sospechoso:
    Use_Groups: [audited, solo-survival]
    Match: '(?i)\\b(?:example\\.com|badsite\\.net)\\b'
    Priority: 50
    Contexts: [CHAT, COMMAND, SIGN, BOOK, ANVIL, NICK]
    Deny: true
    Warn_es: "<red>Ese enlace no está permitido."
    Points: 1
    Stop_After: true
```

## Imports

Otros YAML junto a `rules.yml` pueden aportar grupos compartidos y reglas:

```yaml
Import: [reglas-compartidas.yml, temporada.yml]
```

Los imports no son recursivos. Chatalyst acepta únicamente nombres de archivo —nunca rutas—,
aplica parseo YAML estricto, acota cantidades y omite archivos repetidos. Las reglas base se cargan
primero; si un nombre se repite, gana la primera definición válida y la automatización no se
ejecuta dos veces. Los grupos importados amplían los grupos base y pueden sustituir campos de un
grupo con el mismo nombre para ese archivo importado.

## Condiciones

Las condiciones contextuales anteriores y las condiciones v2 pueden combinarse:

```yaml
Require:
  Permission: rank.member
  Worlds: [world]
  Gamemodes: [SURVIVAL, ADVENTURE]
  Channels: [global, local]
  Regions: [spawn]
  Discord: true
  Variables:
    "{player_group}": "in:default,member"
    "%vault_eco_balance%": ">=100"
  Data:
    onboarding.step: "equals:pending"
  Scripts:
    - "player.getPing() < 250"
Ignore:
  Worlds: [jail]
  Muted: true
  Uuid: true
  Variables:
    "{player_name}": "regex:^Test[A-Z].*"
  Data:
    moderation.exempt: "equals:true"
  Scripts:
    - "player.isOp()"
```

Todas las entradas de `Require.Variables` y `Require.Data` deben coincidir. Una sola coincidencia dentro de `Ignore` omite la regla. Todos los scripts requeridos deben devolver verdadero; cualquier script de exclusión verdadero la omite. Si los scripts están desactivados o no disponibles, los scripts requeridos fallan de forma cerrada.

Las comparaciones admitidas son igualdad simple, `equals:`, `!=`, `contains:`, `starts:`, `ends:`, `in:a,b,c`, `regex:...`, `>`, `>=`, `<` y `<=`. Los regex condicionales tienen longitud limitada y se conservan en una caché acotada de patrones compilados.

`Require.Regions`/`Ignore.Regions` usan el snapshot asíncrono seguro de regiones WorldGuard y
Chatalyst. `Ignore.Muted` reconoce el mute activo de Chatalyst. `Require.Discord` e
`Ignore.Discord` seleccionan el contexto entrante de Discord; como permisos, estado y acciones
dependen de un jugador, el motor genérico procesa al autor cuando su ID está mapeado en
`/discord link` (o el respaldo manual `Discord.Linked_Accounts`) y ese jugador de Minecraft
está online. Los autores no vinculados u
offline siguen pasando por el filtro dedicado de groserías de Discord.

## Transformaciones de búsqueda

`Strip_Accents: true` pliega acentos solo en una vista interna de búsqueda, por lo que `puta`
también encuentra `pútà`. `Ignore.Uuid: true` enmascara UUID canónicos y de 32 hexadecimales en esa
misma vista para que patrones amplios de dígitos/guiones no capturen `/whitelist add <uuid>`. Los
límites de la coincidencia se proyectan al texto Unicode original: reemplazar nunca borra el UUID
ni quita acentos fuera de la coincidencia real.

## Acciones

```yaml
Actions:
  Commands:
    - "warnings {player} clear"
  Player_Commands:
    - "warp spawn"
  Proxy_Commands:
    - "survival broadcast {player} activó {rule}"
  Rewrite_In_World:
    world_nether: "Este mensaje no está disponible aquí."
  Set_Data:
    onboarding.step: complete
    last.rule: "{rule}"
  Remove_Data: [temporary.flag]
  Open_Book: rules-help
  Audit: true
  Discord_Staff: true
```

- `Deny` cancela el contenido. `Deny_Silently` además oculta ese hecho al remitente.
- `Replace` sustituye coincidencias; `Replace_Raw` permite referencias como `$1`.
- `Replace_Prolong: "*"` coloca un punto Unicode por cada punto Unicode del fragmento original;
  sustituye a `Replace` y exige exactamente un carácter imprimible.
- `Warn`, `Notify`, `Points`, `Kick`, `Fine`, `Sound`, `Title`, `Actionbar`, `Bossbar` y `Toast` son acciones independientes.
- `Actions.Commands` ejecuta comandos de consola.
- `Actions.Player_Commands` ejecuta comandos como el remitente y con sus permisos.
- `Actions.Proxy_Commands` usa `"<servidor-destino> <comando>"` y solo ejecuta en ese backend
  remoto. Requiere transporte autenticado, opt-in local y allowlist según
  [Sincronización por proxy y Redis](proxy-redis.es.md#comandos-de-regla-dirigidos-a-consola).
- `Actions.Rewrite_In_World` reescribe el mensaje completo únicamente en el mundo indicado y
  dispara el mismo evento público de reemplazo que `Replace`.
- `Set_Data` y `Remove_Data` actualizan estado por jugador mediante el backend YAML, SQLite o MySQL seleccionado.
- `Open_Book` abre una entrada de `books.yml`.
- `Audit` escribe un evento `RULE` en el registro diario; `Discord_Staff` envía el mismo evento acotado al canal privado configurado para el bot.

Las acciones aceptan placeholders de Chatalyst, variables JavaScript personalizadas y valores de PlaceholderAPI. Los valores propios de una regla son `{message}`, `{original_message}`, `{rule}` y `{context}`.

## Tiempo y presentación

Usa `Delay_Seconds` como tiempo de espera global y `Player_Delay_Seconds` como tiempo por jugador. `Begins` y `Expires` aceptan `yyyy-MM-dd HH:mm` para reglas temporales. Las acciones visuales usan MiniMessage.

## Cobertura de auditoría

Cuando `Log.Enabled` está activo, interruptores separados permiten registrar chat público, comandos, mensajes privados, correo, carteles, libros y renombrados completados en yunque. Los comandos de autenticación siguen excluidos. Las reglas con `Audit: true` agregan eventos `RULE` explícitos para distinguir contenido ordinario de automatizaciones activadas.

La exclusión también se aplica cuando una regla amplia se evalúa deliberadamente sobre comandos.
Si `/login`, `/register`, `/reg`, `/otp` o un comando sensible configurado por el owner coincide
con una regla, todos sus argumentos se sustituyen por `<redacted>` antes de escribir el evento
`RULE`, alertar al Discord de staff o resolver `{message}` / `{original_message}` en `Notify`.
El aviso privado que vuelve al propio remitente no cambia. Es una última frontera de seguridad;
aun así se recomienda ignorar comandos de autenticación para que una regla ajena no los deniegue
ni castigue.

## Operación

Ejecuta `/cht reload` después de cambiar `rules.yml` o un archivo importado. Un YAML inválido
conserva el snapshot inmutable anterior en vez de sustituirlo silenciosamente por reglas vacías.
Usa `/cht rule` para listar o activar/desactivar reglas cargadas sin editar archivos. Conserva los
comandos con contraseñas en `Apply_To.Ignore_Commands`, prueba expresiones amplias en un servidor
de ensayo y concede permisos de excepción de forma limitada. El motor admite como máximo 512
reglas por snapshot, acota nombres y regex condicionales, y limpia atómicamente los delays al
recargar.
