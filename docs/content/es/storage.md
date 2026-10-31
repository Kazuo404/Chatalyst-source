---
title: Almacenamiento y base de datos
description: Dónde guarda Chatalyst los datos de los jugadores. YAML por defecto, SQLite para un solo servidor o MySQL para compartir datos en una red, con recargas en vivo seguras.
---

Chatalyst guarda datos de los jugadores como muteos, advertencias, apodos, colores de chat, preferencias, correo, reportes y compras. De serie usa archivos YAML y no necesita nada más. Para servidores grandes puedes pasar a SQLite y, en una red, apuntar todos los servidores a la misma base de datos MySQL o MariaDB para que compartan los datos.

Lo que elijas no cambia cómo funciona ninguna función; solo dónde se guardan los datos.

## Elegir dónde guardar

| Sistema | Ajuste | Ideal para |
| --- | --- | --- |
| YAML | `Enabled: false` (por defecto) | Servidores pequeños y medianos. Archivos en `plugins/Chatalyst/data/` |
| SQLite | `Enabled: true`, `Type: SQLITE` | Un solo servidor con muchos datos. Un archivo, `plugins/Chatalyst/data/chatalyst.db`, sin servidor de base de datos |
| MySQL / MariaDB | `Enabled: true`, `Type: MYSQL` | Redes que quieren compartir datos entre servidores |

Paper descarga los controladores de base de datos al arrancar; no van dentro del jar del plugin.

## Configuración

En `config.yml`:

```yaml
Database:
  Enabled: false
  Type: SQLITE
  Host: localhost
  Port: 3306
  Database: chatalyst
  User: root
  Password: ""
  SSL_Mode: PREFERRED
  Network_Shared: true
  Shared_Refresh_Seconds: 5
  Server_Id: ""
```

| Opción | Por defecto | Qué hace |
| --- | --- | --- |
| `Enabled` | `false` | `false` guarda todo en archivos YAML |
| `Type` | `SQLITE` | `SQLITE` o `MYSQL` |
| `Host` / `Port` / `Database` / `User` / `Password` | `localhost` / `3306` / `chatalyst` / `root` / `""` | Conexión a MySQL |
| `SSL_Mode` | `PREFERRED` | `DISABLED`, `PREFERRED`, `REQUIRED`, `VERIFY_CA` o `VERIFY_IDENTITY`. `PREFERRED` usa TLS si el servidor lo ofrece. Usa `REQUIRED` o más para un MySQL remoto |
| `Network_Shared` | `true` | Con un MySQL compartido: `true` comparte los datos entre servidores, `false` mantiene separados los de cada servidor |
| `Shared_Refresh_Seconds` | `5` | Cada cuánto se vuelve a leer una base de datos compartida, como red de seguridad |
| `Server_Id` | `""` | El nombre de este servidor para el almacenamiento. Vacío usa un nombre basado en el puerto |

## Modo red

Con varios servidores en el mismo MySQL, `Network_Shared` decide cómo se comportan:

- **`true`**: reportes, muteos, apodos, correo, tickets y preferencias son los mismos en todos los servidores. Lo mejor para una red.
- **`false`**: cada servidor guarda sus propios datos en la misma base de datos, separados por `Server_Id`.

Dale a cada servidor un `Server_Id` estable y único. Incluso compartiendo, algunos datos se quedan por servidor a propósito, como el [mercado](market), para que la misma publicación no se pueda vender en dos servidores.

Compartiendo, cada servidor vuelve a leer la base de datos cada `Shared_Refresh_Seconds`. Añade [Redis](network#redis) para que los cambios lleguen al instante: un reporte atendido en un servidor se ve en los demás en el momento.

SQLite siempre es por servidor, porque es un archivo local.

## Cómo se protegen los datos

- **Escrituras por lotes.** Los cambios actualizan la memoria al instante y se escriben en segundo plano cada pocos segundos. Un lote que falla se queda en cola y se reintenta, y al apagar se escribe todo.
- **Sin cambios perdidos entre servidores.** Las operaciones que dos servidores podrían hacer a la vez (atender un reporte, responder un ticket, añadir correo) se comprueban contra la base de datos y se reintentan con el valor nuevo en lugar de pisarlo.
- **Archivos atómicos.** Los YAML se escriben primero en un archivo temporal y luego se mueven a su sitio, así que un cierre inesperado nunca deja un archivo a medias. Un archivo que no se puede leer se guarda como `<nombre>.corrupt-<hora>.yml` en lugar de sobrescribirse.
- **Falla cerrado al arrancar.** Si no se puede conectar a la base de datos configurada, Chatalyst no arranca con archivos YAML vacíos. Eso crearía una segunda versión vacía de tus datos y podría pisar la buena cuando MySQL vuelva.

## Recargar y reiniciar

`/chatalyst reload` aplica en vivo, sin reiniciar, estos cambios de MySQL: `Host`, `Port`, `Database`, `User`, `Password`, `SSL_Mode` y `Shared_Refresh_Seconds`. Chatalyst pausa el almacenamiento un momento, escribe todo lo pendiente, prueba la nueva conexión y cambia a ella. Si algo falla, se restaura la conexión anterior.

Estos necesitan reiniciar el servidor, porque cambian dónde están los datos o cómo se llaman: `Enabled`, `Type`, `Network_Shared` y `Server_Id`. La recarga te lo dice y mantiene activo el almacenamiento actual.

## Cambiar de sistema

> [!WARNING]
> Pasar entre YAML, SQLite y MySQL **no** copia tus datos. Haz una copia de seguridad del servidor y mueve los datos tú antes de cambiar `Database.Enabled` o `Database.Type`, o empezarás con los datos de los jugadores vacíos en el sistema nuevo.

## Otras carpetas

| Carpeta | Qué contiene |
| --- | --- |
| `data/` | Los datos de los jugadores al usar YAML, y el archivo de SQLite |
| `logs/` | Registros diarios de auditoría `chat-AAAA-MM-DD.log` (ver [espía y registros](spying#registros-de-auditoria)) |

## Límites

- No hay migración automática entre sistemas.
- Una base de datos compartida sin Redis muestra los cambios de otros servidores como mucho tras `Shared_Refresh_Seconds`, no al instante.
- El cofre compartido del matrimonio es de un solo servidor: con una base de datos compartida, dos cónyuges en servidores distintos abrirían copias separadas.
