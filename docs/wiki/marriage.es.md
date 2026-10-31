# Matrimonios (`/marry`, `/divorce`)

Los jugadores pueden pedirse matrimonio; cuando los dos aceptan, el servidor anuncia la
boda y cada uno aparece en el hover del otro al pasar el cursor por su nombre en el chat.

**Opt-in.** La feature viene **desactivada**. Mientras está apagada, `/marry` y `/divorce`
solo responden con el mensaje de "desactivado" y nada más del plugin cambia.

## Cómo activarla

`players.yml`:

```yaml
Marriage:
  Enabled: true
  Request_Timeout: 60          # segundos que dura una propuesta (mínimo 10)
  Announce: true               # anuncia las bodas PÚBLICAS (la privada nunca avisa)
  Announce_Divorce: false      # las rupturas son privadas por defecto
  # El hover, que funciona como un acta: {partner} y {since} (tiempo casados).
  # {rank_color} tiñe el separador » con el color del rango del jugador, para que la
  # línea combine con las filas de rango/grupo/ping de arriba en vez de destacar.
  Tag: "<#C0B3D9>{$married} {rank_color}» <#FBF2FF>{partner} <#7B68A0>· <#C0B3D9>{since}"
```

Recarga con `/cht reload`. Los comandos necesitan `chatalyst.command.marry` (concedido a
todos por defecto — restríngelo con LuckPerms si quieres que casarse sea un perk de rango).

## Para los jugadores

| Comando | Qué hace |
|---|---|
| `/marry <jugador>` | Manda la propuesta. Al otro le llegan botones **[ Aceptar ]** / **[ Rechazar ]**. |
| `/marry accept` / `/marry deny` | Responde a una propuesta pendiente (es lo que ejecutan los botones). |
| `/marry` | Muestra con quién estás casado y desde cuándo. |
| `/divorce` | Termina el matrimonio. |
| `/marry bank` | Abre el **cofre compartido** de los dos. |
| `/marry tp` | Viaja hasta tu pareja. |
| `/marry <jugador> boda` | Pide matrimonio **con ceremonia pública**. |
| `/marry ceremony` | Celebrar vuestra boda otra vez. |
| `/marry join` | Viajar a una boda a la que te invitaron. |
| `/marry help` | La lista completa de comandos. |

Alias: `/casarse`, `/matrimonio`, `/divorciarse`. Al destinatario se le puede nombrar por
su apodo además de por su nombre real.

## La ceremonia — la eligen los novios

Una boda puede ser discreta (solo el anuncio) o **una ceremonia pública**: marcha nupcial
para todo el servidor e invitación con botón para asistir. **Lo deciden los que se casan**,
no el dueño del servidor:

| Cómo | Qué pasa |
|---|---|
| `/marry <jugador>` | **Boda privada: no se entera nadie.** Ni anuncio, ni título, ni sonido, ni partículas — solo los dos reciben su mensaje. |
| `/marry <jugador> boda` | **Boda pública**: convocatoria, votos, marcha nupcial, título en pantalla e invitación para todo el servidor. |
| `/marry ceremony` | Ya casados, celebran la ceremonia otra vez cuando quieran. |

La única huella que deja una boda privada es el hover: al pasar el cursor sobre sus nombres
en el chat sigue apareciendo con quién están casados. El matrimonio no se oculta, lo que no
hay es *aviso*.

Quien recibe la propuesta **ve si le están pidiendo boda pública** antes de aceptar: decir
que sí a una ceremonia delante de todo el servidor no puede ser una sorpresa.

**La música.** Minecraft no tiene música de boda, así que se toca *nota a nota* con sonidos
de note block. Cada entrada de `Melody` es `"<nota>:<tick>"` — nota `0-24` (de F#3 a F#5, el
rango de un note block, donde `12` es F#4) y el tick en el que suena (20 ticks = 1 segundo).
La de fábrica es el coro nupcial de Wagner («Here comes the bride») en Do:
`G4 | C5 C5 C5 | G4 D5 C5 B4 | C5`. Una entrada mal escrita se salta con aviso en consola —
la boda sigue, solo esa nota se queda muda.

Lo único que hay en config son los límites y la partitura, no la elección:

```yaml
Marriage:
  Ceremony:
    Guest_Teleport: true      # interruptor de SEGURIDAD (ver abajo)
    Window_Seconds: 60        # cuánto dura la invitación
    Cooldown_Minutes: 10      # anti-spam de /marry ceremony
    Gathering_Seconds: 30     # para que los invitados lleguen antes del sí
    Vow_Seconds: 60           # para decir "acepto"
    Style: "hearts"           # efecto sobre la pareja (firework-show, halo, none...)
    Instrument: "block.note_block.bell"
    Melody: ["13:0", "18:5", "18:20", "18:25", "13:40", "20:45", ...]   # 32 notas, ~16 s
```

> **Lee esto antes de dejar `Guest_Teleport` en true.** El botón manda a quien lo pulse a
> donde se casó la pareja, y la invitación llega a todo el servidor. Una pareja puede
> casarse donde sea — incluido un pozo de lava o una trampa de PvP. En `false` la boda sigue
> teniendo música y anuncio, pero no se teletransporta a nadie.

La invitación se cierra al pasar `Window_Seconds`, y un `/cht reload` la cierra al momento.

## Ventajas del matrimonio

```yaml
Marriage:
  Perks:
    Bank:
      Enabled: true
      Rows: 3              # 1-6 (9 huecos por fila)
    Teleport:
      Enabled: true
      Cooldown_Seconds: 120
    Join_Notice: true      # te avisa cuando tu pareja se conecta
```

**Banco compartido** (`/marry bank`). Los dos abren la *misma* instancia del cofre, así que
lo que guarda uno lo ve el otro al momento — igual que un cofre real con dos personas
delante. Se guarda cuando se va el último que lo tenía abierto y al apagar el servidor.

Dos reglas puestas a propósito:

- **El divorcio se rechaza mientras el banco tenga items.** El plugin no los reparte por ti
  ni los borra: vacíalo primero. Una ruptura nunca debe hacer desaparecer items.
- El título sale en el idioma por defecto del servidor para los dos — un inventario
  compartido tiene un solo título, así que no puede seguir el idioma de cada uno.

> **Feature de servidor único.** En una red con base de datos compartida, dos cónyuges
> conectados a backends *distintos* abrirían copias separadas y ganaría el último cierre.
> Déjalo desactivado en redes multi-backend, o asume ese límite.

**Teletransporte** (`/marry tp`) te lleva hasta tu pareja con un cooldown, que solo empieza
a contar si el viaje sale bien. **El aviso de conexión** te avisa cuando entra — una pareja
en modo anónimo nunca lo dispara, porque delataría quién se acaba de conectar.

Reglas que impone el plugin:

- Nadie puede estar casado con dos personas. Se revalidan **los dos lados** en el momento
  de aceptar, no solo al mandar la propuesta.
- Una sola propuesta abierta por jugador: un tercero no puede pisar la pendiente y robarse
  el "sí".
- Las propuestas caducan solas y **no** se persisten: un reinicio las limpia.
- Si quien propuso se desconecta antes de la respuesta, la propuesta se descarta.

## El hover

`formats/chat.yml` lleva la línea en los hovers `prefix` y `player-name`:

```yaml
      - "{married_tag}"
```

`{married_tag}` pinta `Marriage.Tag` sustituyendo `{partner}`. En un soltero se queda vacío
**y la línea entera desaparece** del hover, sin dejar un renglón en blanco. Las
instalaciones existentes reciben la línea automáticamente al actualizar (versión de config
26); un hover que hayas personalizado se deja intacto, así que ahí añádela a mano si la
quieres.

`Marriage.Tag` es la única línea del hover que vive en `players.yml` y no en
`formats/chat.yml`, así que tiene su propia migración para no quedarse atrás respecto a las
filas de rango/grupo/ping: al actualizar, el separador `»` pasa a `{rank_color}` (igual que
esas filas) y se quita el corazón decorativo viejo. La migración solo reconoce las
plantillas de fábrica, de modo que un tag que hayas personalizado se deja tal cual lo
escribiste — incluido cualquier `{rank_color}` que le añadas tú, que se resuelve al color
del rango de quien mira, como en todas partes.

## Placeholders

| Placeholder | Valor |
|---|---|
| `{married_to}` | Nombre mostrado de la pareja, o vacío |
| `{married_tag}` | La línea decorada de `Marriage.Tag`, o vacío |
| `{partner}` / `{since}` | Dentro de `Marriage.Tag`: pareja y tiempo casados |
| `%chatalyst_partner%` | Nombre de la pareja (PlaceholderAPI, para TAB/scoreboards) |
| `%chatalyst_married%` | `yes` / `no` |

## Modo anónimo

Un jugador en [`/anonymous`](spy-anonymous.es.md) **nunca** muestra pareja: `{married_to}`,
`{married_tag}` y las dos variantes de PlaceholderAPI se vacían mientras dura el disfraz.
Saber quién es la pareja identifica a la mitad anónima igual de bien que su propio nombre.

## Notas

- Los apodos se insertan como texto plano y escapado, así que un apodo con MiniMessage no
  puede inyectar formato en el anuncio ni en el hover.
- Las dos filas de un matrimonio se escriben con compare-and-set durable: si falla la
  segunda, se revierte la primera, de modo que no puede existir un matrimonio a medias.
- El divorcio borra primero la fila de la pareja — un divorcio interrumpido te deja marcado
  a *ti*, que se arregla repitiendo el comando, en vez de dejarla a ella casada con un
  fantasma.
