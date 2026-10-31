---
title: Redes con proxy y Redis
description: Usa Chatalyst en una red con Velocity o BungeeCord. Chat entre servidores, paquetes de red firmados, sincronía por Redis y un centro de administración de red para el staff.
---

En una red, Chatalyst hace que tus servidores hablen entre sí. El chat global puede llegar a todos los backends, el staff puede ver el chat de cualquier servidor sin entrar en él, los anuncios pueden ir a toda la red y el staff puede teletransportarse a jugadores de otros servidores. Cada paquete entre servidores va firmado, así que un cliente modificado no puede falsificarlo.

Se usa un único jar universal: instala el mismo `Chatalyst-<versión>.jar` en todos los backends Paper/Purpur y, si usas el modo nativo, también en la carpeta `plugins` del proxy. Paper, Velocity y BungeeCord cargan cada uno solo su parte.

Todos los ajustes están en `network.yml`.

## Elegir el transporte

```yaml
Proxy:
  Enabled: false
  Mode: legacy
```

| Modo | Qué instalas | Ideal para |
| --- | --- | --- |
| `legacy` | Nada en el proxy | Redes sencillas. Usa el reenvío que ya trae BungeeCord; necesita un jugador conectado en el servidor que envía |
| `native` | El mismo jar en la carpeta `plugins` del proxy | Recomendado. Nombres de servidor automáticos, teletransporte entre servidores, directorio de staff |
| `redis` | Un servidor Redis | Los mensajes llegan aunque un servidor no tenga jugadores |
| `both` | El jar en el proxy y Redis | Solo para migrar o probar; puede duplicar mensajes |

Un canal de chat solo cruza servidores si tiene `Proxy: true` en `chat.yml` (ver [canales](channels)). Los canales locales (mundo, región, grupo, frecuencia) se quedan en su servidor salvo que los marques.

## Seguridad de la red

Cada paquete entre servidores va firmado (HMAC-SHA256) y los paquetes viejos se rechazan, así que nadie puede falsificar anuncios, mensajes de soporte o acciones del staff desde un cliente modificado.

```yaml
Proxy:
  Network_Secret: ""
```

- **Velocity con reenvío moderno**: no hay que hacer nada. La clave se deriva del secreto de reenvío en cada backend y en el proxy.
- **Modo legacy o Redis sin reenvío de Velocity**: la clave sale de `Redis.Shared_Secret` si está puesto.
- **BungeeCord en modo nativo** (o cualquier otro caso): pon el mismo valor largo y aleatorio (16 caracteres como mínimo, 32 o más recomendado) como `Network_Secret` en todos los backends, y en `plugins/chatalyst/network-secret.properties` en el proxy:

```properties
network-secret=cambia-esto-por-un-secreto-largo-y-aleatorio
```

> [!DANGER]
> Sin clave, el reenvío por la red queda **bloqueado**. Chatalyst nunca acepta mensajes de red sin firmar. Reinicia todos los backends y el proxy después de cambiar la clave.

## Nombres de servidor

```yaml
Proxy:
  Server_Discovery:
    Mode: auto
    Manual_Server_Id: ""
```

| Opción | Por defecto | Qué hace |
| --- | --- | --- |
| `Mode` | `auto` | Con el puente nativo, cada backend aprende su nombre exacto del proxy (`lobby`, `survival`…) cuando entra el primer jugador |
| `Manual_Server_Id` | `""` | Con `Mode: manual`, el nombre que usa este backend |

Antes de que entre el primer jugador, un backend usa temporalmente `Database.Server_Id`, luego `Redis.Server_Id` y luego `server-<puerto>`. Esto nunca cambia el nombre de los datos del [almacenamiento](storage).

## Redis

```yaml
Redis:
  Enabled: false
  Host: "127.0.0.1"
  Port: 6379
  Password: ""
  Database: 0
  Channel: "chatalyst:network"
  Server_Id: ""
  TLS: false
  Shared_Secret: ""
```

| Opción | Por defecto | Qué hace |
| --- | --- | --- |
| `Host` / `Port` / `Password` / `Database` | `127.0.0.1` / `6379` / `""` / `0` | Tu conexión a Redis |
| `Channel` | `chatalyst:network` | Tiene que ser el mismo en todos los backends |
| `Server_Id` | `""` | Único por backend. Vacío usa `<nombre-servidor>-<puerto>` |
| `TLS` | `false` | Cifra la conexión. Actívalo siempre que Redis no esté en la misma máquina |
| `Shared_Secret` | `""` | **Obligatorio.** El mismo valor en todos los backends (16+ caracteres). Redis no se activa hasta que lo pongas |

Cada mensaje de Redis va firmado y con marca de tiempo, así que solo tus servidores pueden publicar y los mensajes repetidos se rechazan. Si los mensajes dejan de llegar, comprueba que todos los backends tienen exactamente el mismo secreto y que sus relojes no se separan más de cinco minutos.

Con una base de datos MySQL compartida, Redis también avisa al momento a los demás servidores cuando cambian datos, como un reporte atendido o un correo enviado (ver [almacenamiento](storage)).

## Centro de administración de red

`/chatalyst admin` (o `/cht gui`) incluye una vista de red para el staff. Muestra todos los servidores, su población y el chat reciente de cada uno, para que puedas leer lo que pasa en un servidor sin entrar en él.

```yaml
Network_Admin:
  Monitor:
    Enabled: true
    History_Per_Server: 100
    Retention_Minutes: 60
    Max_Live_Subscriptions: 2
    Live_Format: "<#7B68A0>[<#B56CFF>{server}<#7B68A0>] <#C0B3D9>#{channel} <#FBF2FF>{player}<#C0B3D9>: <#FBF2FF>{message}"
  Announcements:
    Enabled: true
    Max_Message_Length: 512
```

| Opción | Por defecto | Qué hace |
| --- | --- | --- |
| `Monitor.Enabled` | `true` | Activa el monitor de chat |
| `History_Per_Server` | `100` | Mensajes guardados por servidor |
| `Retention_Minutes` | `60` | Cuánto tiempo se guardan |
| `Max_Live_Subscriptions` | `2` | Cuántos chats de servidor puede reflejar a la vez un miembro del staff en su propio chat |
| `Live_Format` | ver arriba | Cómo se ve un mensaje reflejado |
| `Announcements.Enabled` | `true` | Permite que `/announce` llegue a todos los servidores |
| `Announcements.Max_Message_Length` | `512` | Longitud máxima de un anuncio de red |

El monitor solo transmite el chat público y de canales que gestiona Chatalyst. Nunca captura comandos, contraseñas, mensajes privados ni correo, y el historial vive solo en memoria.

El centro de administración también tiene:

- **Chats en directo**: refleja el chat de otro servidor en el tuyo (clic derecho en la tarjeta del servidor).
- **Directorio de staff**: todo el staff conectado, su nivel, servidor y mundo, con teletransporte en un clic.
- **Jugadores por servidor**: cabezas de jugador con rango, dinero, mundo, modo de juego, ping y estado AFK; haz clic para teletransportarte, aunque esté en otro servidor.

Los jugadores en vanish nunca aparecen en estos directorios. El teletransporte entre servidores necesita el modo `native`.

### Permisos

| Permiso | Qué permite |
| --- | --- |
| `chatalyst.admin.network` | Todo lo de abajo (op por defecto) |
| `chatalyst.admin.network.monitor` | Abrir el monitor de chat |
| `chatalyst.admin.network.monitor.server.*` | Todos los servidores; `...server.survival` permite solo `survival` |
| `chatalyst.admin.network.monitor.channel.*` | Todos los canales; si no, los canales restringidos piden su permiso normal |
| `chatalyst.admin.network.announce` | Enviar `/announce` a toda la red |
| `chatalyst.admin.staff.directory` / `.teleport` | Ver el directorio de staff / teletransportarse al staff |
| `chatalyst.admin.players.directory` | Ver los jugadores por servidor |
| `chatalyst.admin.players.server.*` | Ver los jugadores de todos los servidores |
| `chatalyst.admin.players.teleport` | Teletransportarse al hacer clic en una cabeza |

## Comandos de reglas en otros servidores

Una [regla](rules-engine) puede ejecutar un comando de consola en otro servidor concreto con `Actions.Proxy_Commands`. Como eso es acceso remoto a la consola, viene apagado y cada comando tiene que estar permitido en **todos** los servidores que lo reciben:

```yaml
Proxy:
  Rule_Commands:
    Enabled: false
    Allowed_Commands: [broadcast, say]
```

Solo se aceptan los nombres de comando de la lista; no se admiten comodines. Cada ejecución queda en el registro de auditoría.

## Límites

- Los modos `legacy` y `native` necesitan al menos un jugador conectado en el servidor que envía para llevar el mensaje. Usa `redis` si necesitas que los mensajes circulen con servidores vacíos.
- Una red solo con Redis no puede ver la lista de servidores del proxy, así que un servidor aparece en el centro de administración cuando ha tenido algo de actividad. Usa nombres manuales si los necesitas fijos.
- El teletransporte entre servidores solo funciona con el puente nativo en el proxy.
- La mesa de ayuda del staff, que reparte las preguntas por la red, está en [reportes y soporte](reports#peticiones-de-ayuda-al-staff).
