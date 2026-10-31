# Sincronización por proxy y Redis

Chatalyst ofrece tres transportes de red para los canales marcados con `Proxy: true`.

## Modos

`legacy` utiliza el canal de mensajes `Forward` de BungeeCord. No necesita un jar en el proxy, pero el reenvío depende de que existan jugadores conectados.

`native` utiliza el mismo jar universal de Chatalyst en Paper/Purpur y en Velocity o BungeeCord. El proxy carga únicamente su pequeño punto de entrada bridge mediante el canal `chatalyst:main`; nunca inicia funciones Bukkit, storage, comandos ni GUIs.

`redis` utiliza pub/sub de Redis. Es el modo recomendado cuando la red debe reenviar mensajes aunque un backend no tenga un jugador local que los transporte.

## Autenticación de paquetes de red

Los mensajes de plugin nativos y legacy usan HMAC-SHA256, una ventana temporal de cinco minutos y una caché acotada de nonces para rechazar paquetes falsificados o repetidos. Con el forwarding moderno de Velocity no requiere configuración: cada backend y el jar universal del proxy derivan una clave específica del protocolo a partir del forwarding secret existente. El forwarding secret nunca se usa directamente como clave HMAC.

BungeeCord no tiene un secreto de forwarding equivalente. Para una red Bungee nativa, genera un único valor aleatorio (mínimo 16 caracteres; se recomiendan 32 o más), configúralo como `Proxy.Network_Secret` en todos los backends y escribe el mismo valor en `plugins/chatalyst/network-secret.properties` del proxy:

```yaml
Proxy:
  Network_Secret: "sustituye-por-un-secreto-de-red-aleatorio-largo"
```

```properties
network-secret=sustituye-por-un-secreto-de-red-aleatorio-largo
```

Un `Proxy.Network_Secret` explícito siempre tiene prioridad sobre la derivación automática. Los modos legacy o exclusivamente Redis pueden derivar la clave de mensajería desde `Redis.Shared_Secret`; Redis sigue firmando por separado su propio sobre pub/sub. Si falta la clave o tiene menos de 16 caracteres, native/legacy fallan de forma cerrada y bloquean el reenvío; ya no existe compatibilidad sin firma. Reinicia todos los backends y el proxy después de cambiar el material de clave.

## Detección automática de servidores

Con `Proxy.Mode: native` (o `both`) no hace falta copiar manualmente una lista de backends ni su ID de red. El bridge universal de Velocity/Bungee ya conoce todos los servidores registrados. Cuando entra el primer jugador, cada backend Paper/Purpur solicita su propia identidad; el proxy responde únicamente a esa conexión de origen con el nombre registrado exacto, como `lobby` o `survival`. Chatalyst usa esos nombres en las tarjetas de topología, el monitor de chat, la presencia del staff, el soporte y el directorio de jugadores.

```yaml
Proxy:
  Enabled: true
  Mode: native
  Server_Discovery:
    Mode: auto
    Manual_Server_Id: ""
```

La mensajería de plugins necesita un jugador conectado. Antes de la primera entrada, Chatalyst usa temporalmente `Database.Server_Id`, después `Redis.Server_Id` y finalmente `server-<puerto>`. La identidad entregada por el proxy sustituye ese fallback en cuanto existe un jugador transportador. También se vuelve a solicitar después de recargas y entradas de jugadores para recuperarse sin reiniciar el backend.

El funcionamiento manual sigue disponible para configuraciones legacy, exclusivamente Redis o con nombres fijados deliberadamente:

```yaml
Proxy:
  Server_Discovery:
    Mode: manual
    Manual_Server_Id: "survival"
```

En modo manual se ignora la respuesta del proxy. Si `Manual_Server_Id` está vacío, se aplica el mismo fallback compatible. `Database.Server_Id` y `Redis.Server_Id` siguen controlando sus namespaces de almacenamiento y Redis; la detección automática solo cambia la identidad de red en ejecución y nunca renombra datos persistidos.

## Configuración del backend

```yaml
Proxy:
  Enabled: true
  Mode: redis

Redis:
  Enabled: true
  Host: "127.0.0.1"
  Port: 6379
  Password: ""
  Database: 0
  Channel: "chatalyst:network"
  Server_Id: "survival-1"
  TLS: false
  Shared_Secret: "cambia-esto-por-un-valor-aleatorio-largo"
```

Todos los backends deben usar el mismo `Channel` y `Shared_Secret`, pero cada backend necesita un `Server_Id` único.

## Comandos de regla dirigidos a consola

`Actions.Proxy_Commands` es una acción privilegiada del motor de reglas. Cada entrada usa
`"<servidor-destino> <comando>"`; nunca se difunde a todos y no puede dirigir la consola del
proxy. El destino es la identidad runtime exacta mostrada en el centro de red, por ejemplo
`survival`:

```yaml
# network.yml — configúralo en el emisor y cada posible receptor
Proxy:
  Enabled: true
  Rule_Commands:
    Enabled: true
    Allowed_Commands: [broadcast, say]

# rules.yml
Rules:
  aviso-remoto:
    Match: '^aviso de red$'
    Actions:
      Proxy_Commands:
        - "survival broadcast {player} activó {rule}"
```

La autenticación por sí sola no autoriza acceso remoto a consola. Tanto el backend emisor como el
receptor requieren `Rule_Commands.Enabled: true`, y la etiqueta debe aparecer en su
`Allowed_Commands` local; no se admiten comodines. Se rechazan controles, comandos demasiado
largos, destinos inválidos/propios/`proxy` y bytes sobrantes. Cada paquete lleva timestamp
acotado y UUID de evento, de modo que `Proxy.Mode: both` no ejecuta una acción dos veces. Toda
ejecución aceptada crea un audit `RULE` con el origen declarado por el backend autenticado y pasa sus argumentos por la
frontera compartida de redacción de comandos sensibles antes de escribirlos.

Los modos native y legacy aún necesitan un jugador que transporte el paquete en el emisor y una
conexión activa al backend destino. Usa Redis si la automatización debe funcionar sin jugadores.
Mantén la allowlist mínima y prefiere un comando dedicado y limitado frente a uno administrativo
general.

## Seguridad de mensajes Redis

`Shared_Secret` es obligatorio: la sincronización Redis permanece inactiva hasta configurarlo. Cada mensaje se firma con HMAC-SHA256 e incluye marca de tiempo y nonce, de modo que solo tus servidores pueden publicar y los reenvíos repetidos se rechazan. Activa `TLS: true` cuando Redis no esté en localhost, ya que el protocolo sin TLS envía contraseña y mensajes sin cifrar.

## Caché de base de datos compartida

Con `Database.Network_Shared: true` y MySQL compartido, Redis también propaga invalidaciones de caché. Así, un reporte reclamado o un correo enviado en un servidor aparece en los demás sin esperar un reinicio.

## Configuración de canales

```yaml
Channels:
  List:
    global:
      Proxy: true
```

Los tipos locales, como frecuencia, mundo, región y party, permanecen locales salvo que marques explícitamente el canal para proxy.

## Centro de administración de red

La GUI principal de administración incluye un centro de operaciones para miembros del equipo con `chatalyst.admin.network.monitor`. Muestra los servidores del proxy, su población actual y una línea de tiempo paginada del chat reciente de cada backend. El staff puede revisar un servidor sin conectarse a él y pulsar una entrada para leer el texto completo.

```yaml
Network_Admin:
  Monitor:
    Enabled: true
    History_Per_Server: 100
    Retention_Minutes: 60
  Announcements:
    Enabled: true
    Max_Message_Length: 512
```

El monitor transmite únicamente chat público y de canales procesado por Chatalyst. No captura comandos, contraseñas, mensajes privados ni correo. El historial es limitado, deduplicado, solo vive en memoria y se descarta al reiniciar. Los permisos están separados: `chatalyst.admin.network.monitor` permite leer líneas de tiempo y `chatalyst.admin.network.announce` habilita el alcance de red en `/announce`.

El bridge nativo de Velocity o Bungee entrega automáticamente la lista completa de servidores registrados, la identidad exacta de este backend y los conteos de jugadores en vivo. El modo exclusivo Redis sigue transmitiendo chat observado y anuncios sin jugador transportador, pero solo puede enumerar servidores que hayan generado actividad local u observada porque Redis no conoce la topología del proxy; usa detección manual si necesitas nombres estables en ese modo.

## Un solo jar universal

Instala el mismo `Chatalyst-<versión>.jar` firmado y sin modificar en el directorio `plugins` de cada backend y del proxy. El jar contiene tres descriptores aislados. Paper lee `plugin.yml`, Velocity lee `velocity-plugin.json` y BungeeCord lee `bungee.yml`; los puntos de entrada de las demás plataformas nunca se cargan. Ya no hacen falta descargas separadas para el proxy.

## Chats en vivo de varios servidores

Desde `/cht admin`, **Chats de servidores en vivo** permite que cada miembro del equipo refleje hasta dos chats de servidores en su chat actual. El límite se configura con `Network_Admin.Monitor.Max_Live_Subscriptions`. En el centro de red también puedes usar click derecho sobre una tarjeta para activar su chat en vivo; el click izquierdo abre la línea de tiempo limitada.

Los permisos se aplican por capas:

- `chatalyst.admin.network.monitor` abre el monitor.
- `chatalyst.admin.network.monitor.server.*` permite todos los backends; `chatalyst.admin.network.monitor.server.lobby` permite únicamente `lobby`.
- También se comprueba el permiso del canal. El canal restringido `staff` requiere su permiso normal o el override explícito `chatalyst.admin.network.monitor.channel.staff`. El nodo `channel.*` permite todos los canales.

Los operadores reciben ambos comodines por defecto. Con LuckPerms, concede el nodo base del monitor y solo los nodos de servidor y canal apropiados para cada grupo del equipo.

## Soporte privado con lenguaje natural

Los jugadores no tienen que conocer una sintaxis con `@`. Preguntas naturales como `algún admin?`, `hay owner?`, `hay alguien del staff?` o `un mod puede ayudarme` se reconocen en el chat por defecto. Chatalyst exige un rango o palabra de equipo completa y una intención de ayuda, por lo que frases normales como `compré el rango admin` no se interceptan. La solicitud original nunca aparece en el general, el historial de canales ni los feeds del monitor de red.

Cada solicitud comienza en el nivel configurado más bajo (`helper` por defecto), aunque el jugador pida un admin. Si nadie responde, el aviso avanza por `mod`, `admin` y `owner` en toda la red. El staff recibe un botón visible **Responder** en el chat; no necesita escribir un comando para aceptar. El bridge arbitra los clics simultáneos: solo el primero abre la sesión. Los demás reciben el nombre de quien ya atiende al jugador y un clic atrasado no puede reemplazarlo. Después ambos participantes escriben normalmente y únicamente ellos reciben esos mensajes. El staff cierra con `/support close`; el usuario vuelve expresamente al canal por defecto/general. Las solicitudes pendientes y las sesiones inactivas caducan automáticamente.

```yaml
Staff_Mentions:
  Enabled: true
  Require_At: false
  Groups: [admin, administrador, helper, ayudante, mod, moderador, owner, soporte, staff, equipo]
  Help_Intent_Words: [algun, alguna, alguien, hay, necesito, ayuda, ayudar, atender, puede, podria, disponible, hablar, need, help, assist, available, support]
  LuckPerms_Group_Permission: chatalyst.staff.mentionable
  Cooldown_Seconds: 15
  Notify_Sender: true
  Priority:
    Escalation_Seconds: 8
    Tiers:
      helper:
        Groups: [helper, ayudante]
        Permission: chatalyst.support.tier.helper
      mod:
        Groups: [mod, moderador]
        Permission: chatalyst.support.tier.mod
      admin:
        Groups: [admin, administrador]
        Permission: chatalyst.support.tier.admin
      owner:
        Groups: [owner]
        Permission: chatalyst.support.tier.owner
  Private_Chat:
    Enabled: true
    Pending_Timeout_Seconds: 300
    Session_Timeout_Minutes: 30
    Max_Message_Length: 512
```

La coincidencia ignora mayúsculas y acentos, usa patrones precompilados de palabras completas y no confunde texto parecido a un correo. LuckPerms solo se consulta al cargar o recargar, nunca por cada mensaje. Los silencios, el saneamiento, las reglas y el antispam siguen ejecutándose antes de capturar una sesión privada.

Cualquier grupo cargado de LuckPerms que herede `chatalyst.staff.mentionable` se agrega automáticamente. Así, los nombres de grupos personalizados no tienen que duplicarse en la lista. Ejecuta `/cht reload` después de cambiar los permisos del grupo.

## Directorio de staff y teletransporte

La GUI de administración incluye un directorio de staff paginado y temporal. Cada entrada muestra el nivel configurado, backend y mundo. `chatalyst.admin.staff.directory` permite verlo y `chatalyst.admin.staff.teleport` habilita el teletransporte con un clic. Si el objetivo está en otro servidor, el bridge nativo de Velocity o Bungee conecta al solicitante con el backend correcto y después realiza allí el teletransporte exacto.

La presencia solo vive en memoria, se actualiza cada 10 segundos y caduca automáticamente. El backend destino vuelve a comprobar el permiso antes de mover al solicitante. El teletransporte entre servidores requiere `Proxy.Mode: native` (o `both`) y el jar universal en el proxy; Redis y el modo legacy pueden transportar chat, pero no pueden mover de forma segura a un jugador entre servidores del proxy.

## Jugadores por servidor

La GUI de administración también incluye **Jugadores por servidor**. Primero muestra los backends permitidos del proxy; al elegir `survival`, por ejemplo, abre únicamente los perfiles recientes publicados por Survival. Cada cabeza muestra nombre de usuario, rango principal de LuckPerms/Vault, dinero de Vault, backend, mundo, modo de juego, ping y estado AFK. Al pulsar la cabeza, el administrador se teletransporta al jugador, incluyendo el cambio mediante el bridge nativo cuando está en otro backend.

Los permisos están separados para aplicar el mínimo privilegio:

- `chatalyst.admin.players.directory` abre el navegador.
- `chatalyst.admin.players.server.*` muestra todos los backends; `chatalyst.admin.players.server.survival` muestra únicamente Survival.
- `chatalyst.admin.players.teleport` habilita el teletransporte al pulsar la cabeza.

Los jugadores en vanish nunca se publican en el directorio de red. La presencia solo vive en memoria, se actualiza cada 10 segundos y caduca después de 30 segundos. El backend destino vuelve a comprobar los permisos de directorio, teletransporte y servidor contra el backend actual real del jugador antes de mover al administrador.
