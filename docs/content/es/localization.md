---
title: Idiomas y mensajes
description: Cada jugador ve Chatalyst en su propio idioma de Minecraft. Edita cualquier mensaje, añade idiomas nuevos y entiende cómo funcionan los textos en inglés y español de la configuración.
---

Chatalyst habla el idioma de cada jugador. Quien tiene el cliente de Minecraft en español lee todos los mensajes, menús y avisos en español, mientras el jugador de al lado lee lo mismo en inglés. No hay que configurar nada por jugador: el plugin usa el idioma que indica el cliente.

Todos los textos del plugin están en la carpeta `lang/`, así que puedes cambiar la redacción de cualquier mensaje sin tocar código. El inglés (`en_US`) y el español (`es_ES`) vienen completos, y puedes añadir cualquier otro idioma poniendo un archivo nuevo.

## Cómo se elige el idioma

En `config.yml`:

```yaml
Locale: en_US
Auto_Detect_Language: true
```

| Opción | Por defecto | Qué hace |
| --- | --- | --- |
| `Locale` | `en_US` | El idioma por defecto. Se usa en la consola, en los anuncios para todo el servidor y con cualquier jugador cuyo idioma no tenga archivo |
| `Auto_Detect_Language` | `true` | `true`: cada jugador ve el idioma de su cliente si existe su archivo. `false`: todos ven `Locale` |

Cuando un jugador entra, Chatalyst busca un archivo en este orden:

1. El idioma exacto del cliente, por ejemplo `pt_BR.yml`.
2. El mismo idioma de cualquier país, por ejemplo `pt_PT.yml` para un cliente `pt_BR`.
3. El `Locale` por defecto.

El idioma sigue al cliente en directo: si un jugador cambia el idioma de Minecraft, el siguiente mensaje ya sale en el nuevo.

## Editar mensajes

Abre `plugins/Chatalyst/lang/es_ES.yml` (o `en_US.yml`), cambia el texto y ejecuta `/chatalyst reload`. Los mensajes usan [MiniMessage](https://docs.advntr.dev/minimessage/format.html), así que funcionan colores, degradados, textos al pasar el ratón y acciones al hacer clic:

```yaml
common:
  no-permission: "<#FF4D6D>No tienes permiso para hacer eso."
```

Las palabras entre llaves como `{player}` o `{id}` las rellena el plugin. Déjalas en tu texto; puedes moverlas de sitio dentro de la frase.

> [!TIP]
> No hace falta que mantengas una copia completa al día. Si a tu archivo le falta una clave (por ejemplo, un mensaje que trae una versión nueva), Chatalyst usa el texto incluido para ese idioma, y el inglés como último recurso. Una actualización nunca muestra una clave en bruto porque tu archivo sea más viejo.

## Añadir un idioma nuevo

1. Copia `lang/en_US.yml` a un archivo nuevo con el nombre del idioma de Minecraft, por ejemplo `lang/fr_FR.yml`, `lang/de_DE.yml` o `lang/pt_BR.yml`.
2. Traduce los valores. Deja las claves (lo que va antes de los dos puntos) sin tocar.
3. Ejecuta `/chatalyst reload`.

Los jugadores con ese idioma en el cliente ven el archivo nuevo automáticamente. Lo que aún no hayas traducido sale en inglés.

## Textos dentro de los archivos de configuración

Algunos textos se escriben directamente en los archivos de configuración en lugar de en `lang/`: las respuestas del bot automático, los anuncios, las pantallas de ban y expulsión, los avisos de las reglas, las tarjetas de la tienda y parecidos. Van en pareja:

```yaml
Warn: "<#FF4D6D>Watch your language."
Warn_es: "<#FF4D6D>Cuida tu lenguaje."
```

Un cliente en español (cualquier variante) ve la versión `_es`; el resto ve la versión base. Si borras la línea `_es`, se usa el texto base para todos. Cuando la pareja es una lista (por ejemplo `Messages` y `Messages_es`), si falta la lista en español se usa entera la otra, así que nadie ve medio mensaje en un idioma y medio en el otro.

> [!NOTE]
> Estas parejas de la configuración solo conocen inglés y español. Un cliente en francés ve su parte en inglés. Todo lo que está en `lang/` se puede traducir a cualquier idioma.

## Los mensajes propios de Minecraft

Con [PacketEvents](integrations#packetevents) instalado, Chatalyst da estilo a las respuestas grises de Minecraft (`/tp`, `/give`, `/gamemode`, `/time`…) en el idioma de cada cliente. Las plantillas están en la sección `vanilla:` de cada archivo de idioma. Deja una plantilla en `""` para mantener el texto original de Minecraft en ese mensaje. Para no tocar mensajes concretos, pon sus claves en `chat.yml`:

```yaml
Vanilla_Messages:
  Enabled: true
  Ignore: []
```

El mensaje de comando desconocido (`Unknown_Command` en `chat.yml`) también sale de los archivos de idioma, y muestra el mismo texto para comandos que no existen, prohibidos u ocultos, así que nunca delata que un comando existe.

## Comandos bilingües

Muchos comandos responden a un nombre en inglés y otro en español: `/anonymous` y `/anonimo`, `/market` y `/mercado`, `/support` y `/soporte`, `/freeze` y `/congelar`. Consulta [todos los comandos](commands) para ver la lista completa de alias.

## Límites

- El idioma sale del ajuste del cliente. Un jugador que juega con el cliente en inglés pero prefiere español verá inglés, salvo que pongas `Auto_Detect_Language: false` y `Locale: es_ES` para todos.
- Solo los archivos de `lang/` admiten idiomas extra. Las parejas `_es` de la configuración cubren únicamente inglés y español.
- Este sistema nunca traduce lo que escriben los jugadores en el chat. Para eso está la [traducción en vivo](translation).
