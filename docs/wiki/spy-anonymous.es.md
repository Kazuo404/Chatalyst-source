# Registro de cofres, compartir inventario y modo anónimo

Tres sistemas añadidos sobre el spy y la suite de staff: vigilancia de contenedores y
mecanismos con coordenadas exactas y teletransporte, inventarios compartibles en el chat
con un botón «Abrir», y un modo de incógnito completo para streamers. Todo es bilingüe
(cada jugador lo ve en su idioma) y seguro en Folia.

## Registro de contenedores y mecanismos

Registra quién **abre** cofres, cofres trampa, barriles, shulkers, ender chests, hornos,
tolvas, soltadores, dispensadores, atriles, vagonetas y barcas con cofre; quién **coloca**
o **rompe** contenedores; y quién **usa** mecanismos (palancas, botones, puertas,
trampillas, verjas, repetidores, comparadores); y su actividad de banco de trabajo:
**crafteos**, **encantamientos** y **tratos cerrados con aldeanos** — estos últimos con
el intercambio completo («3x emerald por 20x wheat»), que es lo que permite detectar
precios rotos o granjas de esmeraldas. Cada entrada guarda jugador, acción, objetivo y
coordenadas exactas.

### Anti-spam por repetición

Repetir la misma acción sobre el mismo objetivo dentro de `Repeat_Seconds` (60 por
defecto) produce **una sola línea**; la siguiente indica cuántas se ocultaron
(«+19 repetidas»), así que no se pierde información, solo el ruido. Abrir un cofre veinte
veces ordenando el almacén es una línea, no veinte.

Los objetivos que **se mueven** (aldeanos, vagonetas con cofre, caballos) se agrupan por
entidad y no por coordenadas: si no, cada paso del aldeano contaba como objetivo nuevo y
el spy se llenaba de líneas idénticas.

Las entradas viven en dos sitios:

- **En memoria** (últimas 1000) para consulta inmediata con `/containerlog`.
- **En los logs diarios** (`logs/chat-YYYY-MM-DD.log`, tipo `CONTAINER`) para auditoría
  histórica; también aparecen con `/chatlog container`.

### El comando

```
/containerlog [jugador...] [cantidad|fecha]     (alias: /contlog, /cofres)
```

Sin fecha lee el buffer reciente; con `hoy`, `ayer` o `YYYY-MM-DD` relee el archivo de
ese día. Cada línea trae dos botones para el staff con permiso de teletransporte:

- **[Ver]** — abre el contenido del contenedor en una vista de solo lectura, sin ir
  hasta él. Muestra lo que hay **ahora mismo** (no una foto del momento en que se abrió),
  y las shulkers de dentro se abren con un click. En un ender chest muestra el del
  jugador, no el bloque. Permiso `chatalyst.containerlog.peek`.
- **[Ir]** — te teletransporta al lado del bloque exacto.
- **[TP]** — te teletransporta hasta el jugador.

Los mismos botones aparecen en el **spy en vivo**: con `/spy` activo y el tipo granular
`container` (cofre en el menú de spy), cada interacción llega al momento. El panel
`/staff` tiene un botón «Registro de cofres» en la fila de herramientas.

### Spy global o por jugador

El spy es **global** por defecto (ves a todo el servidor), pero se puede acotar:

```
/spy                  menú de spy (interruptor, tipos y a quién vigilas)
/spy toggle           encender o apagar
/spy <jugador>        vigilar SOLO a ese jugador (repite para quitarlo)
/spy all              volver al alcance global
```

También se maneja **desde las GUIs**: en `/staff` el botón de spy muestra su estado
(encendido/apagado y alcance) y con **click derecho** lo enciendes o apagas sin salir
del panel (click izquierdo abre los ajustes). Y en `/players` → gestionar hay un botón
**Vigilar con el spy** que pone o quita a ese jugador de la lista con un click.

Vigilar acumula: `/spy Pepe` y luego `/spy Ana` deja a los dos bajo lupa y silencia al
resto. El filtro se aplica a **todos** los tipos (MP, comandos, carteles, libros,
yunques y contenedores), y en los mensajes privados basta con que vigiles a una de las
dos puntas para verlos. Se puede vigilar a alguien desconectado: sus líneas llegarán en
cuanto entre.

El primer objetivo **enciende** el spy si estaba apagado, y quitar el último lo **vuelve
a apagar** — así, dejar de vigilar a alguien nunca te deja mirando a todo el servidor sin
haberlo pedido. Si el spy ya estaba encendido en global antes de vigilar a nadie, quitar
el último objetivo te devuelve al global (que es de donde venías). El mensaje te dice en
cuál de los dos casos estás, y el menú muestra la lista con un click para volver al
global.

### Configuración (`players.yml` y `moderation.yml`)

```yaml
Spy:
  Containers:
    Enabled: true      # apaga TODO el sistema
    Mechanisms: true   # palancas, botones, puertas, trampillas, verjas...
    Crafting: true     # crafteos y encantamientos
    Trading: true      # tratos cerrados con aldeanos
    Repeat_Seconds: 60 # agrupa repeticiones (0 = una línea por repetición)
```

```yaml
Log:
  Containers: true     # persistencia en los archivos diarios
```

### Permisos

| Permiso | Default | Qué da |
| --- | --- | --- |
| `chatalyst.spy.container` | false | Ver el spy de contenedores en vivo |
| `chatalyst.command.containerlog` | op | Usar `/containerlog` y su botón del panel |
| `chatalyst.containerlog.teleport` | op | Ver y usar los botones [Ir] y [TP] |
| `chatalyst.containerlog.peek` | op | Ver y usar el botón [Ver] (abrir contenidos) |
| `chatalyst.containerlog.exempt` | false | Este jugador nunca deja rastro |

Los tres primeros están incluidos en `chatalyst.staff.*`.

## Shulkers dentro de las revisiones

En las vistas de solo lectura de `/invsee` y `/echest` (y en los inventarios compartidos
por chat), **un click sobre una shulker box abre su contenido** en otra vista de solo
lectura, con flecha para volver.

En las sesiones de **edición** (`invsee.edit`) el inventario es real y el click izquierdo
sigue cogiendo y moviendo items: ahí se usa **click derecho con el cursor vacío** para
ver el interior de una shulker. Al abrirla se aplica lo editado hasta ese momento, y la
flecha reabre la sesión con el estado fresco. Con items en el cursor, el click derecho
conserva su comportamiento normal (soltar de uno en uno).

El editor de inventario ya permitía modificar la **armadura puesta** (fila inferior,
botas → casco) y la **segunda mano**; ahora los paneles bloqueados de esa fila muestran
una guía con la distribución para que sea evidente. Los cambios se aplican al cerrar.

## /clearinventory

```
/clearinventory <jugador> [all|ender]     (alias: /clearinv, /ci, /limpiarinv)
```

Sin argumento limpia **todo**: almacén, hotbar, armadura equipada, segunda mano, el item
agarrado con el cursor y la rejilla de crafteo 2×2. `ender` limpia solo el ender chest;
`all` limpia ambos. Un modo desconocido no borra nada (muestra el uso). Corre en la
región del objetivo (Folia), avisa al jugador afectado en su idioma y queda en la
auditoría. Permiso: `chatalyst.command.clearinventory` (op, incluido en
`chatalyst.staff.*`). Es parte de la suite dinámica: solo existe con
`Staff_Moderation.Enabled: true`.

## Compartir inventario en el chat: [inv] y [ec]

Cualquier jugador puede escribir `[inv]` (o `[inventory]`/`[inventario]`) o `[ec]`
(o `[ender]`/`[enderchest]`) en el chat. El token se convierte en una etiqueta con botón
**▶ Abrir** que cualquiera puede clicar durante **5 minutos**:

- La vista es **en vivo y de solo lectura** (se lee en la región del dueño al abrir).
- Las shulkers de dentro se abren con un click.
- El botón lleva un **token opaco**, nunca el UUID ni el nombre real: nadie puede forjar
  el comando para mirar a quien no compartió, y un anónimo no se delata al compartir.

Config: `Chat.Inventory_Share: true` en `chat.yml`. Permiso del emisor:
`chatalyst.chat.shareinv` (default true).

## Mensajes vanilla con estilo

Minecraft manda su propio feedback de comandos (`/tp`, `/xp`, `/gamemode`, `/give`,
`/kill`, `/effect`, `/time`, `/weather`...) como texto plano gris:

```
BurnInSilence teletransportado hasta Nec1x
Nec1x tiene 4,575 de exp. (nivel 47) y necesita 213 de exp para subir su nivel
```

Chatalyst los detecta y los reescribe con el estilo del plugin, **en el idioma de cada
cliente**:

```
» Teletransportaste a BurnInSilence hasta Nec1x.
» Nec1x tiene 4575 puntos de experiencia.
```

Funciona porque el servidor manda esos mensajes como componentes *traducibles* (una clave
más sus argumentos). El plugin reconoce la clave, saca los argumentos como `{0}`, `{1}`,
`{2}`... y los mete en la plantilla de `lang/<idioma>.yml`, bajo `vanilla:`. Hay 28 claves
cubiertas de fábrica; editarlas es solo tocar el lang, y poner una plantilla **vacía**
(`""`) conserva el mensaje vanilla de esa clave.

```yaml
# chat.yml
Vanilla_Messages:
  Enabled: true
  Ignore: []   # claves vanilla que quieres dejar como están
```

**Requiere PacketEvents** instalado y `PacketEvents.Enabled: true` (integrations.yml):
estos mensajes no pasan por la API de Bukkit, solo por la capa de red. Sin PacketEvents
el resto del plugin funciona igual y verás un aviso informativo en consola al arrancar.

## Modo anónimo: /anonymous

```
/anonymous [on|off]     (alias: /anon, /anonimo)
```

Pensado para streamers. Al activarlo:

- **Nombre**: un alias generado con pinta de jugador real — `ShadowWolf42`,
  `Nether_Nomad`, `ItzPanda`, `xXBlazeXx`, `frostgolem`... (`Name_Style: realistic`,
  cientos de miles de combinaciones; con `confusing` vuelven los ilegibles tipo
  `illiliolil`). Nunca coincide con otro alias, con un conectado ni con **nadie que haya
  jugado en este servidor** (el disfraz no suplanta miembros). Se propaga a chat, tab,
  plugin TAB, nametag fancy y sugerencias de tab-complete de *todos* los comandos.
- **Rango y tags**: tu prefix/suffix/grupo real de LuckPerms o Vault, `/tag`, party, color
  de rango y tu **color de chat personal** (`/chatcolor`) desaparecen de todos los
  formatos — incluidos los resueltos por PlaceholderAPI (`%luckperms_prefix%` y compañía
  se reescriben para el sujeto anónimo).
- **Rango del disfraz**: en vez de salir «pelado», el anónimo lleva el tag de un miembro
  corriente — el prefijo del grupo `Anonymous.Disguise_Group` (`default` de fábrica), o el
  literal `Anonymous.Tag` si lo rellenas. Aparece en chat, tab, nametag y hovers, para que
  el disfraz se mezcle: un nombre sin **nada** delante en una lista donde todos llevan tag
  señala al anónimo igual de bien que su rango real. Pon los dos en `""` para volver al
  comportamiento anterior (nombre a secas).
- **Skin**: un **pool de skins reales** (`Anonymous.Skins`, cuentas cuyas skins se
  descargan firmadas de Mojang al arrancar): cada sesión viste una distinta al azar,
  como si fueras un jugador cualquiera de NameMC. Las cabezas de las GUIs muestran la
  misma skin de la sesión. Las cuentas inexistentes se saltan con aviso en consola.
- **Fuera del juego**: el bot de Discord de Chatalyst publica el alias dentro de su
  mensaje formateado, nunca el nombre real. Los anuncios del mercado y los mensajes de
  muerte también usan el alias.
- **Identidad estable por sesión**: dentro de la misma conexión eres **siempre la misma
  persona**. Si haces `/anonymous off` y lo vuelves a activar, recuperas el mismo alias y
  la misma skin — cambiar de cara a media sesión delataría que los dos disfraces son el
  mismo jugador.
- **Persistencia**: quien se desconecta anónimo vuelve anónimo — y con **alias y skin
  nuevos en cada conexión** (`Rotate_Name_On_Join`, activo por defecto): solo al
  reconectar «eres otra persona», y nadie puede rastrearte entre días. El alias nuevo
  tampoco es **el mismo de la sesión anterior**: con un pool de nombres finito el azar lo
  repetiría de vez en cuando, y «otra persona» que resulta llamarse igual que la de ayer no
  engaña a nadie. `/nick` queda bloqueado mientras dura el disfraz (quitarlo con `/nick off`
  te desenmascararía sin querer).
- **Los mensajes de entrada y salida** anuncian el **alias**, nunca el nombre real —
  incluido el adiós, que se compone un par de ticks *después* de desconectar: el alias se
  retiene el tiempo suficiente (y más allá de una reconexión en el mismo segundo) para que
  la despedida no pueda caer nunca en tu nombre real.
- **Cambiar de identidad estando conectado** parece intencionalmente una reconexión cuando
  `Simulate_Reconnect` está activo (valor de fábrica): al activar el anónimo se ve salir la
  identidad real y entrar el alias; al desactivarlo sale el alias y entra la identidad
  restaurada. Son mensajes simulados para cada espectador: el jugador nunca se desconecta.
  Ponlo en `false` para cambiar silenciosamente en el mismo sitio.
- **La entrada del alias no llega en el mismo segundo que tu salida.** Nadie se reconecta en
  cero segundos, y ese detalle delataba el disfraz por muy bueno que fuera el alias. El
  disfraz se aplica **al instante** —quien pide anonimato lo necesita ya— pero el aviso de
  entrada espera entre 1 y 5 minutos, sorteados cada vez (`Realistic_Transition`). Sigues
  jugando disfrazado durante la espera; lo único que llega tarde es el mensaje.
- **Una cara elegida con `/anon set <n>` se queda contigo** al desconectar y volver, hasta
  que la cambies o apagues el anónimo. Rotar es lo correcto para un alias sorteado —cada
  sesión eres otra persona—, pero un slot concreto es un personaje. Si el slot fijado ya no
  existe, o su cara la lleva otro jugador en ese momento, se cae a un alias nuevo: dos
  disfraces idénticos delatarían a los dos.

`/anonymous off` restaura apodo, tags, rango visible y la skin original exactos.

### Configuración (`players.yml`)

```yaml
Anonymous:
  Enabled: true
  Name_Style: "realistic"   # realistic = ShadowWolf42...; confusing = "illiliolil"
  Name_Chars: "ilo"         # solo estilo confusing
  Name_Length: 10           # solo estilo confusing (máx. 16)
  Rotate_Name_On_Join: true # alias Y skin nuevos en cada conexión
  Simulate_Reconnect: true  # salida/entrada falsas al alternar; false = cambio silencioso
  Realistic_Transition:     # la entrada del alias llega minutos despues, no en el acto
    Enabled: true
    Min_Seconds: 60
    Max_Seconds: 300
  Disguise_Group: "default" # rango que viste el disfraz ("" = sin tag)
  Tag: ""                   # tag literal que sustituye al del grupo, p. ej. "<gray>[Miembro] "
  Skins:                    # pool de disfraces: una skin real al azar por sesión
    - "Daniel"
    - "Emma"
    # ...añade las que quieras (cualquier cuenta existente, p. ej. de NameMC)
  Skin_Name: "windexonv"    # entrada extra/respaldo del pool (la del «?» negro)
```

El disfraz también cubre el **grupo indirecto**: un anónimo entra con la entrada fancy
genérica (no la de su rango) y chatea con el formato por defecto aunque su grupo tenga
un formato propio en `Chat_Format_Groups`.

Permiso: `chatalyst.command.anonymous` (op por defecto — concédelo al rango streamer).

### Lo que el staff sigue viendo (a propósito)

`/realname`, `/seen`, los logs, la auditoría y el registro de contenedores usan el nombre
real: el anonimato es de cara a los jugadores, no a la moderación.

### Rastro de auditoría (`Log.Anonymous`)

Ponerse el disfraz — tanto por `/anonymous` como por la rotación de cada entrada — escribe
la correspondencia **nombre real → alias** en el log diario del plugin, y solo ahí:

```
[ANONYMOUS] BurnInSilence: -> Ninja60 (adc749c2-0db9-4b2d-a438-c4250eb9b93b)
```

Ni la consola en vivo ni el chat la ven, así que nada delata al streamer en el momento;
pero si `Ninja60` hace algo sancionable, el staff puede averiguar después quién era. Aparece
en `/chatlog` (solo staff) como cualquier otra entrada. Pon `Log.Anonymous: false` en
`moderation.yml` para **cero rastro** — a cambio de no poder atar un alias a un jugador una
vez terminada la sesión.

### Límites honestos

- El **nombre de perfil** real sigue viajando a nivel de protocolo (F3, sample del ping,
  plugins de scoreboard de terceros). Ocultarlo requeriría reescritura de paquetes.
- El nombre vanilla sobre la cabeza lo tapa **Fancy_Nametags** (o TAB con
  `invisible-nametags: true`): mantén uno de los dos activo.
- Si **TAB** (u otro plugin de scoreboard/tablist) pinta el prefijo con
  `%luckperms_prefix%`, ese plugin delatará el rango por su cuenta. Cambia su config a
  los placeholders conscientes del anonimato de Chatalyst: `%chatalyst_prefix%`,
  `%chatalyst_suffix%`, `%chatalyst_group%` (vacíos durante el disfraz) y
  `%chatalyst_anonymous%` (yes/no).
- Un puente de Discord de terceros todavía puede publicar nombres reales por su cuenta.
  Elimínalo cuando actives el bot integrado de Chatalyst.
