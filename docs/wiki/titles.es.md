# Sistema de títulos y logros

`/tag` abre el catálogo del jugador. Ahí se ve cada título, sus reglas, el progreso actual,
la categoría, la rareza, los puntos de logro y una vista previa. El perfil inferior resume
logros obtenidos, porcentaje y puntuación. Al pulsarlo se abren las colecciones y el ranking
global. El jugador puede filtrar por categoría y estado,
ordenar por prioridad, rareza, puntos o nombre y equipar con un clic cualquier título
desbloqueado.
`Quitar título` conserva todos los desbloqueos.

## Comandos y permisos

| Acción | Permiso | Comportamiento |
|---|---|---|
| `/tag` | `chatalyst.tag` | Abre el catálogo y evalúa el progreso actual |
| `/tag <id>` | `chatalyst.tag` | Evalúa el progreso y equipa un título desbloqueado |
| `/tag off` o `/tag reset` | `chatalyst.tag` | Quita el título de catálogo sin borrar logros |
| `/tag admin` | `chatalyst.tag.admin` | Abre la administración completa; incluye `chatalyst.tag` |
| `/tag prefix\|suffix <texto\|off>` | `chatalyst.tag` | Compatibilidad con tags personales heredados |
| `/tag prefix\|suffix <jugador> <texto\|off>` | `chatalyst.tag.others` | Modifica el tag heredado de otro jugador conectado |

`chatalyst.tag`, `chatalyst.tag.color`, `chatalyst.tag.others` y
`chatalyst.tag.admin` son permisos de operador por defecto. El permiso
`chatalyst.tag.color` habilita MiniMessage en los comandos heredados; el texto del catálogo
lo diseña el administrador y siempre se sanea antes de guardarse.

## Experiencia del jugador

El catálogo usa páginas de hasta 45 títulos. Solo muestra definiciones activas; un título
con **Oculto hasta desbloquear** desaparece por completo hasta obtenerse, mientras que un
logro **Secreto** conserva una ficha `???` sin revelar nombre, descripción, icono, rareza,
categoría ni requisitos.

- Clic izquierdo o derecho en **Categoría**, **Estado** y **Orden** recorre las opciones en
  ambos sentidos.
- Los estados disponibles son todos, desbloqueados y bloqueados.
- El orden puede ser por prioridad, rareza, puntos o nombre.
- Cada ficha muestra la vista previa junto al nombre, lado `LEFT`/`RIGHT`, espaciado,
  rareza, categoría, puntos, temporada, requisitos y progreso numérico.
- El perfil muestra títulos obtenidos, total activo, porcentaje, puntos y acceso a
  colecciones.
- Las colecciones permiten abrir el ranking global y equipar un título obtenido desde su
  detalle.

Abrir el catálogo o intentar equipar fuerza una evaluación actual antes de decidir si el
título está bloqueado. El autocompletado no revela IDs secretos u ocultos que todavía no
conoce el jugador.

## Administración desde la GUI

Abre `/tag admin` o `/chatalyst gui` → **Estudio de contenido** →
**Títulos y requisitos**. Desde ahí se puede:

- crear, duplicar, editar, activar, ocultar y eliminar títulos;
- cambiar nombre, texto MiniMessage, descripción e icono;
- colocar cada título en `LEFT` o `RIGHT`;
- elegir entre 0 y 8 espacios respecto al nombre (el valor inicial es 1);
- organizar por categoría, rareza y prioridad;
- asignar puntos, marcar logros secretos y programar temporadas completas;
- personalizar colecciones, sus iconos y el bono por completarlas;
- combinar hasta 8 reglas mediante `TODAS` (`ALL`) o `CUALQUIERA` (`ANY`);
- crear cadenas de progreso que exigen haber desbloqueado otro título;
- detectar bloques minados, objetos fabricados/encontrados/en inventario, pesca, cría y
  advancements de Minecraft;
- diseñar el logro nativo: texto, descripción, icono, marco, chat y sonido;
- configurar hasta 8 recompensas como comandos de consola;
- conceder o revocar un título desde una lista visual de jugadores conectados o buscando
  cualquier jugador conocido;
- filtrar el catálogo administrativo por categoría y estado;
- activar el aviso de desbloqueo y el autoequipado del primer título.

Los títulos nuevos y las copias se crean **desactivados**, de modo que pueden terminarse
antes de publicarlos. Los clics derecho e izquierdo recorren en sentidos opuestos la
posición, el espaciado, la rareza, el marco y los filtros; `Shift + clic` abre las
colecciones desde el filtro de categoría. Las eliminaciones y los restablecimientos
delicados requieren dos clics. No se puede eliminar un título que todavía sea requisito de
otro.

Los campos de texto se capturan por chat y no se publican. Una entrada vacía, `cancel` o
`cancelar` vuelve sin cambios; los campos opcionales también aceptan la palabra indicada por
la propia GUI para limpiarlos. Cada cambio del catálogo se guarda atómicamente en
`tags.yml` antes de aparecer como aplicado.

## Requisitos

| Tipo | Uso |
|---|---|
| `NONE` | Disponible para todos |
| `MOB_KILLS` | `Target: ANY` o una entidad (`ZOMBIE`, `SKELETON`, etc.) y una cantidad |
| `PLAYER_KILLS` | Cantidad de jugadores derrotados |
| `PLAYTIME_MINUTES` | Minutos jugados |
| `BLOCKS_MINED` | Cantidad minada del Material indicado |
| `ITEMS_CRAFTED` | Cantidad fabricada del Material indicado |
| `ITEMS_PICKED_UP` | Cantidad histórica recogida/encontrada del Material |
| `ITEMS_OWNED` | Cantidad presente ahora mismo en el inventario |
| `FISH_CAUGHT` | Peces capturados |
| `ANIMALS_BRED` | Animales criados |
| `ADVANCEMENT` | Advancement vanilla o personalizado (`namespace:ruta`) |
| `PERMISSION` | Permiso requerido en `Target` |
| `TITLE_UNLOCKED` | ID de otro título que se debe obtener primero |
| `MANUAL` | Solo mediante concesión administrativa |

Un título sin reglas es libre. Con varias reglas, `TODAS` (`ALL`) exige cumplirlas todas y
`CUALQUIERA` (`ANY`) acepta la primera satisfecha. Las reglas de estadísticas usan los
contadores vanilla de Minecraft. Al cumplirse, el desbloqueo se guarda en el backend
configurado y no se pierde si después cambia el requisito.

Las cadenas se resuelven en la misma evaluación: obtener el primer logro puede conceder los
siguientes si sus demás reglas ya se cumplen. El editor rechaza IDs inexistentes y ciclos
directos o transitivos.

### Cuándo se vuelve a evaluar

Chatalyst escucha incrementos de estadísticas, recogida de objetos, clics y arrastres de
inventario y advancements completados. La comprobación se difiere un tick para leer el
valor ya confirmado por Paper y agrupa eventos repetidos del mismo jugador. Además, cada
jugador conectado recibe un barrido de seguridad cada 1 200 ticks (aproximadamente 60
segundos a 20 TPS), que recupera cambios sin evento directo como permisos, tiempo,
inventarios modificados por otros plugins o un hot-enable.

`ITEMS_PICKED_UP` es histórico porque usa la estadística vanilla; `ITEMS_OWNED` es una foto
del inventario actual. Las cantidades numéricas aceptan de `1` a `1 000 000 000`. Una regla
`NONE` vuelve libre un grupo `CUALQUIERA` (`ANY`); una regla `MANUAL` nunca se satisface
automáticamente.

## Puntos, secretos y temporadas

Cada rareza propone puntos iniciales (`5`, `10`, `25`, `50`, `100`, `250`), pero el valor se
puede cambiar entre `0` y `1 000 000`. El total del perfil se calcula desde el catálogo y los
desbloqueos actuales, así que editar puntos o revocar acceso actualiza el total sin contadores
duplicados.

Un logro **secreto** aparece como `???` y no revela nombre, descripción, icono, rareza,
categoría ni reglas antes de obtenerlo. **Oculto hasta desbloquear** es más estricto: no
aparece en el catálogo.

El panel **Temporada** configura:

- ID de temporada;
- inicio inclusivo y fin exclusivo;
- fechas locales `AAAA-MM-DD HH:MM`, o un instante ISO-8601;
- límites abiertos usando `off`.

Cambiar el ID crea una nueva clave de desbloqueo y reinicia esa temporada sin borrar el
historial anterior. Solo se obtienen logros bloqueados dentro de la ventana activa. Un logro
estacional no libre que ya fue obtenido continúa equipable cuando termina la temporada.
Los títulos libres no crean una fila de desbloqueo: están disponibles mientras su temporada
esté activa.

## Colecciones y ranking

Cada categoría del catálogo se convierte automáticamente en una colección. Esto evita
administrar dos listas distintas: para mover un título de colección solo se cambia su categoría.
Desde **Administrar títulos**, `Shift + clic` sobre el filtro de categoría abre la gestión de
colecciones. Allí se puede cambiar:

- nombre visible;
- descripción;
- icono;
- puntos adicionales por completar todos sus títulos.

Los títulos desactivados no cuentan en el total ni bloquean la colección. Los secretos y
ocultos sí cuentan, pero conservan sus reglas de privacidad. La ficha muestra progreso, puntos
base y bono; desde el detalle también se puede equipar un título obtenido.
Al terminar una colección se muestra un toast nativo de desafío, aviso de chat y sonido; la
transición se compara antes y después del desbloqueo para no repetirla en cada evaluación.

El ranking compara puntos totales, títulos, porcentaje y colecciones completadas. Se calcula
desde el catálogo y los desbloqueos persistentes, incluyendo jugadores conectados, sin guardar
un contador duplicado que pueda quedar obsoleto al editar puntos o temporadas. Ordena primero
por puntos, luego por cantidad de títulos y finalmente por UUID para mantener un resultado
determinista.

## Presentación del logro

Cada título puede mostrar el toast real de advancements de Minecraft. Desde el panel
**Presentación del logro** se configura:

- toast activado o desactivado;
- título y descripción con MiniMessage;
- cualquier Material como icono;
- marco `TASK`, `GOAL` o `CHALLENGE`;
- mensaje de chat traducido o completamente personalizado;
- sonido, volumen y tono;
- una prueba privada que no desbloquea el título ni ejecuta recompensas.

Los textos aceptan `{player}`, `{uuid}`, `{title}`, `{title_id}`, `{category}`, `{rarity}`,
`{points}` y `{season}`.
Los colores y decoraciones se convierten al componente JSON del advancement; las acciones
click/hover se eliminan. El ajuste global `Avisar al desbloquear` funciona como interruptor
maestro para toast, chat y sonido.

Con PacketEvents instalado, el advancement temporal se envía únicamente al cliente que obtiene
el logro: no modifica su progreso vanilla ni el registro global del servidor. Sin PacketEvents,
Chatalyst utiliza advancements ocultos, reutilizables y acotados, y elimina su progreso después
de mostrar el toast. El fallback reutiliza como máximo 2 048 presentaciones distintas durante
la vida del proceso. En ambos casos se conservan el texto, icono y marco creados desde la GUI.

## Recompensas

Al obtener automáticamente un título se pueden ejecutar hasta 8 comandos de consola, en
orden. Cada comando admite hasta 256 caracteres, se guarda sin `/` inicial y acepta
`{player}`, `{uuid}`, `{title_id}`, `{title_name}`, `{points}` y `{season}`. Primero se
persiste el desbloqueo y después se programan las recompensas en el scheduler global,
compatible con Folia. Un fallo de un comando se registra y no impide intentar los siguientes.

Una concesión manual no ejecuta las recompensas del título concedido, lo que evita abusos
mediante ciclos de revocar/conceder. Sí puede completar una cadena `TITLE_UNLOCKED`; los
títulos nuevos obtenidos automáticamente por esa cadena conservan sus propias recompensas.

## Escala y migración

El catálogo admite hasta 500 títulos. Cada título está acotado a 8 reglas y 8 recompensas
para mantener predecibles la GUI y la evaluación periódica. Los archivos antiguos con una
sección singular `Requirement` se leen y migran automáticamente al siguiente guardado.

| Campo | Límite aplicado |
|---|---:|
| ID de título | 48 caracteres normalizados |
| Nombre visible | 48 caracteres |
| Texto visible | 64 caracteres de texto plano |
| Descripción | 256 caracteres |
| Categoría | 32 caracteres |
| Prioridad | `-10 000` a `10 000` |
| Espaciado | `0` a `8`, predeterminado `1` |
| ID de temporada | 32 caracteres normalizados |
| Título / descripción del toast | 64 / 128 caracteres |
| Mensaje de chat del logro | 256 caracteres |
| Volumen / tono | `0.0–10.0` / `0.5–2.0` |
| Nombre / descripción de colección | 48 / 160 caracteres |

## Persistencia, red y recarga

Las definiciones, colecciones y ajustes globales viven en `plugins/Chatalyst/tags.yml`. La
selección equipada y los desbloqueos usan los stores `tag-selections` y `tag-unlocks` del
backend configurado; el prefijo/sufijo heredado permanece en `tags`. Por eso YAML, SQLite y
MySQL reciben el mismo modelo de datos y las invalidaciones del almacenamiento compartido
pueden actualizar las cachés locales.

La GUI escribe `tags.yml` inmediatamente y publica el nuevo snapshot solo después de una
escritura correcta. En la implementación actual, `/cht reload` **no vuelve a cargar el
catálogo de `tags.yml`**: administra el catálogo desde la GUI o reinicia después de editar
ese archivo manualmente. No edites el archivo al mismo tiempo que una GUI administrativa,
porque el siguiente guardado de la GUI representa el snapshot en memoria.

## Verificación en servidor

Para validar una instalación nueva:

1. Crea un título desactivado con una regla pequeña, por ejemplo `ITEMS_OWNED: DIAMOND × 1`.
2. Personaliza toast, chat y sonido, y usa **Probar presentación**.
3. Activa el título, consigue el objeto y confirma toast, chat, sonido y recompensa una sola vez.
4. Reinicia el servidor, abre `/tag` y confirma que el desbloqueo y la selección siguen presentes.
5. Repite con PacketEvents instalado y sin él; revisa consola si el cliente no muestra el toast.

PacketEvents es recomendable para presentaciones dinámicas ilimitadas y aisladas por cliente,
pero no es obligatorio. La prueba dentro de la GUI no desbloquea, no equipa y no ejecuta
recompensas.

## Comportamientos actuales que debes conocer

- Los títulos desactivados dejan de contar como obtenidos y no bloquean colecciones. Sin
  embargo, esta versión todavía suma los puntos de una definición desactivada si su
  desbloqueo persistente existe; pon sus puntos en `0` antes de desactivarla si no debe
  afectar el perfil o ranking.
- Los comandos de recompensa no tienen todavía una cola durable de reintento. Un apagado
  después de guardar el desbloqueo y antes de ejecutar el comando no lo reproduce al volver
  a iniciar.

## Compatibilidad

Los títulos usan `{tag_prefix}` y `{tag_suffix}`. `/tag prefix|suffix` sigue disponible para
datos heredados; al equipar un título del catálogo, este toma prioridad. Al quitarlo reaparece
el tag heredado sin destruirlo.

El sistema de [nametags flotantes](nametags.es.md) continúa separado: puede usar los mismos
placeholders en su formato si se desea mostrar el título también sobre la cabeza.
Durante `/anonymous`, `{tag_prefix}` y `{tag_suffix}` se vacían para no filtrar la identidad
real del jugador.

Los plugins de terceros deben usar la [API versionada de títulos y logros](titles-api.es.md)
en vez de los gestores internos. Ofrece eventos durables de ciclo de vida, mutaciones
tipadas, futuros de progreso seguros para Folia y vistas de catálogo seguras para jugadores.
