---
title: Nametags flotantes
description: Cambia el nombre de un solo color sobre la cabeza por un tag flotante con degradados, rango, iconos, una línea de título y animaciones, además del tabulador.
---

Minecraft solo puede dibujar el nombre sobre la cabeza de un jugador en un único color. El nametag flotante lo sustituye por una línea de texto flotante con el estilo completo: degradados letra a letra, negrita, el prefijo del rango, iconos de ItemsAdder y la marca de AFK. Puedes añadir encima una segunda línea para un título como «REY DE TODO», y animarlo.

El mismo archivo (`nametag.yml`) guarda también el nametag clásico por equipos y el tabulador propio de Chatalyst.

## Cómo se activa

Todo viene apagado. En `plugins/Chatalyst/nametag.yml`, pon `Nametag.Fancy.Enabled: true` y ejecuta `/cht reload`:

```yaml
Nametag:
  Enabled: false
  Prefix: "{player_prefix}"
  Suffix: "{player_suffix}"
  Name_Color: "{rank_color}"
  Update_Interval: 30
  Fancy:
    Enabled: false
    Format: "{nametag_icon_left}{player_prefix}{player_nick}{afk_tag}{nametag_icon_right}"
    Height: 0.3
    Update_Interval: 2
    Hide_Sneaking: true
```

> [!WARNING]
> Sin TAB, Chatalyst oculta el nombre vanilla por su cuenta. Con TAB instalado, pon también `scoreboard-teams` → `invisible-nametags: true` en el `config.yml` de TAB, o el nombre vanilla se verá detrás del flotante.

## Configuración

### Tag flotante

| Opción | Por defecto | Qué hace |
|---|---|---|
| `Fancy.Enabled` | `false` | Enciende el nametag flotante. |
| `Fancy.Format` | iconos, prefijo, apodo, marca AFK | Lo que enseña la línea. Admite cualquier [placeholder](placeholders). |
| `Fancy.Height` | `0.3` | A qué altura flota el texto sobre la cabeza. |
| `Fancy.Update_Interval` | `2` | Segundos entre refrescos cuando no hay animación. |
| `Fancy.Hide_Sneaking` | `true` | Atenúa el tag mientras el jugador va agachado. |

El tag sigue al jugador en teletransportes, muertes y cambios de mundo, y se oculta en vanish y en modo espectador.

### Iconos laterales

`{nametag_icon_left}` y `{nametag_icon_right}` van a cada lado del tag y cada jugador los pone con `/nametagtitle iconleft` y `/nametagtitle iconright`. Están pensados para iconos de ItemsAdder (un escudo para un guardián, una corona, el emblema de un clan), pero admiten cualquier MiniMessage. Están vacíos hasta que se ponen.

### Línea de título

Una segunda línea encima del tag:

```yaml
    Title:
      Enabled: false
      Format: "{nametag_title}"
      Default_Format: ""
      Height: 0.55
      Max_Length: 32
```

| Opción | Por defecto | Qué hace |
|---|---|---|
| `Title.Enabled` | `false` | Enciende la línea de título. |
| `Title.Format` | `{nametag_title}` | El título de cada jugador, puesto con `/nametagtitle title`. |
| `Title.Default_Format` | `""` | Se usa cuando el jugador no tiene título propio. Apúntalo a un meta de LuckPerms para títulos por rango, por ejemplo `%luckperms_meta_nametag_title%`. |
| `Title.Height` | `0.55` | Altura de la línea de título. Mantenla por encima del `Height` del tag. |
| `Title.Max_Length` | `32` | Longitud máxima del título. |

Si el título queda vacío, la línea entera desaparece.

### Animación

Un mismo reloj de animación mueve el tag flotante, el tabulador y el fotograma del apodo que usan los mensajes nuevos del chat.

```yaml
    Animation:
      Enabled: false
      Mode: "frames"
      Interval_Ticks: 6
      Tab_Interval_Ticks: 10   # minimo 6; subelo en redes grandes
      Apply_To:
        Tab: true              # requiere Tab_List.Enabled o TAB.Sync_Nick
        Chat: true             # snapshot vigente; no reenvia ni duplica mensajes
      Frames:
        - "<#FF4D6D>{player_nick}"
        - "<#E0AAFF>{player_nick}"
        - "<#3DFFA8>{player_nick}"
        - "<#B56CFF>{player_nick}"
      Wave:
        Content: "{player_nick}"
        Colors:
          - "#FFD700"
          - "#FF4D6D"
          - "#FFD700"
      Per_Player: false
```

| Opción | Por defecto | Qué hace |
|---|---|---|
| `Enabled` | `false` | Enciende la animación compartida. |
| `Mode` | `frames` | `frames` rota la lista `Frames`. `wave` desliza el degradado `Wave.Colors` sobre `Wave.Content`. |
| `Interval_Ticks` | `6` | Velocidad de la animación (de 2 a 200 ticks). |
| `Tab_Interval_Ticks` | `10` | Velocidad de actualización del tabulador (de 6 a 200). Súbelo en redes grandes. |
| `Apply_To.Tab` | `true` | Anima el tabulador (necesita `Tab_List.Enabled` o `TAB.Sync_Nick`). |
| `Apply_To.Chat` | `true` | Los mensajes nuevos usan el fotograma del momento. |
| `Per_Player` | `false` | Deja que cada jugador elija su animación en `/nickcolor`. |

Con `Per_Player: true`, cada jugador elige uno de los 32 `Presets` de fábrica (arcoíris, fuego, océano, neón, oro, aurora y más, todos editables). Las animaciones por jugador solo mueven el tag flotante y la línea de título; el tabulador y el chat enseñan el degradado estático.

La animación sustituye el **color** del apodo, pero conserva sus estilos (negrita, cursiva, etc.).

### Nametag clásico

`Nametag.Enabled` usa equipos del scoreboard para poner el prefijo y el sufijo del rango alrededor del nombre vanilla. El nombre en sí solo puede tener un color: `{rank_color}` lo toma del rango y `{nick_color}` del color elegido en `/nickcolor`. `Update_Interval` va en segundos (mínimo 5). Apágalo si otro plugin de nametags ya gestiona los equipos.

### Tabulador

```yaml
Tab_List:
  Enabled: false
  Name_Format: "{player_prefix}{rank_color}{player_name}{afk_tag}"
  Update_Interval: 10
  Header:
    - "<gradient:#240046:#9D4EDD:#E0AAFF>Your Server</gradient>"
    - ""
  Footer:
    - ""
    - "<#C0B3D9>Connection: {ping_ms} <#7B68A0>· {ping_quality}"
```

`Update_Interval` va en segundos. `{ping_ms}` enseña la latencia como número coloreado, `{ping_quality}` como palabra y `{ping_bars}` como barras de señal.

### Plugin TAB

Con TAB instalado, Chatalyst registra sus placeholders directamente en TAB (sin PlaceholderAPI): `%chatalyst_nick%`, `%chatalyst_nick_plain%`, `%chatalyst_channel%`, `%chatalyst_muted%`, `%chatalyst_warnings%` y `%chatalyst_chatcolor%`.

```yaml
TAB:
  Sync_Nick: true
```

`Sync_Nick` manda los apodos y sus fotogramas directamente a la lista de TAB. Mientras TAB esté presente, el `Tab_List` propio y el `Nametag` clásico de Chatalyst se quedan apagados para no pelearse.

## Comandos y permisos

| Comando | Permiso (por defecto) | Qué hace |
|---|---|---|
| `/nametagtitle title <texto\|off>` (`/ntitle`) | `chatalyst.nametagtitle` (op) | Pone tu línea de título. |
| `/nametagtitle iconleft\|iconright <texto\|off>` | `chatalyst.nametagtitle` (op) | Pone tus iconos laterales. |
| `/nametagtitle <title\|iconleft\|iconright> <jugador> <texto\|off>` | `chatalyst.nametagtitle.others` (op) | Cambia el título o los iconos de otro jugador. |

| Permiso | Por defecto | Qué permite |
|---|---|---|
| `chatalyst.nametagtitle.color` | op | Colores e iconos de ItemsAdder en el título y los iconos. |
| `chatalyst.nametag.seeself` | op | Ver tu propio nametag flotante (útil en F5 para probar). |

Los títulos y los iconos pasan las mismas comprobaciones que los apodos: se quitan los caracteres invisibles, los colores necesitan el permiso `.color`, se aplican las reglas y el filtro de palabrotas, y se bloquean las etiquetas de clic y hover.

## Límites

- La línea de título es una segunda entidad flotante. Si la separación entre las dos líneas no queda bien en tu versión, ajusta `Height` y `Title.Height`.
- Los iconos necesitan ItemsAdder (o un paquete de recursos que traduzca el token). Sin él, `:shield:` sale como texto.
- Las líneas del chat no se pueden animar después de enviarse: cada mensaje nuevo usa el fotograma de ese momento.
- En [modo anónimo](anonymous), el título y los iconos laterales se ocultan.
