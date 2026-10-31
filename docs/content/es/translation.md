---
title: Traducción en vivo
description: Traducción opcional del chat en vivo al idioma del cliente de cada jugador mediante un servicio compatible con LibreTranslate, con caché y respaldo seguro.
---

La traducción en vivo deja que se entiendan jugadores que hablan idiomas distintos. Quien la activa lee el chat en otros idiomas traducido al idioma de su cliente de Minecraft. Quien escribe, y quien comparte su idioma, ven el mensaje original.

Solo toca lo que los jugadores escriben en el chat. Nunca traduce los mensajes del propio plugin, los menús, los comandos, las respuestas del bot ni los avisos de moderación: se quedan tal cual los escribiste.

La traducción se activa dos veces: tú la enciendes para el servidor y cada jugador decide si la quiere para sí.

## Cómo se activa

1. Consigue un servicio de traducción que hable la API de LibreTranslate. Dos opciones sencillas:
   - Instala tu propio [LibreTranslate](https://github.com/LibreTranslate/LibreTranslate) (gratis, sin clave) y pon `Provider_Url: "http://127.0.0.1:5000/translate"`.
   - Usa un servidor de LibreTranslate alojado y pega su clave de API.
2. En `plugins/Chatalyst/integrations.yml`, rellena `Provider_Url`, pon `Enabled: true` y ejecuta `/cht reload`. También se puede hacer desde `/cht gui`.
3. Ahora aparece «Traducción del chat» en el menú `/toggle` de cada jugador. Empieza apagada; cada uno la enciende con el menú o con `/toggle translate`.

```yaml
Translation:
  Enabled: false
  # Full URL of the LibreTranslate-compatible /translate endpoint.
  Provider_Url: ""
  # Optional API key (leave empty for a keyless self-hosted server).
  Api_Key: ""
  # Give up on a translation after this many ms (then that viewer sees the original).
  Timeout_Ms: 1500
  # Don't translate very short messages (emotes, "gg", "ok"): min characters.
  Min_Length: 3
  # How many recent (language, text) results to cache in memory.
  Cache_Size: 500
```

## Configuración

| Opción | Por defecto | Qué hace |
|---|---|---|
| `Enabled` | `false` | Enciende la traducción. Sigue apagada mientras `Provider_Url` esté vacío. |
| `Provider_Url` | `""` | URL completa del endpoint `/translate`. |
| `Api_Key` | `""` | Clave de API, si tu proveedor la pide. |
| `Timeout_Ms` | `1500` | Tiempo máximo de espera por traducción (de 300 a 10.000 ms). Pasado ese tiempo, ese lector ve el original. |
| `Min_Length` | `3` | Los mensajes más cortos nunca se traducen. |
| `Cache_Size` | `500` | Resultados recientes guardados en memoria (de 32 a 10.000). Las frases repetidas salen al instante. |

### Cómo funciona

- Solo se pide una traducción cuando la lee alguien que la tiene activada y el idioma de su cliente es distinto del de quien escribe. Un servidor donde todos hablan el mismo idioma no gasta nada.
- Cada idioma de destino se traduce una sola vez por mensaje, lo lean los jugadores que lo lean.
- Es normal un pequeño retraso (entre 0,2 y 0,5 segundos) mientras llega el texto.
- Si el servicio está caído, va lento o está apagado, todos ven el original. El chat nunca se rompe.

## Comandos y permisos

| Comando | Permiso (por defecto) | Qué hace |
|---|---|---|
| `/toggle translate` | `chatalyst.command.toggle` (true) | Cada jugador activa o desactiva para sí la traducción en vivo. |

## Límites

- Mientras un mensaje se entrega traducido, llega como línea del sistema en lugar de como línea de chat firmada. Los registros, el [bot de Discord](discord-bot), las menciones, la moderación y el silencio en la sombra siguen funcionando, pero el botón de borrar del staff no puede retirar esas líneas de los clientes. `/clearchat` y los silencios funcionan igual.
- Solo se traduce el chat público. Los mensajes privados, el correo y los carteles no.
- La calidad de la traducción depende por completo de tu proveedor.
- Los mensajes se envían al servicio de traducción que configures. Usa uno propio si el chat no debe salir de tu infraestructura.
