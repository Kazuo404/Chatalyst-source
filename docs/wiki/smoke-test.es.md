# Checklist de smoke test

Ejecuta esto en un servidor real de pruebas antes de cada release. Cubre el riesgo residual que
la auditoría estática no puede cerrar: schedulers reales de Paper/Folia, drivers reales de
SQLite/MySQL y recorridos completos de comandos/GUI.

## Matriz de entorno

Repite el flujo básico en cada fila que aplique a tu despliegue:

| Servidor | Java | Almacenamiento |
|---|---|---|
| Paper 1.21.1 | 21 | SQLite (por defecto) |
| Paper 1.21.11 | 21 | MySQL |
| Paper 26.2+ | 25 | SQLite o MySQL |
| Folia última | 21+ | SQLite o MySQL |

Opcional: Velocity o BungeeCord delante con `Network_Secret` configurado, y una pasada con el
modo de red Redis.

## 1. Ciclo de vida

- [ ] Arranque limpio: aparece el banner, la licencia valida, sin warnings ni stack traces.
- [ ] `/chatalyst reload` termina y el chat sigue renderizando después.
- [ ] MySQL: cambia `SSL_Mode` o rota la contraseña y ejecuta `/chatalyst reload`; debe confirmar
      la reconexión y conservar nicks, sanciones, correo y mercado.
- [ ] MySQL: prueba temporalmente credenciales incorrectas; el reload debe fallar solo para la
      candidata, registrar que verificó la conexión anterior y mantener operativo el storage.
- [ ] Cambiar temporalmente `Network_Shared` debe indicar «requiere reinicio» sin cambiar los
      namespaces activos. Restaura el valor antes de continuar.
- [ ] Apagado limpio (`stop`): sin errores ni spam de tareas canceladas; en el siguiente arranque
      no hay avisos de journals de recuperación salvo que hubiera un crash real.

## 2. Núcleo de chat

- [ ] Chat global: renderizado por espectador, menciones e historial (`/ch history`).
- [ ] Cambia canal de escritura/escucha y silencia un canal; reinicia y verifica que la selección
      persistió.
- [ ] Chat de frecuencia entre dos jugadores en la misma frecuencia.
- [ ] La traducción está apagada por defecto; `/toggle translate` la activa y llegan líneas
      traducidas.

## 3. Moderación (dos cuentas; repite en Folia)

> **Opt-in:** la suite de staff ahora viene con `Staff_Moderation.Enabled: false` para que
> Chatalyst no compita con otro plugin de staff. Ponlo en `true` en `moderation.yml` antes de
> esta sección; desactivado, `/staff`, `/ban`, `/vanish`… ni siquiera se registran. Con
> `Command_Priority: true` (default) los comandos de staff de Chatalyst responden aunque otro
> plugin registre las mismas etiquetas.

> **Trampa con OPs:** con el `Staff_Moderation.Exempt_Ops: true` por defecto, los operadores
> no pueden ser castigados ni congelados y siempre ven al staff vanisheado. Quita el OP a la
> cuenta objetivo o pon `Exempt_Ops: false` (y `Vanish.Ops_See_Vanished: false`) en
> `moderation.yml` antes de probar; si no, cada check de abajo dirá "objetivo protegido".

- [ ] `/mute`, `/unmute`, `/ban`, `/unban`, `/kick`, `/freeze`, `/vanish <otro>` desde una cuenta
      de staff contra un jugador en *otra* región (lejos). Sin `IllegalStateException` ni errores
      de región en consola; la respuesta vuelve al moderador.
- [ ] Caja de herramientas: `/invsee` y `/echest` abren revisiones de solo lectura (también con
      los botones de cofre y ender chest en `/players` → gestionar), `/seen` y
      `/check` responden para objetivos conectados, offline y en vanish, `/tpto` y `/tphere`
      teletransportan entre regiones sin errores en consola (alias en español: `/verinv`,
      `/verender`, `/visto`, `/checar`, `/irhacia`, `/traer`).
- [ ] Freeze duro: el congelado queda COMPLETAMENTE estático (ni caminar, ni saltar, ni mover la
      cámara) y ve el aviso completo (título + panel + sonido) incluso siendo OP con
      `Exempt_Ops: false`. Cualquier desconexión (quit, se le va el internet, cierra el juego)
      banea permanente tras la gracia corta (`Disconnect_Grace_Seconds`, default 3).
- [ ] Dashboard de staff → Jugadores abre la lista de moderación: CUALQUIER clic en una cabeza va
      directo al menú de gestión (nada de menú social de mensaje/ignorar). Con
      `chatalyst.command.invsee.edit` / `echest.edit`, los botones de cofre abren vistas
      EDITABLES cuyos cambios se aplican al cerrar.
- [ ] Flujo de reportes sin comandos: abre un reporte → **Moderar** → congela al objetivo con un
      motivo preset estando DESCONECTADO (los motivos personalizados se escriben en el chat y
      nunca salen al chat público). En su próximo login le aparece el aviso de freeze y no puede
      moverse; si se vuelve a desconectar, ban permanente (FreezeGuard). El motivo elegido debe
      quedar en el log de auditoría.
- [ ] Visibilidad del freeze: congelar (o que un congelado entre) avisa a todo el staff con el
      motivo y un botón clicable **[IR HACIA ÉL]**; clic IZQUIERDO (golpear) sobre un congelado
      no hace daño y muestra quién lo congeló y por qué (el clic derecho queda intacto); el
      botón de hielo del dashboard abre la lista de congelados (clic teletransporta, shift+clic
      libera).
- [ ] Cobertura del vanish: un staff en vanish desaparece del mundo, del tab, de los mensajes de
      entrada/salida, de los nametags, de todas las listas y menús, Y del ping de la lista de
      servidores (el contador "x/y" y los nombres del hover no deben incluirlo).
- [ ] Pantalla de ban: al intentar entrar, el baneado ve la pantalla completa — motivo, staff,
      tiempo restante desglosado (años/meses/días/horas/minutos, recalculado en cada intento)
      y la fecha exacta en que se levanta; los permanentes dicen "nunca".
- [ ] Baneo de conexión: `/banip <jugador>` (o el alcance "Cuenta + conexión" del compositor)
      expulsa a todos los que compartan la conexión; otra cuenta entrando desde ella es
      rechazada Y auto-baneada (`Ip_Ban.Ban_New_Accounts`), al staff le llega la alerta de
      IpGuard, `/check` muestra el baneo de conexión y `/unbanip` retira solo la parte de
      conexión.
- [ ] Compositor de baneo: el botón Banear abre el compositor — cicla la duración, cicla motivos
      preset o shift+clic para escribir uno con estilos (`<rainbow>`, `&4`, estilos del server),
      cicla el estilo, y la vista previa debe coincidir con la pantalla real que ve el baneado.
- [ ] Desmutear/desbanear a alguien sin sanción: el mensaje debe decir "no existe", no un error de
      almacenamiento — y al revés cuando la base de datos esté caída.
- [ ] `/chatlog <fecha>` sobre un log grande responde sin congelar el tick.

## 3b. Registro de cofres, compartir inventario y modo anónimo

- [ ] Abre un cofre, coloca/rompe un barril y usa una palanca con la cuenta B; con `/spy`
      y `chatalyst.spy.container` en la cuenta A llegan las líneas en vivo con coordenadas.
- [ ] `/spy <cuenta B>`: solo llegan sus líneas (prueba con una tercera cuenta que no
      debe aparecer, y con un MP suyo); `/spy all` vuelve a verlo todo. El menú de spy
      muestra la lista de vigilados.
- [ ] Con el spy APAGADO, `/spy <cuenta B>` y luego `/spy <cuenta B>` otra vez: el spy
      queda APAGADO (no global). Repite con el spy ya encendido en global antes: al quitar
      el último vigilado vuelve a global. El mensaje distingue ambos casos.
- [ ] Desde GUI: en `/players` → gestionar, el botón **Vigilar** añade/quita a ese jugador
      (el icono cambia a diana). En `/staff`, click derecho sobre el spy lo enciende y
      apaga, y el lore refleja estado y alcance.
- [ ] `/containerlog` muestra las entradas; **[Ver]** abre el contenido del cofre (y las
      shulkers de dentro con un click), **[Ir]** teletransporta al bloque exacto y
      **[TP]** hasta el jugador. Rompe el cofre y pulsa **[Ver]**: avisa de que ya no hay
      contenedor. `/containerlog <jugador> hoy` relee el archivo del día.
- [ ] `/invsee` (solo lectura) sobre un jugador con una shulker: el click abre su
      contenido y la flecha vuelve. Igual dentro de `/echest`.
- [ ] Con `invsee.edit`: **click derecho** (cursor vacío) sobre una shulker abre su
      interior; el izquierdo la sigue cogiendo con normalidad.
- [ ] Abre el mismo cofre 10 veces seguidas: UNA línea de spy; pasado `Repeat_Seconds`,
      la siguiente indica «+9 repetidas». Abre varios aldeanos: no se repiten líneas
      idénticas al moverse. Craftea, encanta y cierra un trato: salen sus líneas, y la del trato muestra
      qué se llevó y qué pagó.
- [ ] Con `invsee.edit`: cambia una pieza de armadura puesta y la segunda mano desde la
      fila inferior; al cerrar, el objetivo la lleva puesta.
- [ ] `/clearinventory <jugador>` limpia almacén, armadura, segunda mano y cursor;
      `all` limpia también el ender chest; un modo con typo no borra nada.
- [ ] Cuenta B escribe `[inv]` y `[ec]`: cualquiera puede abrir la vista 5 minutos; tras
      caducar, el botón responde «caducó». Las shulkers de dentro se abren con click.
- [ ] `/anonymous` con la cuenta B: alias con pinta de jugador real en chat/tab/nametag,
      sin rango ni
      tags (también con formatos que usan `%luckperms_prefix%`), skin de incógnito para
      la cuenta A y cabeza disfrazada en `/players`. El lore con el nombre real solo lo
      ve quien tiene el permiso de `/realname`.
- [ ] Anónimo: `/nick off` queda bloqueado con aviso; relog y sigue anónimo pero con un
      alias Y skin DISTINTOS (Rotate_Name_On_Join); muerte en
      PvP muestra el alias; si está activo el bot de Discord, publica el alias dentro del
      mensaje formateado del bot.
- [ ] Anónimo con `/chatcolor` puesto: sus mensajes salen SIN ese color (no se le
      reconoce por el color). `/anonymous off` + on en la misma sesión devuelve el MISMO
      alias y skin; tras relog, distintos.
- [ ] Estando anónimo, SAL del servidor: el mensaje de salida (y el de Discord si lo
      tienes) debe anunciar el ALIAS, nunca tu nombre real. Lo mismo con solo `/nick`.
- [ ] `/anonymous off` restaura apodo, rango, color de chat y skin originales exactos.

- [ ] Con PacketEvents activo: `/tp`, `/xp query`, `/kill`, `/give` y `/gamemode` salen
      con el estilo del plugin (»  y colores) y en el idioma de cada cliente. Pon
      `Vanilla_Messages.Enabled: false` y vuelven los vanilla. Sin PacketEvents, aviso
      informativo en consola y ningún error.

## 4. Durabilidad de preferencias

- [ ] Configura nick, tag, color/estilo de chat y toggles; reinicia el servidor; todo sobrevive.
- [ ] Con MySQL: para la base de datos e intenta cambiar una preferencia — el comando debe
      reportar fallo y el valor antiguo debe permanecer (nada de éxito solo en cache).
- [ ] `/itemname` consume exactamente una cuota por renombrado; dos renombrados rápidos nunca
      superan el límite.

## 5. Correo y amigos

- [ ] Envía, acepta y rechaza solicitudes de amistad desde la GUI; verifica que la identidad
      aceptada es la clicada (añade antes varias solicitudes pendientes).
- [ ] Bloquear/desbloquear y los cambios de privacidad persisten tras reiniciar.

## 6. Mercado y trade (con economía Vault)

- [ ] Publica un item y cómpralo desde una segunda cuenta; se mueven dinero e item; el historial
      lo registra.
- [ ] Trade directo con doble aceptación; modifica la oferta tras un accept — las confirmaciones
      deben reiniciarse.
- [ ] Mata el servidor (`kill -9` / finalizar tarea) en mitad de un trade y reinicia: sin items
      duplicados ni perdidos; los sobrantes caen al alijo; `/market settlements` muestra lo que
      requiera operador.

## 7. Reportes y reglas

- [ ] Dos jugadores usan `/report` a la vez: los IDs son únicos y crecen de forma monótona.
- [ ] Un contador de data store de reglas incrementa correctamente tras un reinicio.

## 8. Red (si hay proxy)

- [ ] Sin secreto configurado, el transporte de plugin messaging debe negarse a arrancar
      (fail-closed).
- [ ] Con el secreto, los mensajes privados cross-server y el handoff de staff funcionan en ambas
      direcciones.

## 9. Compras y vinculación obligatoria (trabajo del 2026-07-27)

Todo lo de abajo se reescribió hace poco y **nada de ello se ha ejecutado en un servidor real**.
Son exactamente los caminos donde una auditoría independiente encontró fallos, así que son los
que merece la pena recorrer. Hazlo con `Store.Enabled: true`, un producto de cada tipo y un
paquete de Tebex en sandbox.

### 9a. El carrito con varios paquetes — la comprobación más valiosa

El `{transaction}` de Tebex identifica el **pago**, no el paquete, así que un carrito con dos
productos ejecuta dos comandos con el mismo id. Antes solo se entregaba el primero.

- [ ] Compra **dos productos distintos en un mismo pago** (sandbox, o ejecuta los dos comandos a
      mano con el *mismo* id de transacción: `chatalyst store give <uuid> kit-a tbx-test-1` y
      `chatalyst store give <uuid> kit-b tbx-test-1`).
- [ ] `/store` muestra **dos** compras pendientes, no una.
- [ ] Se entregan las dos. Ninguna se reporta como duplicada.
- [ ] Repite con dos paquetes de **rango** en un pago: los dos rangos llegan a LuckPerms.

### 9b. Inventario lleno — nada puede acabar en el suelo

- [ ] Deja el inventario con dos huecos. Compra un producto de tres stacks.
- [ ] `/store claim` lo rechaza con el mensaje de hacer espacio. **No cae nada al suelo.**
- [ ] La compra sigue apareciendo en `/store` después.
- [ ] Libera huecos y reclama otra vez: se entrega exactamente una vez.
- [ ] Con tres compras de un objeto y dos huecos, reclama todo: se entregan dos y la tercera
      queda pendiente. Mira el suelo a tu alrededor: tiene que estar limpio.

### 9c. Desconectarse a mitad de la entrega

- [ ] Con una compra pendiente, entra y sal dentro de la ventana de `Join_Delay_Ticks`.
- [ ] Vuelve a entrar: la compra sigue debiéndose y se entrega. Nunca se entrega dos veces.

### 9d. Reinicio con trabajo pendiente

- [ ] Deja una compra pendiente y un derecho de rango activo, y reinicia el servidor.
- [ ] `/chatalyst store status` sigue contando la compra pendiente.
- [ ] `/chatalyst tebex status` sigue contando la compra registrada.
- [ ] El rango sigue aplicado en LuckPerms tras el reinicio.

### 9e. Entrega con LuckPerms sin cargar

- [ ] Ejecuta un grant de rango en los primeros segundos del arranque (o con LuckPerms apagado).
- [ ] Queda **registrado**, no rechazado, y el rango aparece cuando corre el reconciliador.

### 9f. Caducidad y reembolsos

- [ ] Entrega un rango con `Tebex_Duration` corto (p. ej. `2m`). Comprueba que LuckPerms quita
      los permisos al segundo exacto por su cuenta.
- [ ] Dentro de un intervalo de barrido, el rol de Discord también desaparece.
- [ ] Compra un rango dos veces (dos transacciones) y revoca la **primera**: el rango sobrevive.
- [ ] Da un rango a un jugador que TAMBIÉN tenga ese grupo puesto a mano por un admin con
      contexto (p. ej. `server=survival`). Deja caducar la compra: el grupo con contexto del
      admin debe seguir ahí.

### 9g. Vinculación obligatoria — siempre debe fallar en abierto

- [ ] Con `Required.Enabled: true` y el bot conectado: al no vinculado se le retiene, recibe un
      código automáticamente y no puede chatear ni usar comandos salvo `/discord`.
- [ ] Completar `!link CODIGO` en Discord lo libera al instante.
- [ ] **Apaga el bot** (vacía el token y recarga) con alguien retenido: se le libera en el
      siguiente ciclo del recordatorio, con explicación. Nadie se queda encerrado.
- [ ] Quita `chatalyst.command.discord` a un jugador de prueba: **no** debe ser retenido.
- [ ] Con AuthMe o nLogin instalado, el aviso aparece solo después del `/login`, nunca antes.

### 9i. La vitrina por secciones

Viene apagada, así que lo primero es comprobar que nada cambió hasta que la enciendes.

- [ ] Con `Store_Sections.Enabled: false`, `/ranks` es la misma lista plana de antes.
- [ ] Enciéndela con dos secciones y da `Shop.Category` a un rango y a un objeto.
- [ ] `/ranks` abre ahora en las secciones; cada una muestra cuántas entradas tiene.
- [ ] Al entrar en una sección solo salen sus entradas; el botón de abajo a la izquierda
      vuelve a las secciones en vez de cerrar el menú.
- [ ] Una sección cuyas entradas estén todas ocultas por `View_Permission` no aparece.
- [ ] Pon a una entrada una `Category` que no exista: debe salir en "Otros", **no**
      desaparecer de la tienda.
- [ ] Un objeto no muestra duración ni "ya lo tienes"; un rango sigue mostrando ambos.
- [ ] Al hacer clic en cualquier tarjeta llega el botón seguro de compra de esa entrada.

### 9h. Reubicación de config (solo en un servidor que venga de una build anterior)

- [ ] Tras el primer arranque, `nametag.yml` existe con tus valores anteriores de `Nametag`, y
      `integrations.yml` ya no contiene esa sección.
- [ ] Existe `integrations.yml.pre-move.bak`.
- [ ] `/chatalyst features` lista los interruptores de nametag apuntando a `nametag.yml`.

## Evidencia

Captura en cada pasada: software y build del servidor, versión de Java, backend de
almacenamiento, el log de arranque desde el banner de Chatalyst hasta el primer join, y cualquier
warning de consola. Registra los fallos contra el hallazgo correspondiente en
[audit-2026-07-15.es.md](audit-2026-07-15.es.md).
