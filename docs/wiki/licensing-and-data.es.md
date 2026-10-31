# Licencia, DRM y datos

Chatalyst usa un sistema de licencia. Esta página dice exactamente qué hace, para que puedas
decidir antes de comprar y para que puedas contar a tus jugadores qué guarda tu servidor.

## Comprobación de licencia (DRM)

- Cada compra tiene una clave de licencia, y la misma clave sirve en todos tus servidores.
- El servidor necesita internet para llegar al servicio de licencias.
- Lo contacta al arrancar, luego cada 6 horas para comprobar que la clave no está revocada, y una
  vez al día para avisar de que sigue en marcha.
- Una caída corta no cambia nada: una comprobación correcta vale 7 días.
- **Chatalyst se apaga solo si pasa 7 días sin una comprobación válida o si la clave está
  revocada.** Tampoco arranca si sus archivos firmados fueron modificados.

## Qué se envía al servicio de licencias

- Clave de licencia y un id de instalación aleatorio
- IP y puerto del servidor, y la IP que ve el servicio
- Software y versión del servidor, versión de Chatalyst y marca de la compilación
- MOTD, modo online y jugadores conectados y máximos
- Sistema operativo, versión de Java, núcleos y límite de memoria

No se envían nombres de jugadores, mensajes de chat, IP de jugadores ni archivos del servidor. Una
copia modificada informa de esos mismos datos del servidor una vez.

## Qué guarda Chatalyst en tu servidor

Todo esto se queda en tus archivos o en tu base de datos.

- **Conexiones de jugadores:** con el rastreo de multicuentas activo, se guardan las IP y su
  relación con las cuentas para moderar. Se borran tras
  `Staff_Moderation.Alts_Retention_Days` (365 por defecto). Con `Track_Alts: false` solo se guarda
  la última conexión, y con 0 días las filas se conservan siempre.
- **Registros de chat:** archivos diarios, si el registro está activado.
- **Registros de moderación:** baneos, silencios, advertencias y su historial.

Como dueño del servidor, tú decides qué conservar y eres responsable de informar a tus jugadores.

## Conexiones salientes opcionales

Solo funcionan si las activas: el bot y los webhooks de Discord, Tebex y otras tiendas, Redis,
MySQL, la traducción en vivo y la comprobación de actualizaciones.

## Componentes de código abierto

Mira `META-INF/THIRD-PARTY-NOTICES.md` dentro del jar.
