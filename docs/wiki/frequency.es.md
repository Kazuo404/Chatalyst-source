# Chat por radiofrecuencia

El canal `local` integrado funciona como radio, no como chat de proximidad por distancia. Los jugadores solo lo oyen cuando tienen una frecuencia activa y reciben mensajes de quienes están sintonizados en el mismo valor.

## Configuración

```yaml
Channels:
  Frequency:
    # Vacío inicia la radio apagada. Usa "10.00" para sintonizar jugadores nuevos.
    Default: ""
  List:
    local:
      Type: FREQUENCY
      Force_Listen: true
```

Las frecuencias válidas van de `10.00` a `99.99`. La selección persiste en el backend de almacenamiento configurado. Un valor predeterminado vacío significa que los jugadores nuevos no oyen ni transmiten por radio hasta sintonizarla.

## Comandos

- `/frequency` abre la interfaz de sintonización.
- `/frequency 42.50` sintoniza directamente; también se acepta una coma decimal.
- `/frequency off` apaga la radio.
- `/f <mensaje>` transmite una vez por la frecuencia sintonizada sin cambiar el canal de chat activo.

`/f` usa el flujo normal del chat, incluidos silencios, reglas, antispam, formato, registros y permisos del canal. `Force_Listen` mantiene audible la radio local mientras el jugador permanece en otro canal activo, pero la entrega sigue filtrándose por frecuencia.

El equipo con `chatalyst.bypass.range` puede oír todas las frecuencias activas para moderar. Los canales de frecuencia, mundo, región y grupo no se guardan en el historial global porque reproducirlos podría revelar mensajes restringidos.

## Comportamiento en red

La radiofrecuencia es local a un backend. No la envíes por proxy: las audiencias sintonizadas pertenecen a ese servidor. Usa un canal `GLOBAL` separado cuando la conversación deba cruzar Velocity, BungeeCord o Redis.
