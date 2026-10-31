---
title: Mensajes privados
description: /msg y /reply instantáneos entre jugadores conectados, con lista de ignorados, interruptor personal, espía para el staff y paso opcional al correo.
---

Los mensajes privados son conversaciones instantáneas, de uno a uno, entre jugadores conectados en el mismo servidor. Traen una lista de ignorados que se guarda, un interruptor personal para dejar de recibirlos, sus propios formatos y, si quieres, visibilidad para el staff mediante el espía.

Los mensajes privados vienen **apagados**. Muchos servidores prefieren usar solo el [Correo](mail), que también llega a quien está desconectado. El correo sigue funcionando tanto si los mensajes privados están encendidos como si no.

## Cómo se activa

En `plugins/Chatalyst/players.yml`:

```yaml
Private_Messages:
  Enabled: false
```

Pon `Enabled: true` y ejecuta `/cht reload`. Mientras esté en `false`, `/msg`, `/reply` e `/ignore` solo responden que los mensajes privados están desactivados.

## Configuración

| Opción | Archivo | Por defecto | Qué hace |
|---|---|---|---|
| `Private_Messages.Enabled` | `players.yml` | `false` | Interruptor general de `/msg`, `/reply` e `/ignore`. |
| `Mail.Msg_Fallback` | `players.yml` | `false` | Manda el `/msg` al buzón del destinatario si este apagó los mensajes privados. |
| `Chat.Name_Click_Prefer` | `chat.yml` | `mail` | Con `msg`, al pulsar un nombre en el chat se sugiere `/msg <jugador>` (solo con los privados encendidos). |
| `Chat.Ignore.Hide_In_Chat` | `chat.yml` | `true` | `/ignore` también oculta el chat público de ese jugador tras una marca «[ver]». |

### Formatos

Quien envía y quien recibe ven formatos distintos, guardados en `formats/pm-sender.yml` y `formats/pm-receiver.yml`:

```yaml
Parts:

  from:
    Message: "<#FBF2FF>{sender_name} <#7B68A0>» <reset>"
    Hover:
      - "<#C0B3D9>{$pm-reply} {sender_name}"
    Suggest_Command: "/r "

  message:
    Message: "<#C0B3D9>{message}"
```

Placeholders: `{sender_name}`, `{receiver_name}` y `{message}`. El espía del staff usa `formats/spy.yml`. Todos los campos de una parte están en [Formatos de chat](formats).

### Reglas de entrega

| Situación | Qué pasa |
|---|---|
| El dueño desactivó los mensajes privados | `/msg`, `/reply` e `/ignore` no están disponibles. |
| El destinatario los apagó (`/toggle pm`) | No se entrega. Con `Mail.Msg_Fallback: true` va a su buzón y se avisa al remitente. |
| El destinatario ignora al remitente | Se descarta sin avisar. Nunca pasa al correo. |
| El remitente está silenciado | Los mensajes privados quedan bloqueados mientras dure el silencio. |
| El destinatario está en vanish | Solo lo alcanza el staff con `chatalyst.bypass.reach`. |

El staff con `chatalyst.bypass.reach` llega a quien le ignora o tiene los mensajes privados apagados.

### Paso al correo

```yaml
  Msg_Fallback: false
```

Si lo enciendes, un `/msg` a quien apagó los mensajes privados acaba en su buzón. Se siguen aplicando todas las reglas del correo: su ajuste de recibir correo, los remitentes bloqueados, la carpeta de spam y la espera entre envíos.

## Comandos y permisos

| Comando | Permiso | Qué hace |
|---|---|---|
| `/msg <jugador> <mensaje>` (`/m`, `/w`, `/tell`, `/whisper`) | `chatalyst.command.msg` (true) | Envía un mensaje privado. |
| `/reply <mensaje>` (`/r`) | `chatalyst.command.msg` (true) | Responde a tu última conversación. |
| `/ignore <jugador>` | `chatalyst.command.ignore` (true) | Ignora o deja de ignorar a un jugador. La lista se guarda. |
| `/toggle pm` | `chatalyst.command.toggle` (true) | Deja de recibir mensajes privados o vuelve a recibirlos. |
| `/spy` | `chatalyst.spy` o `chatalyst.spy.pm` (op / false) | Staff: ver los mensajes privados de otros. Ver [Espía, registros y combate](spying). |

`chatalyst.bypass.reach` (op) deja al staff escribir a quien le ignora, tiene los privados apagados o está en vanish. Donde se pide un nombre de jugador también vale su apodo.

## Límites

- Los mensajes privados solo llegan a jugadores conectados en el mismo servidor. Para desconectados o para otros servidores, usa el [Correo](mail).
- Un mensaje privado no se guarda. Si necesitas constancia, activa el registro de privados: ver [Espía, registros y combate](spying).
- A quien está en [modo anónimo](anonymous) el otro lo ve con su alias. El espía del staff sigue mostrando el nombre real.
