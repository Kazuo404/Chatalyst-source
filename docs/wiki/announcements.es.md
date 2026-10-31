# Anuncios y animaciones

Chatalyst ofrece dos rutas para anuncios:

- `Announcer` rota automáticamente los mensajes configurados y puede esperar a que exista actividad real en el chat.
- `/announce` abre el compositor manual para miembros del equipo con `chatalyst.command.announce`.

Los comandos rápidos `/broadcast`, `/say` y `/me` siguen disponibles, pero no incluyen el selector de animación. Usa `/broadcast [chat|title|actionbar|bossbar|toast] <mensaje>` para elegir un display estático.

## Flujo del compositor

1. Ejecuta `/announce` como jugador.
2. Elige un display: `EPIC`, chat, título, barra de acción, bossbar o toast.
3. Elige un sonido de una sola reproducción, un estilo de la paleta de `/chatcolor` y una animación.
4. Pulsa el objeto de mensaje y escribe texto plano para el anuncio. Chatalyst captura el mensaje en lugar de publicarlo; `cancel` o `cancelar` cancela la entrada.
5. Elige el alcance: el backend actual o toda la red del proxy.
6. Obtén una vista previa privada o envíalo al alcance seleccionado.

Los anuncios completos también se escriben en consola y en la auditoría de seguridad del equipo. El sonido seleccionado se reproduce una vez al comenzar la vista previa o el envío.

## Anuncios globales de red

El selector de alcance aparece cuando `Proxy.Enabled` y `Network_Admin.Announcements.Enabled` están activos. El equipo necesita `chatalyst.admin.network.announce`, además de `chatalyst.command.announce`. Cada backend reproduce por su cuenta el mismo display, estilo, sonido y animación; por tanto, un anuncio `EPIC` alcanza al lobby, survival y cualquier otro servidor conectado.

La GUI de administración también incluye un acceso directo a **Anuncio global de red**. La vista previa siempre es privada y local; solo el envío final cruza la red. El backend de origen registra `network-announce` en la auditoría, mientras los backends receptores identifican en consola el servidor de origen y al miembro del equipo que lo envió.

Los modos native y legacy necesitan un jugador conectado que transporte el mensaje del backend a través del proxy. Redis puede entregar anuncios aunque no exista un jugador local. Los paquetes tienen límites, marca de tiempo y deduplicación; un paquete recibido nunca vuelve a reenviarse.

## Displays

`EPIC` combina un título animado, chat estático enmarcado y una bossbar animada. El sonido elegido es opcional y se reproduce por separado.

Las animaciones solo se aplican a título, barra de acción y bossbar. El chat y el toast son displays de un solo envío y permanecen estáticos aunque se seleccione una animación.

## Presets

| Preset | Efecto |
| --- | --- |
| Estático | Muestra el estilo seleccionado sin animación. |
| Máquina de escribir | Revela el texto progresivamente. |
| Expansión | Revela el texto desde el centro hacia afuera. |
| Glitch | Comienza ofuscado y se descifra de izquierda a derecha. |
| Ola de color | Desplaza la fase de un gradiente o arcoíris; los colores planos usan arcoíris. |
| Cuadros | Rota plantillas MiniMessage configurables, incluidos colores, degradados y decoraciones. |
| Pulso | Alterna el estilo seleccionado con una versión gris atenuada. |
| Alerta | Alterna el estilo seleccionado con rojo intenso y negrita. |
| Marquesina | Desplaza una ventana de 24 caracteres por un letrero repetido. |
| Cuenta atrás | Muestra 3, 2, 1 y después el mensaje. |

Las animaciones funcionan a 10 fotogramas por segundo durante unos ocho segundos. Un `/announce` nuevo sustituye la animación anterior para cada jugador y display afectados, evitando títulos o barras de acción intercalados y bossbars apiladas. Una vista previa privada solo sustituye la animación de ese administrador.

### Cuadros configurables

El preset `Cuadros` usa `Announcer.Animation` en `bot.yml`. Cada elemento es una plantilla MiniMessage completa y `{msg}` inserta el texto escrito en el compositor. Por ello, cada cuadro puede usar un color plano, un degradado, decoraciones como negrita/cursiva o texto fijo alrededor:

```yaml
Announcer:
  Animation:
    Interval_Ticks: 6
    Frames:
      - "<gradient:#FF4D6D:#FFC933>{msg}</gradient>"
      - "<gradient:#FFC933:#3DFFA8><bold>{msg}</bold></gradient>"
      - "<gradient:#3DFFA8:#00E5FF>{msg}</gradient>"
      - "<gradient:#00E5FF:#FF4D6D>{msg}</gradient>"
```

`Interval_Ticks` acepta de 2 a 200 ticks. Mientras este preset está activo, la plantilla de cada cuadro sustituye el estilo de paleta elegido en la GUI. En un anuncio de red, el preset `frames` cruza el proxy y cada backend reproduce su secuencia local; conserva esta sección idéntica en todos los backends si quieres exactamente el mismo aspecto.

> **Accesibilidad:** La alerta cambia de color rápidamente. Úsala con moderación; pulso u ola de color son opciones más tranquilas para avisos rutinarios.

## Preferencia del jugador

`/toggle announcements` desactiva únicamente los anuncios rotatorios automáticos. No oculta broadcasts manuales ni avisos enviados mediante `/announce`.
