---
title: Tienda, rangos y Tebex
description: Vende rangos y productos de un solo uso con Tebex y entregas que nunca se pierden. Catálogo de rangos, tienda dentro del juego, reclamación de objetos y celebración de compras.
---

Chatalyst conecta tu tienda de Tebex con el juego sin una segunda cola ni un plugin aparte. Vende dos tipos de cosas y las trata distinto a propósito:

- Los **rangos** son un *estado*. El jugador tiene el rango, puede caducar y, si alguna vez desaparece, Chatalyst lo vuelve a poner. Se definen en `integrations.yml` y se reparan continuamente.
- Los **productos** (objetos, comandos, dinero) son un *evento*. Se entregan exactamente una vez y nunca se repiten, aunque el comando llegue dos veces. Se definen en `store.yml`.

En los dos casos la compra se escribe en disco antes de entregar nada, porque Tebex da un comando por hecho en cuanto lo envía y nunca lo reenvía. Nada depende de que Tebex lo reintente.

Los jugadores ven todo en una tienda dentro del juego, `/ranks`, y recogen sus productos con `/store`.

> [!NOTE]
> Necesitas el plugin oficial de Tebex instalado. Chatalyst usa su ejecutor de comandos y nunca consulta Tebex una segunda vez, lo que podría entregar la misma compra dos veces.

## El catálogo de rangos

Cada rango se declara una sola vez en `Rank_Catalog`, al principio de `integrations.yml`. La clave (`vip` abajo) es un ID estable que usan Tebex, Discord y la tienda. Viene vacío:

```yaml
Rank_Catalog: {}
```

Un ejemplo, sacado de los comentarios del archivo real:

```yaml
Rank_Catalog:
  vip:
    LuckPerms_Group: "vip"
    Discord_Role_Id: "222222222222222222"
    Discord_Priority: 100
    Tebex_Enabled: true
    Tebex_Duration: "permanent"
    Presentation:
      Use_Global: true
    Shop:
      Visible: true
      Display_Name: "<gradient:#B56CFF:#E4A8FF><bold>VIP</bold></gradient>"
      Description:
        - "<#C0B3D9>Exclusive chat colors and cosmetics"
      Icon: EMERALD
      Price: "$9.99 USD"
      Purchase_Url: "https://your-store.tebex.io/package/1234567"
      Order: 10
      Featured: true
      View_Permission: ""
      Preview:
        - Icon: DIAMOND_HELMET
          Name: "<#E0AAFF>Rank prefix"
          Lore:
            - "<#C0B3D9>Shown in chat and the tab list"
```

| Opción | Qué hace |
| --- | --- |
| `LuckPerms_Group` | El grupo exacto de LuckPerms que da este rango |
| `Discord_Role_Id` | El rol de Discord que va con él (ver [bot de Discord](discord-bot#sincronia-de-rangos)) |
| `Discord_Priority` | Qué rol gana cuando un miembro tiene varios |
| `Tebex_Enabled` | Si Tebex puede entregar este rango |
| `Tebex_Duration` | `permanent`, o una duración como `30d` o `12h` |
| `Presentation` | Celebración al entregarlo; `Use_Global: true` usa la global |
| `Shop` | La tarjeta que se ve en `/ranks`. Necesita `Visible: true` y un `Purchase_Url` HTTPS real |
| `Shop.Icon` | Un material vanilla (`EMERALD`) o un ID de ItemsAdder (`miservidor:icono_vip`) |
| `Shop.Preview` | Lo que ve el comprador antes de pagar. Escribe aquí exactamente lo que incluye el rango |

Cada texto admite su versión `_es` (`Display_Name_es`, `Description_es`…) para los clientes en español.

## Vender rangos con Tebex

Actívalo en `integrations.yml`:

```yaml
Tebex:
  Enabled: false
  Console_Only: true
  Reconcile:
    Interval_Seconds: 300
    Batch_Size: 10
  Allowed_Groups: []
  Notify_Discord: true
```

Luego, en el panel de Tebex, pon estos comandos en el paquete (`vip` es el ID del catálogo):

```text
Initial:  chatalyst tebex grant {id} vip {transaction}
Expiry:   chatalyst tebex revoke {id} vip {transaction}
```

Usa la misma línea `revoke` para reembolsos y contracargos. Usa siempre `{id}` (el UUID del jugador), no `{username}`: el nombre puede cambiar, el UUID nunca.

| Opción | Por defecto | Qué hace |
| --- | --- | --- |
| `Enabled` | `false` | Activa la entrega de rangos |
| `Console_Only` | `true` | Solo la consola (Tebex) puede ejecutar los comandos de entrega. Déjalo activado |
| `Reconcile.Interval_Seconds` | `300` | Cada cuánto pasa la revisión en segundo plano |
| `Reconcile.Batch_Size` | `10` | Jugadores revisados por pasada. La mitad se reserva para rangos recién caducados |
| `Allowed_Groups` | `[]` | Grupos que Tebex puede dar. Vacío = cualquier grupo existente; pon tus rangos de tienda para más seguridad |
| `Notify_Discord` | `true` | Anuncia las entregas en el canal privado del staff en Discord |

### Nada pagado se pierde

Una compra se guarda como lo que el jugador posee: el rango, su caducidad exacta y la transacción. El grupo de LuckPerms y el rol de Discord salen de ese registro. Chatalyst compara el registro con la realidad y repara cualquier diferencia cuando el jugador entra, tras cada compra, cuando un comprador vincula Discord y en una revisión lenta en segundo plano.

Eso cubre una entrega fallida, pero también cosas que ningún reintento notaría: un rango quitado a mano, una sincronía de Discord que pisa un grupo pagado o una copia de seguridad restaurada.

Un rango termina cuando se acaba su `Tebex_Duration`. La caducidad se escribe en el propio grupo de LuckPerms, así que los permisos dejan de aplicarse en el segundo exacto, y la revisión actualiza después el rol de Discord y el registro.

> [!WARNING]
> Chatalyst solo toca los grupos que entregó él, así que un rango que diste a mano nunca se quita. La otra cara: si quitas a mano en LuckPerms un rango **pagado**, vuelve en la siguiente revisión, porque la compra dice que el jugador lo sigue teniendo. Usa `/chatalyst tebex revoke` o deja que termine su duración.

### Pagos fuera de Tebex

Para un pago cobrado por otra vía (una transferencia, un trato en Discord), entrégalo a mano con la referencia del pago:

```text
/chatalyst tebex grant <jugador-o-uuid> <rango> <id-del-pago>
```

La duración sale del `Tebex_Duration` del rango. El pago entra en el mismo registro que una compra de Tebex, así que se repara, se renueva y se reembolsa igual.

## Vender objetos, comandos y dinero

Los productos viven en `store.yml`. La tienda viene apagada y el catálogo vacío:

```yaml
Store:
  Enabled: false
  Console_Only: true
  Command: "store"
  Command_Aliases:
    - "tienda"
    - "claimitems"
  Delivery: AUTO
  Join_Delay_Ticks: 40
  Retry_Interval_Seconds: 60
  Required_Free_Slots: 2
  Notify_Discord: true

Store_Catalog: {}
```

| Opción | Por defecto | Qué hace |
| --- | --- | --- |
| `Enabled` | `false` | Activa la tienda de objetos |
| `Console_Only` | `true` | Solo la consola (Tebex) puede ejecutar el comando de entrega |
| `Command` / `Command_Aliases` | `store` / `tienda`, `claimitems` | El comando del jugador. Solo se registra con la tienda activa, y se omite con un aviso si otro plugin lo tiene. `/chatalyst:store` siempre funciona |
| `Delivery` | `AUTO` | `AUTO` entrega en cuanto el comprador está conectado y tiene sitio. `MANUAL` espera a `/store claim` |
| `Join_Delay_Ticks` | `40` | Espera tras entrar antes de una entrega automática |
| `Retry_Interval_Seconds` | `60` | Cada cuánto se reintentan las entregas pendientes |
| `Required_Free_Slots` | `2` | Huecos libres necesarios en el inventario antes de entregar objetos |
| `Notify_Discord` | `true` | Anuncia las entregas en el canal privado del staff en Discord |

El paquete de Tebex ejecuta:

```text
chatalyst store give {id} <id_producto> {transaction}
```

Cada producto de `Store_Catalog` tiene un `Type`: `ITEMS` (materiales con cantidad, nombre, lore, encantamientos, model data y brillo), `COMMANDS` (comandos de consola con `{player}` y `{uuid}`, para llaves de cajas, mascotas o cualquier cosa de otro plugin) o `MONEY` (un ingreso por Vault). Un producto puede combinar varios; llegan juntos o no llega ninguno. Ejemplo del archivo:

```yaml
Store_Catalog:
  starter-kit:
    Name: "<gradient:#B56CFF:#E4A8FF><bold>Starter kit</bold></gradient>"
    Type: ITEMS
    Items:
      - Material: DIAMOND_SWORD
        Amount: 1
        Enchantments:
          sharpness: 3
          unbreaking: 2
      - Material: GOLDEN_APPLE
        Amount: 8
  crate-keys:
    Name: "<#E4A8FF>5 crate keys"
    Type: COMMANDS
    Commands:
      - "crates give {player} legendary 5"
  coin-pouch:
    Name: "<#FFD166>5,000 coins"
    Type: MONEY
    Amount: 5000
```

Cómo se comporta una entrega:

- **Comprador conectado y con sitio**: se entrega al momento.
- **Comprador desconectado**: la entrega espera, sobrevive a reinicios y llega cuando entre. El dinero se puede ingresar sin conexión si tu plugin de economía lo admite.
- **Inventario lleno**: no se tira nada al suelo. La entrega queda pendiente y se reintenta.
- **Entregado**: la entrega se consume. Una transacción reenviada o un segundo servidor con la misma base de datos no pueden repetirla.

Solo se pueden entregar los IDs que están en el catálogo, así que un comando mal escrito o malintencionado no puede inventarse un producto.

Los jugadores usan `/store` para abrir un menú con lo que se les debe, `/store claim` para recogerlo todo y `/store list` para verlo en el chat. Permiso: `chatalyst.command.store` (todos).

## La tienda: /ranks

Una sola tienda dentro del juego muestra tus rangos y, si quieres, tus productos.

```yaml
Tebex:
  Shop:
    Enabled: false
    Command: "ranks"
    Command_Aliases:
      - "rangos"
    Sign:
      Enabled: true
      Tag: "[ranks]"
    Title: "<gradient:#240046:#9D4EDD:#E0AAFF><bold>Rank Store</bold></gradient>"
    Rank_Url_Template: ""
    Home_Url: ""
    Close_After_Select: true
    Show_Raw_Link: false
```

| Opción | Por defecto | Qué hace |
| --- | --- | --- |
| `Enabled` | `false` | Activa la tienda |
| `Command` / `Command_Aliases` | `ranks` / `rangos` | El comando del jugador. No es `shop` a propósito, porque `/shop` suele ser de un plugin de tienda. `/chatalyst ranks` siempre funciona |
| `Sign.Enabled` / `Sign.Tag` | `true` / `[ranks]` | Escribe la etiqueta en la primera línea de un cartel y se convierte en una entrada a la tienda. Requiere `chatalyst.sign.shop` |
| `Title` | ver arriba | Título del menú |
| `Rank_Url_Template` | `""` | Patrón de URL opcional con `{rank_id}`, `{player}`, `{uuid}` para rangos sin `Purchase_Url` |
| `Home_Url` | `""` | Enlace a la portada de tu tienda |
| `Show_Raw_Link` | `false` | Imprime también la URL en el chat |

Al hacer clic en una tarjeta se abre una vista previa; el botón de compra está dentro, así que un clic sin querer nunca abre un enlace. Minecraft siempre pregunta al jugador antes de abrir el navegador. Solo se publican enlaces HTTPS válidos.

Un producto de `store.yml` aparece en la misma tienda cuando tiene un bloque `Shop:` con `Visible: true` y un `Purchase_Url` real. Con `Preview_Kit` puede mostrar el contenido real de un kit que ya tengas en AdvancedKits, PlayerKits2 o PlayerKits.

### Secciones

`Store_Sections` en `store.yml` agrupa la tienda en secciones (rangos, kits…). Viene apagado, lo que deja una sola lista:

```yaml
Store_Sections:
  Enabled: false
  Categories: {}
```

Cada entrada indica su sección en `Shop.Category`. Una sección sin nada visible se oculta, y una entrada que nombra una sección que no existe va a una sección final "Otros" en lugar de desaparecer.

## Celebración de la compra

Cuando se entrega un rango, Chatalyst puede celebrarlo con una línea de chat, un aviso tipo logro, un título, la barra de acción, un sonido y fuegos artificiales decorativos. Edítalo en el juego con `/chatalyst tebex gui` o en `integrations.yml`:

```yaml
Tebex:
  Presentation:
    Enabled: true
    Audience: PURCHASER
    Preset: SUBTLE
    Offline_Delivery: true
```

| Opción | Por defecto | Qué hace |
| --- | --- | --- |
| `Audience` | `PURCHASER` | `PURCHASER` solo lo ve el comprador; `GLOBAL`, todo el servidor |
| `Preset` | `SUBTLE` | `SUBTLE`, `FANCY`, `SILENT` o `CUSTOM`. Un preset solo enciende o apaga efectos; tus textos se quedan |
| `Offline_Delivery` | `true` | Si el comprador estaba desconectado, se lo muestra al volver |

Cada rango puede usar el diseño global o uno propio (`Presentation.Use_Global: false`). Placeholders: `{player}`, `{rank_id}`, `{rank}`, `{group}`, `{duration}`, `{transaction}`. Cada texto tiene su versión `_es`, que se elige por espectador. Los fuegos artificiales son decorativos y nunca hacen daño.

## Comandos y permisos

| Comando | Permiso | Qué hace |
| --- | --- | --- |
| `/ranks` | `chatalyst.command.ranks` (todos) | Abre la tienda |
| `/store` | `chatalyst.command.store` (todos) | Muestra y recoge tus compras |
| `/chatalyst tebex gui` | `chatalyst.admin.tebex.presentation` o `chatalyst.admin` | Edita y previsualiza la celebración de compra |
| `/chatalyst tebex status` | consola | Cuántas compras hay registradas |
| `/chatalyst tebex grant …` y `revoke …` | consola | Entrega o retira un rango |
| `/chatalyst store give …` | consola | Entrega un producto |
| — | `chatalyst.sign.shop` (op) | Crear un cartel de entrada a la tienda |

Con `Console_Only: false`, los jugadores con `chatalyst.admin.tebex` (rangos) o `chatalyst.admin.store` (productos) también pueden ejecutar los comandos de consola. Deja `Console_Only: true` salvo que tengas un motivo.

## Límites

- Chatalyst no procesa pagos. Eso lo hace Tebex (que ya acepta PayPal y tarjetas); Chatalyst entrega lo que se pagó.
- Los productos `COMMANDS` necesitan al comprador conectado, así que esperan a que entre.
- Las ventajas de un rango viven en LuckPerms y en tus otros plugins. La tienda no puede conocerlas, así que escríbelas tú en `Shop.Preview`.
