# Suite de moderación de staff

Un kit de moderación completo y 100% por GUI: kick, ban, tempban, baneos de conexión
(IP), mute (con shadow mutes), warns con escalada automática, revisiones por freeze,
notas de staff, vanish, inspección de inventarios e historial de castigos por jugador.
Todo queda auditado y todo texto visible por el jugador está localizado (cada uno lo lee
en su idioma).

## Opt-in por diseño

La suite viene **desactivada** (`Staff_Moderation.Enabled: false` en `moderation.yml`)
para que Chatalyst nunca compita con el plugin de staff que el servidor ya usa
(otros plugins de moderación o staff). Mientras está apagada, ninguno de los comandos de staff
se registra siquiera.

Con la suite activa, `Command_Priority: true` (el default) hace que Chatalyst reclame las
etiquetas planas (`/ban`, `/kick`, `/vanish`...) aunque otro plugin las registrara
primero; el comando desplazado se recuerda y se restaura si desactivas el módulo en un
reload. Ponlo en `false` para tomar solo etiquetas libres (las formas con namespace
`/chatalyst:ban` siempre funcionan).

`Exempt_Ops: true` (default) hace a los operadores incastigables e incongelables. Ponlo
en `false` en servidores pequeños donde cada jugador de confianza conserva OP y aun así
debe poder moderarse.

## Comandos

Cada herramienta tiene alias bilingües — todos los comandos funcionan en inglés y en
español.

| Comando | Alias | Propósito |
| --- | --- | --- |
| `/staff [menu\|on\|off\|players]` | `/staffmode`, `/modmode` | Modo staff y dashboard de moderación |
| `/kick <jugador> [motivo]` | `/expulsar` | Kick con colores, gradientes y estilos con nombre |
| `/warn <jugador> <motivo>` | `/amonestar`, `/advertir` | Aviso: suma puntos y queda en el historial |
| `/note <jugador> <texto>` | `/nota` | Adjunta una nota de staff (funciona offline) |
| `/ban <jugador> [motivo]` | `/banear` | Ban permanente con pantalla fancy |
| `/tempban <jugador> <tiempo> [motivo]` | `/bantemporal` | Ban temporal |
| `/unban <jugador>` | `/pardon`, `/desbanear` | Quita un ban de Chatalyst o nativo |
| `/banip <jugador\|ip> [tiempo] [motivo]` | `/ipban`, `/banearip` | Banea la cuenta y su conexión (bloquea alts) |
| `/unbanip <jugador\|ip>` | `/ipunban`, `/desbanearip` | Quita un ban de conexión |
| `/freeze <jugador> [motivo]` | `/congelar` | Congela a un jugador para una revisión |
| `/unfreeze <jugador>` | `/descongelar` | Libera a un jugador de la revisión |
| `/vanish [jugador] [on\|off]` | `/v`, `/esfumarse` | Alterna el vanish de staff |
| `/invsee <jugador>` | `/verinv` | Revisa (y opcionalmente edita) un inventario |
| `/echest <jugador>` | `/ec`, `/verender` | Revisa (y opcionalmente edita) un ender chest |
| `/seen <jugador>` | `/visto`, `/lastseen` | Cuándo se vio al jugador por última vez |
| `/check <jugador>` | `/checar` | Resumen de castigos y estado |
| `/tpto <jugador>` | `/goto`, `/irhacia` | Teletranspórtate hacia un jugador |
| `/tphere <jugador>` | `/bring`, `/traer` | Trae a un jugador hacia ti |
| `/clearinventory <jugador> [all\|ender]` | `/clearinv`, `/ci`, `/limpiarinv` | Limpia inventario, armadura puesta, segunda mano y cursor (con `ender`/`all`, también el ender chest) |

`/mute <jugador> [tiempo] [motivo]` (`-s` para shadow mute) y `/unmute` pertenecen al set
de moderación de chat y funcionan con o sin la suite.

## Moderación 100% por GUI

El staff nunca tiene que escribir comandos:

- **Dashboard** (`/staff menu`): lista de jugadores online (el clic va directo al menú de
  gestión), lista de congelados, reportes y herramientas.
- **Menú de gestión**: botones de kick, ban, mute, freeze, warn, notas, historial, alts e
  inventarios para un jugador.
- **Motivos preset** (`Reason_Presets` en `moderation.yml`) o un **motivo personalizado
  escrito por chat** que se captura en privado — nunca sale al chat público. Se admiten
  colores, gradientes y estilos con nombre, igual que en un comando.
- **Compositor de baneos**: duración (permanente primero, luego `Ban.Gui_Durations`),
  motivo, estilo visual y una vista previa exacta de la pantalla de ban antes de
  confirmar.
- **Compositor de mutes**: la misma experiencia — duraciones de `Mute.Gui_Durations`,
  presets o motivos escritos por chat con estilos, vista previa — más un interruptor de
  **shadow mute** (`-s`: el muteado no se entera).
- **Menú de gestión offline**: modera jugadores desconectados, incluidos los baneos de
  conexión.
- Cada confirmación despacha el comando real, que re-verifica permisos, protege a los
  objetivos con bypass y escribe la auditoría.

## Castigos

- **Kick / ban / tempban** muestran pantallas fancy localizadas configuradas en
  `moderation.yml` (`Kick.Screen`, `Ban.Screen` + variantes `_es`). La pantalla de ban
  admite `{remaining}` (cuenta regresiva completa — años, meses, días, horas, minutos,
  recalculada en cada intento de login) y `{expires}` (fecha y hora exactas).
- **Warns** (`/warn`) suman `Warning_Points.Manual_Warn_Points` puntos (0 = solo
  registra), respetan `chatalyst.bypass.warn` y quedan en el historial de castigos. Los
  puntos decaen según `Warning_Points.Decay_After`.
- **Escalada automática** (`Staff_Moderation.Escalation`): cruzar `Kicks_For_Ban`
  expulsiones recientes (dentro de `Kick_Window`) o `Warn_Points_For_Ban` puntos de aviso
  aplica un ban automático de `Ban_Duration` con motivo por defecto localizado, lo anuncia
  al staff y lo registra en el historial. Cada disparador se desactiva con `0`.
- **Historial de castigos**: las últimas 20 entradas por jugador (ban, tempban, kick,
  freeze, mute, ban del FreezeGuard, ban por escalada) se ven en `/check` (últimas 3) y
  completas en el menú de historial, lo más reciente primero, solo lectura.

## Revisiones por freeze

`/freeze` inicia una revisión de staff:

- Funciona **offline**: la revisión empieza en el próximo login del objetivo.
- Inmovilización total: ni caminar, ni saltar, ni girar la cámara, ni monturas.
- El único canal del congelado es el **chat privado de revisión** con el staff que lo
  congeló (más `/support` si el revisor está offline). `Allowed_Commands` controla la
  whitelist corta; `Restrict_Chat: true` mantiene su chat fuera del público.
- Un aviso a pantalla completa (localizado, con botón de revisión en Discord cuando
  `Discord_Invite_URL` está definido) advierte: **no te desconectes**.
- Salir de cualquier forma (quit, pérdida de conexión, matar el cliente) dispara un **ban
  permanente** tras `Disconnect_Grace_Seconds` (default 3s — justo para un transfer de
  proxy o una reconexión instantánea).
- Las alertas al staff incluyen un botón **[IR HACIA ÉL]**; el dashboard lista a los
  congelados y el clic izquierdo muestra el motivo.

## Baneos de conexión (IP) y alts

- `/banip` banea la cuenta **y** su conexión hasheada con SHA-256, expulsa a todos los que
  la compartan y rechaza futuros logins con la pantalla fancy de ban. Con
  `Ip_Ban.Ban_New_Accounts: true`, las cuentas nuevas que entren por una conexión baneada
  quedan baneadas de forma durable — cambiar de IP después ya no salva al alt. El staff
  recibe alerta en ambos casos (IpGuard).
- **Nunca se guarda la IP en claro** — solo su hash, suficiente para agrupar nombres que
  entran desde la misma conexión.
- Con `Track_Alts: true`, `/check` muestra pistas de posibles alts y el **menú de alts**
  lista una cabeza por cuenta que comparte la conexión; el clic abre el menú de gestión
  offline de ese alt. Conexión compartida es **pista, no prueba** (hermanos, cibercafés):
  el menú solo consulta, jamás castiga en bloque.
- Nota honesta: Minecraft Java no tiene ban por hardware. Un ban de conexión hasheada más
  el auto-baneo de alts es lo más fuerte que un servidor puede hacer.

## Notas de staff

`/note <jugador> <texto>` adjunta memoria compartida de staff a un jugador ("avisado
verbalmente, la próxima es mute") — incidentes que no llegaron a castigo. Las notas
funcionan offline, tienen tope por jugador (la más vieja cae), el jugador nunca las ve, y
hay un menú para leer/añadir/borrar (shift+clic borra). Permiso: `chatalyst.staff.notes`.

## Vanish, modo staff e inventarios

- `/vanish` oculta al staff; con `Vanish.Hide_From_Ping: true` los desvanecidos también
  salen del contador y del hover del ping de la lista de servidores.
  `Ops_See_Vanished: false` hace que incluso los OPs necesiten un
  `chatalyst.seevanished` concedido explícitamente.
- El modo staff (`/staff on`) puede auto-vanish (`Staff_Mode.Vanish_On_Enter`) y permitir
  vuelo (`Staff_Mode.Allow_Flight`).
- `/invsee` y `/echest` abren vistas en vivo; con `chatalyst.command.invsee.edit` /
  `chatalyst.command.echest.edit` el staff puede editar (armadura y segunda mano
  incluidas). Los cambios se aplican al cerrar y todo queda auditado.

## Permisos clave

| Nodo | Concede |
| --- | --- |
| `chatalyst.command.staff` | Modo staff y dashboard |
| `chatalyst.command.kick` / `.ban` / `.mute` / `.freeze` / `.warn` | Cada castigo |
| `chatalyst.command.banip` | Baneos de conexión |
| `chatalyst.command.invsee` / `.invsee.edit` | Ver / editar inventario (igual con `.echest`) |
| `chatalyst.command.vanish` / `.vanish.others` | Vanish propio / ajeno |
| `chatalyst.command.seen` / `.check` | Consultas |
| `chatalyst.staff.notes` | Leer, añadir y borrar notas de staff |
| `chatalyst.staff.alts` | Ver pistas de alts y el menú de alts |
| `chatalyst.seevanished` | Ver al staff desvanecido |
| `chatalyst.bypass.kick` / `.ban` / `.mute` / `.freeze` / `.warn` | Protege al staff de staff no-operador |
| `chatalyst.notify.security` | Alertas de seguridad (IpGuard, nombres ofensivos, evasión de filtros) |

Todas las acciones de moderación se escriben en el registro de auditoría (ver `Log` en
`moderation.yml`) y, con `Staff_Security.Enabled`, los comandos sensibles del staff se
reflejan en la consola y en el canal privado del bot de staff.
