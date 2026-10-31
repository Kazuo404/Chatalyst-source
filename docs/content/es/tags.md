---
title: Tags, títulos y efectos
description: Un catálogo de títulos desbloqueables con requisitos, logros, temporadas y recompensas, prefijos personales y tags de rango animados para tab y scoreboards.
---

Los títulos le dan a los jugadores algo que conseguir. Montas un catálogo de títulos (por ejemplo `[Cazador]` por matar 100 criaturas), cada uno con sus requisitos, rareza, puntos y recompensas. Los jugadores lo recorren con `/tag`, ven su progreso y se equipan cualquier título que hayan desbloqueado. El título equipado aparece junto a su nombre en el chat.

Esta página también cubre el prefijo/sufijo personal de siempre (`/tag prefix`) y los tags de rango animados (`tagfx.yml`) para el tabulador y los scoreboards.

## Cómo se activa

El catálogo siempre está disponible. Dos permisos deciden quién lo usa:

- `chatalyst.tag` (op por defecto) deja abrir `/tag` y equiparse títulos. Dáselo a todos los que deban coleccionar títulos.
- `chatalyst.tag.admin` (op) abre el editor con `/tag admin`.

Los títulos aparecen en el chat con los placeholders `{tag_prefix}` y `{tag_suffix}`, que el `formats/chat.yml` de fábrica ya incluye.

## Configuración

### El catálogo

Los títulos viven en `plugins/Chatalyst/tags.yml`. Todo se edita en el juego con `/tag admin` (o `/cht gui` → Contenido → Títulos), así que casi nunca hace falta tocar el archivo. Este es el título de ejemplo que trae el plugin:

```yaml
Settings:
  Notify_Unlock: true
  Auto_Equip_First: false

Titles:
  hunter:
    Display_Name: Cazador
    Text: <gradient:#FFD166:#FF4D6D><bold>[Cazador]</bold></gradient>
    Description: Demuestra tu experiencia derrotando criaturas.
    Material: IRON_SWORD
    Position: LEFT
    Spacing: 1
    Enabled: true
    Hidden_Until_Unlocked: false
    Category: Cacería
    Rarity: RARE
    Order: 10
    Points: 25
    Secret: false
    Season:
      Id: ''
      Starts_At: ''
      Ends_At: ''
    Requirements:
      Mode: ALL
      Rules:
      - Type: MOB_KILLS
        Target: ANY
        Amount: 100
    Rewards:
      Commands: []
```

| Ajuste | Por defecto | Qué hace |
|---|---|---|
| `Settings.Notify_Unlock` | `true` | Muestra el aviso de desbloqueo (notificación, chat y sonido). Es el interruptor de los tres. |
| `Settings.Auto_Equip_First` | `false` | Equipa solo el primer título que desbloquea cada jugador. |

| Campo del título | Qué hace |
|---|---|
| `Display_Name` / `Text` | Nombre en los menús y texto MiniMessage que sale en el chat. |
| `Position` | `LEFT` (antes del nombre) o `RIGHT` (después). |
| `Spacing` | De `0` a `8` espacios entre el título y el nombre. Por defecto `1`. |
| `Category`, `Rarity`, `Order` | Cómo se agrupa y se ordena. Cada categoría se convierte en una colección. |
| `Points` | Puntos de logro que suma al perfil del jugador. |
| `Secret` | Muestra `???` hasta que se consigue. |
| `Hidden_Until_Unlocked` | Quita el título del catálogo hasta que se consigue. |
| `Season` | Un id de temporada con inicio y fin opcionales. Cambiar el id empieza una temporada nueva. |
| `Requirements` | Hasta 8 reglas, combinadas con `Mode: ALL` o `ANY`. |
| `Rewards.Commands` | Hasta 8 comandos de consola que se ejecutan al desbloquearlo de forma automática. |
| `Achievement` | Notificación, chat y sonido al desbloquearlo. |

### Tipos de requisito

| Tipo | Significado |
|---|---|
| `NONE` | Disponible para todos. |
| `MOB_KILLS` | Matar `Amount` criaturas. `Target` es `ANY` o un tipo de entidad. |
| `PLAYER_KILLS` | Matar a `Amount` jugadores. |
| `PLAYTIME_MINUTES` | Jugar `Amount` minutos. |
| `BLOCKS_MINED` | Minar `Amount` bloques del material de `Target`. |
| `ITEMS_CRAFTED` | Fabricar `Amount` objetos del material de `Target`. |
| `ITEMS_PICKED_UP` | Recoger `Amount` objetos del material de `Target`. |
| `ITEMS_OWNED` | Tener ahora mismo `Amount` del material en el inventario. |
| `FISH_CAUGHT` | Pescar `Amount` peces. |
| `ANIMALS_BRED` | Criar `Amount` animales. |
| `ADVANCEMENT` | Completar el logro de `Target` (`namespace:ruta`). |
| `PERMISSION` | Tener el permiso de `Target`. |
| `TITLE_UNLOCKED` | Desbloquear antes otro título. Los ciclos se rechazan. |
| `MANUAL` | Solo lo concede un administrador. |

El progreso se comprueba cuando cambia la estadística correspondiente y en una revisión de seguridad aproximadamente cada minuto. Un título conseguido se queda conseguido aunque luego cambies sus requisitos.

Los comandos de recompensa admiten `{player}`, `{uuid}`, `{title_id}`, `{title_name}`, `{points}` y `{season}`. Los títulos concedidos a mano desde el editor no ejecutan sus recompensas, así que conceder y retirar en bucle no sirve para farmearlas.

### Colecciones y ranking

Cada categoría se convierte sola en una colección. Desde el editor puedes darle a cada colección su nombre, descripción, icono y puntos extra por completarla. Los jugadores ven las colecciones y un ranking global por puntos desde su perfil en `/tag`.

### Prefijo y sufijo personal

El tag personal de siempre sigue funcionando. `/tag prefix <texto>` y `/tag suffix <texto>` ponen un texto libre que sale por `{tag_prefix}` y `{tag_suffix}`. Un título del catálogo equipado tiene prioridad; el tag personal vuelve al quitarse el título. La longitud máxima se fija en `players.yml`:

```yaml
Tag:
  Max_Length: 16
```

### Tags de rango animados

`tagfx.yml` anima tags de rango hechos con glifos de fuente de ItemsAdder. Cada efecto lista sus fotogramas con caracteres reales de la fuente (`Chars`, uno por fotograma) o con el atajo `Glyph` + `Frames`. El archivo de fábrica trae un efecto `quasar` de 12 fotogramas escrito con `Chars` y este ejemplo comentado del atajo:

```yaml
  #owner:
  #  Glyph: 'tag_owner'
  #  Frames: 12
  #  Interval_Ms: 70
```

| Placeholder | Valor |
|---|---|
| `%chatalyst_fx_<nombre>%` | El fotograma actual de ese efecto. |
| `%chatalyst_fx_auto%` | El fotograma actual del rango de quien mira (efecto con el mismo nombre que el grupo de LuckPerms). Si no hay, devuelve el prefijo normal. |

`Interval_Ms` son los milisegundos por fotograma (mínimo 20). Usa `Chars` con los caracteres reales de la fuente: funcionan en todas partes. El atajo `Glyph` + `Frames` solo se ve donde ItemsAdder filtra el texto (el chat y sus menús). Todos los jugadores ven el mismo fotograma a la vez.

## Comandos y permisos

| Comando | Permiso (por defecto) | Qué hace |
|---|---|---|
| `/tag` | `chatalyst.tag` (op) | Abre el catálogo de títulos. |
| `/tag <id>` | `chatalyst.tag` (op) | Equipa un título desbloqueado. |
| `/tag off` | `chatalyst.tag` (op) | Quita el título. Los desbloqueos se conservan. |
| `/tag admin` | `chatalyst.tag.admin` (op) | Abre el editor del catálogo. |
| `/tag prefix\|suffix <texto\|off>` | `chatalyst.tag` (op) | Pone tu prefijo o sufijo personal. |
| `/tag prefix\|suffix <jugador> <texto\|off>` | `chatalyst.tag.others` (op) | Cambia el tag personal de otro jugador. |

`chatalyst.tag.color` (op) permite colores MiniMessage en el prefijo y sufijo personal. Los placeholders de títulos para scoreboards (`%chatalyst_title_name%`, `%chatalyst_achievement_points%`…) están en [Placeholders](placeholders).

## Límites

- Los tags animados no se animan en el chat: una línea de chat se dibuja una sola vez. Se animan en el tabulador, sobre la cabeza, en scoreboards, bossbars y menús.
- ItemsAdder puede reasignar los caracteres de la fuente al añadir contenido. Si los tags salen como cuadros vacíos, revisa `plugins/ItemsAdder/storage/font_images_unicode_cache.yml`.
- El catálogo admite hasta 500 títulos, cada uno con hasta 8 reglas y 8 comandos de recompensa.
- Si el servidor se para entre el desbloqueo y el comando de recompensa, el comando no se reintenta.
- En [modo anónimo](anonymous), `{tag_prefix}` y `{tag_suffix}` quedan vacíos.
