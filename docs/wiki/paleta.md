# Palette — space neon

Chatalyst's visual identity. Not one repeated colour: a **family with hierarchy** where
each tone means something.

## The rule that overrides everything

**El tono depende del fondo sobre el que se dibuja.** Minecraft tiene dos superficies muy
distintas y usar el mismo color en ambas garantiza que en una se vea mal:

| Superficie | Fondo | Tonos que funcionan |
|---|---|---|
| Título de una GUI | barra **gris clara** del inventario | **oscuros** |
| Nombres de ítem y lore | tooltip negro | claros |
| Chat | el mundo, casi siempre oscuro | claros |
| Banner de consola | terminal negra | claros |

Un lila `#E0AAFF` tiene contraste **1.08** sobre la barra gris del inventario: invisible.
Ese mismo lila da **10.4** sobre un tooltip. Por eso los títulos van al tramo oscuro de la
rampa y todo lo demás al claro.

## The ramp

```
#1A0B2E   espacio profundo   bordes de separador
#4A0080   morado profundo    inicio del título de panel
#5C0099   violeta neón       inicio en sub-pantallas
#C4009B   magenta eléctrico  final del título de panel
#A300C9   magenta            final en sub-pantallas
#FF2DCF   magenta neón       centro de separadores
#E4A8FF   lila glow          inicio del nombre de ítem
#8FD9FF   cian glow          final del nombre de ítem
```

## How it is used

| Elemento | Valor | Contraste |
|---|---|---|
| **Título de panel** | `<gradient:#4A0080:#C4009B><bold>` | 7.7 → 3.2 sobre gris |
| **Título de sub-pantalla** | `<gradient:#5C0099:#A300C9><bold>` | 6.4 → 3.6 sobre gris |
| **Nombre de ítem** | `<gradient:#E4A8FF:#8FD9FF>` | 10.4 → 12.4 sobre tooltip |
| **Descripción (lore)** | `<#C0B3D9>` | 9.8 sobre oscuro |
| **Tenue, símbolos `»`** | `<#7B68A0>` | 4.1 |
| **Valores destacados** | `<#FBF2FF>` | 18.2 |
| **Separador** | `<gradient:#1A0B2E:#FF2DCF:#1A0B2E><strikethrough>` | — |
| **Chat y banner** | `<gradient:#9D00FF:#4DE8FF>` | morado → cian, sobre oscuro |
| **Boda** | `<gradient:#E4A8FF:#FF2DCF>` | lila glow → magenta |

Los nombres de ítem no se fijan en el archivo de idioma: `MenuItems` aplica el degradado lila→cian a
todo nombre que no traiga estilo propio. Un solo punto colorea las GUIs enteras.

## Violet-biased neutrals

Un gris puro se lee como «no elegido». Los de aquí van sesgados hacia el morado para que el
cuerpo de texto **pertenezca** a la paleta:

```
cuerpo   #AAAAAA → #C0B3D9    contraste 8.29 → 9.81
tenue    #555555 → #7B68A0    contraste 2.58 → 4.10
valores  #FFFFFF → #FBF2FF    contraste 19.3 → 18.2
```

Ninguno pierde legibilidad; los dos primeros la mejoran.

## Neon semantics

Los colores vanilla de Minecraft son neón y gritan sobre el morado. Estos conservan su
**significado** y bajan la saturación:

| Rol | Vanilla | Paleta |
|---|---|---|
| Comandos y clicables | `aqua` #55FFFF | `#00E5FF` cian eléctrico |
| Avisos | `yellow` #FFFF55 | `#FFC933` ámbar neón |
| Errores | `red` #FF5555 | `#FF4D6D` rojo neón |
| Confirmaciones | `green` #55FF55 | `#3DFFA8` verde neón |
| Economía | `gold` #FFAA00 | `#FFB627` oro intenso |
| Acentos | `light_purple` | `#FF2DCF` magenta neón |

Todos por encima de 5.9 de contraste sobre fondo oscuro; el verde y el cian pasan de 12.

## Deliberately outside the palette

- **`Legacy.java`** traduce `&7` → `<gray>` para los jugadores: ahí el gris es el código de
  color, no estilo.
- **`ChatColorPalette`** ofrece los 16 colores vanilla en `/chatcolor`. Si eliges «Gris»
  debe salir gris de verdad.
- **`ConfigUpgrader`** guarda literales históricos con los que detecta configuraciones
  viejas. Modernizarlos rompería las migraciones.
- **Los degradados de `/chatcolor`** (fuego, océano, galaxia...) son contenido que elige el
  jugador, no identidad del plugin.
- **21 colores vanilla sueltos** donde el color es el contenido (`dark_red` para peligro
  extremo, `black` de fondo).

## Recolouring the whole plugin

Sustituye el par de cada superficie **sin cruzarlos**: los títulos necesitan un par oscuro
y el chat uno claro. Si usas el mismo en ambos, uno de los dos quedará ilegible — es
exactamente el error que produjo esta versión del documento.
