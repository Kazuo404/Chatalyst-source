# Nametags flotantes: título, iconos laterales y animación

El nametag fancy sustituye el nombre vanilla plano sobre la cabeza por uno o dos renglones
flotantes (`TextDisplay`) que renderizan el estilo **completo** — gradientes, negrita,
cursiva, iconos de ItemsAdder — saltándose el límite de un color plano de los nametags de
scoreboard. El texto es **flotante, sin caja de fondo**. Todo es bilingüe (cada espectador
lo ve en su idioma) y seguro en Folia.

## El renglón del tag

La línea principal (prefijo de rango + apodo) se configura en `nametag.yml`:

```yaml
Nametag:
  Fancy:
    Enabled: false
    Format: "{nametag_icon_left}{player_prefix}{player_nick}{afk_tag}{nametag_icon_right}"
    Height: 0.3            # a qué altura sobre la cabeza
    Update_Interval: 2     # segundos entre refrescos (si no anima)
    Hide_Sneaking: true    # se atenúa al agacharse, como el nombre vanilla
```

Ocultar el nombre vanilla: sin TAB, Chatalyst lo hace con un team propio
(`NAME_TAG_VISIBILITY: NEVER`). Con **TAB** instalado los teams son suyos, así que pon
`scoreboard-teams -> invisible-nametags: true` en el `config.yml` de TAB (Chatalyst lo avisa
por consola al arrancar), o el nombre vanilla se ve detrás del fancy.

## Iconos laterales: un escudo para un guardián

`{nametag_icon_left}` y `{nametag_icon_right}` van a los lados del tag y se ponen por
jugador. Están pensados para iconos de ItemsAdder — un escudo para marcar a un guardián, una
corona, un emblema de clan — pero aceptan cualquier MiniMessage:

```
/nametagtitle iconleft :shield:
/nametagtitle iconright :crown:
/nametagtitle iconleft off
```

Están vacíos (e invisibles) hasta que se ponen, y solo se ven si el `Format` por defecto
conserva los placeholders `{nametag_icon_left}` / `{nametag_icon_right}` (así viene de
fábrica).

## El renglón del título: "REY DE TODO"

Un segundo renglón flotante **encima** del tag, para un título. Actívalo y apúntalo al
título por jugador:

```yaml
Nametag:
  Fancy:
    Title:
      Enabled: false
      Format: "{nametag_title}"      # el título por jugador que pone el comando
      Default_Format: ""             # respaldo cuando el jugador no tiene (ver abajo)
      Height: 0.55                   # por encima del Height del tag
      Max_Length: 32
```

Se pone con el comando (el tuyo, o el de otro jugador con el permiso de staff):

```
/nametagtitle title <gradient:#FFD700:#FFA500>REY DE TODO
/nametagtitle title Nec1x REY DE TODO
/nametagtitle title off
```

Alias: `/ntitle`, `/nametagicon`. Si el título resuelto queda vacío **el renglón entero se
retira** — sin dejar una fila flotante en blanco. Cuando un jugador no tiene título propio,
entra `Default_Format`, que es donde viven los **títulos por rango**: apúntalo a un valor de
LuckPerms/PlaceholderAPI y todos los de un rango llevan el mismo título sin comando:

```yaml
Default_Format: "%luckperms_meta_nametag_title%"
```

```
/lp user Nec1x meta set nametag_title "REY DE TODO"
```

## Animación en el flotante, TAB y chat

El mismo reloj puede animar continuamente el tag flotante y el TAB. El chat captura el
cuadro vigente cuando se envía un mensaje; el cliente de Minecraft no permite editar una
línea ya publicada, así que esa línea conserva su cuadro en vez de seguir moviéndose.

Se configura en `nametag.yml`:

```yaml
Nametag:
  Fancy:
    Animation:
      Enabled: false
      Mode: "frames"           # frames | wave
      Interval_Ticks: 6        # velocidad de la animación (mínimo 2)
      Tab_Interval_Ticks: 10   # velocidad del TAB (mínimo 6; subir en redes grandes)
      Apply_To:
        Tab: true              # Tab_List interno o TAB con Sync_Nick
        Chat: true             # snapshot al enviar; nunca duplica mensajes
      Frames:                  # mode=frames: rota estos, uno por intervalo
        - "<#FF4D6D>{player_nick}"
        - "<#FFC933>{player_nick}"
        - "<#3DFFA8>{player_nick}"
        - "<#00E5FF>{player_nick}"
      Wave:                    # mode=wave: desliza un gradiente sobre el texto
        Content: "{player_nick}"
        Colors: ["#FFD700", "#FF4D6D", "#FFD700"]
```

`frames` rota la lista — colores alternos, texto parpadeante, iconos que cambian — un cuadro
cada `Interval_Ticks`. `wave` mantiene un solo renglón y desliza el gradiente `Wave.Colors`
sobre `Wave.Content` (la fase del gradiente avanza cada tick, dando una ola de color). Con la
animación activa, el nametag entero se refresca al ritmo de `Interval_Ticks` en vez de
`Update_Interval`. Un modo `frames` sin cuadros cae al `Format` estático.

Las plantillas de animación controlan el **color** del apodo, así que el color estático de
`/nickcolor` no puede tapar el cuadro/degradado móvil. El submenú Styles sí se conserva:
negrita, cursiva, subrayado, tachado y ofuscado permanecen dentro de todos los cuadros.

Para la lista integrada, activa `Tab_List.Enabled`. Con el plugin TAB, conserva
`TAB.Sync_Nick: true`; Chatalyst envía cada cuadro vigente mediante su API. El TAB usa su
propio `Tab_Interval_Ticks` acotado porque cada cambio de nombre se retransmite a quienes
están conectados.

En chat, `{player_nick}` y `{rank_nick}` usan el cuadro que estaba vigente al renderizar el
mensaje. Los mensajes nuevos siguen la animación, mientras las líneas anteriores quedan
inmutables. Fingir movimiento continuo obligaría a reenviar o sustituir líneas de chat
firmado, produciendo duplicados y problemas de compatibilidad; Chatalyst no lo hace.

## Comandos y permisos

```
/nametagtitle <title|iconleft|iconright> <texto|off>            el tuyo
/nametagtitle <title|iconleft|iconright> <jugador> <texto|off>  staff
```

| Permiso | Default | Qué da |
| --- | --- | --- |
| `chatalyst.nametagtitle` | op | Poner tu propio título/iconos |
| `chatalyst.nametagtitle.color` | op | Usar colores (MiniMessage) e iconos de ItemsAdder |
| `chatalyst.nametagtitle.others` | op | Cambiar el título/iconos de otros jugadores |

El texto pasa las mismas defensas que `/nick` y `/tag`: se quitan caracteres invisibles,
colores solo con el permiso `.color`, filtro de insultos y motor de reglas, y se bloquean
`<click>`/`<hover>` — el título lo ven todos sobre tu cabeza, así que no debe llevar
acciones.

## Anonimato

Un título "REY DE TODO" o un icono de guardián flotando sobre un jugador en `/anonymous` lo
delataría igual que su rango, así que `{nametag_title}`, `{nametag_icon_left}` y
`{nametag_icon_right}` se **vacían mientras dura el disfraz**.

## Límites honestos

- El título es una **segunda entidad de display** montada en el jugador. Los dos renglones
  se separan por la diferencia entre el `Height` del tag y el `Title.Height`; si tu build de
  Minecraft apila los pasajeros de otra forma, ajusta esos dos números hasta que el hueco
  quede bien.
- Los iconos dependen de **ItemsAdder** (o cualquier resource pack que mapee el `:token:`):
  sin él, el token `:shield:` sale como texto literal.
- En servidores muy grandes, subir `Update_Interval` / `Interval_Ticks` mantiene barato el
  rearmado del texto por tick. Sube `Tab_Interval_Ticks` por separado para reducir el tráfico
  del TAB sin volver menos suave el tag flotante.
