---
title: Archivos de configuración
description: Dónde vive cada ajuste de Chatalyst, cómo se reparten los archivos por tema y cómo recargar, revisar y reparar tu configuración sin riesgos.
---

Chatalyst guarda sus ajustes en varios archivos YAML pequeños en lugar de un `config.yml` enorme. Cada archivo cubre un tema (chat, moderación, jugadores, el bot de Discord…), así que solo abres el del apartado que quieres cambiar, y cada opción lleva un comentario que explica para qué sirve.

Todos los archivos están en `plugins/Chatalyst/`. Se crean con valores por defecto comentados la primera vez que arranca el servidor, y las opciones nuevas se añaden solas cuando actualizas el plugin.

## Los archivos de un vistazo

| Archivo | Qué controla |
| --- | --- |
| `config.yml` | Clave de licencia, avisos de actualización, ajustes por grupo, base de datos, idioma por defecto |
| `chat.yml` | Formato del chat, canales, enrutador de canales, `/me`, `/say`, menciones, gramática, ocultar comandos del tabulador, mensajes vanilla con estilo, mensaje de comando desconocido |
| `moderation.yml` | Antispam, antibot, seguridad del chat, bloqueador de comandos, alcance del mute, [suite de staff](staff-suite), puntos de advertencia, auditoría del staff, registros, reportes, cooldowns de comandos |
| `players.yml` | AFK, mensajes privados, correo, apodos, `/tag`, color de chat, carteles con estilo, `/itemname`, recién llegados, espía, [modo anónimo](anonymous), matrimonios |
| `bot.yml` | Anunciador automático y el [bot de respuestas automáticas](auto-responses) |
| `network.yml` | Peticiones de ayuda al staff, puente con el proxy, centro de administración de red, Redis |
| `integrations.yml` | Catálogo de rangos, [bot de Discord](discord-bot), Tebex, chat de voz, dynmap, PacketEvents, scripts, variables propias, comandos propios, traducción en vivo |
| `store.yml` | Compras de una sola vez: objetos, comandos y dinero ([tienda](store)) |
| `economy.yml` | Plugins de comercio, mercado, `/trade`, ranking de dinero |
| `nametag.yml` | Nametags flotantes, lista de tabulador y el enganche con el plugin TAB |
| `giveaway.yml`, `rentals.yml`, `gifts.yml` | Sorteos, alquiler de ventajas y cajas de regalo |
| `rules.yml` | El [motor de reglas](rules-engine) (antipublicidad, antimayúsculas…) |
| `swear.yml` | El [filtro de groserías](chat-filter#filtro-de-groserias) |
| `books.yml` | Los [libros](books) del servidor |
| `messages.yml`, `events.yml`, `tags.yml`, `tagfx.yml` | Mensajes de entrada, salida y muerte, eventos programados, catálogo de títulos y efectos de tag |
| `formats/` | Un archivo por formato de chat (`chat.yml`, `spy.yml`…) |
| `lang/` | Todos los mensajes que ve el jugador, un archivo por idioma ([idiomas](localization)) |
| `protected-names.txt` | Lista compartida de nombres que nadie puede usar como apodo (el plugin la mantiene al día) |

Los datos de los jugadores se guardan en `data/` y los registros de auditoría en `logs/` (ver [almacenamiento](storage)).

> [!TIP]
> Una sección vive siempre en el mismo archivo. Si no sabes dónde está una opción, busca su nombre en todos los `.yml`: aparece en uno solo.

## config.yml

`config.yml` solo guarda los ajustes básicos. Este es el archivo real (con los comentarios recortados):

```yaml
License:
  Key: ""

Permissions_List:
  Grant_Command: "/lp group <group> permission set {permission} true"

Updates:
  Check: true
  Url: ""
  Notify_Admins: true
  Download_Url: ""

Groups:
  Enabled: true
  List: {}

Database:
  Enabled: false
  Type: SQLITE
  # ... ver la página de Almacenamiento

Locale: en_US
Auto_Detect_Language: true
```

| Opción | Por defecto | Qué hace |
| --- | --- | --- |
| `License.Key` | `""` | Tu clave de licencia personal (ver [Conseguir la licencia](license)) |
| `Permissions_List.Grant_Command` | comando de LuckPerms | Lo que se escribe en tu chat al hacer clic en un permiso de `/chatalyst permissions`. `{permission}` es el nodo |
| `Updates.Check` | `true` | Al arrancar consulta `Updates.Url` por si hay una versión nueva. No descarga nada |
| `Updates.Url` | `""` | URL que devuelve la última versión en texto (`1.2.0`) o JSON (`{"version":"1.2.0"}`). Vacía desactiva la comprobación |
| `Updates.Notify_Admins` | `true` | Avisa a los admins de la versión nueva al entrar |
| `Updates.Download_Url` | `""` | Enlace opcional que aparece en el aviso |
| `Groups.Enabled` | `true` | Activa o desactiva los ajustes por grupo |
| `Groups.List` | `{}` | Ajustes por rango, según el grupo principal de LuckPerms (o Vault si no hay LuckPerms) |
| `Locale` | `en_US` | Idioma por defecto de la consola, los anuncios y los clientes sin archivo propio |
| `Auto_Detect_Language` | `true` | Cada jugador ve los mensajes en el idioma de su cliente |

### Ajustes por grupo

`Groups.List` permite que un rango use otro formato de chat u otro retraso del antispam. Solo afecta a los grupos de la lista; el resto usa los ajustes generales.

```yaml
Groups:
  Enabled: true
  List:
    vip:
      Format: chat-vip          # formats/chat-vip.yml
      Chat_Delay_Seconds: 0     # sin espera entre mensajes
    default:
      Chat_Delay_Seconds: 3
```

`Format` solo se aplica a los canales que usan el formato de chat base; los canales de staff o de comercio mantienen el suyo. `Chat_Delay_Seconds` sustituye el retraso del [antispam](chat-filter#antispam) para ese grupo.

## Aplicar los cambios

La mayoría de los cambios se aplican con:

```text
/chatalyst reload
```

`/cht` es la forma corta de `/chatalyst`. La recarga cambia toda la configuración de golpe, así que un mensaje enviado durante la recarga usa los ajustes viejos o los nuevos, nunca una mezcla.

Algunas cosas siguen necesitando reiniciar el servidor:

- Añadir o quitar un comando propio en `Command_Aliases` (los existentes sí se recargan).
- `Database.Enabled`, `Database.Type`, `Database.Network_Shared` y `Database.Server_Id` (ver [almacenamiento](storage#recargar-y-reiniciar)).

## Revisar la configuración

| Comando | Qué hace |
| --- | --- |
| `/chatalyst doctor` | Lee todos los archivos y enumera los problemas: materiales inválidos, IDs mal puestos, textos demasiado largos, valores que faltan |
| `/chatalyst doctor discord` | Pregunta a Discord si el bot tiene los permisos, la posición de rol y los intents que necesita |
| `/chatalyst features` | Muestra qué funciones están activas y, de cada una apagada, la opción y el archivo exactos que la encienden |
| `/chatalyst permissions [categoría] [página]` | Lista todos los permisos por categoría; al hacer clic en uno se escribe el comando para darlo |

Todos necesitan `chatalyst.admin` (op por defecto).

## Actualizaciones y autorreparación

Al instalar una versión nueva, Chatalyst añade las opciones nuevas a tus archivos y conserva tus valores y comentarios. Nunca tienes que borrar un archivo para conseguir un ajuste nuevo.

Si un archivo tiene un error de sintaxis YAML (una comilla sin cerrar, una sangría mal puesta), el plugin no se cae:

- Con `config.yml` guarda tu archivo roto como `config-broken-<fecha>.yml`, arranca con los valores por defecto, rescata tu clave de licencia para que el servidor siga arrancando e imprime en consola la línea exacta del problema.
- Con los demás archivos guarda una copia como `<archivo>.broken-<fecha>.bak` y carga la versión por defecto de ese archivo.

Corrige la línea en la copia, vuelve a ponerla en su sitio y ejecuta `/chatalyst reload`.

> [!WARNING]
> Haz siempre una copia de tu configuración antes de cambios grandes. La copia automática te protege de errores de sintaxis, no de un valor que hayas cambiado por error.

## Límites

- YAML es estricto con la sangría: usa espacios, nunca tabuladores.
- Algunos textos van en pareja (`Message` / `Message_es`). Los idiomas extra que añadas en `lang/` ven la parte en inglés de esas parejas; todo lo que está en `lang/` se puede traducir por completo (ver [idiomas](localization)).
- Añadir o quitar comandos propios necesita reiniciar, porque el servidor arma su lista de comandos al arrancar.
