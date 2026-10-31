---
title: Matrimonios
description: Los jugadores se declaran, se casan en privado o con ceremonia pública y marcha nupcial, y comparten cofre, teletransporte y una línea en el hover del chat.
---

Los matrimonios dejan que dos jugadores lo hagan oficial. Uno se declara, el otro acepta, y desde entonces su pareja aparece en el hover del chat como un certificado de matrimonio. Las parejas tienen un cofre compartido, un teletransporte al otro y un aviso cuando el otro se conecta.

La pareja decide cuánto se entera el resto: una declaración normal es privada, y pedir ceremonia la convierte en un evento del servidor con invitación, votos y una marcha nupcial tocada nota a nota.

## Cómo se activa

Los matrimonios vienen **apagados**. En `plugins/Chatalyst/players.yml`, pon `Enabled: true` y ejecuta `/cht reload`:

```yaml
Marriage:
  Enabled: false
  # Seconds a proposal stays open before it expires (minimum 10).
  Request_Timeout: 60
  Announce: true
  # Divorces are private by default: breaking up in front of everyone is not fun.
  Announce_Divorce: false
  Tag: "<#C0B3D9>{$married} {rank_color}» <#FBF2FF>{partner} <#7B68A0>· <#C0B3D9>{since}"
```

Mientras esté apagado, `/marry` y `/divorce` solo responden que la función está desactivada. Los comandos necesitan `chatalyst.command.marry`, que todos tienen de fábrica; quítaselo a los grupos si quieres que casarse sea una ventaja de rango.

## Configuración

### General

| Opción | Por defecto | Qué hace |
|---|---|---|
| `Enabled` | `false` | Enciende los matrimonios. |
| `Request_Timeout` | `60` | Segundos que dura abierta una declaración (mínimo 10). |
| `Announce` | `true` | Anuncia las bodas públicas al servidor, en el idioma de cada jugador. Un `/marry` normal siempre es silencioso. |
| `Announce_Divorce` | `false` | Anuncia los divorcios. |
| `Tag` | línea de certificado | La línea del hover. `{partner}` es la pareja y `{since}` cuánto llevan casados. |

El `formats/chat.yml` de fábrica enseña `{married_tag}` en el hover del nombre. Para un jugador soltero queda vacío y la línea desaparece.

### Ceremonia

| Cómo | Qué pasa |
|---|---|
| `/marry <jugador>` | Boda privada. Sin anuncio, título, sonido ni partículas. |
| `/marry <jugador> boda` | Boda pública: invitación, votos, marcha nupcial, título y efectos. El otro jugador ve que se pide ceremonia antes de aceptar. |
| `/marry ceremony` | Ya casados: repetir la ceremonia. |

Una boda pública va en tres pasos: se avisa al servidor de que empieza en `Gathering_Seconds` y se abre la invitación; cada novio recibe un botón de «Sí, quiero» y tiene `Vow_Seconds` para pulsarlo; con los dos votos suena la marcha y se guarda el matrimonio. Si alguien se echa atrás, se desconecta o se le acaba el tiempo, la boda se cancela y no se guarda nada.

| Opción | Por defecto | Qué hace |
|---|---|---|
| `Ceremony.Guest_Teleport` | `true` | El botón de la invitación teletransporta a los invitados a la boda. |
| `Ceremony.Gathering_Seconds` | `30` | Tiempo para que lleguen los invitados antes de los votos. |
| `Ceremony.Vow_Seconds` | `60` | Tiempo que tiene cada novio para decir «Sí, quiero». |
| `Ceremony.Window_Seconds` | `60` | Cuánto dura la invitación de `/marry ceremony`. |
| `Ceremony.Cooldown_Minutes` | `10` | Minutos hasta que la misma pareja pueda hacer otra ceremonia. |
| `Ceremony.Style` | `hearts` | Efecto sobre la pareja: `hearts`, `firework-show`, `fireworks`, `halo`, `spiral`, `enchant`, `totem`, `notes`, `snow`, `portal`, `none`. |
| `Ceremony.Instrument` | `block.note_block.bell` | Sonido de bloque musical de la marcha. |
| `Ceremony.Melody` | Coro nupcial de Wagner | La marcha, nota a nota, como `"<nota>:<tick>"`. |

> [!WARNING]
> La invitación llega a todo el servidor y teletransporta a quien la pulse al sitio donde se casó la pareja, que puede ser un pozo de lava o una trampa PvP. Pon `Guest_Teleport: false` para mantener la música y el anuncio sin teletransportar a nadie.

Cada entrada de `Melody` es una nota de `0` a `24` (de Fa#3 a Fa#5, el rango del bloque musical; `12` es Fa#4) y el tick en que suena (20 ticks = 1 segundo). La de fábrica dura unos 16 segundos. Una entrada mal escrita se salta con un aviso en consola.

### Ventajas

```yaml
  Perks:
    Bank:
      Enabled: true
      Rows: 3            # 1-6 (9 slots per row)
    # /marry tp travels to your spouse.
    Teleport:
      Enabled: true
      Cooldown_Seconds: 120
    # Tells you when your spouse comes online (anonymous players never trigger it).
    Join_Notice: true
```

| Opción | Por defecto | Qué hace |
|---|---|---|
| `Perks.Bank.Enabled` | `true` | Un cofre compartido que ambos abren con `/marry bank`. |
| `Perks.Bank.Rows` | `3` | Tamaño del cofre, de 1 a 6 filas. |
| `Perks.Teleport.Enabled` | `true` | `/marry tp` te lleva hasta tu pareja. |
| `Perks.Teleport.Cooldown_Seconds` | `120` | Espera, que solo cuenta si el viaje sale bien. |
| `Perks.Join_Notice` | `true` | Te avisa cuando tu pareja se conecta. |

Los dos ven el mismo cofre, así que lo que guarda uno lo ve el otro al momento. El divorcio se rechaza mientras el cofre tenga objetos: vacíalo antes, para que una ruptura nunca haga desaparecer nada.

## Comandos y permisos

Todos los comandos necesitan `chatalyst.command.marry` (por defecto `true`).

| Comando | Qué hace |
|---|---|
| `/marry <jugador>` (`/casarse`, `/matrimonio`) | Se declara. El otro recibe botones de Aceptar y Rechazar. |
| `/marry <jugador> boda` | Se declara pidiendo ceremonia pública. |
| `/marry accept` / `/marry deny` | Responde a una declaración. |
| `/marry` | Muestra con quién estás casado y desde cuándo. |
| `/marry bank` | Abre el cofre compartido. |
| `/marry tp` | Te lleva hasta tu pareja. |
| `/marry ceremony` | Celebra una ceremonia para un matrimonio que ya existe. |
| `/marry join` | Te lleva a una boda a la que te invitaron. |
| `/marry help` | Lista todos los comandos de matrimonio. |
| `/divorce` (`/divorciarse`) | Pone fin al matrimonio. |

Placeholders: `{married_to}`, `{married_tag}`, `%chatalyst_partner%` y `%chatalyst_married%`. Ver [Placeholders](placeholders).

## Límites

- El cofre compartido funciona en un solo servidor. En una red con base de datos compartida, dos novios en servidores distintos abrirían copias separadas y ganaría el último en cerrar.
- Nadie puede estar casado con dos personas, y cada jugador solo puede tener una declaración abierta. Las declaraciones no se guardan: un reinicio las borra.
- Quien está en [modo anónimo](anonymous) nunca muestra pareja, porque la pareja lo identificaría.
