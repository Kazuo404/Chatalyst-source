# Bot y flujo de staff en Discord

Chatalyst incluye su propio bot bidireccional. No necesita DiscordSRV, un proceso externo
ni webhooks: el bot se conecta al iniciar el plugin y se desconecta al apagarlo.

## Tutorial completo: crear e instalar el bot

1. Abre la página oficial
   [Discord Developer Portal](https://discord.com/developers/applications), inicia sesión,
   pulsa **New Application**, escribe un nombre y confirma con **Create**.
2. En **Bot**, personaliza nombre/icono si quieres y pulsa **Reset Token** para obtener el
   token. Copia el token una sola vez: es una contraseña, no es el Application ID, Public
   Key ni Client Secret. Nunca lo publiques ni lo subas a Git.
3. En esa misma página, dentro de **Privileged Gateway Intents**, activa:

   - **MESSAGE CONTENT INTENT**: obligatorio para leer el chat y los comandos `!`.
   - **SERVER MEMBERS INTENT**: obligatorio si `Rank_Sync.Direction` incluye
     Discord → Minecraft (`BOTH` también lo incluye).

4. Abre **Installation**. En **Installation Contexts** deja **Guild Install** activo.
   Selecciona **Discord Provided Link** y, para Guild Install, agrega el scope `bot`.
   `applications.commands` es opcional porque Chatalyst usa comandos de texto, no slash
   commands.
5. Concede únicamente:

   - **View Channels**
   - **Send Messages**
   - **Read Message History**
   - **Manage Roles**, solo si usarás sincronización de rangos

6. Copia el enlace de **Install Link**, ábrelo, elige **Add to server** y selecciona tu
   servidor. Necesitas permiso para administrar ese servidor.
7. Si usarás rangos, entra a **Server Settings > Roles** y mueve el rol del bot por encima
   de todos los roles que administrará. Discord no permite que un bot otorgue un rol igual
   o superior a su rol más alto.
8. En Discord abre **User Settings > Advanced** y activa **Developer Mode**. Haz clic
   derecho en el servidor → **Copy Server ID** y en el canal → **Copy Channel ID**.
9. Edita `plugins/Chatalyst/integrations.yml`:

```yaml
Discord:
  Enabled: true
  Bot_Token: "TOKEN_PRIVADO"
  Guild_Id: "123456789012345678"
  Channel_Id: "234567890123456789"
  Minecraft_To_Discord: true
  Discord_To_Minecraft: true
  To_Discord_Format: "**{player}**: {message}"
  To_Minecraft_Format: "<#8B5CF6>[Discord] <#FBF2FF>{discord_user}<#C0B3D9>: <reset>{message}"
```

Reinicia el servidor o ejecuta `/chatalyst reload`. La consola confirma primero que el bot
está arrancando y después el usuario con el que se conectó. Si el token se filtra,
restablécelo inmediatamente en **Developer Portal > Bot > Reset Token**. Ejecuta
`/chatalyst doctor` para detectar IDs inválidos o secciones incompletas.

Referencias oficiales: [crear e instalar una app de Discord](https://docs.discord.com/developers/quick-start/getting-started),
[Gateway e intents](https://docs.discord.com/developers/events/gateway) y
[permisos/jerarquía de roles](https://docs.discord.com/developers/topics/permissions).

El bot ignora mensajes de bots y webhooks para evitar bucles. Los mensajes enviados desde
Minecraft no permiten menciones reales a usuarios, roles ni `@everyone`.

## Enrutado por canal

El canal principal refleja el chat global. Los canales locales o privados no salen a
Discord salvo que se mapeen explícitamente:

```yaml
Discord:
  Channel_Ids:
    trade: "345678901234567890"
    staff: "456789012345678901"
```

Un mensaje entrante en uno de esos canales se entrega únicamente a quienes escuchan el
canal Chatalyst correspondiente. Un nombre de canal inexistente se bloquea y deja un aviso
en consola.

## Comandos de reportes para staff

Las alertas usan el mismo bot en un canal privado:

```yaml
Discord:
  Staff:
    Enabled: true
    Channel_Id: "567890123456789012"
    Priority_Channel_Id: ""
    Handle_From_Discord: true
    Command_Prefix: "!"
    # OBLIGATORIO: canal permitido + usuario o rol permitido.
    Allowed_Channel_Ids: ["567890123456789012"]
    Allowed_User_Ids: []
    Allowed_Role_Ids: ["678901234567890123"]
```

La autorización es cerrada por defecto: el mensaje debe venir de un canal permitido
**y** de un usuario o rol permitido. Un canal privado por sí solo no demuestra identidad.

Comandos:

- `!reports`
- `!info <id>`
- `!claim <id>`
- `!reply <id> <mensaje>`
- `!resolve <id> [nota]`
- `!pending <id> [nota]`
- `!reject <id> [nota]`
- `!reopen <id>`

Los tickets prioritarios se bloquean desde Discord por defecto. Activa
`Discord.Staff.Priority_From_Discord` únicamente si el canal y sus listas de autorización
están restringidos al rango adecuado.

## Vínculos, DMs y sincronización de rangos

El jugador ejecuta `/discord link` en Minecraft y envía `!link CÓDIGO` por DM al bot
(también funciona en el servidor configurado). El código es de un solo uso, caduca y el
vínculo se guarda en YAML/SQL. `/discord unlink` lo elimina. Los reportes pueden usar ese
vínculo para avisar al reportante por DM.

Los vínculos manuales siguen disponibles como respaldo administrativo:

```yaml
Discord:
  Linked_Accounts:
    "00000000-0000-0000-0000-000000000000": "789012345678901234"
  Staff:
    DM_Reporter: true
```

La clave siempre es el UUID de Minecraft y el valor es el ID de usuario de Discord.

## Catálogo central de rangos

Cada rango se configura una sola vez. La clave (`owner`, `vip`, `vip_monthly`) es el
`Rank ID` estable usado por Chatalyst y Tebex; `LuckPerms_Group` es el nombre exacto del
grupo creado en LuckPerms, y `Discord_Role_Id` es el ID numérico copiado desde Discord:

```yaml
Rank_Catalog:
  owner:
    LuckPerms_Group: "owner"
    Discord_Role_Id: "111111111111111111"
    Discord_Priority: 1000
    Tebex_Enabled: false
    Tebex_Duration: "permanent"
  vip:
    LuckPerms_Group: "vip"
    Discord_Role_Id: "222222222222222222"
    Discord_Priority: 100
    Tebex_Enabled: true
    Tebex_Duration: "permanent"

Discord:
  Rank_Sync:
    Enabled: true
    Direction: BOTH
    Initial_Source: MINECRAFT
    Fallback_Group: default
```

`Discord_Priority` decide qué rango gana si alguien tiene varios roles administrados. Solo
los grupos del catálogo pueden viajar de Discord a LuckPerms. Los cambios se reciben por
eventos y se agrupan durante 350 ms para evitar llamadas repetidas.

## Rangos comprados con Tebex

Instala el plugin oficial de Tebex, marca el rango con `Tebex_Enabled: true` en
`Rank_Catalog` y activa `Tebex.Enabled`. En el paquete usa el `Rank ID`, no el nombre del
grupo de LuckPerms:

```text
chatalyst tebex grant {id} vip {transaction}
```

En Expiry, Refund y Chargeback usa:

```text
chatalyst tebex revoke {id} vip {transaction}
```

La duración se configura en `Tebex_Duration` (`permanent`, `30d`, `12h`, etc.). La
transacción y la expiración absoluta se guardan antes de tocar LuckPerms, por lo que un
reintento de Tebex no duplica ni extiende el rango. Deja `Console_Only: true`.
Cuando existe `Rank_Catalog`, la sintaxis antigua que recibía un grupo y duración
directamente queda bloqueada para que no pueda saltarse `Tebex_Enabled`.

### Tienda de rangos para jugadores

`/cht ranks` abre una GUI paginada de compra; `/cht shop`, `/cht shops`, `/cht tienda` y
`/cht rangos` son alias. Esta tienda y el editor administrativo cumplen funciones distintas:
`/cht ranks` muestra productos a jugadores, mientras `/cht tebex gui` diseña la celebración
que aparece después de entregar la compra.

Activa la tienda y configura cada tarjeta con el enlace HTTPS exacto del paquete:

```yaml
Tebex:
  Enabled: true
  Shop:
    Enabled: true
    Title: "<gradient:#240046:#9D4EDD:#E0AAFF><bold>Tienda de rangos</bold></gradient>"
    Home_Url: "https://tu-tienda.tebex.io"
    Selection_Message: "<#C0B3D9>Elegiste {rank}. Usa el botón seguro:"
    Button_Text: "<gradient:#B56CFF:#E4A8FF><bold>COMPRAR {rank_id}</bold></gradient>"
    Show_Raw_Link: false

Rank_Catalog:
  vip:
    LuckPerms_Group: "vip"
    Tebex_Enabled: true
    Tebex_Duration: "30d"
    Shop:
      Visible: true
      Display_Name: "<gradient:#B56CFF:#E4A8FF><bold>VIP</bold></gradient>"
      Description:
        - "<#C0B3D9>Colores y cosméticos exclusivos"
        - "<#C0B3D9>Duración de 30 días"
      # Material vanilla o ID namespaced de ItemsAdder:
      Icon: "example:vip_icon"
      Price: "$9.99 USD"
      Purchase_Url: "https://tu-tienda.tebex.io/package/1234567"
      Order: 10
      Featured: true
      View_Permission: ""
```

El clic de inventario no puede abrir silenciosamente un navegador: Minecraft exige que el
jugador confirme un evento `open_url`. Por eso Chatalyst cierra la GUI y envía un botón
clicable al chat enlazado únicamente al paquete seleccionado. Solo se publican URLs HTTPS
válidas; una URL HTTP, malformada o con credenciales se rechaza. Como alternativa a
`Purchase_Url`, `Tebex.Shop.Rank_Url_Template` admite `{rank_id}`, `{player}` y `{uuid}`,
pero la URL exacta por paquete es más segura cuando Tebex usa IDs numéricos.

`Shop.Icon` acepta materiales vanilla (`EMERALD`) e IDs de ItemsAdder
(`namespace:vip_icon`). Los nombres, descripciones, mensajes y botones también resuelven
font-images de ItemsAdder como `:vip_badge:`. Si ItemsAdder está desinstalado o el ID no
existe, la tarjeta usa `EMERALD` sin romper la tienda; `/cht doctor` explica el fallback.

### Presentación visual de la compra

Abre `/chatalyst tebex gui` (o `/cht tebex gui`) para editar la entrega sin tocar archivos.
La primera tarjeta controla la presentación global y las demás corresponden a cada `Rank ID`
habilitado para Tebex. Cada rango puede heredar el diseño global o tener uno propio.

La GUI permite cambiar el alcance (`PURCHASER` solo para el comprador o `GLOBAL` para todos),
usar los presets `SUBTLE`, `FANCY`, `SILENT` y `CUSTOM`, editar todos los textos MiniMessage,
activar chat, toast, title, action bar, sonido y fuegos artificiales, además de previsualizar el
resultado antes de guardarlo. Los presets solo cambian los interruptores de efectos: nunca
reemplazan los textos personalizados.

La misma configuración está disponible en `integrations.yml`:

```yaml
Rank_Catalog:
  vip:
    LuckPerms_Group: "vip"
    Tebex_Enabled: true
    Tebex_Duration: "30d"
    Presentation:
      Use_Global: false
      Preset: FANCY
      Audience: GLOBAL
      Chat:
        Message: "<gradient:#B56CFF:#E4A8FF>✦ {player} ahora es {rank_id} ✦</gradient>"
      Title:
        Enabled: true
        Title: "<gold><bold>¡NUEVO RANGO!</bold></gold>"
      Firework:
        Enabled: true

Tebex:
  Presentation:
    Enabled: true
    Audience: PURCHASER
    Preset: SUBTLE
    Offline_Delivery: true
```

Placeholders disponibles: `{player}`, `{rank_id}`, `{rank}`, `{group}`, `{duration}` y
`{transaction}`. La presentación se ejecuta únicamente después de que LuckPerms confirme el
rango y Chatalyst guarde el recibo `DONE`. Si una entrega privada ocurre con el comprador
desconectado, se conserva y se muestra al volver a entrar. Los fuegos artificiales son
cosméticos y no causan daño. Esta función no depende del bot de Discord.
