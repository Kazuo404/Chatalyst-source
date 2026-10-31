---
title: Libros y normas
description: Libros del servidor que se abren como un libro de Minecraft de verdad. Escribe tus normas o una guía una vez, ábrela con un comando y edítala en el juego sin tocar archivos.
---

Los libros son la forma más cómoda de mostrar textos largos: las normas del servidor, una guía para nuevos, una lista de comandos. Chatalyst los abre como un libro escrito de verdad, con colores, texto clicable y placeholders como el nombre del jugador. Los jugadores abren un libro con `/book <nombre>`; el staff puede escribir y editar libros en el juego escribiendo en un libro normal y guardándolo.

Vienen dos libros listos para editar: `rules` y `help`.

## Configuración

Los libros están en `books.yml`:

```yaml
Books:

  rules:
    Title: "<#D8B4FE>Rules"
    Author: "Server"
    Permission: ""
    Pages:
      - "<bold><dark_red>Server rules</dark_red></bold>\n\n<black>1. Respect all players.\n\n2. No spam or advertising.\n\n3. No hacks or bug abuse."
      - "<black>4. Watch your language.\n\n5. Have fun and be kind.\n\n<#C0B3D9><i>Thanks for reading, {player_name}.</i>"

  help:
    Title: "<#B56CFF>Help"
    Author: "Server"
    Permission: ""
    Pages:
      - "<bold><dark_aqua>Useful commands</dark_aqua></bold>\n\n<black>/msg <player> - private message\n/r - reply\n/channel - switch channel\n/mail - mailbox\n/nick - nickname"
      - "<black>/chatcolor - chat color\n/ignore - ignore someone\n\n<#C0B3D9><i>Type /book to see the books.</i>"
```

| Clave | Qué hace |
| --- | --- |
| `Title` | El título del libro (MiniMessage) |
| `Author` | El autor que aparece en el libro |
| `Permission` | Permiso necesario para abrirlo. Vacío = todos |
| `Pages` | Una entrada por página. Usa `\n` para saltar de línea |

Las páginas usan [MiniMessage](https://docs.advntr.dev/minimessage/format.html), así que puedes poner colores, textos al pasar el ratón y comandos clicables. También funcionan los placeholders: `{player_name}`, tus [variables propias](integrations#variables-propias) y los valores de PlaceholderAPI.

> [!TIP]
> Una página de libro es pequeña. Si el texto se sale, pártelo en dos páginas en lugar de encogerlo.

## Escribir libros en el juego

No hace falta editar YAML. El staff con `chatalyst.book.admin` puede:

1. Ejecutar `/book new` para recibir un libro y pluma vacío.
2. Escribir las páginas como en cualquier libro de Minecraft. Los colores y el formato se conservan.
3. Tenerlo en la mano y ejecutar `/book save <nombre> [permiso]` para guardarlo. Usa `-` como permiso para que sea público. El título y el autor se toman del libro si lo firmaste.

Para cambiar un libro que ya existe, `/book edit <nombre>` te da una copia editable con sus páginas actuales; cámbiala y guárdala otra vez con el mismo nombre. `/book delete <nombre>` lo borra.

## Abrir libros

| Comando | Quién | Qué hace |
| --- | --- | --- |
| `/book <nombre>` | todos | Abre un libro para ti |
| `/book list` | todos | Lista los libros que puedes abrir |
| `/book open <nombre> <jugador>` | staff | Abre un libro en la pantalla de otro jugador |
| `/book new` | staff | Te da un libro vacío para escribir |
| `/book edit <nombre>` | staff | Te da una copia editable de un libro |
| `/book save <nombre> [permiso]` | staff | Guarda el libro que tienes en la mano |
| `/book delete <nombre>` | staff | Borra un libro |

`/books` es un alias de `/book`. Permisos: `chatalyst.command.book` (todos) para abrir libros, `chatalyst.book.admin` (op) para escribir, editar, borrar y abrir libros a otros. El staff puede abrir todos los libros, tengan el `Permission` que tengan.

## Mostrar las normas en el momento justo

- Una [regla](rules-engine) puede abrir un libro automáticamente con `Actions.Open_Book`, por ejemplo el libro de normas cuando alguien se salta una.
- El [bot de respuestas automáticas](auto-responses) ya trae una respuesta para "dónde están las normas".
- Puedes abrir el libro de normas a quien acaba de entrar por primera vez con un comando de [recién llegados](newcomers): `book open rules {player_name}`.

## Límites

- Los libros son iguales para todos los idiomas. Si quieres un libro de normas en español, crea un segundo libro (por ejemplo `normas`).
- Los libros que escriben los jugadores en supervivencia no son libros de este sistema. De esos se encargan el [espía](spying) y el [motor de reglas](rules-engine), y los jugadores muteados no pueden firmarlos.
