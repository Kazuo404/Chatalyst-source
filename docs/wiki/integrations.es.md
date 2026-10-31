# Integraciones

Chatalyst degrada limpiamente: las integraciones opcionales se detectan al arranque y se
omiten si no están.

## Hooks soportados

- LuckPerms: rangos, prefijos y datos de permisos.
- Vault: rangos/prefijos de respaldo y economía para `/baltop`.
- PlaceholderAPI: placeholders externos.
- Bot de Discord integrado: puente bidireccional, enrutado de canales, formato por canal,
  tarjetas del mercado y comandos de reportes.
- WorldGuard: canales por región.
- Parties: canales de party.
- ItemsAdder: imágenes de fuente, tokens de chat e ids de items personalizados en `[item]`.
- Nexo: ids de items personalizados en `[item]` y detección de content-packs.
- TAB: placeholders nativos y sincronización de nicks.
- dynmap: marcadores de reportes para tickets abiertos.
- Simple Voice Chat / Plasmo Voice: puente de comandos mute/unmute.
- PacketEvents: bloqueo de comandos a nivel de paquete antes de que Bukkit vea el comando.
- Seguridad de staff: rastro de auditoría para acciones sensibles de moderadores.
- LibreTranslate (o cualquier endpoint compatible): traducción de chat en vivo por
  espectador.

## Discord, rangos y Tebex

El bot de Discord pertenece a Chatalyst y se inicia y detiene junto con el plugin; no
requiere DiscordSRV ni webhooks. El `Rank_Catalog` de `integrations.yml` es la única
fuente de verdad para asociar un ID estable con su grupo de LuckPerms, rol de Discord y
duración de Tebex. Consulta el
[tutorial completo del bot, sincronización y Tebex](discord-staff.es.md).

La entrega de rangos también incluye una presentación global o distinta por `Rank ID`,
editable desde `/chatalyst tebex gui` o `integrations.yml`. Puede ser privada o global,
discreta, llamativa o silenciosa, y combinar chat, toast, title, action bar, sonido y fuegos
artificiales cosméticos. No necesita que el bot de Discord esté activado.

Cada texto es **bilingüe** con la convención `_es` de siempre (`Message`/`Message_es`,
`Title`/`Title_es`…), y con `Audience: GLOBAL` eso importa más que en ningún otro sitio: el
anuncio lo lee todo el servidor a la vez, así que el idioma se elige **por espectador** y el
mismo anuncio sale en inglés para unos y en español para otros. Si reescribes un texto y no lo
traduces, tu versión cubre los dos idiomas: nunca se mezcla tu frase con la traducción de
fábrica. Editando desde `/chatalyst tebex gui` escribes el idioma de **tu** cliente — un
cliente en español edita `_es` y deja el inglés intacto.

Para los jugadores el comando es **`/ranks`** (alias `/rangos`), renombrable con
`Tebex.Shop.Command`. Nunca les pidas que escriban el comando de administración del propio
plugin. Chatalyst **no le quita** una etiqueta a otro plugin: si la que elijas está ocupada la
salta y avisa en consola de quién es, así que tu plugin de tienda conserva `/shop`; el
respaldo `/chatalyst ranks` funciona siempre. Cada tarjeta usa su propio enlace HTTPS exacto de
Tebex y al pulsarla envía un botón seguro al chat para que el cliente confirme el navegador.
Los iconos admiten materiales vanilla o IDs de ItemsAdder y los textos aceptan sus font-images.

### Lo que se paga nunca se pierde

Tebex da por ejecutado un comando en cuanto lo lanza y no lo reenvía nunca, así que una
entrega que fallara de nuestro lado destruiría la compra en silencio. Por eso Chatalyst no
modela la entrega como una orden a ejecutar, sino como un **estado deseado al que converger**.

Una compra se registra como *derecho adquirido* — lo que ese jugador posee: el rango, su
caducidad absoluta y la transacción. Ese registro es la fuente de verdad. El grupo de
LuckPerms y el rol de Discord se derivan de él, y un reconciliador compara ese estado derivado
con la realidad y repara la diferencia al entrar, tras cada compra, cuando el comprador vincula
su Discord y en un barrido de fondo rotatorio.

La consecuencia práctica es que una entrega fallida deja de ser un caso especial: es solo
deriva. Y también lo es la deriva que ninguna cola de reintentos habría detectado — un rango
quitado a mano, una sincronización de Discord pisando un grupo pagado, un backup restaurado,
LuckPerms perdiendo un nodo, o una caducidad que vence. Todo converge. Una compra que llega
mientras LuckPerms aún no ha cargado se registra y se aplica momentos después en vez de ser
rechazada, y un rol de Discord solo se da por entregado cuando existe de verdad en el miembro,
así que un bot caído o un comprador que vincula su cuenta días más tarde acaban igualmente con
su perk.

**Un rango termina cuando se acaba su tiempo de Tebex**, no porque alguien se lo quite. La
duración que pongas en `Tebex_Duration` se convierte en una caducidad absoluta que se escribe
en el propio nodo de LuckPerms, así que los permisos dejan de aplicarse al segundo exacto sin
que intervenga el plugin. El barrido atiende después a los vencidos **antes que a nadie**, de
modo que el rol de Discord y el registro caen dentro del mismo intervalo en vez de esperar su
turno en la rotación. `/chatalyst tebex revoke` existe para reembolsos y contracargos — lo
manda Tebex —, no como forma habitual de quitar un rango.

El reconciliador solo toca grupos que el propio Chatalyst entregó a ese jugador, así que un
rango que un admin dio a mano nunca se confunde con deriva ni se elimina. La contrapartida es
que sacar un rango pagado de LuckPerms a mano no se mantiene: la compra sigue diciendo que lo
posee, así que vuelve en la siguiente pasada. Deja que lo termine la duración.
`/chatalyst tebex status` muestra cuántas compras hay registradas, y `Tebex.Reconcile` ajusta
el barrido.

**Quién es el comprador es siempre su UUID.** En el panel de Tebex usa `{id}`: el nombre de un
jugador cambia y el UUID no, así que solo el UUID sigue identificándolo después de un cambio de
nombre — nada de lo que compró se pierde por cambiárselo. Aun así, la entrega **acepta también
un nombre** y lo resuelve contra la cuenta real (el jugador conectado, o la caché de nombres
del servidor) antes de guardar su UUID, porque un paquete configurado con `{username}` o un
pago cobrado fuera de la tienda no pueden perderse por eso: Tebex da el comando por ejecutado
en cuanto lo manda y **no lo reenvía nunca**. Por la misma razón, una entrega rechazada — la
integración apagada, un ID de rango que no existe, un grupo que no está permitido — se registra
como SEVERE en la consola y se avisa al staff, en vez de contestarle a una consola que nadie
está mirando.

## Vender objetos y cualquier otra cosa (`store.yml`)

Los rangos viven en `integrations.yml` y los bienes de un solo uso en **`store.yml`**, y la
separación no es cosmética: son garantías opuestas.

Un rango es **estado**. Lo posees, puede caducar, y si desaparece Chatalyst puede reponerlo
comparando lo que posees con lo que tienes. Por eso se reconcilia para siempre.

Un objeto es **evento**. Se entrega una sola vez y no se puede "reparar": el comprador pudo
gastarlo, tirarlo o morir con él, y reconciliarlo le daría una copia nueva cada pocos minutos.
Así que los bienes usan la garantía contraria: entrega **como mucho una vez**.

Cada producto de `Store_Catalog` admite tres cosas, combinables en un mismo producto:
`Items` (con nombre, lore, encantamientos y model data), `Commands` (comandos de consola con
`{player}` y `{uuid}`, para cualquier cosa que pertenezca a otro plugin: llaves de cajas,
mascotas, cosméticos) y `Money` (depósito vía Vault). El paquete de Tebex ejecuta:

```
chatalyst store give {id} <product_id> {transaction}
```

Solo se pueden entregar IDs declarados en el catálogo, así que un comando mal escrito no puede
inventar un producto — y la línea de comandos nunca transporta materiales, cantidades ni
comandos a ejecutar, solo un ID.

La compra se escribe en disco **antes** de entregar nada. Si el comprador está desconectado,
espera y se entrega al entrar; si tiene el inventario lleno, **no se tira nada al suelo**: la
entrega sigue pendiente y se reintenta, así que los objetos no se pierden en la lava ni se los
lleva quien pasaba por ahí. Al entregarse, la compra se consume y ni un reenvío de Tebex ni
otro backend sobre la misma base de datos pueden repetirla.

`/store` abre un menú con lo que se le debe: cada tarjeta usa el primer objeto del producto
como icono, lista lo que contiene y se recoge con un clic; el botón inferior recoge todo.
`/store list` conserva la salida por chat y `/store claim` recoge todo sin abrir nada.

### La vitrina por secciones

Un producto con bloque `Shop:` aparece **en la misma tienda que los rangos** (`/ranks`), no en
una aparte. Necesita `Visible: true` y una `Purchase_Url` HTTPS real: una tarjeta que no se
puede comprar es peor que ninguna, así que sin URL no se lista. Sin bloque `Shop:` el producto
sigue siendo entregable, simplemente no se anuncia.

`Store_Sections` agrupa la tienda en secciones. Está **apagado de fábrica**: con
`Enabled: false` la vitrina es la lista plana de siempre. Al encenderlo, la tienda abre en las
secciones y cada una contiene las entradas —rangos u objetos, mezclados— que la nombran en su
`Shop.Category`.

Las secciones viven en su propio bloque, no como un campo suelto en cada producto, para que el
orden, el icono y el nombre de la tienda se editen en un solo sitio. Una sección sin nada
visible se oculta, y una entrada que nombra una sección inexistente **no se pierde**: cae en
una sección final "Otros", de modo que una errata nunca borra en silencio algo que vendes.

## Vinculación obligatoria de la cuenta

`Discord.Linking.Required` convierte la vinculación en un requisito para jugar. Quien no la
tenga queda retenido al entrar: recibe su código automáticamente y no puede chatear, usar
comandos ni —opcionalmente— moverse, interactuar o pelear hasta completar `/discord link`.
`Kick_After_Seconds` desconecta a quien nunca vincule; con `0` solo se le restringe.

El requisito es **fail-open a propósito**. Solo se aplica mientras toda la cadena funciona de
verdad: bot activado y configurado, vinculación activada, sesión de la gateway en READY y el
`Guild_Id` configurado accesible. Ante una caída, un token reseteado o un bot expulsado del
servidor de Discord no se retiene a nadie, y quien ya estuviera esperando se libera con un
aviso. Ningún jugador puede quedar bloqueado por algo que no depende de él.

Con AuthMe, nLogin o un plugin de login similar instalado, el aviso espera al evento de login
de ese plugin para no pelearse con la pantalla de autenticación. Si ese evento no se puede
enlazar, el aviso aparece `Auth_Fallback_Seconds` después de entrar, y `Auth_Login_Event`
permite indicar la clase del evento de un plugin no listado. `/discord` siempre está permitido
aunque no aparezca en `Allowed_Commands`; deja ahí los comandos de tu plugin de login para que
un jugador retenido pueda autenticarse. `chatalyst.discord.exempt` (op por defecto) exime del
requisito por completo.

## Traducción de chat en vivo

Con un backend compatible con [LibreTranslate](https://github.com/LibreTranslate/LibreTranslate),
Chatalyst puede traducir cada línea elegible del chat público al idioma del cliente de
Minecraft de cada espectador: un jugador con el cliente en español lee en español un
mensaje escrito en inglés, y viceversa. El emisor y quienes comparten su idioma ven el
original, así no se desperdicia ninguna traducción.

Se configura bajo `Translation` en `integrations.yml`:

- `Enabled` — apagado por defecto; necesita la URL de un backend para hacer algo.
- `Provider_Url` — el endpoint `/translate` (auto-hospeda LibreTranslate para un servidor
  gratuito y sin clave).
- `Api_Key` — opcional, para endpoints hospedados.
- `Timeout_Ms` / `Min_Length` / `Cache_Size` — ajuste acotado de peticiones y caché.

Notas de diseño:

- **Fail-open.** Si el backend está caído, lento o desactivado, cada espectador
  simplemente ve el texto original — el chat nunca se rompe ni bloquea el hilo del
  servidor.
- **Asíncrono + caché.** La traducción corre sobre el `HttpClient` del JDK; el mensaje se
  pide una vez por idioma destino y se cachea (LRU), así las frases repetidas son
  instantáneas. Un pequeño retraso (~0.2–0.5s) es normal en la primera aparición.
- **Opt-in explícito a dos niveles.** El owner debe activar el proveedor, y cada jugador
  debe además elegir `/toggle translate` (también disponible en la GUI de `/toggle`). El
  consentimiento del jugador arranca apagado y persiste como preferencia explícita.
- **Solo cuando ayuda.** Chatalyst pide una traducción solo para espectadores con opt-in
  cuyo idioma de cliente difiere del emisor. Una audiencia monolingüe no genera ninguna
  llamada de traducción.
- **Limitación del chat firmado.** Una línea traducida se entrega como mensaje de sistema
  por espectador. Mutes, reglas, anti-spam, logs, Discord y menciones siguen corriendo,
  pero Minecraft no puede retirar esa línea con la X de borrado del staff una vez
  mostrada.
- **Privacidad.** El proveedor configurado recibe el texto plano del chat y el idioma
  destino. Prefiere un endpoint auto-hospedado, usa HTTPS con proveedores remotos y revisa
  su política de retención de datos.

## Alcance actual

ItemsAdder se usa activamente para el reemplazo de imágenes de fuente. El bot de Discord,
WorldGuard, Parties, Vault, LuckPerms y TAB tienen rutas de código activas.
Nexo, dynmap y los puentes de voz ya aportan comportamiento concreto; PacketEvents añade
una vía rápida a nivel de paquete para el bloqueo de comandos cuando está activo. La
seguridad de staff audita los comandos sensibles de moderadores hacia la consola y el
canal privado del bot.
