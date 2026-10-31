---
title: Menciones
description: Suena un aviso y se resalta el nombre cuando alguien te menciona en el chat, por nombre real o apodo, y cada jugador puede apagarlo.
---

Las menciones hacen que nadie se pierda cuando le hablan. Cuando un mensaje nombra a un jugador, ese jugador oye un sonido y ve su propio nombre resaltado en la línea. Nadie más ve el resaltado.

Funciona con el nombre real y con el apodo, porque muchas veces el apodo es el único nombre que conocen los demás. También funciona con el alias de quien está en [modo anónimo](anonymous).

## Cómo se activa

Las menciones vienen activadas. La configuración está en `plugins/Chatalyst/chat.yml`:

```yaml
Mentions:
  Enabled: true
  # Seconds between mention pings for the SAME mentioned player (0 = no cooldown).
  Cooldown_Seconds: 0
  Require_At: false
  Sound: "minecraft:block.note_block.pling"
  # Highlight the mentioned player's name in the chat (only they see their name highlighted).
  Highlight:
    Enabled: true
    Color: yellow
    Bold: true
```

Tras editar, ejecuta `/cht reload`.

## Configuración

| Opción | Por defecto | Qué hace |
|---|---|---|
| `Enabled` | `true` | Enciende o apaga el sonido de mención. |
| `Cooldown_Seconds` | `0` | Segundos mínimos entre dos avisos al mismo jugador. `0` = sin espera. Máximo una hora. |
| `Require_At` | `false` | `true` = solo cuenta `@nombre`. `false` = también cuenta el nombre suelto. |
| `Sound` | `minecraft:block.note_block.pling` | Clave del sonido con namespace. |
| `Highlight.Enabled` | `true` | Resalta el nombre mencionado en la línea, solo para el mencionado. |
| `Highlight.Color` | `yellow` | Color del resaltado. |
| `Highlight.Bold` | `true` | Pone en negrita el nombre resaltado. |

### Cómo se detecta una mención

- El nombre tiene que aparecer como palabra completa. `@Notch`, `Notch,` y `notch.` cuentan. `Notchy` no.
- Da igual mayúsculas o minúsculas.
- Solo se avisa a quien recibe el mensaje de verdad. Un jugador en otro [canal](channels) o en otra frecuencia de radio no oye nada.
- Quien escribe nunca se avisa a sí mismo.

## Comandos y permisos

| Comando | Permiso | Qué hace |
|---|---|---|
| `/toggle mentions` | `chatalyst.command.toggle` (true) | Cada jugador puede apagar para sí el sonido de mención. |

## Menciones al staff

Pedir staff en el chat («¿algún admin?», «necesito un helper») es otra función: la petición llega en privado al nivel de staff adecuado en lugar de avisar a todo el mundo. Se configura en `network.yml`, en `Staff_Mentions`. Ver [Reportes y soporte](reports).

## Límites

- El sonido solo suena para quien recibe el mensaje. Las menciones en mensajes privados o en el correo no lo activan.
- Un nombre muy corto (dos o tres letras) puede coincidir con palabras normales. Si tus jugadores tienen nombres así, pon `Require_At: true`.
