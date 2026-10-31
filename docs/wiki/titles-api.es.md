# API de títulos y logros

## Contrato e instalación

La API v1 de Chatalyst es una frontera estable sobre los detalles internos `TagManager`,
`TagCatalog`, archivos YAML y GUIs. El build normal produce:

- `Chatalyst-1.0.0.jar`: plugin runtime completo.
- `Chatalyst-API-1.0.0.jar`: contrato pequeño de compilación bajo `dev.chatalyst.api`.

Añade el jar de API como `compileOnly`/`provided`; nunca lo coloques en la carpeta de plugins del
servidor. Declara Chatalyst en `depend` cuando sea obligatorio o en `softdepend` cuando la
integración pueda desactivarse.

```java
ChatalystApi api = ChatalystApi.get()
        .orElseThrow(() -> new IllegalStateException("Chatalyst no está disponible"));
TitlesApi titles = api.titles();
```

El servicio se registra después de cargar el catálogo y los almacenes de jugadores, y se retira
antes del apagado. `ChatalystApi.API_VERSION` versiona este contrato público de forma independiente
a la versión del plugin.

## Lecturas inmutables y privacidad

El servicio de títulos expone valores inmutables:

| Método | Resultado |
|---|---|
| `catalog()` | Catálogo administrativo completo, incluidas definiciones desactivadas, ocultas y secretas |
| `catalogFor(uuid)` | Entradas seguras para jugadores: omite títulos ocultos bloqueados y no entrega la definición de secretos bloqueados |
| `find(id)` / `equipped(uuid)` | Definición inmutable opcional |
| `isUnlocked(uuid, id)` | Acceso actual permanente, de temporada o a título gratuito |
| `profile(uuid)` | ID equipado, puntos derivados, títulos obtenidos/disponibles y totales de colecciones |
| `collections(uuid)` | Presentación, membresía y progreso del jugador por colección |
| `ranking(additionalPlayers)` | Snapshot determinista ordenado por puntos, obtenidos y UUID |
| `settings()` | Opciones de notificación y autoequipamiento del primer título |

`catalog()` es intencionalmente administrativo y contiene configuración secreta. Usa
`catalogFor()` para cualquier integración visible por jugadores. PlaceholderAPI solo muestra un
título equipado ya desbloqueado y oculta todos los valores de títulos/progreso en modo anónimo.

## Progreso y evaluación

Las estadísticas, advancements, inventario y permisos de Minecraft deben consultarse desde el
hilo propietario del jugador. Por eso estos métodos devuelven futuros:

```java
titles.progress(player, "hunter").thenAccept(progress ->
        progress.ifPresent(value ->
                getLogger().info(value.titleId() + ": " + value.satisfied())));

titles.evaluate(player).thenAccept(result -> {
    if (result.successful()) {
        getLogger().info("Títulos nuevos: " + result.unlocked().size());
    }
});
```

Chatalyst agenda ambas operaciones mediante el scheduler de entidad en Paper, Purpur y Folia.
`evaluate` usa el procesador normal de desbloqueos, por lo que los títulos recién obtenidos
reciben su toast de Minecraft, mensaje de chat, sonido y recompensas configuradas. Un jugador
offline o retirado produce `PLAYER_OFFLINE`; nunca usa como fallback un hilo inseguro.

## Mutaciones de estado tipadas

`equip`, `unequip`, `grant` y `revoke` devuelven `TitleMutationResult`. El estado distingue:

- `SUCCESS` y el idempotente `NO_CHANGE`.
- `NOT_FOUND`, `DISABLED`, `LOCKED`, `ALREADY_UNLOCKED` y `NOT_UNLOCKED`.
- `INVALID_ARGUMENT`, `VALIDATION_FAILED`, `DEPENDENCY_CONFLICT` y `CAPACITY_REACHED`.
- `STORAGE_FAILED` y `PLAYER_OFFLINE`.

El `TitleMutationContext` opcional registra `PLAYER`, `ADMIN`, `REQUIREMENT`, `AUTO_EQUIP`, `API`
o `SYSTEM`, además de actor y razón acotada. El contexto se copia a los eventos de ciclo de vida.

```java
TitleMutationContext context = TitleMutationContext.admin(
        moderator.getUniqueId(), moderator.getName(), "Premio de temporada");
TitleMutationResult result = titles.grant(player.getUniqueId(), "champion", context);
if (!result.successful()) {
    getLogger().warning(result.status() + ": " + result.message());
}
```

El desbloqueo y la selección se persisten antes de devolver `SUCCESS`. Conceder mediante la API
de estado sigue la semántica administrativa y no ejecuta comandos arbitrarios de recompensa;
usa `evaluate(player)` para la obtención normal por requisitos y su pipeline completo de
presentación/recompensas.

## Administración validada del catálogo

Usa `TitleDefinition.builder(id)` y pasa el resultado inmutable a `create` o `update`. Las
definiciones incluyen:

- Texto, descripción, material, posición `LEFT`/`RIGHT` y separación.
- Estado activado, oculto y secreto, categoría, rareza, orden y puntos.
- ID/inicio/fin de temporada.
- Grupos de requisitos `ALL`/`ANY` y todos los tipos de obtención.
- Comandos ordenados de recompensa desde consola.
- Texto/icono/marco del toast, mensaje de chat y presentación sonora.

El servicio también admite `duplicate`, `delete` seguro ante dependencias, `updateCollection` y
un `updateSettings` atómico para ambas opciones. Chatalyst valida IDs, materiales, tipo de item,
objetivos de reglas, cantidades, ventanas de temporada, dependencias ausentes, ciclos y el límite
de 500 títulos antes de escribir. `tags.yml` se reemplaza atómicamente antes de publicar el nuevo
snapshot.

## Eventos durables de ciclo de vida

Todos los eventos de títulos están en `dev.chatalyst.api`:

| Evento | Momento garantizado |
|---|---|
| `TitleUnlockEvent` | El nuevo desbloqueo ya es durable |
| `TitleEquipEvent` | La selección válida y desbloqueada ya es durable |
| `TitleUnequipEvent` | La eliminación de la selección ya es durable |
| `TitleRevokeEvent` | La eliminación del desbloqueo existente ya es durable |
| `TitleCollectionCompleteEvent` | Un nuevo desbloqueo completa una colección derivada |
| `TitleCatalogChangeEvent` | Una creación/edición/duplicación/eliminación por GUI o API ya se escribió atómicamente |

No se dispara ningún evento de ciclo de vida ante una operación idempotente o un fallo de
almacenamiento. Revocar un título equipado dispara el evento de revocación y después el de
desequipamiento automático. Los eventos son síncronos con la operación terminada, pero su marca
asíncrona de Bukkit sigue el contexto llamador; cada handler debe respetar el hilo del evento y
agendar correctamente el acceso a entidades o mundos.

Los eventos existentes `ChannelMessageEvent`, `ChannelSwitchEvent`, `RuleMatchEvent` y
`RuleReplaceEvent` mantienen compatibilidad de código fuente.

## Valores de PlaceholderAPI

La expansión `%chatalyst_...%` ahora incluye:

| Grupo | Placeholders |
|---|---|
| Identidad equipada | `title_id`, `title_name`, `title_text` |
| Presentación | `title_prefix`, `title_suffix`, `title_position` |
| Metadatos | `title_rarity`, `title_category`, `title_season`, `title_points` |
| Perfil de logros | `achievement_points`, `achievements_earned`, `achievements_total`, `achievements_percent` |
| Colecciones | `collections_completed`, `collections_total` |

El prefijo/sufijo incluye el lado configurado, `<reset>` y la separación. Un jugador sin título
equipado obtiene metadatos vacíos y cero puntos de título. Todos los valores de esta tabla quedan
vacíos mientras el jugador está en modo anónimo.

Los placeholders existentes de canal, apodo, moderación, AFK, rango, anonimato y matrimonio
mantienen compatibilidad.

