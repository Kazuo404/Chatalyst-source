---
title: Bot de Discord
description: Un bot de Discord bidireccional integrado en Chatalyst. Puente de chat, vinculación de cuentas, sincronía de rangos y baneos, roles de castigo y herramientas de staff desde Discord, sin DiscordSRV.
---

Chatalyst trae su propio bot de Discord. Se conecta cuando arranca tu servidor y se desconecta cuando se apaga, así que no hay DiscordSRV, ni procesos externos, ni webhooks que mantener. Un solo bot lo hace todo: refleja el chat en los dos sentidos, vincula cuentas de Minecraft con Discord, mantiene sincronizados los rangos y los baneos, y le da a tu staff un canal privado para atender reportes y moderar desde Discord.

Todo viene apagado. Enciendes solo las partes que quieras, en la sección `Discord` de `integrations.yml`.

## Crear el bot

1. Abre el [Discord Developer Portal](https://discord.com/developers/applications), pulsa **New Application**, ponle un nombre y créala.
2. Entra en **Bot** y pulsa **Reset Token**. Copia el token: funciona como una contraseña. No lo compartas nunca.
3. En **Privileged Gateway Intents**, activa **MESSAGE CONTENT INTENT**. Activa también **SERVER MEMBERS INTENT** si vas a sincronizar rangos de Discord hacia Minecraft.
4. Entra en **Installation**, deja **Guild Install** y añade el scope `bot`. Dale **View Channels**, **Send Messages** y **Read Message History**. Añade **Manage Roles** para la sincronía de rangos o el rol de vinculado, y **Ban Members** para la sincronía de baneos.
5. Usa el enlace de instalación para meter el bot en tu servidor de Discord.
6. En Discord, abre **Ajustes de usuario > Avanzado** y activa el **Modo desarrollador**. Haz clic derecho en tu servidor y en tu canal de chat para copiar sus IDs.
7. Rellena `integrations.yml`:

```yaml
Discord:
  Enabled: true
  Bot_Token: "TU_TOKEN_PRIVADO"
  Guild_Id: "123456789012345678"
  Channel_Id: "234567890123456789"
```

8. Reinicia el servidor o ejecuta `/chatalyst reload`. La consola confirma qué cuenta de bot se ha conectado.

Ejecuta `/chatalyst doctor discord` cuando quieras: le pregunta directamente a Discord si el bot tiene los permisos, la posición de rol y los intents que necesita tu configuración.

> [!DANGER]
> Si tu token se filtra alguna vez, reinícialo en el Developer Portal de inmediato. Quien tenga el token controla el bot.

> [!TIP]
> Para la sincronía de rangos, el rol de vinculado, la sincronía de baneos y los roles de castigo, sube el rol del bot **por encima** de todos los roles que tenga que dar, quitar o banear, en **Ajustes del servidor > Roles**. Discord no deja que un bot gestione roles iguales o superiores al suyo.

## Puente de chat

```yaml
Discord:
  Activity: "with {online}/{max} players"
  Activity_Refresh_Seconds: 60
  Minecraft_To_Discord: true
  Discord_To_Minecraft: true
  Channel_Ids: {}
  To_Discord_Format: "**{player}**: {message}"
  Channel_Formats: {}
  To_Minecraft_Format: "<#5865F2>[Discord] <#FBF2FF>{discord_user}<#C0B3D9>: <reset>{message}"
  Max_Inbound_Length: 256
  Announce_Join_Quit: false
  Join_Message: "{player} joined"
  Quit_Message: "{player} left"
```

| Opción | Por defecto | Qué hace |
| --- | --- | --- |
| `Activity` | `with {online}/{max} players` | El estado "Jugando a…" del bot. `{online}` nunca cuenta al staff en vanish |
| `Activity_Refresh_Seconds` | `60` | Cada cuánto se actualiza el estado |
| `Minecraft_To_Discord` | `true` | Envía el chat del juego a Discord |
| `Discord_To_Minecraft` | `true` | Envía los mensajes de Discord al juego |
| `Channel_Ids` | `{}` | Asigna un canal del juego a su propio canal de Discord, por ejemplo `trade: "123..."` |
| `To_Discord_Format` | `**{player}**: {message}` | Cómo se ve un mensaje del juego en Discord |
| `Channel_Formats` | `{}` | Un aspecto distinto por canal del juego, para que comercio y subastas se distingan aunque compartan sala en Discord. Placeholders: `{player}`, `{message}`, `{channel}` |
| `To_Minecraft_Format` | ver arriba | Cómo se ve un mensaje de Discord en el juego |
| `Max_Inbound_Length` | `256` | Longitud máxima de un mensaje de Discord que entra al juego |
| `Announce_Join_Quit` | `false` | Publica las entradas y salidas en Discord |

El canal principal refleja el chat global. Los canales privados y locales nunca salen del juego salvo que los asignes en `Channel_Ids`. Los mensajes de Discord pasan por el filtro de groserías antes de que nadie los vea en el juego, el bot ignora a otros bots y webhooks, y nada escrito en el juego puede mencionar a `@everyone` ni a un rol en Discord.

### Tarjetas del mercado

Cuando se cierra una venta o una subasta en el [mercado](market), el bot puede publicar una tarjeta con el vendedor, el comprador, el objeto y el precio.

```yaml
Discord:
  Market:
    Enabled: false
    Channel_Id: ""
    Sales: true
    Auctions: true
    Returns: false
```

Con `Channel_Id` vacío se usa el canal principal. `Returns` (publicaciones caducadas, retiradas o sin vender) viene apagado porque es lo más ruidoso.

## Vinculación de cuentas

Los jugadores vinculan su cuenta de Minecraft con Discord en dos pasos:

1. En el juego: `/discord link`. Reciben un código corto.
2. En Discord: envían `!link CÓDIGO` al bot, por mensaje privado o en el canal configurado.

```yaml
Discord:
  Linking:
    Enabled: true
    Command: "!link"
    Channel_Id: ""
    Linked_Role_Id: ""
    Code_Expires_Seconds: 600
```

| Opción | Por defecto | Qué hace |
| --- | --- | --- |
| `Enabled` | `true` | Permite vincular |
| `Command` | `!link` | El comando de Discord que completa la vinculación |
| `Channel_Id` | `""` | Canal donde se acepta el comando. Vacío = cualquier canal. Los mensajes privados al bot siempre funcionan |
| `Linked_Role_Id` | `""` | Rol que se da al vincular y se quita al desvincular. Vacío = ninguno |
| `Code_Expires_Seconds` | `600` | Cuánto dura un código |

La vinculación se guarda por el UUID de la cuenta, así que un cambio de nombre o un `/nick` nunca la rompen. `/discord unlink` la elimina y `/discord status` la muestra. Los códigos son de un solo uso y funcionan entre servidores que comparten base de datos.

### Vinculación obligatoria

`Linking.Required` convierte la vinculación en condición para jugar. Un jugador sin vincular queda retenido al entrar: recibe su código automáticamente y no puede chatear ni usar comandos hasta que vincule.

```yaml
    Required:
      Enabled: false
      Only_Buyers: true
      Bypass_Permission: "chatalyst.discord.exempt"
      After_Auth_Login: true
      Auth_Fallback_Seconds: 20
      Auth_Login_Event: ""
      Announce_Delay_Ticks: 20
      Reminder_Seconds: 45
      Kick_After_Seconds: 0
      Auto_Code: true
      Sound: "minecraft:entity.experience_orb.pickup"
      Restrict:
        Chat: true
        Commands: true
        Movement: false
        Interaction: true
        Damage: true
      Allowed_Commands:
        - "/discord"
        - "/login"
        - "/register"
        - "/l"
        - "/reg"
        - "/premium"
        - "/help"
```

| Opción | Por defecto | Qué hace |
| --- | --- | --- |
| `Only_Buyers` | `true` | Solo retiene a quien compró un rango cuyo rol de Discord no se le puede entregar hasta que vincule. `false` retiene a todos |
| `Bypass_Permission` | `chatalyst.discord.exempt` | Quien tenga este permiso nunca queda retenido (op por defecto) |
| `After_Auth_Login` | `true` | Con AuthMe, nLogin o parecidos, espera a que el jugador inicie sesión |
| `Auth_Fallback_Seconds` | `20` | Si no se detecta el evento del plugin de login, muestra el aviso estos segundos después de entrar |
| `Auth_Login_Event` | `""` | Clase del evento de un plugin de login que no esté en la lista |
| `Reminder_Seconds` | `45` | Cada cuánto se repite el recordatorio |
| `Kick_After_Seconds` | `0` | Desconecta a quien siga sin vincular tras este tiempo. `0` nunca expulsa |
| `Auto_Code` | `true` | Genera el código automáticamente |
| `Restrict.*` | ver arriba | Lo que no puede hacer un jugador retenido. `Damage` bloquea el daño recibido y causado |
| `Allowed_Commands` | ver arriba | Comandos que puede usar un jugador retenido. `/discord` siempre funciona; añade los de tu plugin de login |

> [!NOTE]
> La vinculación obligatoria **falla en abierto**. Solo retiene a los jugadores mientras toda la cadena funciona de verdad: el bot activo, un token válido, la conexión con Discord lista y tu servidor accesible. Si Discord cae o el bot ha sido expulsado, nadie queda retenido y quien estaba esperando se libera. Nunca se deja fuera a un jugador por algo que no depende de él.

## Sincronía de rangos

Mantiene iguales los grupos de LuckPerms y los roles de Discord. Los rangos se declaran una sola vez en el `Rank_Catalog` central al principio de `integrations.yml`, que se comparte con Tebex (ver [tienda](store#el-catalogo-de-rangos)).

```yaml
Discord:
  Rank_Sync:
    Enabled: false
    Direction: BOTH
    Initial_Source: MINECRAFT
    Exclusive_Discord_Roles: true
    Remove_On_Discord_Role_Loss: true
    Fallback_Group: "default"
    Sync_On_Ready: true
    Ready_Sync_Limit: 500
    Debounce_Milliseconds: 350
```

| Opción | Por defecto | Qué hace |
| --- | --- | --- |
| `Direction` | `BOTH` | `BOTH`, `MINECRAFT_TO_DISCORD` o `DISCORD_TO_MINECRAFT` |
| `Initial_Source` | `MINECRAFT` | Qué lado manda justo después de vincular una cuenta |
| `Exclusive_Discord_Roles` | `true` | Un miembro lleva un solo rol de rango: al darle el de su rango se le quitan los demás roles gestionados. `Discord_Priority` decide cuál gana si tiene varios |
| `Remove_On_Discord_Role_Loss` | `true` | Perder el rol en Discord quita el grupo en el juego |
| `Fallback_Group` | `default` | Grupo que se da al perder el rango sincronizado |
| `Sync_On_Ready` | `true` | Revisa a los miembros vinculados cuando el bot se conecta |
| `Ready_Sync_Limit` | `500` | Máximo de miembros revisados en ese momento |
| `Debounce_Milliseconds` | `350` | Agrupa cambios rápidos en una sola actualización |

Discord nunca puede dar un grupo de LuckPerms que no esté en el catálogo, y nunca puede quitar un rango pagado con Tebex.

## Sincronía de baneos

Un ban, en los dos lados. Un jugador baneado en Minecraft pierde su sitio en Discord, y alguien baneado en Discord deja de poder jugar.

```yaml
Discord:
  Ban_Sync:
    Enabled: false
    Direction: BOTH
    Minecraft_Reason: "Banned on our Discord server"
```

`Direction` es `BOTH`, `TO_DISCORD` o `TO_MINECRAFT`. Las cuentas se emparejan solo por la vinculación, nunca por el nombre, así que un jugador sin vincular simplemente no se refleja. El bot necesita el permiso **Ban Members**.

Solo un ban **permanente** de Minecraft se convierte en ban de Discord, porque un ban de Discord no caduca y duraría más que uno temporal. Un ban temporal se muestra con un rol de castigo (ver abajo), si esa función está activa. Antes de banear, el bot manda un mensaje privado al jugador, porque después Discord puede no dejarle escribirle.

## Roles de castigo

El bot puede crear y mantener roles que muestran un castigo en la lista de miembros: baneado, congelado o muteado, con una idea aproximada del tiempo que queda. Los roles los crea él mismo.

```yaml
Discord:
  Punishment_Roles:
    Enabled: false
    Refresh_Seconds: 300
    Server_Name: "our server"
    Ban:
      Remove_On_Permanent: true
      Dm: "You were permanently banned from **{server}**.\nReason: {reason}"
      Appeal: "If you believe this is a mistake, you can appeal by replying to this message."
      Tiers:
        month:
          Name: "Banned · 1+ month"
          Color: "#8B0000"
          Min_Remaining_Seconds: 2592000
        week:
          Name: "Banned · 1+ week"
          Color: "#FF4D6D"
          Min_Remaining_Seconds: 604800
        days:
          Name: "Banned · days"
          Color: "#FF7A9E"
          Min_Remaining_Seconds: 86400
        hours:
          Name: "Banned · hours"
          Color: "#FF4D6D"
          Min_Remaining_Seconds: 0
```

La misma estructura existe para `Freeze` (un nivel, "Frozen · under review") y `Mute` ("Muted · 1+ day" y "Muted"). Los nombres y colores son tuyos: tradúcelos si tu comunidad es hispana. Los niveles se comparan del más largo al más corto, así que deja el último en `0`. `Refresh_Seconds` baja a los miembros de nivel según se les acaba el tiempo y quita el rol cuando termina el castigo. Con `Remove_On_Permanent: true` un ban permanente saca al miembro de Discord (tras el mensaje `Dm`); con `false` se queda y lleva el rol de baneado. Renombrar un nivel deja el rol viejo a propósito: bórralo a mano en Discord.

> [!NOTE]
> Existen niveles porque el nombre de un rol de Discord es el mismo para todos los que lo llevan, así que no puede mostrar el tiempo exacto de cada uno. El tiempo exacto va en el mensaje privado.

## Canal de staff

Los reportes, el chat de staff y las alertas de seguridad pueden ir a un canal privado de Discord a través del mismo bot.

```yaml
Discord:
  Staff:
    Enabled: false
    Channel_Id: ""
    Priority_Channel_Id: ""
    Reports: true
    Staff_Chat: true
    Alerts: true
    Handle_From_Discord: false
    Commands_From_Discord: false
    Command_Prefix: "!"
    Allowed_Channel_Ids: []
    Allowed_User_Ids: []
    Allowed_Role_Ids: []
    DM_Reporter: false
    Priority_From_Discord: false
```

| Opción | Por defecto | Qué hace |
| --- | --- | --- |
| `Channel_Id` | `""` | El canal privado del staff |
| `Priority_Channel_Id` | `""` | Canal aparte para reportes prioritarios. Vacío usa `Channel_Id` |
| `Reports` / `Staff_Chat` / `Alerts` | `true` | Qué se publica ahí |
| `Handle_From_Discord` | `false` | Permite gestionar reportes desde Discord |
| `Commands_From_Discord` | `false` | Permite ejecutar comandos de moderación desde Discord |
| `Allowed_Channel_Ids` | `[]` | Canales donde se aceptan comandos |
| `Allowed_User_Ids` / `Allowed_Role_Ids` | `[]` | Quién puede usarlos (IDs de usuario o de rol) |
| `DM_Reporter` | `false` | Avisa por privado a quien reportó, si tiene la cuenta vinculada |
| `Priority_From_Discord` | `false` | Permite ver y gestionar reportes prioritarios desde Discord |

Un comando solo se acepta si viene de un canal permitido **y** de un usuario o rol permitido. Un canal privado por sí solo nunca cuenta como permiso.

**Comandos de reportes** (con `Handle_From_Discord`): `!reports`, `!info <id>`, `!claim <id>`, `!reply <id> <mensaje>`, `!resolve <id> [nota]`, `!pending <id> [nota]`, `!reject <id> [nota]`, `!reopen <id>`. Las tarjetas de reporte también traen botones.

**Comandos de moderación** (con `Commands_From_Discord`): las herramientas de la [suite de staff](staff-suite) que tienen sentido desde fuera del juego, como `!ban`, `!kick`, `!freeze`, `!mute`, `!check` y `!seen`. Se ejecutan como la consola y su respuesta vuelve al canal. Las que necesitan que estés en el juego (`/invsee`, `/tpto`, `/vanish`…) se rechazan explicando el motivo, y `/clearinventory` se rechaza porque no se puede deshacer.

## Límites

- El bot usa comandos de texto (`!link`, `!reports`), no comandos de barra.
- Para vincular, el jugador tiene que estar conectado al juego para recibir el código.
- Los comandos de moderación desde Discord solo funcionan con la [suite de staff](staff-suite) activada.
- Si usas otro puente de Discord a la vez (como DiscordSRV), seguirá publicando por su cuenta, incluidos los nombres reales de los jugadores en [modo anónimo](anonymous). Quítalo cuando actives el bot integrado.
