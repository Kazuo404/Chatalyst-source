---
title: Integraciones
description: Los plugins con los que trabaja Chatalyst, qué aporta cada conexión y los ajustes de chat de voz, dynmap, PacketEvents, variables propias y comandos propios.
---

Chatalyst funciona por sí solo, pero mejora junto a los plugins que seguramente ya tienes. Todas las integraciones son opcionales: se detectan al arrancar el servidor y se omiten sin ruido si el otro plugin no está. Nunca tienes que instalar nada solo para que Chatalyst arranque.

Esta página resume qué aporta cada conexión. Las más grandes tienen su propia página: el [bot de Discord](discord-bot), [Tebex y la tienda](store), el [proxy y Redis](network) y la [traducción en vivo](translation).

## Se detectan solos

No necesitan configuración. Instala el otro plugin y Chatalyst lo usa.

| Plugin | Qué hace Chatalyst con él |
| --- | --- |
| LuckPerms | Lee rangos, prefijos y sufijos para los formatos del chat; ajustes por grupo; sincronía de rangos con Discord y Tebex |
| Vault | Alternativa para rangos y prefijos si no hay LuckPerms; la economía de `/baltop`, el mercado, los alquileres y el dinero de la tienda |
| PlaceholderAPI | Cualquier `%placeholder%` funciona en formatos y mensajes, y Chatalyst añade los suyos `%chatalyst_...%` (ver [placeholders](placeholders)) |
| ItemsAdder | Imágenes de fuente y emojis en el chat, iconos propios en menús y tarjetas de la tienda, IDs de objetos propios en `[item]` |
| Nexo | IDs de objetos propios en `[item]` |
| WorldGuard | Canales de chat por región (ver [canales](channels)) |
| Parties | Canal de chat del grupo y el placeholder `{party_tag}` |
| TAB | Placeholders que respetan el anonimato y sincronía de apodos, para que TAB y Chatalyst no se peleen por la lista de jugadores |
| EssentialsX | Los apodos se copian a Essentials (`/tpa <apodo>` funciona); Chatalyst carga antes y se queda con nombres como `/msg`, `/nick`, `/afk` y `/me` |
| Lib's Disguises / Morphy | El nametag y el rango de un jugador disfrazado se ocultan para no delatar el disfraz; el staff en vanish puede conservar un cuerpo disfrazado (ver [suite de staff](staff-suite#vanish)) |
| MythicMobs | Los nombres de los mobs propios salen bien en los mensajes de muerte |
| AuthMe, nLogin y parecidos | La vinculación obligatoria con Discord espera a que el jugador haya iniciado sesión |
| AdvancedKits, PlayerKits2, PlayerKits | La tienda puede mostrar el contenido real de un kit (ver [tienda](store)) |
| PlayerVaultsX | El staff con `chatalyst.command.vaultsee` revisa las mochilas de cualquiera, conectado o no, con `/vaultsee <jugador> [número]` o el botón del menú del jugador: una copia de solo lectura que nunca abre la mochila. Con `chatalyst.command.vaultsee.edit`, el clic derecho abre la mochila real a través del propio PlayerVaultsX (ver [suite de staff](staff-suite)) |
| Plugins de mercado y subastas | Sus publicaciones se pueden anunciar en el canal de comercio (ver [mercado](market)) |

> [!NOTE]
> Chatalyst nunca se queda con un comando que ya tiene otro plugin, salvo que la función lo diga (por ejemplo, `/ranks` o `/store` se omiten con un aviso en consola si están ocupados). La única excepción es la [suite de staff](staff-suite), que activas a propósito.

## Chat de voz

Une los muteos de Chatalyst con tu plugin de chat de voz, para que un jugador muteado también quede callado por voz. Funciona con Plasmo Voice o con un addon de Simple Voice Chat que tenga comandos de consola para mutear y desmutear.

```yaml
Voice:
  Enabled: false
  Provider: auto
  Mute_Command: "vmute {player} {duration} {reason}"
  Unmute_Command: "vunmute {player}"
```

| Opción | Por defecto | Qué hace |
| --- | --- | --- |
| `Enabled` | `false` | Activa el puente |
| `Provider` | `auto` | `auto` prueba primero Plasmo Voice y luego los comandos de abajo; `plasmo` prefiere los nombres de Plasmo Voice; `custom` solo usa los comandos de abajo |
| `Mute_Command` | `vmute {player} {duration} {reason}` | Comando de consola que se ejecuta al mutear |
| `Unmute_Command` | `vunmute {player}` | Comando de consola que se ejecuta al terminar el muteo |

## dynmap

Pone un marcador en el mapa web por cada [reporte](reports) abierto, en el sitio donde se hizo, para que el staff vea dónde están pasando los problemas.

```yaml
Dynmap:
  Reports:
    Enabled: false
    Set_Id: "chatalyst.reports"
    Set_Label: "Chatalyst Reports"
    Icon: "exclamation"
    Persistent: false
    Hide_By_Default: false
    Show_Labels: false
    Layer_Priority: 10
```

| Opción | Por defecto | Qué hace |
| --- | --- | --- |
| `Enabled` | `false` | Activa los marcadores de reportes |
| `Set_Id` / `Set_Label` | `chatalyst.reports` / `Chatalyst Reports` | El id de la capa y el nombre que se ve en el mapa |
| `Icon` | `exclamation` | Icono de dynmap para cada marcador |
| `Persistent` | `false` | Si dynmap conserva los marcadores tras reiniciar |
| `Hide_By_Default` | `false` | La capa empieza oculta |
| `Show_Labels` | `false` | Muestra la etiqueta junto a cada marcador |
| `Layer_Priority` | `10` | Posición de la capa en la lista de capas del mapa |

## PacketEvents

PacketEvents permite a Chatalyst trabajar a nivel de red. Es opcional, pero algunas funciones lo necesitan.

```yaml
PacketEvents:
  Enabled: false
```

Con PacketEvents instalado y `Enabled: true`:

- Los comandos bloqueados se paran antes de que el servidor llegue a procesarlos.
- Las respuestas propias de Minecraft (`/tp`, `/give`…) se muestran con estilo en el idioma de cada jugador (ver [idiomas](localization#los-mensajes-propios-de-minecraft)).
- El [modo anónimo](anonymous) también puede ocultar el nombre real en títulos, barras de acción, barras de jefe y títulos de menú que escriben otros plugins.

Sin él todo lo demás sigue funcionando, y la consola te dice al arrancar qué extras quedan apagados.

## Variables propias

Textos reutilizables que puedes poner en cualquier formato o mensaje como `{nombre}`. Los cambias una vez aquí y se actualizan en todos los sitios donde se usan.

```yaml
Variables:
  server_name: "<gradient:#240046:#9D4EDD:#E0AAFF>YourServer</gradient>"
  discord_link: "discord.gg/example"
  vip_tag: "js: player.hasPermission('chatalyst.vip') ? '<#D8B4FE>[VIP] ' : ''"
  name_len: "js: player.getName().length()"
```

Un valor que empieza por `js:` es una pequeña expresión JavaScript que se evalúa para cada jugador. Solo recibe `player` y `playerName`, se ejecuta en un motor filtrado que bloquea las clases peligrosas y se compila una vez y queda en caché.

```yaml
Scripting:
  Enabled: true
```

Pon `Scripting.Enabled: false` si no usas variables `js:`. Paper descarga el motor de JavaScript al arrancar; no va dentro del jar del plugin.

## Comandos propios

Comandos sencillos que envían unas líneas y/o ejecutan comandos de consola, como `/discord` o `/web`.

```yaml
Command_Aliases:
  discord:
    Aliases: [dc]
    Permission: ""
    Messages:
      - "<#5865F2>Our Discord: <#FBF2FF><underlined>https://discord.gg/example"
    Messages_es:
      - "<#5865F2>Nuestro Discord: <#FBF2FF><underlined>https://discord.gg/example"
    Commands: []
  web:
    Aliases: [website, pagina]
    Messages:
      - "<#3DFFA8>Our website: <#FBF2FF><underlined>https://example.com"
    Messages_es:
      - "<#3DFFA8>Nuestra web: <#FBF2FF><underlined>https://example.com"
```

| Opción | Qué hace |
| --- | --- |
| `Aliases` | Otros nombres para el mismo comando |
| `Permission` | Permiso necesario para usarlo (vacío = todos) |
| `Messages` / `Messages_es` | Líneas que recibe el jugador (clientes en inglés / en español) |
| `Commands` | Comandos de consola que se ejecutan |

> [!WARNING]
> Los comandos propios se registran al arrancar el servidor. Editar uno que ya existe se aplica con `/chatalyst reload`, pero añadir o quitar uno necesita reiniciar.

## Límites

- Chatalyst habla con otros plugins mediante su API pública o detectándolos. Si alguno cambia su API en una actualización grande, la integración se apaga sola en lugar de romper tu servidor, y la consola lo avisa.
- El puente de voz solo ejecuta los comandos que configures. No puede mutear la voz en un plugin que no tenga comando de muteo.
- Las alertas de seguridad y la auditoría de comandos del staff están en [castigos](punishments#auditoria-del-staff).
