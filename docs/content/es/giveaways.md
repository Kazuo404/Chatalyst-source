---
title: Sorteos
description: Un sorteo en directo que ve todo el servidor, con cuenta atrás, ruleta de cabezas, inmunidad mientras gira y premios desde objetos hasta rangos y dinero.
---

En Chatalyst un sorteo es un evento en directo. El staff lo lanza, todo el mundo recibe una cuenta atrás y luego se abre, a la vez para todos los jugadores que participan, una ruleta con sus cabezas. La rueda gira, frena con un tic cada vez más agudo y se para en el ganador, que se lleva un título, un anuncio, un cohete y el premio.

De fábrica el staff nunca gana, y nadie puede recibir daño mientras tiene la rueda en pantalla.

## Cómo se activa

Los sorteos vienen encendidos, con dos premios de ejemplo. La configuración está en `plugins/Chatalyst/giveaway.yml`:

```yaml
Giveaway:
  Enabled: true
  Command: "sorteo"
  Command_Aliases:
    - "giveaway"
    - "rifa"
    - "raffle"
  Warmup_Seconds: 20
  Countdown_Marks: [30, 20, 10, 5, 4, 3, 2, 1]
  Ready_Seconds: 3
  Spin_Seconds: 9
  Winner_Seconds: 6
  Reopen_If_Closed: true
```

El staff abre `/sorteo`, elige una categoría, luego un premio y lo lanza. O escribe `/sorteo start <id del premio> [segundos] [cantidad]`.

## Configuración

### Cómo transcurre un sorteo

1. **Calentamiento.** Se avisa a todos de que el sorteo va a empezar, con una línea en el chat, un título y una bossbar que se vacía, y una cuenta atrás en los últimos segundos.
2. **Inicio.** La ruleta se abre a la vez para todos los participantes y se activa la inmunidad.
3. **Giro.** Las cabezas pasan y la rueda frena; el sonido del tic sube de tono.
4. **Ganador.** Se elige en el último momento, al azar entre los jugadores que siguen conectados. Después se entrega el premio y la inmunidad se retira unos segundos más tarde.

| Opción | Por defecto | Qué hace |
|---|---|---|
| `Enabled` | `true` | Enciende los sorteos. |
| `Command` / `Command_Aliases` | `sorteo` / `giveaway`, `rifa`, `raffle` | Nombres del comando. Si otro plugin tiene un nombre, se salta; `/chatalyst:sorteo` siempre funciona. |
| `Warmup_Seconds` | `20` | Tiempo entre el aviso y la apertura de la rueda. `0` empieza al momento. |
| `Countdown_Marks` | `30, 20, 10, 5, 4, 3, 2, 1` | Segundos que se anuncian durante el calentamiento. |
| `Ready_Seconds` | `3` | Pausa tras abrirse el menú, para que todos lean el premio. |
| `Spin_Seconds` | `9` | Cuánto gira la rueda. |
| `Winner_Seconds` | `6` | Cuánto se queda el ganador en pantalla. |
| `Reopen_If_Closed` | `true` | Vuelve a abrir la ruleta a quien la cierre mientras gira. |
| `Immunity.Enabled` | `true` | Sin daño, hambre ni fuego durante el sorteo. |
| `Immunity.Extra_Seconds` | `5` | Protección extra tras anunciarse el ganador. |
| `Immunity.Cancel_Void` | `true` | También anula el daño del vacío. Pon `false` si tu spawn está sobre el vacío. |
| `Firework` | `true` | Un cohete inofensivo a los pies del ganador. |
| `Amount_Presets` | `1000` … `100000` | Hasta 6 atajos de dinero en el menú. |
| `Notify_Discord` | `true` | Publica el resultado en el canal de staff de Discord (necesita el canal de staff del [bot de Discord](discord-bot)). |

`Sounds` fija el sonido de cada paso (inicio, cuenta atrás, tics de la rueda, tics finales, ganador) y el rango de tono de la rueda.

### Quién participa

| Opción | Por defecto | Qué hace |
|---|---|---|
| `Participants.Exempt_Permission` | `chatalyst.giveaway.exempt` | Quien tenga este permiso mira pero nunca sale sorteado. |
| `Participants.Exclude_Staff` | `true` | El staff nunca sale sorteado. |
| `Participants.Staff_Permissions` | nodos de moderación de Chatalyst, vanilla, EssentialsX y LiteBans | Quien tenga alguno cuenta como staff. |
| `Participants.Staff_Groups` | `owner`, `admin`, `mod`, `helper`… | Grupos principales de LuckPerms que cuentan como staff. |
| `Participants.Exclude_Vanished` | `true` | Los jugadores en vanish no participan. |
| `Participants.Include_Afk` | `false` | Los jugadores AFK no participan. |
| `Participants.Excluded_Worlds` | `[]` | Mundos cuyos jugadores ni participan ni ven el menú. |

Los operadores siempre cuentan como staff. Los permisos de staff reconocen a un rango de staff se llame como se llame, incluso en otro idioma.

### Premios

Cada premio está en `Giveaway_Prizes` y tiene un `Type`:

| Tipo | Qué da |
|---|---|
| `ITEMS` | Objetos escritos en el archivo (`Material`, `Amount`, `Name`, `Lore`, `Enchantments`, `Model_Data`, `Glow`). |
| `COMMANDS` | Comandos de consola. `{player}` y `{uuid}` son el ganador. |
| `MONEY` | Dinero por Vault. La cantidad se escribe al lanzar el sorteo, como cifra (`50000`) o como franja (`15000-35000`) que se tira cada vez. |
| `STORE` | Un producto que ya vendes, por su id de `Store_Catalog` en `store.yml`. Ver [Tienda, rangos y Tebex](store). |
| `RANK` | Un rango. Si ese rango tiene Tebex activado, pasa por los mismos registros que una compra; si no, se escribe directamente en LuckPerms. |
| `PERMISSION` | Nodos de permiso de LuckPerms en `Permissions`, para una ventaja que no es un rango entero. |

Cualquier premio puede llevar además `Commands` y `Permissions`. `Duration` (`7d`, `30d`, `12h` o `permanent`) dice cuánto duran el rango y los permisos. Este es uno de los premios de fábrica:

```yaml
Giveaway_Prizes:
  diamantes:
    Name: "<gradient:#54A6FF:#B6F7D4><bold>Diamond stash</bold></gradient>"
    Name_es: "<gradient:#54A6FF:#B6F7D4><bold>Alijo de diamantes</bold></gradient>"
    Type: ITEMS
    Icon: DIAMOND
    Glow: true
    Description:
      - "<#C0B3D9>32 diamonds and a netherite ingot"
    Description_es:
      - "<#C0B3D9>32 diamantes y un lingote de netherita"
    Items:
      - Material: DIAMOND
        Amount: 32
      - Material: NETHERITE_INGOT
        Amount: 1
```

El id del premio (`diamantes`) es lo que escribes en `/sorteo start <id>`. Que sea corto, en minúsculas y definitivo.

La lista de permisos de un premio también se edita en el juego: `/sorteo`, clic derecho en el premio, añadir con el libro y shift+clic para quitar. Al guardar se escribe `giveaway.yml` conservando sus comentarios.

### Rangos como premio

Con `Ranks.Auto_Detect: true`, los grupos de `Ranks.Names` (`vip`, `mvp++`, `legend`…) que existan en LuckPerms se ofrecen como premio automáticamente. Los grupos de `Ranks.Never` y cualquier grupo que dé un permiso de staff nunca se ofrecen. `Ranks.Duration` (por defecto `permanent`) es lo que dura un rango concedido directamente.

### Diseño del menú

`Giveaway_Categories` agrupa los premios en el menú (kits, rangos, dinero, ventajas, objetos). `Giveaway_Menu` fija la altura del menú, la rejilla de cartas y los huecos de los botones. Las cartas se centran solas.

## Comandos y permisos

| Comando | Permiso (por defecto) | Qué hace |
|---|---|---|
| `/sorteo` (`/giveaway`, `/rifa`, `/raffle`) | `chatalyst.command.giveaway` (true) | Los jugadores ven el estado o vuelven a abrir la rueda en marcha. El staff abre el menú. |
| `/sorteo start <id> [segundos] [cantidad]` | `chatalyst.command.giveaway.admin` (op) | Lanza un sorteo. La cantidad es para premios de dinero. |
| `/sorteo cancel` | `chatalyst.command.giveaway.admin` | Cancela el sorteo en marcha. |
| `/sorteo list` | `chatalyst.command.giveaway.admin` | Lista los premios y cualquier problema que tengan. |
| `/sorteo give <jugador> <id>` | `chatalyst.command.giveaway.admin` | Entrega un premio a mano. |
| `/sorteo status` | `chatalyst.command.giveaway.admin` | Muestra el sorteo en marcha o el último ganador. |

`chatalyst.giveaway.exempt` (false) deja a un jugador fuera del sorteo.

## Límites

- Si el ganador se desconecta en el último segundo, cobra cuando vuelve. Si antes se reinicia el servidor, la consola avisa al staff para terminarlo con `/sorteo give`.
- Los objetos que no caben en el inventario del ganador caen a sus pies; el ganador está quieto e inmune cuando caen.
- Los premios `RANK` y `PERMISSION` necesitan LuckPerms. `MONEY` necesita Vault.
