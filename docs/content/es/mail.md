---
title: Correo
description: Un buzón en el juego para jugadores conectados y desconectados, con menú, escritura en libro, amigos, carpeta de spam y aviso al entrar.
---

El correo deja que los jugadores se escriban aunque el otro no esté conectado. La bandeja de entrada es un menú: cada carta enseña la cabeza del remitente, su apodo y un avance del texto. Al pulsarla se abre como un libro escrito con botones de **Responder** y **Borrar**. Para escribir se usa el libro y pluma de siempre.

El correo es independiente de los [Mensajes privados](private-messages). Viene activado y es lo que se sugiere, de fábrica, al pulsar el nombre de un jugador en el chat.

## Cómo se activa

El correo viene encendido en `plugins/Chatalyst/players.yml`:

```yaml
Mail:
  Enabled: true
  Send_Cooldown_Seconds: 3
  Max_Message_Length: 1024
  Max_Messages: 100
  Msg_Fallback: false
  Require_Friend: false
  Notifications:
    Enabled: true
    Toast: true
    Sound:
      Enabled: true
      Key: "minecraft:block.note_block.chime"
      Volume: 0.65
      Pitch: 1.35
```

## Configuración

| Opción | Por defecto | Qué hace |
|---|---|---|
| `Enabled` | `true` | Interruptor general del buzón. |
| `Send_Cooldown_Seconds` | `3` | Segundos de espera entre dos cartas. `0` lo desactiva. |
| `Max_Message_Length` | `1024` | Caracteres máximos por carta. Se mantiene entre 64 y 4096. |
| `Max_Messages` | `100` | Cartas guardadas por carpeta (entrada y spam). Si se llena, se borra la más antigua. `0` o menos = sin límite. |
| `Msg_Fallback` | `false` | Entrega un `/msg` en el buzón si el destinatario apagó los mensajes privados. |
| `Require_Friend` | `false` | `true` = el correo de quien no es tu amigo va a spam en vez de a la bandeja. |
| `Notifications.Enabled` | `true` | Avisa de las cartas nuevas con una línea clicable en el chat y en la barra de acción. |
| `Notifications.Toast` | `true` | Muestra también una notificación tipo logro. |
| `Notifications.Sound.*` | chime, `0.65`, `1.35` | Sonido del aviso, volumen y tono. |

Quien tiene cartas sin leer también recibe el aviso al entrar.

### Amigos, spam y bloqueos

- **Amigos.** Se piden con `/inbox friend <jugador>`. La petición llega con botones de **Aceptar** y **Rechazar**. El menú de amigos enseña las skins reales y los apodos actuales.
- **Carpeta de spam.** Con `Require_Friend: true`, el correo de desconocidos no se rechaza: va a spam para no molestar.
- **Bloqueos.** Cualquier remitente se puede bloquear desde una carta. La lista de bloqueados se gestiona desde el menú de ajustes.
- **Privacidad.** Cada jugador puede dejar de recibir correo desde el menú de ajustes.

## Comandos y permisos

Todos los comandos de correo necesitan `chatalyst.command.mail` (por defecto `true`).

| Comando | Qué hace |
|---|---|
| `/inbox` | Abre la bandeja de entrada. |
| `/inbox spam` | Abre la carpeta de spam. |
| `/inbox friends` | Abre el menú de amigos. |
| `/inbox friend <jugador>` | Envía una petición de amistad. |
| `/inbox accept <jugador>` / `/inbox deny <jugador>` | Responde a una petición de amistad. |
| `/inbox send <jugador> <mensaje>` | Envía una carta desde la línea de comandos. |
| `/inbox read` | Muestra tu bandeja en el chat y la marca como leída. |
| `/inbox reply <mensaje>` | Responde a la carta más reciente. |
| `/inbox clear` | Vacía tu bandeja. |

`chatalyst.bypass.mailcooldown` (op) se salta la espera entre envíos. El comando se llama `/inbox` a propósito: así no choca con el `/mail` de EssentialsX.

## Límites

- El correo se guarda en tu sistema de almacenamiento. Con una base de datos MySQL compartida acompaña al jugador por toda la red: ver [Almacenamiento y base de datos](storage).
- Un jugador silenciado no puede enviar cartas mientras dure el silencio.
- Quien está en [modo anónimo](anonymous) envía el correo con su alias.
