# Formatos condicionales, libros y API de reglas

## Partes de formato condicionales

Cada entrada de `Parts` sigue necesitando solamente `Message`. Las siguientes condiciones opcionales controlan quien crea o recibe la parte:

```yaml
Parts:
  staff-badge:
    Message: "<red>[STAFF] "
    Sender_Permission: chatalyst.staff

  moderation-button:
    Message: "<dark_gray>[<red>x<dark_gray>] "
    Receiver_Permission: chatalyst.deletemessages
    Receiver_Variable: "{world} !event_world"
    Run_Command: "/chatalyst removemessage {message_uuid}"
```

- `Sender_Permission` y `Receiver_Permission` aceptan la negacion `!permission.node`.
- `Sender_Variable` y `Receiver_Variable` usan `placeholder valor_esperado`.
- Una parte cuyo comando usa `{message_uuid}` se oculta automáticamente cuando Paper no entregó una firma utilizable; no requiere otra condición. El estado premium/offline de la cuenta y `enforce-secure-profile` no son requisitos de Chatalyst.
- Los valores admiten igualdad, `!=`, `>`, `>=`, `<`, `<=`, `contains:`, `starts:`, `ends:`, `in:` y `regex:` acotado.
- `Sender_Condition` y `Receiver_Condition` aceptan JavaScript escrito por administradores y reutilizan la cache compilada y restringida.
- `Insertion` inserta texto con shift-clic.
- `View_Permission` sigue funcionando como alias compatible de `Receiver_Permission`.

Las condiciones del emisor y el componente se calculan una sola vez. Solo las condiciones del receptor se ejecutan por espectador, conservando el modelo de render preparado de Chatalyst.

## Libros del servidor dentro del juego

Los jugadores pueden seguir usando `/book <nombre>`. Los administradores con `chatalyst.book.admin` pueden usar:

- `/book list`
- `/book new`
- `/book edit <nombre>`
- `/book save <nombre> [permiso]`
- `/book delete <nombre>`
- `/book open <nombre> [jugador]`

El panel de administracion ahora abre una biblioteca paginada. Un clic abre el libro, shift-clic crea una copia editable y el control central crea un borrador. Los guardados y borrados escriben `books.yml` de forma asincrona con reemplazo atomico y publican la definicion nueva inmediatamente.

## Eventos de integracion de reglas

Otros plugins pueden escuchar:

- `RuleMatchEvent`: cancelable despues de superar todas las condiciones declarativas y antes de adquirir cooldown o estado.
- `RuleReplaceEvent`: cancelable y con resultado de reemplazo modificable.
- Los eventos existentes `ChannelMessageEvent` y `ChannelSwitchEvent` siguen disponibles.

Los handlers deben respetar las reglas de hilos de Bukkit. El evento se marca asincrono cuando el contexto de reglas se procesa fuera del hilo principal.

Los títulos y logros tienen ahora un servicio versionado, eventos durables de ciclo de vida y
un jar de compilación propio. Consulta [API de títulos y logros](titles-api.es.md).
