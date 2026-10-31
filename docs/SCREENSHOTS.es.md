# Guion de capturas para la documentación

La web tiene seis espacios de captura. Toma las imágenes con `F2`, copia los PNG finales a
`docs/img/` y conserva exactamente los nombres indicados abajo. Para las capturas de chat usa
una ventana 16:9 y escala de GUI 2 o 3; evita mostrar IPs, licencias, contraseñas o nombres reales.

## Preparación

- Usa tres cuentas de prueba: `AstroAdmin`, `AstroTest` y `AstroTarget`.
- Da OP a `AstroAdmin`. Para mostrar toda la suite de comandos, activa
  `Staff_Moderation.Enabled: true` en `moderation.yml` y reinicia o ejecuta `/cht reload`.
- Para crear varios reportes seguidos:
  `/lp user AstroTest permission set chatalyst.report.bypass true`.
- Deja unas líneas limpias en el chat antes de cada toma. Recorta menús a aproximadamente
  1280×720; la portada puede ser 1600×900.

## 1. `docs/img/chat-hero.png`

1. Con `AstroTest`, sostén una espada o herramienta atractiva y usa
   `/itemname <gradient:#7A5CFF:#00E0FF>Espada Astral</gradient>`.
2. Abre `/chatcolor` y elige un gradiente. Opcional: `/nickcolor` y
   `/tag prefix <gold>[VIP]`.
3. Desde `AstroTest` escribe: `Hola @AstroAdmin, mira mi [item] :heart:`.
4. Toma la foto desde `AstroAdmin`, con el cursor sobre el nombre de `AstroTest`. Deben verse
   el hover de rango, el item, la mención coloreada y el botón `[x]` de moderación.

## 2. `docs/img/commands.png`

1. Como `AstroAdmin`, ejecuta `/cht commands`.
2. Pulsa una vez `[Siguiente →]`; no escribas el número de página.
3. Captura la segunda página completa, incluido el pie con `[← Anterior]` y
   `[Siguiente →]`. Si cabe, deja el cursor sobre un comando para mostrar que es clicable.

## 3. `docs/img/permissions.png`

1. Ejecuta `/cht permissions`.
2. Pulsa una vez `[Siguiente →]`.
3. Pon el cursor sobre uno de los primeros permisos y captura también el pie con ambos botones.
   No pulses el permiso: ese clic prepara el comando de LuckPerms en el chat.

## 4. `docs/img/formats.png`

1. Ejecuta `/formatedit chat`.
2. Pon el cursor sobre la parte `player-name` o `prefix`.
3. Captura la GUI completa con los componentes ordenados y el tooltip visible.

## 5. `docs/img/market.png`

Requiere Vault y un proveedor de economía. Activa `Market.Enabled: true` en `economy.yml` y usa
`/cht reload`. Con objetos distintos en la mano, crea dos o tres anuncios:

```text
/market sell 125
/market auction 300 60
/market sell 75
```

Después ejecuta `/market` y captura el navegador con una venta y una subasta visibles.

## 6. `docs/img/reports.png`

Desde `AstroTest`, con `AstroTarget` conectado, crea tres tickets:

```text
/report AstroTarget spam repetido en el chat
/report AstroTarget publicidad por mensaje
/report AstroTarget insultos a otros jugadores
```

Anota los IDs que devuelve el chat. Desde `AstroAdmin`, deja el primero abierto, reclama el
segundo y pon el tercero en espera:

```text
/reports claim <ID-2>
/reports pending <ID-3> esperando más evidencia
/reports
```

Captura la GUI con los estados **abierto**, **reclamado** y **pendiente**. Los resueltos no se
muestran en esta bandeja de abiertos.
