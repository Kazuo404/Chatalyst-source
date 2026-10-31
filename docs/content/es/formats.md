---
title: Formatos de chat
description: Construye cada línea de chat con partes MiniMessage con hover, clics y condiciones, además de objetos, inventarios, emojis y tokens en el chat.
---

Un formato de chat decide cómo se ve un mensaje: el prefijo del rango, el nombre, el separador y el mensaje, cada uno con su propio texto al pasar el ratón y su acción al hacer clic. Los formatos usan [MiniMessage](https://docs.advntr.dev/minimessage/format.html), así que los degradados, los colores hex y las decoraciones funcionan en todas partes.

El formato de chat viene activado. El de fábrica ya enseña el rango, la etiqueta de party, una tarjeta completa al pasar el ratón (rango, party, ping, matrimonio) y un botón de borrar solo para el staff.

## Cómo se activa

El formato se controla en `plugins/Chatalyst/chat.yml`:

```yaml
Chat:
  # Enable or disable Chatalyst chat formatting.
  Enabled: true

  # What clicking a player name in chat suggests ({msg_command} in the formats):
  #   mail = /inbox send <player>  (works even with private messages disabled; default)
  #   msg  = /msg <player>         (only when Private_Messages.Enabled is true;
  #                                 falls back to mail otherwise)
  Name_Click_Prefer: mail

  # Name of the file (without .yml) inside formats/ used by default.
  Default_Format: chat
```

Cada formato es un archivo de `plugins/Chatalyst/formats/`. Un [canal](channels) elige su formato con el campo `Format`. Los archivos de fábrica son:

| Archivo | Para qué se usa |
|---|---|
| `chat.yml` | Chat global, local, de región y de party. |
| `staff.yml` | El canal de staff. |
| `trade.yml` / `auction.yml` | Los canales de comercio y subastas. |
| `pm-sender.yml` / `pm-receiver.yml` | [Mensajes privados](private-messages). |
| `spy.yml` | Lo que ve el staff en el espía de mensajes privados. |

## Configuración

### Partes

Un formato es una lista ordenada de partes dentro de `Parts`. Esta es una copia recortada del `formats/chat.yml` de fábrica:

```yaml
Parts:

  # Visible only to players with chatalyst.deletemessages, and automatically omitted
  # when Paper did not supply a usable signed-message signature for the line.
  delete:
    Message: "<#7B68A0>[<#FF4D6D>x<#7B68A0>] <reset>"
    Hover: "<#FF4D6D>{$delete-msg}"
    Run_Command: "/chatalyst removemessage {message_uuid}"
    View_Permission: chatalyst.deletemessages

  prefix:
    Message: "%luckperms_prefix%{tag_prefix}"
    Suggest_Command: "{msg_command}"

  party:
    Message: "{party_tag}"

  player-name:
    Message: "<reset>{rank_nick}"
    Suggest_Command: "{msg_command}"

  suffix:
    Message: "{tag_suffix}%luckperms_suffix%"

  separator:
    Message: " <#7B68A0>» <reset>"

  message:
    Message: "{rank_color}{message}"
```

La parte que contiene `{message}` es donde va el texto del jugador.

| Campo | Qué hace |
|---|---|
| `Message` | Texto MiniMessage. Admite [placeholders](placeholders) `{...}` y `%...%`. |
| `Hover` | Una línea o una lista de líneas al pasar el ratón. |
| `Run_Command` | Ejecuta un comando al hacer clic. |
| `Suggest_Command` | Escribe un comando en la barra de chat al hacer clic. |
| `Open_Url` | Abre una URL al hacer clic. |
| `Copy_To_Clipboard` | Copia texto al hacer clic. |
| `Insertion` | Inserta texto en la barra de chat con shift+clic. |
| `Sender_Permission` / `Receiver_Permission` | Muestra la parte solo si quien escribe (o quien lee) tiene el permiso. Con `!` delante se niega. |
| `Sender_Variable` / `Receiver_Variable` | Comparación `"{placeholder} valor"`. |
| `Sender_Condition` / `Receiver_Condition` | Condición JavaScript aislada (con caché). |
| `View_Permission` | Nombre antiguo de `Receiver_Permission`. |

### Condiciones

`Sender_Variable` y `Receiver_Variable` comparan un placeholder con un valor. El valor puede ser texto normal (igualdad) o empezar por `!=`, `>`, `>=`, `<`, `<=`, `contains:`, `starts:`, `ends:`, `in:` (lista separada por comas) o `regex:`. Ejemplo: `"{player_group} in:helper,mod,admin"`.

Las condiciones del emisor se evalúan una vez por mensaje; solo las del lector se evalúan por cada jugador. En servidores con mucho chat, usa permisos y variables antes que JavaScript.

> [!TIP]
> La parte del prefijo usa `%luckperms_prefix%`, que necesita PlaceholderAPI y su expansión de LuckPerms. Sin ellos, usa `{player_prefix}`, que Chatalyst lee directamente de LuckPerms o Vault.

### Colores del rango

`{rank_color}` es el primer color del prefijo del rango. El formato de fábrica pinta el nombre y el mensaje con él, así que cada rango combina solo. `{rank_gradient}` construye un degradado neón a juego con ese color y se usa en las barras del hover. Cámbialos por colores fijos si lo prefieres.

### Objetos, inventarios, emojis y tokens

Los jugadores pueden añadir contenido a sus mensajes:

| Se escribe en el chat | Resultado | Permiso (por defecto) |
|---|---|---|
| `[item]` o `[i]` | El objeto de la mano con su descripción completa. | `chatalyst.chat.item` (true) |
| `[inv]`, `[inventory]`, `[inventario]` | Una vista de solo lectura del inventario, abierta 5 minutos. | `chatalyst.chat.shareinv` (true) |
| `[ec]`, `[enderchest]` | Una vista de solo lectura del cofre de ender. | `chatalyst.chat.shareinv` (true) |
| `:heart:`, `:star:`… | Un símbolo Unicode. `/cht emojis` los lista. | `chatalyst.chat.emoji` (true) |
| `[creeper]`, `[diamond]`… | Una etiqueta con vista previa del objeto. | `chatalyst.chat.pixel` (true) |
| Una URL | Un enlace clicable. | `chatalyst.chat.links` (op) |
| Etiquetas MiniMessage | Colores en su propio mensaje. | `chatalyst.chat.color` (op) |

| Opción | Por defecto | Qué hace |
|---|---|---|
| `Clickable_Links` | `true` | Hace clicables los enlaces de quien tiene `chatalyst.chat.links`. |
| `Item_Placeholder` | `true` | Activa `[item]`. |
| `Item_Custom_Ids` | `true` | Añade al `[item]` el id de Nexo o ItemsAdder de los objetos personalizados. |
| `Inventory_Share` | `true` | Activa `[inv]` y `[ec]`. |
| `Pixel_Images.Enabled` | `true` | Activa los tokens. |
| `Emojis.Enabled` | `true` | Activa los emojis `:nombre:`. |
| `Emojis.Custom` | `shrug`, `tableflip` | Tus propios emojis (`nombre: símbolo`). Pueden sustituir a los de fábrica. |
| `Party_Tag` | `"<#7B68A0>[<#B56CFF>{party}<#7B68A0>] "` | Cómo se ve `{party_tag}` para quien está en una party. |

Los tokens se definen en `Chat.Pixel_Images.Tokens`:

```yaml
  Pixel_Images:
    Enabled: true
    Tokens:
      creeper:
        Material: CREEPER_HEAD
        Display: "<#7B68A0>[<#3DFFA8>Creeper<#7B68A0>]"
        Hover: "<#3DFFA8>Creeper Head"
```

> [!NOTE]
> Minecraft vanilla no puede dibujar emojis a color, solo símbolos Unicode. Para emojis con imagen usa las imágenes de fuente de ItemsAdder: Chatalyst convierte sus `:nombre:` en el chat y en los apodos, y respeta los permisos por emoji de ItemsAdder. Quien lo lea necesita el paquete de recursos.

### /me, /say y gramática

```yaml
# /me format (third-person action). {message} = the action typed.
Me:
  Format: "<#C0B3D9>* <#FBF2FF>{player_name} <#C0B3D9>{message}"

# /say format (server message).
Say:
  Format: "<#7B68A0>[<#B56CFF>Server<#7B68A0>] <#E0AAFF>{message}"
  Format_es: "<#7B68A0>[<#B56CFF>Servidor<#7B68A0>] <#E0AAFF>{message}"

Grammar:
  Enabled: false
  Capitalize: true       # capitalize the first letter
  Punctuation: true      # add a period at the end
  Min_Length: 4
```

`Grammar` viene apagado. Si lo enciendes, pone en mayúscula la primera letra y añade un punto final a los mensajes de al menos `Min_Length` caracteres.

### Ocultar a los ignorados

`Chat.Ignore.Hide_In_Chat` (por defecto `true`) convierte los mensajes públicos de quien tienes en `/ignore` en una marca pequeña «[ignorado] nombre · [ver]». Al pulsar **[ver]** se muestra solo ese mensaje.

### Editar en el juego

`/formatedit <formato>` abre un menú para editar cada parte sin tocar el archivo. `/chatcolor` deja que cada jugador elija su color, degradado y estilos de chat: ver [Apodos y colores](nicknames).

## Comandos y permisos

| Comando | Permiso | Qué hace |
|---|---|---|
| `/formatedit <formato>` (`/feditor`) | `chatalyst.command.editor` (op) | Abre el editor gráfico de formatos. |
| `/chatcolor [color\|reset]` | `chatalyst.command.chatcolor` (true) | Abre el menú de color o fija un color. |
| `/me <acción>` | `chatalyst.command.me` (true) | Acción en tercera persona. |
| `/say <mensaje>` | `chatalyst.command.say` (op) | Mensaje del servidor. |
| `/cht emojis` | ninguno | Lista clicable de emojis. |

`chatalyst.deletemessages` (op) muestra el botón de borrar delante de cada mensaje. Consulta [todos los permisos](permissions).

## Límites

- El botón de borrar solo aparece cuando Paper aporta una firma válida para esa línea. Las líneas entregadas por la [traducción en vivo](translation) no se pueden retirar.
- Una línea de chat no se puede editar después de enviarse, así que los nombres animados solo cambian en los mensajes nuevos.
- Los placeholders de PlaceholderAPI necesitan PlaceholderAPI y la expansión correspondiente.
