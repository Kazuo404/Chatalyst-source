# Checklist de benchmarks

Usa esto antes de afirmar rendimiento a gran escala.

## Matriz de servidores

- Paper, último build soportado.
- Purpur, último build soportado.
- Folia, último build soportado.
- Red Velocity con al menos 3 backends.
- Modo de red Redis con al menos 3 backends.

## Escenarios

- 100, 300 y 600 jugadores online.
- Spam de chat global a tasas controladas.
- Spy de staff activo en varias cuentas de staff.
- Motor de reglas y filtro de groserías activos.
- Puente de Discord activado y desactivado.
- Modo de proxy `legacy`, `native` y `redis`.

## Evidencia

Captura:

- Enlace del profiler spark.
- CPU, heap y configuración de GC.
- La config de Chatalyst usada.
- Tasa de mensajes y número de jugadores.
- Cualquier warning de la consola.

No publiques afirmaciones de rendimiento sin configs reproducibles y salida del profiler.
