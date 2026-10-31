---
title: Apodos y colores
description: Apodos que pasan a ser la identidad del jugador en todas partes, con antisuplantación, y colores, degradados y estilos de apodo y chat desde un menú.
---

En Chatalyst un apodo es más que un adorno. Pasa a ser el nombre que los demás ven en el chat, en el tabulador, sobre la cabeza, en el correo y en los menús, y los comandos de Chatalyst lo aceptan como si fuera el nombre real.

Los apodos pasan las mismas defensas que el chat: se quitan los caracteres invisibles, se aplican las reglas y el filtro de palabrotas, y nadie puede quedarse con el nombre de otro jugador. Después, cada jugador colorea su apodo y su chat desde dos menús.

## Cómo se activa

Los apodos y colores siempre están disponibles. El acceso se controla con permisos: `/nick` necesita `chatalyst.nick` y los colores `chatalyst.nick.color`, ambos de op por defecto. Dáselos a los rangos que deban tenerlos. `/chatcolor` está abierto a todos.

La configuración está en `plugins/Chatalyst/players.yml`:

```yaml
Nick:
  Max_Length: 16
  Set_Display_Name: true
  Sync_Essentials: true
  Tab_Complete_Nicks: true
  Tab_Complete_Bypass: chatalyst.bypass.nicktab
  Hide_Real_Names_In_List: true
  Protected_Names:
    - DevKazuo
    - Chatalyst
```

## Configuración

### Apodos

| Opción | Por defecto | Qué hace |
|---|---|---|
| `Max_Length` | `16` | Longitud máxima, sin contar los códigos de color. |
| `Set_Display_Name` | `true` | También cambia el display name de Bukkit, para que otros plugins escriban el apodo. |
| `Sync_Essentials` | `true` | Copia el apodo a EssentialsX, para que `/tpa <apodo>` y `/whois` funcionen. |
| `Tab_Complete_Nicks` | `true` | Cambia los nombres reales por apodos en el autocompletado de todos los comandos. |
| `Tab_Complete_Bypass` | `chatalyst.bypass.nicktab` | Quien tenga este permiso sigue viendo los nombres reales. |
| `Hide_Real_Names_In_List` | `true` | `/list` responde con los nombres que ven los jugadores, no con los de las cuentas. |
| `Protected_Names` | `DevKazuo`, `Chatalyst` | Nombres que nadie puede usar como apodo ni como título del nametag. |

### Antisuplantación

Sin `chatalyst.nick.impersonate`, un jugador no puede elegir un apodo que coincida con:

- el nombre de cualquier jugador que haya entrado en tu servidor, conectado o no;
- el apodo actual de otro jugador;
- un nombre de `Protected_Names` o de `protected-names.txt` (una lista común que el plugin mantiene al día).

La comparación ignora mayúsculas, espacios y letras parecidas, así que «I b a i» o las letras de ancho completo no sirven para colarse. Un nombre nunca bloquea a su dueño real.

### Dónde se ve el apodo

| Sitio | Cómo |
|---|---|
| Chat | `{player_nick}` y `{rank_nick}` en todos los formatos. |
| Tabulador | La lista de Chatalyst, o TAB mediante `TAB.Sync_Nick` / `%chatalyst_nick%`. |
| Sobre la cabeza | Un solo color con el nametag clásico; el estilo completo con el [nametag flotante](nametags). |
| Correo y menús | Remitentes, amigos, selectores, ranking de dinero y lista de jugadores. |
| Comandos | `/msg`, `/inbox send`, `/report`, `/mute` y los demás comandos de Chatalyst aceptan el apodo. |
| Otros plugins | El display name de Bukkit y, con `Sync_Essentials`, EssentialsX. |

> [!TIP]
> Las imágenes de fuente de ItemsAdder funcionan en los apodos: `/nick Pepo:crown:` guarda la imagen dentro del apodo (respetando los permisos por emoji de ItemsAdder), así que se ve en todos los sitios donde sale el apodo.

### Colores del apodo y del chat

`/nickcolor` abre un menú con colores sólidos (separados en fuertes y pastel), degradados, un creador de degradados, estilos de texto y, si están activadas, animaciones. `/chatcolor` ofrece la misma paleta para los mensajes de chat del jugador.

Los 16 colores vanilla son gratis. Los colores y degradados extra salen de `Chat_Color` en `players.yml`:

```yaml
Chat_Color:
  Colors:
    blush: "<#FF6B9B>"
    coral: "<#FF7A59>"
    honey: "<#FFC857>"
  Gradients:
    sunset: "<gradient:#FF512F:#F09819>"
    ocean: "<gradient:#2E3192:#1BFFFF>"
```

El archivo de fábrica trae 9 colores y 37 degradados. Cada color extra necesita `chatalyst.color.<id>` y cada degradado `chatalyst.gradient.<id>`, así que puedes venderlos como ventaja de rango. `chatalyst.color.*` y `chatalyst.gradient.*` los desbloquean todos.

Para obligar a un color a ir al grupo fuerte o pastel del menú de apodo, ponlo en `Chat_Color.Categories.strong` o `Chat_Color.Categories.pastel`. Los que no estén en la lista se ordenan por brillo.

### Apodos animados

Con `Nametag.Fancy.Animation.Per_Player: true` en `nametag.yml`, cada jugador elige una animación (arcoíris, fuego, océano…) desde `/nickcolor`. La animación se ve en el nametag flotante. Ver [Nametags flotantes](nametags#animacion).

## Comandos y permisos

| Comando | Permiso (por defecto) | Qué hace |
|---|---|---|
| `/nick <apodo>` | `chatalyst.nick` (op) | Pone tu apodo. Admite espacios. |
| `/nick off` | `chatalyst.nick` (op) | Quita tu apodo. |
| `/nick <jugador> <apodo>` | `chatalyst.nick.others` (op) | Cambia el apodo de otro jugador. |
| `/realname <apodo>` (`/rn`) | `chatalyst.command.realname` (op) | Muestra el nombre real detrás de un apodo. |
| `/nickcolor` | `chatalyst.nick.color` (op) | Abre el menú de color del apodo. |
| `/nickcolor <jugador> <color\|reset>` | `chatalyst.nick.color.others` (op) | Colorea el apodo de otro jugador. |
| `/chatcolor [color\|reset]` | `chatalyst.command.chatcolor` (true) | Abre el menú de color de chat o fija un color por su id. |
| `/chatcolor <jugador> <color\|reset>` | `chatalyst.chat.color.others` (op) | Cambia el color de chat de otro jugador. |

| Permiso | Por defecto | Qué permite |
|---|---|---|
| `chatalyst.nick.color` | op | Colores y MiniMessage en el apodo. |
| `chatalyst.nick.impersonate` | op | Un apodo que coincide con el nombre de otro jugador. |
| `chatalyst.gradient.custom` | op | El creador de degradados de dos colores en `/nickcolor`. |
| `chatalyst.nick.style.*` | op | Estilos de apodo: negrita, cursiva, subrayado, tachado y ofuscado. |
| `chatalyst.chat.style.*` | op | Estilos de chat. Negrita, cursiva, subrayado y tachado son `true` para todos; ofuscado es de op. |
| `chatalyst.bypass.nicktab` | op | Ver los nombres reales en el autocompletado. |

## Límites

- El autocompletado vanilla de comandos de otros plugins puede seguir sacando los nombres de perfil. Escribir el apodo funciona.
- El nametag clásico sobre la cabeza solo admite un color sólido. Para degradados, usa el [nametag flotante](nametags).
- Quien está en [modo anónimo](anonymous) no puede cambiar su apodo: es su disfraz.
