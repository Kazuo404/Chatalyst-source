# Mercado e intercambio directo

Chatalyst incluye dos funciones económicas opcionales. Ambas vienen apagadas:

- `Market.Enabled` controla el mercado persistente de ventas y subastas.
- `Trade.Enabled` controla intercambios directos entre dos jugadores conectados.

Los canales `trade` y `auction` tienen interruptores `Enabled` independientes y ambos vienen apagados. Con `Auto_Enable_With_Market_Plugins: true`, detectar un plugin externo de subastas expone `auction`, mientras un plugin de tiendas expone `trade`. Esta activación temporal habilita sus avisos y el destino del router sin encender el mercado nativo de Chatalyst. Usa `false` para impedir el automatismo o `Enabled: true` para mantener el canal permanentemente.

Un canal que no esté activado manual ni automáticamente desaparece de `/channel`, alias, autocompletado, enrutamiento y GUI. Los botones restantes se centran automáticamente. La detección cubre AuctionHouse, zAuctionHouse (v4), zAuctionHouseV3, CrazyAuctions, PlayerAuctions, AuctionMaster, QuickShop, QuickShop-Hikari, ChestShop, ShopGUIPlus, EconomyShopGUI, DynamicShop y BossShopPro.

Vault y un proveedor de economía son necesarios para operar con dinero. Los intercambios directos solo con objetos pueden mantenerse mediante `Trade.Allow_Money: false`.

## Mercado integrado

`/market` abre un navegador de 36 anuncios con vistas para todo el catálogo, ventas directas, subastas y anuncios propios. Se puede ordenar por más recientes, menor precio, mayor precio o próximos a terminar. La cabecera resume anuncios y reclamos; el pie concentra publicación, historial, alijo y páginas.

`/market publish` abre una bóveda de publicación donde el jugador puede añadir varios objetos. Al pulsar uno se abre su atelier con controles precisos de valoración `±1/10/100/1000`, cifra exacta por chat y descripción independiente. Después, la colección elige venta o subasta, duración y lanzamiento programado opcional. `/market sell <precio>` y `/market auction <precio> [minutos]` conservan la ruta rápida para el objeto en mano.

El objeto sale del inventario y entra en depósito antes de anunciarse. `Market.Max_Listings` limita los anuncios activos salvo que el vendedor tenga `chatalyst.market.bypasslimit`.

### Ventas

`/market buy <id>` inicia un contrato con el vendedor conectado. El comprador puede ofrecer dinero, objetos o ambos cuando `Market.Allow_Item_Offers` está activo. Ambas identidades, aportaciones y confirmaciones permanecen visibles. Cualquier cambio reinicia ambas confirmaciones y abre una breve ventana de revisión; la entrega solo ocurre cuando los dos aceptan la misma oferta estable.

### Subastas

`/market bid <id> <cantidad>` registra una puja respetando el incremento mínimo configurado. Al cerrar, se cobra al ganador y se entrega el objeto. Si el pago no puede completarse, el objeto vuelve de forma segura al vendedor.

### Recuperación y administración

- `/market cancel <id>` devuelve el depósito; el equipo con `chatalyst.market.manage` puede cancelar anuncios ajenos.
- `/market claim` recupera objetos que no cupieron en un inventario o pertenecían a un jugador desconectado.
- `/market history` muestra actividad reciente de ventas, subastas, cancelaciones y vencimientos.
- `/market credits list|retry <id>|paid <id>` reconcilia reintegros Vault en cola.
- `/market settlements list|paid <id>|unpaid <id>` resuelve un pago que quedo en estado ambiguo
  `PROCESSING` tras una caida. Comprueba el ledger del proveedor de economia antes de elegir.
- Los permisos de resaltado por rango pueden colorear los nombres de producto según `Market.Highlight.Ranks`.

Los datos del mercado se aíslan deliberadamente por backend, incluidos anuncios, depósitos de recuperación, historial y avisos. Permanecen locales incluso con MySQL compartido para impedir que un mismo objeto se compre por separado en dos servidores. Con `Market.Defer_To_Plugins: true`, el mercado integrado se aparta cuando detecta un plugin de mercado dedicado compatible.

## Intercambio directo

`/trade <jugador>` envía una solicitud. El destinatario usa `/trade accept [jugador]` o `/trade deny`. Ambos deben estar conectados en el mismo backend. Cada lado tiene su aportación editable, la columna protegida de la contraparte y una tesorería precisa. Cualquier cambio de objetos o dinero borra ambas confirmaciones y abre una ventana de revisión; el intercambio solo termina cuando ambos sellan el mismo contrato estable.

Desconectarse o cancelar devuelve los objetos depositados a sus propietarios. La entrega entra primero en el alijo durable, por lo que un inventario lleno nunca provoca drops. El staff con `chatalyst.market.manage` puede revisar y reconciliar trades monetarios interrumpidos mediante `/trade settlements list|paid <id>|unpaid <id>`.
