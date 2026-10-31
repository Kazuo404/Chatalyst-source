---
title: Herramientas del jugador
description: Las herramientas del día a día que Chatalyst da a los jugadores. AFK, preferencias personales, ignorar, /me, colores de chat, mostrar objetos en el chat, renombrar objetos, carteles de colores y la lista de jugadores.
---

Además del chat, Chatalyst da a los jugadores un conjunto de herramientas pequeñas que hacen el servidor más cómodo: marcarse como ausente, apagar lo que no quieren ver, ignorar a alguien, elegir un color de chat, enseñar un objeto o su inventario en el chat, y más. La mayoría funcionan para todos desde el principio; unas pocas están pensadas como ventaja de rango y piden un permiso.

Las funciones más grandes tienen su propia página: [mensajes privados](private-messages), [correo](mail), [apodos y colores](nicknames), [libros](books), [mercado](market) y [matrimonios](marriage).

## AFK

Los jugadores se marcan como ausentes tras un rato sin moverse, chatear ni usar comandos. Solo se le avisa al jugador, en privado; no se anuncia a nadie. `/afk` lo activa o quita a mano.

```yaml
Afk:
  Enabled: true
  Timeout_Seconds: 300
  Tab_Suffix: " <#C0B3D9>[AFK]"
  Notify: true
  Ignore_Vanished: true
```

| Opción | Por defecto | Qué hace |
| --- | --- | --- |
| `Timeout_Seconds` | `300` | Tiempo sin actividad antes de marcar AFK |
| `Tab_Suffix` | `" <#C0B3D9>[AFK]"` | Lo que muestra `{afk_tag}` en la lista de jugadores |
| `Notify` | `true` | Avisa al jugador cuando pasa a AFK y cuando vuelve |
| `Ignore_Vanished` | `true` | El staff en vanish nunca se marca AFK solo (`/afk` a mano sigue funcionando) |

El estado está disponible como `{afk_tag}` en los formatos y como `%chatalyst_afk%` para PlaceholderAPI y TAB.

## Preferencias personales

`/toggle` abre un menú donde cada jugador enciende o apaga funciones para sí mismo. También funciona directo: `/toggle <nombre>`.

| Preferencia | Por defecto | Qué controla |
| --- | --- | --- |
| `pm` | activada | Recibir mensajes privados |
| `announcements` | activada | Ver los anuncios automáticos |
| `mentions` | activada | El sonido cuando alguien te menciona |
| `joinquit` | activada | Ver los mensajes de entrada y salida de otros jugadores |
| `translate` | desactivada | [Traducción en vivo](translation) del chat a tu idioma (solo aparece si el servidor la tiene configurada) |

La elección se guarda y se mantiene al reconectar.

## Ignorar jugadores

`/ignore <jugador>` impide que los mensajes privados y el correo de ese jugador te lleguen. Vuelve a usarlo para dejar de ignorarlo. Por defecto también oculta su chat público:

```yaml
Chat:
  Ignore:
    Hide_In_Chat: true
```

Con `Hide_In_Chat: true`, los mensajes de un jugador ignorado se reducen a una marca pequeña "[ignorado] nombre · [ver]". Al hacer clic en **[ver]** ves solo ese mensaje. Pon `false` para que `/ignore` afecte únicamente a los mensajes privados.

## /me

Una acción en tercera persona en el chat: `/me saluda` se ve como "* Steve saluda". El aspecto está en `chat.yml`:

```yaml
Me:
  Format: "<#C0B3D9>* <#FBF2FF>{player_name} <#C0B3D9>{message}"
```

Los jugadores muteados no pueden usar `/me` (ver [castigos](punishments#muteos)).

## Color de chat

`/chatcolor` abre un menú para elegir el color de tus mensajes. Los 16 colores de Minecraft están siempre disponibles; los colores y degradados extra de `players.yml` piden cada uno su permiso, así que puedes darlos como ventaja de rango.

```yaml
Chat_Color:
  Colors:
    blush: "<#FF6B9B>"
    coral: "<#FF7A59>"
    # ...
  Gradients:
    sunset: "<gradient:#FF512F:#F09819>"
    ocean: "<gradient:#2E3192:#1BFFFF>"
    # ...
```

| Permiso | Qué desbloquea |
| --- | --- |
| `chatalyst.command.chatcolor` (todos) | Abrir el menú y usar los 16 colores básicos |
| `chatalyst.color.<id>` / `chatalyst.color.*` | Un color extra / todos |
| `chatalyst.gradient.<id>` / `chatalyst.gradient.*` | Un degradado / todos |
| `chatalyst.chat.style.bold`, `.italic`, `.underlined`, `.strikethrough` (todos) | Estilos de texto |
| `chatalyst.chat.style.obfuscated` (op) | El estilo "mágico" que se revuelve |
| `chatalyst.chat.color.others` (op) | Cambiar el color de otro jugador: `/chatcolor <jugador> <color>` |

`/chatcolor reset` vuelve al color por defecto. Los mismos colores se usan para los apodos (ver [apodos](nicknames)).

## Enseñar cosas en el chat

Los jugadores pueden escribir estas etiquetas en cualquier mensaje:

| Etiqueta | Qué aparece | Permiso |
| --- | --- | --- |
| `[item]` o `[i]` | El objeto de tu mano, con toda su descripción al pasar el ratón | `chatalyst.chat.item` (todos) |
| `[inv]`, `[inventory]`, `[inventario]` | Un botón **Abrir** que muestra tu inventario | `chatalyst.chat.shareinv` (todos) |
| `[ec]`, `[ender]`, `[enderchest]` | Un botón **Abrir** que muestra tu cofre de ender | `chatalyst.chat.shareinv` (todos) |
| `:heart:`, `[creeper]`… | Emojis y pequeñas etiquetas estilo píxel | `chatalyst.chat.emoji`, `chatalyst.chat.pixel` (todos) |

Los inventarios compartidos son en vivo y de solo lectura, cualquiera puede abrirlos durante 5 minutos, y las shulkers de dentro se abren con un clic. El botón nunca lleva tu nombre real, así que compartir no delata a un jugador en [modo anónimo](anonymous). Los interruptores están en `chat.yml`:

```yaml
Chat:
  Item_Placeholder: true
  Item_Custom_Ids: true
  Inventory_Share: true
```

`Item_Custom_Ids` añade el ID de ItemsAdder o Nexo de un objeto propio a la etiqueta `[item]`.

## Renombrar objetos

`/itemname <nombre>` renombra el objeto de tu mano con colores (`&4&lESPADA OSCURA` o MiniMessage). Es una ventaja de rango con un número limitado de usos por jugador.

```yaml
Item_Name:
  Enabled: true
  Uses: 1
  Max_Length: 40
```

| Opción | Por defecto | Qué hace |
| --- | --- | --- |
| `Uses` | `1` | Renombrados que recibe cada jugador. `0` o menos = ilimitados |
| `Max_Length` | `40` | Nombre más largo, sin contar los códigos de color |

`/itemname reset` quita el nombre sin gastar un uso. Permisos: `chatalyst.command.itemname` (op) para usarlo, `chatalyst.bypass.itemname` (op) para usos ilimitados. Los objetos con nombre aparecen en los mensajes de muerte.

## Carteles de colores

Los jugadores con el rango adecuado pueden escribir colores, degradados y arcoíris en carteles de verdad.

```yaml
Fancy_Signs:
  Enabled: true
  Permission: "chatalyst.sign.colors"
  Styles:
    galaxy:
      Format: "<gradient:#6A00FF:#00D4FF:#FF4FD8><bold>{text}</bold></gradient>"
    sunset:
      Format: "<gradient:#FF512F:#F09819>{text}</gradient>"
```

Un solo permiso, `chatalyst.sign.colors`, desbloquea todos los colores, degradados, arcoíris y estilos con nombre. Los jugadores pueden escribir `&4&lImportante`, `<gradient:#ff0000:#0000ff>Hola</gradient>`, `<rainbow>Hola</rainbow>` o un estilo con nombre como `<galaxy>Hola`. También se aceptan alias en español como `<gradiente:...>` y `<arcoiris>`. Solo se permiten etiquetas visuales; los clics, textos al pasar el ratón y comandos están siempre bloqueados. Los nombres de estilo usan minúsculas, números, `_` o `-`, y su `Format` tiene que llevar `{text}`.

## Lista de jugadores

`/players` (también `/list`, `/jugadores`) abre un menú con todos los conectados. Al hacer clic en una cabeza puedes escribirle, ignorarle, mandarle correo o comerciar con él. El staff ve ahí botones extra de moderación (ver [suite de staff](staff-suite)).

Con `Nick.Hide_Real_Names_In_List: true` en `players.yml`, la respuesta del `/list` de Minecraft también muestra los apodos en lugar de los nombres reales de las cuentas.

## Mensaje de bienvenida

`/motd` vuelve a mostrar el mensaje de entrada; `/motd first` muestra la versión de la primera vez. Las líneas están en `messages.yml` bajo `Motd` y, por defecto, en el idioma de cada jugador desde los archivos de idioma.

## Comandos y permisos

| Comando | Permiso | Por defecto |
| --- | --- | --- |
| `/afk` | `chatalyst.command.afk` | todos |
| `/toggle [nombre]` | `chatalyst.command.toggle` | todos |
| `/ignore <jugador>` | `chatalyst.command.ignore` | todos |
| `/me <acción>` | `chatalyst.command.me` | todos |
| `/chatcolor [color]`, `/chatcolor reset` | `chatalyst.command.chatcolor` | todos |
| `/itemname <nombre>`, `/itemname reset` | `chatalyst.command.itemname` | op |
| `/players` | `chatalyst.command.players` | todos |
| `/motd [first]` | `chatalyst.command.motd` | todos |

## Límites

- El AFK se detecta por servidor; no sigue al jugador por la red.
- Los inventarios compartidos se pueden abrir durante 5 minutos; después el botón caduca.
- Los emojis son símbolos Unicode que dibuja la fuente de Minecraft. Los emojis a todo color necesitan ItemsAdder.
