# Changelog

## Sin publicar — canales más seguros, un anti-exploit más fuerte y menos esperas de almacenamiento (2026-10-31)

- **Los canales privados siguen siendo privados.** Un canal PARTY necesita el plugin Parties y sin
  él no se carga. Una errata en `Type` rechaza el canal en vez de dejarlo público. Solo los
  canales GLOBAL cruzan servidores, así que `Proxy` se ignora en los demás tipos.
- **Nuevo tipo de canal `GROUP`** (`Group_Placeholder`): chat de clan para cualquier plugin de
  clanes, con un placeholder. El staff puede leer canales de party y clan con
  `chatalyst.spy.channel`.
- **Los colores escritos siguen los mismos permisos que `/chatcolor`.** Degradados y arcoíris
  piden `chatalyst.gradient.*` o `chatalyst.gradient.custom`, y el hex pide `chatalyst.color.*`.
  Los colores con nombre siguen pidiendo solo `chatalyst.chat.color`.
- **El texto de los jugadores nunca llega a PlaceholderAPI**, ni en mensajes ni en apodos.
- **Discord:** las URL llegan intactas, se quitan las etiquetas de color,
  `Discord.Emoji_Replacements` convierte símbolos en emojis (también por color) y
  `Discord.Mentions` deja avisar a miembros.
- **Anti-exploit:** tope total por conexión que también cubre login y configuración, corchetes
  anidados en comandos, límites de libros, y una ráfaga ahora cuenta como una sola expulsión.
- **Almacenamiento:** una base de datos lenta ya no bloquea el hilo principal en `put`, y el
  refresco compartido lee un namespace cada vez (`Shared_Refresh_Seconds` pasa a 30 por defecto).
- **Rendimiento:** lista de jugadores, nametags, guardia de anónimos, limpieza de apodos,
  placeholders de títulos y el autocompletado de `/unban` ya no repiten trabajo sin cambios.
- **Nuevo:** `/chatalyst sendformat`, `{player_uuid}`, `{receiver_uuid}`, `{killer_uuid}`, `{health}`.
- **Además:** las lecturas masivas usan su propia conexión, los scripts corren en hasta 4 motores a
  la vez, un barrido grande del mercado se reparte entre ticks, los baneos caducados se guardan de
  una vez y los datos de conexión se borran tras `Staff_Moderation.Alts_Retention_Days` (365 por
  defecto).
- **Licencias:** GNU Trove ahora lo descarga el servidor y el jar incluye
  `META-INF/THIRD-PARTY-NOTICES.md`. Se quitó la integración opcional con LiteBans.

## Sin publicar — un vanish que solo mira, y alejarse que por fin sirve (2026-08-04)

- **El vanish es para mirar, no para tocar** (`Staff_Moderation.Vanish.Observer_Only`, cuatro
  interruptores, todos encendidos). Invisible se podía romper, poner, abrir cofres, pegar y
  recoger del suelo. Cada una de esas cosas delata el vanish tan rápido como que te vean — un
  bloque que se rompe solo, un cofre que se abre sin nadie delante —, pero el motivo de fondo no
  es el disfraz: es la ventaja. Invisible se mina dentro de una base, se vacía un cofre y se
  remata una pelea, y nada de eso tiene respuesta posible. El daño RECIBIDO se deja intacto a
  propósito: un vanish invulnerable convierte la lava, el void y cualquier trampa en un mirador
  gratis, así que quien se mete donde no debe paga lo que pagaría cualquiera — muerte y drop
  incluidos. Las herramientas de staff no se bloquean nunca: el kit, los menús y el editor de
  inventario son con lo que se mira. Y como no puedes comer, tampoco pasas hambre: no es una
  ventaja — no se gana nada con una barra que igualmente no podrías rellenar —, simplemente
  evita que una guardia de cuarenta minutos acabe en muerte por inanición, con una tumba
  marcando el sitio exacto desde el que mirabas.
- **Volver y anunciarse son ya el mismo instante.** El alias reaparecía y la línea que decía
  que entraba llegaba después — por su propia cadena de tareas y, encima, con los `Delay_Ticks`
  del fancy join, que de fábrica son medio segundo. En ese hueco se te podía ver aparecer de la
  nada antes de que nada dijera que habías vuelto, que delata igual que verte transformarte. La
  reaparición viaja ahora como callback hasta el momento en que la línea sale de verdad al chat.
- **Un grupo de disfraz que LuckPerms no tiene ya no falla en silencio.** Poner en
  `Disguise_Group` un grupo inexistente dejaba al disfraz sin rango ninguno y nadie decía nada:
  solo se notaba en que salías con el nombre pelado, que es justo lo que esa opción existe para
  evitar. Ahora avisa en consola, diciendo qué grupo es, y se cae a `default`.
- **Ahora se tapa el nombre en lo que escriben OTROS plugins** (`Anonymous.Guard_Other_Plugins`,
  encendido). Una tumba con tu nombre flotando encima es tan buena pista como cualquier cosa que
  imprima el servidor: la lee quien pase, se queda ahí horas y está clavada justo donde el alias
  acababa de morir. Lo mismo el aviso de un anticheat o la línea de muerte de otro plugin de
  chat. No se pueden arreglar uno a uno, y no hay forma de saber qué habrá instalado mañana, así
  que se corrige al final del camino: justo antes de que el texto llegue al cliente, el nombre
  real se convierte en el alias que ese jugador lleva puesto. No es censura, es traducción — el
  mensaje llega entero, nombrando a la persona como la conoce quien lo lee. Los nombres flotantes
  de entidades se cazan al aparecer y otra vez un tick después, porque casi ningún plugin nombra
  la entidad en el mismo instante en que la crea; los mensajes necesitan PacketEvents instalado.
  Al staff que puede usar `/realname` no se le cambia nada. Se sustituye por palabra completa: un
  nombre dentro de otro más largo se deja en paz.
- **El chat no era el único sitio ruidoso.** El guardián de arriba solo miraba el chat de
  sistema, y un plugin que quiere que algo se NOTE no lo manda al chat: lo manda como título a
  pantalla completa, como subtítulo, como action bar o como bossbar, que es justo donde más se
  lee. Un «¡ha ganado DevKazuo!» ocupando media pantalla delata mucho mejor que cualquier línea
  de chat. Ahora pasan por el mismo filtro esos cuatro caminos, y también el título de los menús
  que abre otro plugin («Cofre de DevKazuo»). Los marcadores se dejan aparte a propósito: ahí el
  nombre no es texto que se lee, es la clave con la que el cliente casa cada línea, y cambiarla
  rompería el marcador en vez de esconder nada.
- **Y el nombre seguía entero dentro de los clicks.** Un `[Reclamar la tumba]` con el texto ya
  tapado podía llevar dentro un `/msg DevKazuo` que aparecía en la caja de chat en cuanto
  alguien pulsara: el reemplazo de texto recorre hijos, hovers y argumentos de traducción, pero
  no entra en la acción de un click. Ahora ese click se QUITA, no se traduce. Un comando con el
  alias dentro ya no es el mismo comando — en el mejor caso no encuentra a nadie, y en el peor
  da con otra cuenta que de verdad se llama así —, y lo mismo vale para un enlace o un
  portapapeles. Sin click el mensaje se sigue leyendo entero; lo único que se pierde es un atajo
  hacia una persona que ahora mismo no existe. Se limpia también dentro de los hijos, de los
  hovers y de los argumentos de un mensaje traducible.
- **Un logro anunciaba el nombre real, y lo anunciaba solo.** El mismo agujero que tenía `/list`
  — el servidor arma la línea con el display name de Minecraft, que es el nombre de la cuenta —
  pero peor de carácter: `/list` hay que escribirlo, y un logro sale disparado a todo el chat sin
  que nadie pida nada. Un anónimo que juega una tarde encadena logros, y con el primero ya se
  sabe quién es. Se reescribe en vez de callarse, porque el logro es suyo y merece contarse: la
  línea es un mensaje traducible cuyo primer argumento es el nombre, así que solo cambia ese
  argumento y lo demás queda intacto — cada cliente lo sigue leyendo en su idioma y el logro
  conserva su hover.
- **`/list` iba diciendo el nombre real** (`Nick.Hide_Real_Names_In_List`, encendido). El chat, el
  tab, los nametags, las muertes y las entradas y salidas enseñaban el apodo — y luego cualquier
  jugador escribía `/list` y leía el nombre de la cuenta de todos ellos. No es un descuido de
  formato que se arregle con estilo: el comando vanilla construye su línea con el display name de
  Minecraft, que es el nombre de la cuenta con el color del equipo, y nunca mira el nombre que
  pone un apodo. `/list uuids` regalaba además el UUID, que encuentra la cuenta aunque el nombre
  no te diga nada. Ahora `/list` lo contesta Chatalyst, con los nombres que la gente ve de verdad
  y respetando lo mismo que respetaba el vanilla: quien no te ve, no te cuenta. Al staff que puede
  usar `/realname` no se le intercepta, porque resolver identidades ya es cosa suya.
- **El hueco del disfraz también frena el correo.** El mismo motivo que los privados, una puerta
  más allá: el correo llega con el otro nombre y avisa al destinatario en cuanto cae. Se comprueba
  en `MailManager.send`, el punto único por el que pasan todos los caminos — comando, respuesta,
  menús, compositor por chat y libro.
- **`Max_Wait_Seconds` se sanea** (de 1 segundo a 1 hora), el último plazo sin límite que quedaba
  en la transición. Un dígito de más no daba error: programaba el techo para dentro de horas y
  dejaba al jugador esperando un disfraz que no llegaba, justo el síntoma que el techo existe
  para evitar.
- **El hueco se cierra en todas las puertas, no solo en las ruidosas.** Un kick o un ban que
  caían dentro de la transición seguían imprimiendo su línea después de que todos te vieran
  marcharte — y quitándote el disfraz, esa línea lleva tu nombre REAL justo debajo de la
  despedida del alias, que es la correlación que todo esto existe para romper. Un logro hacía lo
  mismo por la puerta vanilla que nadie miraba: `Kf3v9x ha conseguido el logro`, gritado a todo
  el server por alguien que acababa de "desconectarse". Y un privado se escapaba por la puerta
  lateral — un lector en vez de todo el server, pero la misma pista. Los tres se callan ahora
  mientras el mundo te da por ido. El castigo se aplica igual y el logro se concede igual; lo
  único que se cae es la línea que lleva tu nombre.
- **Nadie se va del server dos veces.** Entre la salida simulada y la entrada simulada hay un
  hueco en el que, para todos menos para ti, no estás en el server. Desconectarte de verdad ahí
  anunciaba la marcha una SEGUNDA vez — y con el otro nombre, que es la única pista que hacía
  falta para atar el alias a la persona: `DevKazuo left` y acto seguido `Kf3v9x left`. Ahora la
  salida real se calla mientras el mundo ya te da por ido, tanto en el juego como en el puente de
  Discord. La marca se suelta en cuanto se anuncia tu entrada, así que una salida normal no se
  traga nunca.
- **Quitarse el disfraz también espera, no solo ponérselo.** Apagar el modo anónimo te devolvía
  la cara real en el acto, delante de quien estuviera — y eso no es medio problema, es el
  problema entero al revés: quien vea al alias convertirse en ti aprende en un segundo QUIÉN era
  el anónimo, que es justo lo único que todo lo demás intenta evitar. La única forma de apagarlo
  sin delatarse era esconderse a mano con `/vanish` antes. Ahora los dos sentidos esperan al
  mismo momento sin testigos, y los avisos siguen la dirección del cambio: mientras dura la
  espera se te dice que vuelve tu nombre real, no que se te va a poner un disfraz.
- **Alejarse de la gente por fin mete al alias anónimo.** La proximidad se miraba UNA sola vez,
  en el instante del cambio, y de esa foto salían a la vez el retraso de la entrada y el
  escondite. Pulsar `/anonymous` con alguien a menos de 64 bloques te compraba el tramo largo —
  hasta cinco minutos — y alejarse después no cambiaba nada, porque nadie volvía a preguntar:
  seguías oculto esperando por un testigo que ya se había ido. Que es justo el *ghost time* que
  la feature existe para evitar. Ahora la espera larga es un TECHO: la pregunta se repite cada
  segundo y la entrada se adelanta en cuanto no queda nadie mirando — nunca antes del tramo
  corto, porque salir y entrar en el mismo suspiro delata el disfraz igual de bien.
- **La lista de jugadores delataba el cambio ella sola.** Que estuvieras escondido durante el
  hueco lo decidía el mismo chequeo de proximidad que todo lo demás: nadie a menos de
  `Observer_Radius`, así que nada que esconder. Pero esconderse es lo que hace que tu fila de la
  lista de jugadores SE VAYA y vuelva, y esa lista no mide en bloques. Visible, la fila es la
  misma todo el rato y lo único que cambia es su texto — `DevKazuo` convirtiéndose en `Kf3v9x`,
  en sitio, para todo el servidor a la vez y da igual a qué distancia esté cada uno. El cambio
  exacto que todo esto existe para que no se vea, ocurriendo en el único sitio que no tiene
  alcance — y además en el camino normal, el de "no te estaba mirando nadie", que es sobre el
  que está construida la feature. La proximidad sigue decidiendo la ESPERA y lo que dura la
  ausencia; la ausencia en sí pasa siempre fuera de la vista, que cuando nadie vio el cambio son
  los pocos segundos de `Rejoin_*_Seconds`.
- **No se finge nada si nadie se enteró de que te ibas.** La marca de "este jugador ya no está"
  se ponía antes de difundir el adiós — y ese adiós no se difunde si estás en vanish o si los
  mensajes de salida están apagados. Así que un cambio que nadie podía notar te costaba la
  ilusión entera: sin chat, sin privados, sin línea de muerte, de kick ni de logro, durante
  hasta cinco minutos, tapando una correlación que no existía y con un aviso que decía que el
  servidor acababa de verte salir. Ahora la salida simulada informa de si llegó a alguien, y sin
  eso no hay hueco, ni escondite, ni silencio: el disfraz simplemente cambia.
- **A quien el servidor acaba de despedir no se le oye.** El hueco entre la salida y la entrada
  simuladas escondía el cuerpo y los dos mensajes, y luego te dejaba hablar por encima de todo
  ello. Una sola frase lo deshacía: el chat acababa de decir `DevKazuo salió` y ahí estaba
  `Kf3v9x` hablando antes de haber entrado nunca — y al revés es peor, porque quitarse el
  disfraz despide al alias y lo siguiente que se lee es tu nombre REAL, justo debajo. Un jugador
  que se ha ido no habla, así que ya no habla: el mensaje se corta antes del formato, de los
  canales y del puente de Discord, y se te dice por qué. La muerte se silencia igual — la muerte
  ocurre entera, drop y respawn incluidos, y solo se cae la línea que lleva tu nombre. Y no es
  un castigo sin salida: alejarte de la gente cierra el hueco antes, que es justo lo que te dice
  el aviso.
- **Quitárselo ya no enseña la cara vieja en la lista de jugadores.** Pedir el disfraz y
  arrepentirse antes de que el alias hubiera entrado cancelaba la llegada pendiente — y te
  devolvía a la lista de todos con la identidad que estaba a punto de cambiar, un instante antes
  de que cambiara. Quien tuviera el tab abierto veía la entrada mutar de alias a nombre real: el
  cambio en sitio que el escondite, la espera y el retraso existen para evitar, colado por la
  puerta de atrás. Ahora sigues sin verte hasta la llegada siguiente, que es cuando la cara
  nueva ya está puesta.
- **El cooldown del disfraz no empezaba a contar nunca** (`Anonymous.Switch_Cooldown_Seconds`,
  `0` de fábrica, así que ningún servidor se quedó sin freno en silencio). Se armaba desde el
  comando, justo después de pedir el disfraz — pero como el cambio pasó a esperar un momento en
  que nadie mirara, en ese instante no había nada activo todavía, así que siempre parecía que la
  activación había fallado. Ahora se arma donde una identidad nace de verdad, lo que además hace
  que un cambio que falle por almacenamiento ya no deje un cooldown fantasma detrás.
- **Arrepentirse a mitad de la espera vale, en los dos sentidos.** Pedir el disfraz mientras se
  esperaba a QUITARLO contestaba "ya estabas anónimo" y te lo quitaba igual un minuto después —
  la misma trampa que se acababa de arreglar en la otra dirección, en espejo. Y volver a pedir
  lo que ya viene tampoco abre una segunda espera: se repite el aviso y ya. Elegir una cara
  guardada con `/anon set` cancela un apagado pendiente, porque escoger un personaje es lo
  contrario de pedir dejar de serlo.
- **Un callback perdido ya no puede dejar el disfraz colgado.** `Max_Wait_Seconds` solo se
  comprobaba al empezar cada vuelta del sondeo, y cada vuelta la programaba la anterior: un solo
  callback caído rompía la cadena y dejaba al jugador esperando para siempre, sin nada en
  consola. El techo pasa a ser su propia tarea y no depende del sondeo.

## 1.0.0 — primera publicación (2026-08-04)

Todo lo que hay debajo sale en la 1.0.0. Las secciones conservan la fecha en que se
escribieron, de la más reciente a la más antigua: cada una cuenta una feature, y se leen
mejor enteras que fundidas en una sola lista.

### Compras que no se pueden perder, vinculación obligatoria y texto que se lee bien (2026-07-28)

- **Un ban, los dos lados** (`Discord.Ban_Sync`, apagado de fábrica). Un jugador baneado en
  Minecraft que conserva su sitio en Discord está baneado a medias — ahí es donde discute el ban y
  donde arregla el alt con el que vuelve. Qué cuenta es cuál sale del VÍNCULO, nunca de un nombre:
  a quien no está vinculado no se le espeja nada, porque adivinar una cuenta de Discord desde un
  nick es como se banea a quien no era. Un ban temporal se ENSEÑA pero no se aplica en Discord —
  allí un ban no caduca, así que espejar uno de un día sobreviviría al castigo por años, porque
  nada vuelve a levantarlo. Solo un ban permanente saca la cuenta, y se le explica por privado
  ANTES de sacarla, mientras el bot todavía puede alcanzarla. `Direction` elige un sentido o los dos.
- **Los castigos se ven en Discord** (`Discord.Punishment_Roles`, apagado de fábrica). Un jugador
  baneado, congelado o silenciado que en la lista de miembros es idéntico a los demás es un hueco
  que el staff rellena de memoria. El bot crea y mantiene los roles él solo. Van por TRAMOS y no
  por días exactos, y no es un atajo: un rol es UNA cadena compartida por todos los que lo llevan,
  así que no puede decir "30 días" a uno y "12" a otro, y un rol por día necesitaría uno por día
  contra un tope de 250 por gremio. Un barrido baja a la gente de tramo según se les acaba el
  tiempo y desviste el castigo que simplemente caducó — y solo habla con Discord cuando algo
  cambió de verdad.
- **`/chatalyst doctor discord` le pregunta a Discord.** El doctor de config solo puede leer
  archivos, y lo que se rompe aquí vive en el otro lado: un permiso concedido en un menú, un rol
  arrastrado por encima de otro, un intent marcado en el portal. Falla uno y no crashea nada — el
  bot conecta, la config parece correcta, y las features no hacen nada en silencio. Esto informa
  de la conexión, de si cada canal configurado es visible Y escribible, de Manage Roles y Ban
  Members cuando están encendidas las features que los necesitan, de la posición del rol de
  vinculado, y de si la lista de staff autoriza a alguien. Solo lee: no deja nada que limpiar.
- **La puerta de Discord ejecuta el comando que revisó.** La etiqueta se validaba contra el
  catálogo de Chatalyst y luego se despachaba por el mapa global del servidor, donde `ban`, `ci`,
  `note` o `check` también son de EssentialsX y de media docena más — o sea que la puerta
  inspeccionaba un comando y ejecutaba otro, como consola y sin que nadie lo revisara. Peor: el
  catálogo es una lista estática que no sabe si la suite está siquiera registrada, así que con
  `Staff_Moderation` apagado la puerta seguía abriendo. Ahora se despacha por la forma con
  namespace, que no existe si el módulo está apagado. `/clearinventory` además se rechaza desde
  Discord: es la única herramienta de la suite que no se deshace — la armadura puesta y el cofre
  de ender no vuelven — y pedida por chat, contra alguien a quien no estás viendo, un nombre mal
  escrito deja de costar una disculpa.
- **Los tramos de castigo son tuyos.** Quitar el tramo que no quieres (sin rol para castigos
  cortos, algo que la config documenta como válido) volvía en el siguiente arranque, así que la
  opción documentada no se podía ni mantener. Ahora están protegidos como cualquier otra colección
  del dueño, con una siembra de una vez para que un servidor que actualiza siga recibiendo los de
  fábrica — sin tramos la feature no falla, se queda MUDA.
- **Una entrega rechazada no puede falsificar líneas de log.** El detalle del rechazo lleva dentro
  lo que llegó en el comando — nombre del comprador, id de producto o rango, referencia del pago —
  y acaba en dos sitios donde una línea de más es una línea falsa: el rastro en consola que
  explica por qué no se entregó una compra cobrada, y un aviso al staff en Discord, que además
  interpreta markdown.
- **La vinculación obligatoria retenía a todos si faltaba la clave.** `Only_Buyers` viene como
  `true` y el doctor lo asume `true`, pero el gate lo leía con `false` por defecto — así que una
  config sin esa clave retenía al servidor entero en la puerta mientras el diagnóstico informaba
  justo de lo contrario.
- **Moderación desde Discord** (`Discord.Staff.Commands_From_Discord`, apagado de fábrica).
  `!ban`, `!kick`, `!freeze`, `!mute`, `!check`, `!seen` y el resto de la suite funcionan sin
  estar dentro del juego. Se ejecutan como CONSOLA — no como quien los escribe, que ni siquiera
  está conectado — detrás de la misma lista de canales y usuarios/roles que los reportes, y la
  respuesta del comando vuelve al canal, así que `!check` sirve de algo. Lo que necesita estar
  dentro (`/invsee`, `/echest`, `/tpto`, `/tphere`, `/vanish`, `/staff`) se rechaza explicando
  por qué: abren algo delante de ti o te mueven, así que desde fuera no significan nada.
  Interruptor propio a propósito: cerrar un reporte y banear no son la misma autoridad.
- **Las tarjetas de reporte llevan botones.** Reclamar, Responder, Resolver y Rechazar, con una
  ventana de texto para el mensaje o la nota de cierre. Misma puerta que los comandos; con la
  puerta cerrada la tarjeta sale sin botones en vez de enseñar botones que responden "no".
- **El estado del bot enseña el servidor.** `Discord.Activity` acepta `{online}` y `{max}` y se
  refresca solo; quien esté en vanish no cuenta, para que la lista de Discord no sea la rendija
  por la que se ve que hay un moderador mirando.
- **Canal propio para vincular la cuenta** (`Discord.Linking.Channel_Id`). El privado con el bot
  vale siempre: es el sitio más discreto para mandar un código.
- **La transición del anon ya no se delata.** El disfraz se aplicaba al instante mientras la
  entrada se anunciaba minutos después, así que quien mirara veía al jugador transformarse en el
  sitio justo cuando el chat decía que se había ido. Ahora desaparece cuando se va su nombre y
  vuelve con el alias exactamente cuando el chat anuncia que entra
  (`Realistic_Transition.Hide_During_Transition`).
- **Un baneo de IP ya sabe *de qué cuenta* era.** Solo guardaba el nombre del baneado y lo
  comparaba con quien se conectara, así que un baneado que se cambiara el nombre dejaba de
  reconocerse a sí mismo: entraba por la puerta de los alts, se llevaba un segundo ban "por ser
  su propio alt" y el staff recibía una alerta que no era verdad. El registro guarda ahora el
  UUID de la cuenta y compara por él; las filas escritas antes siguen comparando por nombre
  hasta que se rebanean. El nombre que ve el staff se resuelve desde el UUID, así que es por el
  que van a preguntar.
- **Los nombres guardados ya no se quedan congelados.** Las tarjetas del mercado en Discord
  cogían el nombre del vendedor tal y como era al publicar; una tarjeta se queda escrita, así
  que quien se cambiara el nombre se convertía en un desconocido. El nombre guardado es ahora
  solo un respaldo para enseñar algo, y se resuelve desde el UUID mientras el servidor conozca
  la cuenta.
- **Decir "hola" dos veces ya no es spam.** El mensaje anterior se recordaba hasta que el
  jugador se desconectaba, así que saludar y volver a saludar veinte minutos después contaba
  como repetido — y un repetido no solo bloquea la línea, también suma aviso hacia el silencio
  automático, así que cuatro saludos costaban 15 minutos callado. La comprobación de repetidos
  tiene ahora memoria (`Anti_Spam.Repeat_Seconds`, 30s; 0 restaura el comportamiento anterior)
  y un mínimo de longitud (`Anti_Spam.Min_Repeat_Length`, 5) para que los mensajes cortos de
  siempre no cuenten. El spam de verdad — publicidad, enlaces, parrafadas — es largo y llega
  seguido, así que se sigue parando.
- **Un trato cerrado del mercado publica una tarjeta en Discord** (`Discord.Market`, apagado
  de fábrica). El anuncio del juego es una línea de chat que se lleva el siguiente mensaje; el
  embed queda escrito, con la operación entera encima: vendedor, comprador, objeto y lo que se
  pagó, incluido un trueque mixto de dinero *y* objetos. Ventas, subastas y devoluciones se
  apagan por separado; las devoluciones vienen apagadas por ser lo más ruidoso y lo menos
  noticia. El texto de la tarjeta va en inglés a propósito: un canal de Discord lo lee todo el
  servidor a la vez y no hay un espectador de quien deducir el idioma.
- **Cada canal del juego puede verse distinto dentro de un mismo canal de Discord**
  (`Discord.Channel_Formats`). Comercio y subastas suelen compartir sala, y ahí una venta y una
  puja se leían igual. El canal que no tenga formato propio hereda el general.
- **El anuncio de compra habla los dos idiomas.** Sus textos eran lo único escrito por el dueño
  que seguía leyéndose de una sola clave, así que un anuncio `GLOBAL` — el que lee todo el
  servidor a la vez — salía en un solo idioma para todos. Chat, toast, title, subtítulo y action
  bar aceptan ahora su clave `_es` paralela, elegida **por espectador**. Si reescribes un texto
  y no lo traduces, tu versión cubre los dos idiomas en vez de emparejarse con la traducción de
  fábrica, y el merge de actualización ya no cuela un `_es` de fábrica junto a un texto que ya
  habías reescrito. El editor gráfico escribe el idioma del cliente que lo edita.
- **Una entrega ya no se tira por un nombre, ni se rechaza en silencio.** Las compras ya se
  guardaban por UUID — cambiarse el nombre nunca costó un rango —, pero la entrega no aceptaba
  nada más, así que un paquete configurado con `{username}`, o un pago cobrado fuera de la
  tienda y entregado a mano, se perdía entero. Ahora un nombre se resuelve contra la cuenta
  real (jugador conectado, y si no la caché de nombres del servidor) y solo se guarda su UUID;
  seguir sin inventar un UUID a partir del nombre sigue siendo la regla. Además, toda entrega
  rechazada se registra como SEVERE y avisa al staff: Tebex da el comando por ejecutado y no lo
  reenvía nunca.
- **Vinculación obligatoria de Discord** (`Discord.Linking.Required`, apagada de fábrica). A
  quien no la tenga se le retiene al entrar y se le emite el código automáticamente. Es
  fail-open a propósito: solo se aplica mientras el bot está READY, su servidor de Discord es
  accesible y los códigos se pueden guardar de verdad, así que una caída nunca deja a nadie
  bloqueado. Con AuthMe/nLogin instalado el aviso espera a su evento de login en vez de
  pelearse con la pantalla de autenticación.
- **Las compras son derechos adquiridos, no entregas.** Lo que un jugador posee es la fuente de
  verdad; el grupo de LuckPerms y el rol de Discord se derivan de ahí y se reparan al entrar,
  tras cada compra, al vincular y en un barrido de fondo. Eso cubre deriva que ninguna cola de
  reintentos vería: un rango quitado a mano, una sincronización de Discord pisando un grupo
  pagado, un backup restaurado o una caducidad que vence. Discord ya no puede revocar un rango
  que se pagó.
- **Tienda de objetos nueva** (`store.yml`): vende objetos, comandos de consola y dinero. Los
  bienes usan entrega como mucho una vez — la reclamación se escribe antes de dar nada, espera
  a un comprador desconectado o a un inventario lleno, y se consume al entregarse. No se tira
  nada al suelo. `/store` muestra lo que se debe y lo recoge.
- `Nametag`, `Tab_List` y el hook `TAB` se movieron a su propio `nametag.yml` — eran la mitad de
  `integrations.yml` y no son integraciones. Las configuraciones existentes se migran con copia
  de seguridad.
- Correcciones de una auditoría independiente: el `{transaction}` de Tebex identifica el pago y
  no el paquete, así que un carrito con varios productos entregaba solo el primero en silencio;
  un reembolso podía revocar una compra posterior del mismo rango; el reconciliador podía
  borrar un grupo con contexto que un admin había dado a mano; `claim all` podía dejar la
  última compra en el suelo; la vinculación no era uno-a-uno con base de datos compartida; y un
  jugador sin `chatalyst.command.discord` podía quedar retenido sin salida.
- **El texto ya escrito en disco se repara al arrancar.** Los cierres corregidos dentro del jar
  no ayudaban a nadie que ya tuviera Chatalyst instalado: un archivo en disco siempre gana al
  del jar, así que un servidor existente seguía imprimiendo `</white>` y `</aqua>` a sus
  jugadores — la cuenta atrás de la boda decía "la ceremonia empieza en 30s`</white>`". La
  reparación es deliberadamente estrecha porque toca texto que quizá escribió el dueño: solo
  colores con nombre, y solo si ese color no se abre en ninguna parte del mismo archivo, así
  que un `<white>x</white>` intencionado no se toca. Copia previa en `.bak-tags` y todo al log.
- **`/store` abre la tienda.** Antes abría la bandeja de entregas pendientes — lo último que se
  espera de un comando llamado "store", con la tienda de verdad en `/ranks`. La bandeja es
  ahora un botón dentro del escaparate que indica cuántos objetos esperan, más
  `/store pending` para quien la quiera directa.
- **El disfraz anónimo deja de delatarse solo.** Salir y entrar en el mismo segundo no lo hace
  nadie. El disfraz se sigue aplicando al instante, pero la entrada del alias se anuncia entre
  1 y 5 minutos después, sorteados cada vez — te "vas", sigues jugando disfrazado, y más tarde
  "entra" otro jugador. Una cara elegida con `/anon set` sobrevive además a las reconexiones
  hasta que se cambie o se apague el modo, así que un slot guardado es un personaje y no algo
  que duraba hasta el siguiente reinicio.
- **Un alias de comando editado se aplica al recargar.** Los `Command_Aliases` se registraban una vez
  al arrancar y nunca más, así que editar uno y hacer `/chatalyst reload` no cambiaba nada y
  solo servía reiniciar del todo — sin que nada lo dijera. Ahora el gestor retira sus propios
  alias antes de volver a registrarlos, con el mismo borrado clave a clave que usan los otros
  registradores, porque el mapa de comandos de Paper es una vista cuyo iterador rechaza
  `removeIf`.
- **Cuatro defectos que solo se ven delante de un jugador.** El panel de staff ponía
  "Jugadores congelados ({count})": el contador se le pasaba a la descripción, que no tiene
  contador, en vez de al título. El mensaje de `/effect` decía "Efecto Rapidez aplicado a ."
  porque pedía un tercer argumento a un mensaje que tiene dos, y un índice que no existe se
  renderiza como nada, no como texto visible. Un sonido escrito como lo escribe toda la
  documentación de Minecraft (`BLOCK_BEACON_ACTIVATE`) se convertía en una clave inexistente y
  no sonaba nunca — y el aviso de freeze era el único campo de sonido que rechazaba la forma
  `minecraft:` que usan todos los demás. Ahora las dos formas valen en todos los campos. Por
  último, `Mail.Require_Friend` existía en el código pero en ningún archivo, así que la única
  manera de encontrarlo era leer el código fuente.
- **Una sola paleta, y etiquetas de cierre que salían impresas en pantalla.** Un cierre de
  MiniMessage sin apertura se renderiza como texto literal, así que `<#FBF2FF>{player}</white>`
  le ponía al jugador los caracteres `</white>` delante — 182 sitios, residuo de la paleta
  antigua de colores con nombre, que no puede cerrar un color hex. La convención es re-declarar
  el color siguiente en vez de cerrar el anterior. La misma deriva había llegado a los colores:
  un cuarto oro, un segundo rojo de peligro, un segundo morado de Discord, y dos menús de
  ranking con medallas de podio distintas. Ahora ambas cosas están enumeradas y las vigila un
  test.
- **El texto de configuración que escribe el dueño también es bilingüe.** Nombres,
  descripciones, botones de compra y títulos de sección de la tienda leen una clave paralela
  `_es` para un cliente en español, la misma convención que ya usaban los anuncios y las
  respuestas automáticas. Esa convención estaba reescrita a mano en cada clase que la
  necesitaba, que es justo como una función nueva acaba en un solo idioma sin querer; ahora
  hay una sola implementación, y un test que rompe el build si alguien la vuelve a escribir a
  mano.
- `/chatalyst features` ahora apunta al archivo que realmente contiene cada interruptor, e
  incluye la tienda y Tebex. `Mail.Enabled` lo respetaba el código pero faltaba en
  `players.yml`; ahora se incluye.

### Bot de Discord bidireccional integrado (2026-07-26)

- Se sustituyeron DiscordSRV y el relay público por webhook por un bot propiedad de Chatalyst y
  administrado con su mismo ciclo de vida. Token, servidor, canal principal, formatos y rutas por
  canal viven en `integrations.yml`; `/chatalyst reload` lo reconecta.
- Los mensajes de Minecraft y Discord viajan en ambos sentidos por una sola conexión, con
  prevención de bucles de bots/webhooks, menciones anuladas, entrada acotada y entrega segura
  en Folia.
- Reportes, staff chat y alertas de seguridad usan canales privados del mismo bot. Los comandos
  de reportes conservan autorización cerrada por canal más usuario/rol.
- `/discord link` crea vínculos persistentes de un solo uso para DMs y reglas con identidad;
  `Linked_Accounts` queda como respaldo manual.
- Se añadió sincronización por eventos y con debounce entre grupos LuckPerms y roles de Discord,
  restringida a un mapa explícito para impedir concesiones fuera de configuración.
- Tebex puede entregar o retirar rangos mediante comandos idempotentes con `{id}` y
  `{transaction}`; los rangos temporales conservan su expiración absoluta durante reintentos.
- La entrega de Tebex incorpora una GUI completa y configuración YAML global o por Rank ID,
  audiencia privada/global, presets discretos o llamativos, textos MiniMessage, chat, toast,
  title, action bar, sonido, fuegos artificiales seguros y entrega diferida al volver a entrar.
- `/cht ranks` (`shop`, `shops`, `tienda`, `rangos`) abre una tienda paginada para jugadores,
  definida completamente en YAML con tarjetas por Rank ID, precio, icono, descripción, orden,
  destacado, permisos de visibilidad y enlace HTTPS exacto enviado como botón seguro al chat.
  Sus iconos admiten materiales vanilla o IDs de ItemsAdder, y sus textos resuelven font-images.
- `Rank_Catalog` centraliza el ID estable, grupo LuckPerms, rol/prioridad Discord y duración
  Tebex; las sintaxis de rangos v32 siguen leyéndose como compatibilidad.
- El staff de Discord también puede inspeccionar el hilo con `!info` y responder al reportante
  en tiempo real con `!reply`.

### Corrección de compatibilidad del borrado (2026-07-26)

- Se corrigió el contrato de compatibilidad: Chatalyst puede retirar cada línea para la
  que Paper entregue una `SignedMessage.Signature` utilizable; el estado premium y
  `enforce-secure-profile=true` no son requisitos de Chatalyst. Se verificó con sesiones
  premium y no premium de nLogin. Las líneas sin firma o de sistema conservan el respaldo
  de `/clearchat`.

### Sistema completo de títulos y logros (2026-07-26)

> Documentación completa en [Sistema de títulos y logros](titles.es.md).

- Catálogo paginado para jugadores con progreso, vista previa, categorías, estado y orden por
  prioridad, rareza, puntos o nombre; equipamiento `LEFT`/`RIGHT` con un espacio predeterminado.
- Estudio administrativo 100% por GUI para crear, duplicar, activar, ocultar y eliminar títulos,
  editar todos sus campos, administrar acceso y publicar borradores de forma segura.
- Grupos `TODAS`/`CUALQUIERA` (`ALL`/`ANY`) de hasta ocho reglas: mobs, jugadores, tiempo,
  bloques, objetos fabricados/recogidos/en inventario, pesca, cría, advancements, permisos,
  cadenas y concesión manual.
- Evaluación reactiva por eventos más un barrido de seguridad cada 1 200 ticks para permisos,
  inventarios externos, tiempo y cambios sin evento directo.
- Perfil de logros con puntos configurables, porcentaje completado y valores iniciales por rareza.
- Cadenas `TITLE_UNLOCKED` evaluadas en cascada, con validación de IDs, rechazo de ciclos y
  protección contra borrar dependencias activas.
- Logros secretos, elementos ocultos hasta desbloquear y temporadas administradas desde GUI con
  ID versionado, inicio/fin y reinicio no destructivo.
- Colecciones automáticas por categoría con nombre, descripción, icono y bono configurables;
  ranking global derivado de puntos, títulos y colecciones.
- Presentación nativa completamente editable: MiniMessage, icono, marco, chat, sonido, variables
  y prueba privada que no concede ni recompensa.
- Toasts dirigidos al cliente mediante PacketEvents, sin alterar advancements vanilla; fallback
  oculto, reutilizable y acotado para servidores sin esa integración.
- Hasta ocho recompensas ordenadas como comandos de consola, con variables de jugador, título,
  puntos y temporada; desbloqueos y selecciones durables en YAML, SQLite o MySQL.
- Compatibilidad con `{tag_prefix}`/`{tag_suffix}`, tags personales heredados y ocultación durante
  el modo anónimo.
- Servicio Bukkit versionado `ChatalystApi`/`TitlesApi` y jar independiente de compilación;
  modelos inmutables de catálogo/perfil/progreso y mutaciones durables tipadas.
- Eventos posteriores a persistencia para desbloquear/equipar/desequipar/revocar, completar
  colecciones y cambiar el catálogo, con contexto de actor/causa y evaluación segura en Folia.
- Placeholders públicos de título, puntos de logro y colecciones, todos vacíos en modo anónimo;
  guía bilingüe completa en [API de títulos y logros](titles-api.es.md).

### Interfaz premium auditada y hardening de plataforma (2026-07-22)

> Evidencia documentación-contra-diff: [Auditoría de entrega — 2026-07-22](release-audit-2026-07-22.es.md).

#### Sistema de interfaz premium
- Todas las interfaces de inventario comparten ahora el marco de título morado espacial, ritmo de
  lore y vocabulario semántico de navegación (inicio, volver, anterior, siguiente, página, vista
  previa, refrescar y cerrar).
- Administración, staff, mercado y correo usan hubs de categorías explícitos en vez de amontonar
  todas las acciones en una sola pantalla. Las colecciones runtime/configurables tienen páginas y
  acotan posiciones obsoletas.
- La versión 30 de configuración migra acentos factory cian/magenta/dorado y templates de castigo
  sin sobrescribir texto personalizado por el owner ni paletas seleccionables por jugadores.

#### Auditoría de seguridad y fiabilidad
- **Bug #19 — exposición de comandos sensibles:** los audits de reglas, alertas de reglas a
  Discord y templates `Notify` para staff redactan ahora todos los argumentos de `/login`,
  `/register`, `/reg`, `/otp` y comandos sensibles configurados. Incluso `{original_message}` se
  resuelve como `/login <redacted>`; el aviso que solo ve el remitente permanece intacto. El espejo
  configurable de seguridad de staff usa la misma frontera.
- Los snapshots de reglas, nombres, trabajo de regex, entradas de reportes/correo, cuerpos HTTP y
  buffers de red tienen límites explícitos. El reload limpia delays obsoletos y los cambios de pool
  de storage usan un traspaso protegido del candidato.
- **Auditoría rules v2.1:** `Strip_Accents` y la máscara de UUID solo existen en la vista de
  búsqueda y proyectan coincidencias al Unicode original; `@prolong` admite un punto Unicode
  completo; los imports estrictos deduplican archivos/reglas; autores DiscordSRV vinculados y
  online llegan al contexto `DISCORD`; y `Rewrite_In_World` dispara el evento público de replace.
- Los comandos de jugador corren en su scheduler de entidad y con sus permisos. Los comandos
  remotos de regla ahora exigen backend exacto, opt-in y allowlist en emisor/receptor; UUID/timestamp
  impiden duplicados en modo `both`, se rechazan controles y toda ejecución aceptada queda auditada
  con redacción de credenciales.
- La revisión acumulada de todo el plugin cerró 25 defectos. Las regresiones cubren storage,
  mercado/escrow, proxy/Redis, propiedad Folia, anonimato, localización y autorización de GUI.

### Nametags flotantes: título, iconos y animación (2026-07-21)

> Documentación completa en [Nametags flotantes](nametags.es.md).

#### Nametags fancy
- **Renglón de título** encima del tag (ej. "REY DE TODO"): un segundo `TextDisplay`
  flotante y sin fondo. Se pone por jugador con `/nametagtitle title <texto|off>` (alias
  `/ntitle`, `/nametagicon`), o por rango vía `Title.Default_Format` apuntando a un valor de
  LuckPerms/PAPI. Un título vacío retira el renglón entero.
- **Iconos laterales** `{nametag_icon_left}` / `{nametag_icon_right}` que flanquean el tag
  (ej. un escudo de guardián), por jugador y compatibles con ItemsAdder:
  `/nametagtitle iconleft :shield:`.
- **Animación** (`Nametag.Fancy.Animation`): `frames` rota una lista de cuadros (colores/
  texto/iconos alternos); `wave` desliza un gradiente sobre el texto. Ahora corre de forma
  continua en el flotante y el TAB (interno o vía la API de TAB), mientras el chat captura
  con seguridad el cuadro vigente en cada mensaje nuevo. Coste separado mediante
  `Interval_Ticks` / `Tab_Interval_Ticks`.
- La misma rotación de cuadros ya está disponible como preset `Cuadros` en `/announce`,
  con plantillas propias en `Announcer.Animation.Frames` (admiten degradados MiniMessage),
  intervalo en ticks, vista previa privada y envío por red.
- Placeholders nuevos `{nametag_title}`, `{nametag_icon_left}`, `{nametag_icon_right}`,
  vacíos mientras el jugador está en `/anonymous` (un título o icono delataría al streamer).
- El texto del comando pasa las mismas defensas que `/nick` y `/tag` (sin caracteres
  invisibles, colores tras `chatalyst.nametagtitle.color`, insultos/reglas, `<click>`/
  `<hover>` bloqueados).

### Vigilancia de cofres, inventarios compartidos y modo anónimo (2026-07-20)

> Sigue sin publicarse: el plugin continúa en 1.0.0. Documentación completa en
> [Registro de cofres, compartir inventario y modo anónimo](spy-anonymous.es.md).

#### Registro de contenedores y mecanismos
- Nuevo tipo de spy granular `container`: aperturas de cofres/shulkers/ender chests,
  colocación y rotura de contenedores, uso de mecanismos, crafteos, encantamientos y
  tratos con aldeanos, en vivo y con coordenadas.
- Anti-spam por repetición (`Repeat_Seconds`, 60s): la misma acción sobre el mismo
  objetivo se agrupa en una línea que indica cuántas se ocultaron («+19 repetidas»). Los
  objetivos que se mueven (aldeanos, vagonetas) se agrupan por entidad, no por coordenadas.
- Botón **[Ver]** en cada apertura: abre el contenido del contenedor en solo lectura sin
  moverse (contenido actual, con shulkers clicables; en un ender chest, el del jugador).
  Permiso `chatalyst.containerlog.peek`.
- `/containerlog` (`/contlog`, `/cofres`): historial reciente o por fecha con botones
  **[Ir]** (al bloque exacto) y **[TP]** (al jugador). Botón propio en el panel `/staff`.
- Persistencia como tipo `CONTAINER` en los logs diarios (`Log.Containers`), visible
  también con `/chatlog container`. Config en `Spy.Containers` (players.yml).

#### Spy
- El spy admite alcance **por jugador**: `/spy <jugador>` vigila solo a ese (acumulable,
  también offline), `/spy all` vuelve al global. Aplica a todos los tipos (MP, comandos,
  carteles, libros, yunques, contenedores) y el menú de spy muestra a quién vigilas.
- Quitar el ÚLTIMO vigilado ya no te pasa al spy global sin avisar: si el spy lo había
  encendido ese objetivo, se apaga; si ya estaba en global antes, vuelve a global. El
  mensaje lo indica en cada caso.
- Integrado en las GUIs: botón **Vigilar con el spy** en `/players` → gestionar, y en el
  panel `/staff` el botón de spy muestra estado y alcance, con click derecho para
  encender/apagar sin salir.

#### Inventarios
- Las vistas de solo lectura de `/invsee` y `/echest` abren shulkers con un click, y las
  sesiones de edición con **click derecho** (el izquierdo sigue moviendo items).
- Guía de la fila inferior en el editor invsee (armadura puesta y segunda mano se editan).
- `/clearinventory <jugador> [all|ender]` (`/clearinv`, `/ci`, `/limpiarinv`): limpia
  almacén, armadura equipada, segunda mano, cursor y rejilla de crafteo; opcionalmente el
  ender chest. Folia-safe y auditado.
- Tokens `[inv]` y `[ec]` en el chat: cualquier jugador comparte una vista en vivo de solo
  lectura con botón «Abrir» válido 5 minutos (token opaco, nunca el UUID).

#### Mensajes vanilla
- Nuevo `Vanilla_Messages` (chat.yml): el feedback plano de Minecraft (`/tp`, `/xp`,
  `/gamemode`, `/give`, `/kill`, `/effect`, `/time`...) se reescribe con el estilo del
  plugin y en el idioma de cada cliente. 28 claves cubiertas, plantillas editables en
  `lang/<idioma>.yml` bajo `vanilla:` (vacía = conservar la vanilla). Requiere
  PacketEvents.

#### Modo anónimo para streamers
- `/anonymous` (`/anon`, `/anonimo`): alias generado con pinta de jugador real
  (`ShadowWolf42`, `ItzPanda`...; estilo `confusing` para los ilegibles) que nunca
  suplanta a un miembro del servidor, pool de skins reales (`Anonymous.Skins`) con una
  distinta por sesión, rango/tags/party anulados en todos los formatos (incluidos los
  de PlaceholderAPI como `%luckperms_prefix%`) y cabezas de GUI disfrazadas con la skin
  de la sesión, y el color de chat personal (`/chatcolor`) también se anula. La identidad
  es ESTABLE dentro de una conexión (off + on devuelve el mismo alias y skin) y se renueva
  al reconectar (`Rotate_Name_On_Join`); `/nick` queda bloqueado durante el disfraz.
- El anonimato cubre también mensajes de muerte, anuncios del mercado, la lista
  `/players`, el menú social y el puente de Discord por webhook. El staff conserva
  `/realname`, `/seen`, logs y auditoría con el nombre real.

#### Arreglos
- El mensaje de SALIDA anunciaba el nombre real de quien tenía `/anonymous` (o `/nick`): la
  caché del apodo se liberaba demasiado pronto, antes de que se compusiera el adiós (que se
  arma un par de ticks *después* de la desconexión, para darle a cada uno su idioma). Ahora
  la caché se retiene 60 ticks — más allá de esa ventana y de una reconexión en el mismo
  segundo — con una guarda para que la limpieza de la sesión vieja nunca borre el alias de
  una sesión nueva. El adiós del servidor, el de Discord y el de cualquier otro plugin ya
  ven el alias.
- El alias parpadeaba entre 2–3 nombres al entrar: `loadPlayer` aplicaba un instante el
  alias de la sesión *anterior* antes de que la rotación anónima lo sustituyera — lo que
  además enlazaba visualmente las dos identidades. Quien rota ahora se salta ese paso y
  recibe su alias nuevo directamente.
- El alias rotado podía caer al azar en el **mismo nombre de la sesión anterior**; ahora se
  excluye ese nombre del sorteo.
- La línea del matrimonio en el hover (`Marriage.Tag`) era la única que conservaba el `»`
  morado fijo mientras rango/grupo/ping pasaban al color del rango, porque vive en
  `players.yml`, que la migración del hover nunca tocaba. Ahora se tiñe por rango (sin el
  corazón) y tiene su propia migración — con el gate correcto para alcanzar configs ya
  selladas en la versión anterior, e idempotente, sin tocar tags personalizados. Cubierto
  con un test de regresión.
- El **rastro de auditoría del anónimo** (`Log.Anonymous`, activo) registra la
  correspondencia nombre real → alias solo en el log diario — nunca en consola ni chat —
  para que el staff pueda rastrear un disfraz a posteriori sin delatarlo en vivo.
- El filtro por tipo de `/chatlog <fecha>` ahora se aplica durante la lectura del archivo
  (antes, un tipo minoritario podía salir vacío en días con mucho chat).
- La lista `/players` ya no muestra el nombre real tras un apodo a quien no tiene el
  permiso de `/realname`.
- Los mundos con espacios en el nombre ya no rompen el historial de contenedores ni su
  teletransporte.
- Estilos de GUI unificados (títulos con la marca del plugin, bold consistente en
  acciones de castigo, navegación en amarillo).

### Renovación de la suite de staff (2026-07-16)

> Aún no se ha publicado en ningún lado: el plugin sigue siendo 1.0.0. Esta sección se
> convertirá en las notas de la próxima versión al publicar.

**Cambio importante para instalaciones nuevas:** la suite de staff ahora viene
**desactivada** (`Staff_Moderation.Enabled: false`) para que Chatalyst no compita con otro
plugin de staff. Las instalaciones existentes conservan su valor guardado. Con la suite
activa, `Command_Priority: true` hace que los comandos de staff de Chatalyst respondan
aunque otro plugin registre las mismas etiquetas (el comando desplazado se restaura si
desactivas el módulo).

#### Suite de staff
- Los comandos de staff salieron de `plugin.yml`: se registran en runtime solo con el
  módulo activo.
- Herramientas nuevas con alias bilingües: `/invsee`, `/echest`, `/seen`, `/check`,
  `/tpto`, `/tphere` (`/verinv`, `/verender`, `/visto`, `/checar`, `/irhacia`, `/traer`).
- Moderación 100% por GUI: motivos preset (`Reason_Presets`) o escritos por chat sin salir
  al público; compositor de baneos (duración, motivo, estilo, vista previa exacta); menú de
  moderación offline; botón **Moderar** en el detalle del reporte; la lista de jugadores
  del dashboard va directo al menú de gestión.
- Freeze v2: funciona offline (la revisión empieza en el próximo login), inmovilización
  total (ni caminar, ni saltar, ni cámara, ni monturas), aviso visible incluso para OPs con
  `Exempt_Ops: false`, chat restringido al staff que congeló (`Freeze.Restrict_Chat`),
  alertas al staff con botón **[IR HACIA ÉL]**, lista de congelados en el dashboard, clic
  izquierdo sobre un congelado muestra el motivo, ban permanente por cualquier desconexión
  (gracia por defecto ahora 3s).
- Edición en vivo de inventario/ender chest con `chatalyst.command.invsee.edit` /
  `echest.edit` (incluye armadura y segunda mano; se aplica al cerrar, todo auditado).
- Historial de castigos por jugador (últimos 20: ban, tempban, kick, freeze, mute, ban del
  FreezeGuard) visible en `/check` y en el menú offline.
- Pistas de posibles alts en `/check` (solo conexiones hasheadas con SHA-256;
  `Track_Alts`, permiso `chatalyst.staff.alts`), más un menú de alts: una cabeza por
  cuenta que comparte la conexión, clic abre el menú de moderación offline de ese alt.
  Conexión compartida es pista, nunca prueba — el menú solo consulta, jamás castiga en
  bloque.
- `/warn` (alias `/amonestar`, `/advertir`): avisos manuales que suman puntos de aviso
  configurables (`Moderation.Manual_Warn_Points`) y quedan en el historial de castigos.
  `chatalyst.bypass.warn` protege al staff de warns de no-operadores.
- Escalada automática (`Escalation`): cruzar `Kicks_For_Ban` expulsiones recientes (dentro
  de `Kick_Window`) o `Warn_Points_For_Ban` puntos de aviso escala a un ban automático con
  motivo por defecto localizado, anunciado al staff y registrado en el historial. La
  duración sube por una escalera `Ban_Durations` (`[7d, 30d, perm]`) — cada reincidente
  recibe un ban más severo. Aplicar un ban salda el contador de kicks (los kicks previos
  ya no cuentan), así un unban es borrón y cuenta nueva y el siguiente ban solo llega por
  kicks/puntos nuevos.
- Notas de staff: `/note` (alias `/nota`) adjunta memoria compartida de staff a un jugador
  ("avisado verbalmente, la próxima es mute") — funciona offline, con tope por jugador y
  menú para leer/añadir/borrar (permiso `chatalyst.staff.notes`). Las notas no son
  castigos y el jugador nunca las ve.
- Compositor de mutes: la misma experiencia que el compositor de baneos — duraciones
  preset (`Mute.Gui_Durations`), motivos preset o escritos por chat con estilos, vista
  previa exacta — más un interruptor de shadow mute. Los mutes sin motivo escrito reciben
  un motivo por defecto localizado.
- Menú de historial de castigos: el historial completo por jugador en solo lectura (la
  versión de chat en `/check` solo muestra los últimos 3), lo más reciente primero.

- Baneos de conexión (IP): `/banip` + `/unbanip` (alias `/banearip`, `/desbanearip`) banean
  la cuenta y su conexión hasheada con SHA-256, expulsan a todos los que la compartan,
  auto-banean cuentas nuevas que entren por ella (`Ip_Ban.Ban_New_Accounts`) y alertan al
  staff (IpGuard). El compositor gana el alcance "Cuenta + conexión". Nunca se guarda la
  IP en claro.
- Pantalla de ban v2: placeholders `{remaining}` (desglose completo y localizado — años,
  meses, días, horas, minutos, recalculado en cada intento de login) y `{expires}` (fecha
  y hora exactas), con un diseño por defecto nuevo.

#### Filtro de groserías
- Anti-evasión v2: además de repeticiones, separadores y leet speak, el filtro ahora
  pliega acentos (`pútà`), homoglifos cirílicos, sustituciones fonéticas comunes (`k` por
  `c`, `v` por `u`/`b`: `pvto`, `kabron`) y alfabetos Unicode "fancy" vía NFKC
  (ｆｕｌｌｗｉｄｔｈ, 𝓼𝓬𝓻𝓲𝓹𝓽, ⓒⓘⓡⓒⓛⓔⓓ). Las transiciones camelCase cuentan como
  límite de palabra ("LaPutaDeTuMadre" detecta "Puta" sin marcar "Computadora"), y una
  lista `Contains` nueva caza frases inequívocas pegadas ("laputadetumadre"), censurando
  el token completo. La whitelist se pliega igual, así las palabras legítimas siguen a
  salvo.
- Alertas por nombre de cuenta: cuando entra un jugador cuyo nombre dispara el filtro, el
  staff con `chatalyst.notify.security` recibe una alerta. No se bloquea nada en
  automático — un falso positivo en un nombre vetaría a un jugador legítimo, así que la
  decisión queda en manos del staff.
- Los `swear.yml` antiguos siguen recibiendo los defaults empaquetados para las claves que
  aún no tengan (sin regenerar el archivo).

#### Cambios
- El registro de auditoría ahora viene **activado por defecto**, con interruptores nuevos:
  `Swear` registra lo que el filtro censuró o bloqueó (con el texto original) y `Discord`
  registra los mensajes retransmitidos de Discord al chat del juego.
- El único canal de un congelado es ahora el chat privado de revisión (más `/support` si
  el revisor está offline): `msg` y `reply` salieron de `Freeze.Allowed_Commands` para que
  nada salga de la revisión.

#### Correcciones
- `/staff` a secas ahora abre el dashboard clickeable directamente, en vez de entrar en
  modo staff sin más (que cambiaba el inventario por el kit y no mostraba GUI). Entrar/salir
  del modo staff se hace desde el botón de modo del dashboard, o con `/staff on` / `/staff off`.
- Entrar al modo staff desde el botón de modo del dashboard ahora cierra el menú primero,
  así el kit se coloca y se ve completo — antes las herramientas se escribían detrás del
  menú abierto y el cliente a veces mostraba el kit incompleto hasta reconectar.
- El market y el trade ahora entregan y avisan de una compra o intercambio concluido a los
  jugadores conectados, en vez de dejar las cosas en el alijo (`/market claim`) sin aviso:
  al comprador (y a cada parte de un trueque/trade) se le dice qué recogió, o que su
  inventario está lleno y las cosas quedan esperando.
- El alijo ahora tiene un tope (`Market.Max_Stash`, por defecto 54 stacks). Las compras van
  primero al inventario y el sobrante al alijo; cuando el alijo llega al tope, las nuevas
  compras e intercambios se rechazan con un aviso de "recoge tus cosas primero", así nunca
  se pierde nada en silencio. El alijo en sí nunca rechaza una entrega ya comprometida (los
  items siempre se conservan) — el tope es una barrera al comprar/intercambiar.
- `Ops_See_Vanished: false` ahora funciona de verdad: el default del nodo
  `chatalyst.seevanished` se cambia en runtime (el `isPermissionSet` de Bukkit cuenta el
  `default: op` para OPs).
- Los jugadores en vanish ya no aparecen en el contador ni en el hover del ping de la
  lista de servidores (`Vanish.Hide_From_Ping`).
- Los botones de mute del menú de gestión ahora eligen motivo preset/personalizado igual
  que kick/ban/freeze.
