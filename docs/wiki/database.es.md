# Almacenamiento y base de datos

De forma predeterminada, Chatalyst guarda todo en archivos YAML dentro de `plugins/Chatalyst/data/`. Activa la base de datos para servidores grandes o para compartir datos en una red.

## Configuración

```yaml
Database:
  Enabled: true
  Type: MYSQL          # SQLITE (archivo local) o MYSQL (servidor compartido)
  Host: db.example.com
  Port: 3306
  Database: chatalyst
  User: chatalyst
  Password: "..."
  # TLS: DISABLED, PREFERRED, REQUIRED, VERIFY_CA, VERIFY_IDENTITY.
  # PREFERRED usa TLS cuando el servidor lo ofrece. Usa REQUIRED o una opción
  # más estricta para cualquier MySQL que no esté en localhost.
  SSL_Mode: REQUIRED
  Network_Shared: true
  Shared_Refresh_Seconds: 5
  Server_Id: "survival-1"
```

`SQLITE` no necesita un servidor externo: escribe en `plugins/Chatalyst/chatalyst.db` y siempre pertenece a un solo backend.

## Modo de red

Con un MySQL compartido, `Network_Shared` determina el comportamiento de los backends:

- `true`: reportes, tickets, correo, silencios, apodos y preferencias se comparten en la red. Los contadores atómicos y las escrituras condicionales protegen los identificadores y las operaciones de lectura-modificación-escritura frente a actualizaciones concurrentes.
- `false`: cada servidor conserva sus propios datos en el mismo MySQL, separados mediante `Server_Id` (se genera a partir del puerto si se deja vacío).

Asigna a cada backend un `Server_Id` único y estable. Cuando los datos no se comparten, separa todos los namespaces. Cuando se comparten, todavía aísla stores deliberadamente locales, como el mercado, para evitar que un mismo anuncio pueda venderse en dos servidores.

Con `Network_Shared: true`, Chatalyst reconcilia cada namespace abierto desde MySQL cada `Shared_Refresh_Seconds` (cinco segundos por defecto), de modo que una caché no puede quedar obsoleta para siempre. Redis sigue siendo recomendable para invalidaciones inmediatas; consulta [Proxy and Redis Sync](proxy-redis.md).

## Fiabilidad

Las escrituras normales actualizan inmediatamente la caché en memoria y se agrupan para persistirse en segundo plano cada pocos segundos. Si falla un batch, permanece en cola para reintentarse; al apagar se realiza un flush síncrono final.

Las operaciones que deben conservar una transición concurrente usan compare-and-set (CAS) inmediato contra la base de datos. Si otro backend modifica primero la fila, Chatalyst actualiza su caché y reintenta sobre el valor nuevo en lugar de sobrescribirlo. Esto se usa, por ejemplo, al reclamar reportes, responder tickets y añadir correo a buzones compartidos.

Los snapshots YAML se escriben primero en un archivo temporal y se mueven atómicamente cuando el sistema de archivos lo admite. En caso contrario, Chatalyst usa una sustitución normal, de modo que nunca escribe el snapshot directamente sobre el archivo activo.

## Cambio de backend

Cambiar entre YAML, SQLite y MySQL **no** importa automáticamente los archivos `data/*.yml`. Haz una copia de seguridad y migra expresamente los valores antes de modificar `Database.Enabled` o `Database.Type`.

Si SQL no está disponible durante el arranque, Chatalyst falla cerrado y no sustituye la base autoritativa por un YAML vacío. Así evita crear una segunda realidad o sobrescribir datos cuando MySQL regrese.

## Reload en caliente

`/chatalyst reload` es seguro mientras el chat está activo: canales, reglas, filtros y formatos se sustituyen atómicamente, por lo que cada mensaje usa la configuración antigua completa o la nueva completa, nunca un estado parcial.

En MySQL también aplica `Host`, `Port`, `Database`, `User`, `Password`, `SSL_Mode` y `Shared_Refresh_Seconds` sin reiniciar. Chatalyst pausa brevemente las operaciones de los stores, vacía toda escritura pendiente, prueba una conexión candidata, cambia la conexión y reconcilia todos los cachés. La conexión anterior solo se cierra después de completar todo. Si la candidata o cualquier reconciliación falla, se restauran y verifican la conexión y los cachés anteriores.

Cambiar `Database.Enabled`, `Database.Type`, `Database.Network_Shared` o `Database.Server_Id` sigue requiriendo reinicio porque modifica el backend o la identidad de los namespaces. El comando lo informa expresamente y mantiene activo el almacenamiento anterior.
