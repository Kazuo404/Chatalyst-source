package dev.chatalyst.anonymous;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import dev.chatalyst.db.Store;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.nick.NickManager;
import dev.chatalyst.util.FoliaTasks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Modo anonimo para streamers (/anonymous): apodo aleatorio ilegible, sin rango, sin tags,
 * sin party y con una skin de incognito. Funciona sobre las piezas ya existentes:
 * <ul>
 *   <li><b>Nombre</b>: un nick aleatorio via {@link NickManager} (chat, tab, TAB plugin y
 *       nametag fancy lo recogen solos; el nombre vanilla sobre la cabeza lo oculta
 *       Fancy_Nametags).</li>
 *   <li><b>Rango/tags</b>: {@code Placeholders} anula prefix, suffix, grupo, color de rango,
 *       /tag y party mientras el jugador este anonimo (tambien {player_name} en formatos,
 *       para que ni el hover ni el click de /msg filtren el nombre real).</li>
 *   <li><b>Skin</b>: se copia la skin (firmada) de la cuenta {@code Anonymous.Skin_Name}
 *       via Mojang y se aplica con {@code setPlayerProfile}; al salir del modo se restaura
 *       la original.</li>
 * </ul>
 * El estado persiste (Store "anonymous"): quien se desconecta anonimo vuelve anonimo.
 */
public final class AnonymousManager implements Listener {

    private static final String TEXTURES = "textures";
    /**
     * Alfabeto del alias (configurable en Anonymous.Name_Chars). El default produce
     * nombres tipo "illiliolil": parecen un nick real de Minecraft pero son confusos e
     * imposibles de teclear a mano.
     */
    private static final String DEFAULT_NAME_CHARS = "ilo";
    private static final int DEFAULT_NAME_LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JavaPlugin plugin;
    private final NickManager nicks;
    /** uuid -> apodo original ("" si no tenia). Presencia en el store = modo activo. */
    private final Store store;
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    /**
     * Nombre real de cada conectado, para {@link #disguisedNames()}. Ese metodo se llama
     * desde el hilo de red por cada paquete, y alli no se puede preguntar a Bukkit quien es un
     * UUID: su lista de jugadores es un HashMap que el hilo principal reescribe en cada
     * entrada y salida — el mismo riesgo que {@link #seesRealNames} ya evita con los permisos.
     *
     * <p>Se conserva unos segundos tras desconectar, a proposito: el aviso de salida de OTRO
     * plugin puede llegar despues de que Bukkit ya haya olvidado al jugador, y preguntandole
     * a Bukkit ese aviso salia con el nombre real sin tapar.
     */
    private final Map<UUID, String> realNames = new ConcurrentHashMap<>();
    // Quien puede ver nombres reales, respondido de memoria porque se pregunta desde el hilo
    // de red. Ver seesRealNames.
    private static final long REALNAME_TTL_MILLIS = 30_000L;
    private final Map<UUID, Boolean> realNameViewers = new ConcurrentHashMap<>();
    private final Map<UUID, Long> realNameChecked = new ConcurrentHashMap<>();
    private final Set<UUID> realNameChecking = ConcurrentHashMap.newKeySet();
    /** Snapshot de la skin real de la sesion, para restaurarla sin red al desactivar. */
    private final Map<UUID, ProfileProperty> originalSkin = new ConcurrentHashMap<>();
    /**
     * Identidad del disfraz de ESTA sesion (alias + skin). Sobrevive a un /anonymous off:
     * si vuelves a activarlo sin salir del servidor, eres LA MISMA persona — cambiar de
     * cara a media sesion delataria que ambos disfraces son el mismo jugador. Solo se
     * renueva al reconectar (Rotate_Name_On_Join).
     */
    private record Disguise(String alias, ProfileProperty skin) {
    }

    private final Map<UUID, Disguise> session = new ConcurrentHashMap<>();
    /**
     * Identidad de una cuenta real del pool: su nombre EXACTO (como lo devuelve Mojang) y su
     * skin firmada, EMPAREJADOS. En modo {@code Name_Style: accounts} el disfraz toma los dos
     * de la MISMA cuenta, para que nombre y cara sean un jugador de verdad y coherente.
     */
    private record PoolIdentity(String name, ProfileProperty skin) {
    }

    /** Pool de identidades reales descargadas (nombre + skin firmada de cada cuenta). */
    private volatile java.util.List<PoolIdentity> pool = java.util.List.of();
    /** Solo la descarga iniciada por la configuracion mas reciente puede publicar el pool. */
    private final AtomicLong poolLoadGeneration = new AtomicLong();

    /** Token por jugador: invalida aplicaciones/restauraciones de skin que quedaron async. */
    private final Map<UUID, Object> skinOperations = new ConcurrentHashMap<>();
    /** Evita capturar como "original" una skin falsa mientras su restauracion sigue pendiente. */
    private final Set<UUID> restoringRealSkin = ConcurrentHashMap.newKeySet();

    private volatile boolean enabled;
    private volatile String disguiseGroup = "default";
    /** El aviso del grupo inexistente se da UNA vez por carga, no en cada mensaje. */
    private final java.util.concurrent.atomic.AtomicBoolean warnedMissingGroup =
            new java.util.concurrent.atomic.AtomicBoolean();
    private volatile String disguiseTag = "";
    /**
     * Rango APARENTE del disfraz. Se resuelve perezosamente porque LuckPerms puede no tener
     * los grupos cargados cuando arrancamos; {@link #load()} lo invalida.
     */
    private volatile dev.chatalyst.hook.Placeholders.RankLook look;
    private volatile String skinSources = "";
    private volatile boolean realisticNames = true;
    /** Name_Style: accounts -> el alias ES el nombre real de la cuenta cuya skin lleva. */
    private volatile boolean useAccountNames;
    private volatile char[] nameChars = DEFAULT_NAME_CHARS.toCharArray();
    private volatile int nameLength = DEFAULT_NAME_LENGTH;
    private volatile boolean rotateOnJoin = true;
    /** Cooldown entre CAMBIOS de identidad (evita el churn sospechoso). 0 = desactivado. */
    private volatile long switchCooldownMs;
    /** Ultimo cambio de identidad por jugador, para el cooldown. */
    private final Map<UUID, Long> lastIdentityChange = new ConcurrentHashMap<>();

    /**
     * Llegadas del alias aun por anunciar. El disfraz se aplica AL INSTANTE — quien pide
     * anonimato lo necesita ya —, pero la entrada del alias no: una salida y una entrada en
     * el mismo segundo delatan al anonimo por si solas, porque nadie se reconecta en cero
     * segundos. Asi que el jugador "se va", juega disfrazado, y unos minutos despues el
     * alias "entra", que es justo lo que se ve cuando alguien vuelve de verdad.
     *
     * <p>El valor es un testigo: al vencer el plazo la tarea comprueba que sigue siendo el
     * suyo. Cancelar es entonces sustituir o borrar la entrada, sin depender de poder abortar
     * una tarea ya encolada.
     */
    private final Map<UUID, Long> pendingTransition = new ConcurrentHashMap<>();
    // Quien esta "desconectado" a ojos de los demas mientras espera su entrada.
    private final java.util.Set<UUID> hiddenForTransition = ConcurrentHashMap.newKeySet();
    // Jugadores cuya MARCHA ya se anuncio y cuya vuelta todavia no. Para el resto del server
    // esa gente no esta, y nada de lo que hagan puede volver a anunciarse hasta que "entren".
    private final java.util.Set<UUID> departed = ConcurrentHashMap.newKeySet();
    // Quien pidio disfrazarse y espera un momento en que nadie pueda verlo cambiar.
    private final Map<UUID, Long> awaitingPrivacy = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong transitionTokens =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Registro de auditoria (opcional): la unica constancia de quien hay detras de un disfraz.
     * Se inyecta por setter porque el LogManager se crea despues que este manager. Nullable:
     * sin el, el modo anonimo funciona igual, solo sin dejar rastro investigable.
     */
    private volatile dev.chatalyst.log.LogManager log;

    /** Disfraces guardados por jugador (slots reutilizables), en la misma BD. */
    private final AnonymousSlots slots;

    /**
     * Slot FIJADO por jugador: la cara elegida con {@code /anon set} deja de estar sujeta a
     * la rotacion por conexion. Rotar es lo correcto para un alias sorteado —cada sesion es
     * otra persona y nadie te sigue la pista—, pero cuando alguien elige una cara concreta
     * esta construyendo un personaje, y perderlo al reconectar convierte los slots en algo
     * que solo dura hasta el siguiente /reload.
     *
     * <p>Se guarda en la BD, no en memoria: tiene que sobrevivir a un reinicio, que es
     * justo cuando se notaba que no sobrevivia.
     */
    private final dev.chatalyst.db.Store pinned;

    public AnonymousManager(JavaPlugin plugin, NickManager nicks,
                            dev.chatalyst.db.StorageManager storage) {
        this.plugin = plugin;
        this.nicks = nicks;
        this.store = storage.store("anonymous");
        this.slots = new AnonymousSlots(storage);
        this.pinned = storage.store("anonymous_pinned");
    }

    /** Permiso base para usar los slots (/anon save, set, list, delete). */
    public static final String SLOTS_PERMISSION = "chatalyst.anonymous.save";
    /** Quien lo tenga cambia de identidad sin esperar el cooldown (admins). */
    public static final String COOLDOWN_BYPASS_PERMISSION = "chatalyst.anonymous.cooldown.bypass";

    /** Ms que faltan para poder cambiar de identidad otra vez (0 = puede ya / sin cooldown / bypass). */
    public long switchCooldownRemainingMs(Player player) {
        if (switchCooldownMs <= 0 || player.hasPermission(COOLDOWN_BYPASS_PERMISSION)) return 0;
        Long last = lastIdentityChange.get(player.getUniqueId());
        if (last == null) return 0;
        long elapsed = System.currentTimeMillis() - last;
        return elapsed >= switchCooldownMs ? 0 : switchCooldownMs - elapsed;
    }

    /**
     * Marca que el jugador acaba de cambiar de identidad (arranca su cooldown).
     *
     * <p>Privado a proposito: lo llama el propio manager en los DOS puntos donde una identidad
     * existe de verdad (activarse y cambiar de cara en caliente). Cuando lo llamaba el comando
     * no funcionaba, porque la espera a que nadie mire hace que {@link #activate} vuelva antes
     * de que haya nada activado — asi que alli siempre parecia que la activacion habia fallado
     * y el cooldown no arrancaba nunca.
     */
    private void markIdentityChange(UUID id) {
        if (switchCooldownMs > 0) lastIdentityChange.put(id, System.currentTimeMillis());
    }
    /** Prefijo del permiso de CANTIDAD: chatalyst.anonymous.slots.&lt;n&gt;. */
    public static final String SLOTS_LIMIT_PREFIX = "chatalyst.anonymous.slots.";

    /**
     * Identidad fijada del jugador, o null. Un slot fijado que ya no existe (lo borro) o
     * cuya cara lleva OTRO ahora mismo se ignora y se cae a la rotacion normal: mas vale un
     * alias nuevo que dos jugadores con el mismo disfraz, que delata a los dos.
     */
    private AnonymousSlots.Saved savedPin(UUID id) {
        int slot = pinnedSlot(id);
        if (slot < 0) return null;
        AnonymousSlots.Saved saved = slots.get(id, slot);
        if (saved == null) {
            pinSlot(id, -1);
            return null;
        }
        UUID holder = nicks.findByDisplay(saved.name());
        Player sameName = Bukkit.getPlayerExact(saved.name());
        boolean takenByOther = (holder != null && !holder.equals(id))
                || (sameName != null && !sameName.getUniqueId().equals(id));
        return takenByOther ? null : saved;
    }

    /** Slot fijado del jugador, o -1. */
    public int pinnedSlot(UUID id) {
        String raw = pinned.get(id.toString());
        if (raw == null || raw.isBlank()) return -1;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException corrupt) {
            return -1;
        }
    }

    /** Fija (o desfija con -1) el slot que sobrevive a las reconexiones. */
    private void pinSlot(UUID id, int slot) {
        if (slot < 0) pinned.remove(id.toString());
        else pinned.put(id.toString(), Integer.toString(slot));
    }

    public AnonymousSlots slots() {
        return slots;
    }

    /**
     * Cuantos slots puede guardar este jugador: el mayor {@code n} de un permiso
     * {@code chatalyst.anonymous.slots.<n>} que tenga. Los OP obtienen directamente el tope
     * {@code Anonymous.Max_Saved_Slots}: los nodos {@code slots.<n>} son {@code default: false}
     * (para no regalarlos a todo el mundo), y un {@code default: false} NO lo hereda ni el op,
     * asi que sin este atajo un admin tendria 0 slots. 0 = no puede guardar ninguno.
     */
    public int savedSlotLimit(Player player) {
        if (!player.hasPermission(SLOTS_PERMISSION)) return 0;
        int cap = Math.max(0, Math.min(64,
                plugin.getConfig().getInt("Anonymous.Max_Saved_Slots", 5)));
        if (player.isOp()) return cap; // admin: acceso completo hasta el tope
        int max = 0;
        for (var info : player.getEffectivePermissions()) {
            if (!info.getValue()) continue;
            String node = info.getPermission();
            if (node.startsWith(SLOTS_LIMIT_PREFIX)) {
                try {
                    max = Math.max(max, Integer.parseInt(node.substring(SLOTS_LIMIT_PREFIX.length())));
                } catch (NumberFormatException ignored) {
                    // ".*" u otro comodin: LuckPerms ya expande los slots.<n> declarados arriba
                }
            }
        }
        return Math.min(max, cap);
    }

    /** Inyecta el log de auditoria (real -> alias). Se llama tras crear el LogManager. */
    public void setLog(dev.chatalyst.log.LogManager log) {
        this.log = log;
    }

    // Efecto "reconexion" al hacer /anon estando conectado: en vez de que el nombre cambie en
    // sitio (y la gente ate cabos), se simula que el jugador SALIO (nombre real) y ENTRO el
    // alias (y al reves al desactivar). Los instala el plugin con los mensajes de join/quit.
    // Devuelven si el aviso LLEGO a alguien: una salida simulada no se difunde con el jugador
    // en vanish ni con los mensajes de salida apagados, y todo el disfraz de "se ha ido" que
    // hay montado encima solo tiene sentido si el server llego a leer que se iba.
    private volatile java.util.function.Predicate<Player> simulateQuit = p -> false;
    private volatile java.util.function.BiPredicate<Player, Runnable> simulateJoin =
            (p, atAnnouncement) -> false;

    public void setReconnectSimulators(java.util.function.Predicate<Player> quit,
                                       java.util.function.BiPredicate<Player, Runnable> join) {
        this.simulateQuit = quit != null ? quit : p -> false;
        this.simulateJoin = join != null ? join : (p, atAnnouncement) -> false;
    }

    /** ¿Se simula la salida+entrada al toggle? Config del dueño (default sí). */
    private boolean simulateReconnect() {
        return plugin.getConfig().getBoolean("Anonymous.Simulate_Reconnect", true);
    }

    /**
     * Deja constancia de la correspondencia nombre real -> alias en los logs diarios del
     * plugin, y SOLO ahi: ni la consola en vivo ni el chat la ven. Es lo que permite al staff
     * saber despues quien estaba de incognito sin delatarlo en el momento. Gateado por
     * {@code Log.Anonymous}; sin LogManager o con el toggle apagado, no escribe nada.
     */
    private void audit(Player player, String alias) {
        dev.chatalyst.log.LogManager current = log;
        if (current == null || !current.logsAnonymous()) return;
        current.log("ANONYMOUS", player.getName(),
                "-> " + dev.chatalyst.util.MM.strip(alias) + " (" + player.getUniqueId() + ")");
    }

    public void load() {
        // Quien ya estaba dentro cuando el plugin (re)arranca no pasa por onJoin.
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            realNames.put(online.getUniqueId(), online.getName());
        }
        long loadGeneration = poolLoadGeneration.incrementAndGet();
        enabled = plugin.getConfig().getBoolean("Anonymous.Enabled", true);
        String chars = plugin.getConfig().getString("Anonymous.Name_Chars", DEFAULT_NAME_CHARS);
        nameChars = (chars == null || chars.isBlank() ? DEFAULT_NAME_CHARS : chars.trim())
                .toCharArray();
        // 3..16: el limite alto es el maximo de un nombre de Minecraft (y del apodo).
        nameLength = Math.max(3, Math.min(16,
                plugin.getConfig().getInt("Anonymous.Name_Length", DEFAULT_NAME_LENGTH)));
        rotateOnJoin = plugin.getConfig().getBoolean("Anonymous.Rotate_Name_On_Join", true);
        // 0..3600s: cooldown entre cambios de disfraz; el permiso de bypass lo exonera.
        switchCooldownMs = Math.max(0, Math.min(3600,
                plugin.getConfig().getInt("Anonymous.Switch_Cooldown_Seconds", 0))) * 1000L;
        disguiseGroup = plugin.getConfig().getString("Anonymous.Disguise_Group", "default");
        // El tag lo escribe el dueño: se acepta tanto MiniMessage como codigos & clasicos
        // (los formatos parsean MiniMessage, no legacy).
        disguiseTag = dev.chatalyst.util.Legacy.toMini(
                plugin.getConfig().getString("Anonymous.Tag", ""));
        look = null; // se re-resuelve con la config nueva (o con los grupos ya cargados)
        // Y el aviso se vuelve a permitir: si el dueño acaba de corregir el grupo, callarse
        // estaria bien, pero si lo dejo mal tiene que volver a leerlo tras el reload.
        warnedMissingGroup.set(false);
        // Name_Style: "realistic" (default, nombres generados con pinta real), "confusing"
        // (alfabeto ilegible) o "accounts" (el alias ES el nombre real de la cuenta cuya skin
        // lleva — disfraz coherente: la misma persona de cabo a rabo).
        String nameStyle = plugin.getConfig().getString("Anonymous.Name_Style", "realistic");
        useAccountNames = "accounts".equalsIgnoreCase(nameStyle)
                || "real".equalsIgnoreCase(nameStyle);
        realisticNames = !"confusing".equalsIgnoreCase(nameStyle);
        // Pool de disfraces: la lista Skins (varias cuentas, una identidad por sesion) con
        // Skin_Name como respaldo/entrada extra. Solo se re-descarga si la lista cambio.
        java.util.List<String> accounts = new java.util.ArrayList<>(
                plugin.getConfig().getStringList("Anonymous.Skins"));
        String single = dev.chatalyst.config.ConfigFiles.string(
                plugin.getConfig(), "Anonymous.Skin_Name", "");
        if (single != null && !single.isBlank() && !accounts.contains(single)) {
            accounts.add(single);
        }
        String sources = String.join(",", accounts).toLowerCase(java.util.Locale.ROOT);
        if (enabled && (pool.isEmpty() || !sources.equals(skinSources))) {
            skinSources = sources;
            fetchIdentities(java.util.List.copyOf(accounts), sources, loadGeneration);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** True si este jugador esta en modo anonimo (lo consulta Placeholders por mensaje). */
    public boolean isAnonymous(UUID id) {
        return active.contains(id);
    }

    /**
     * Si a este espectador NO se le tapa el nombre real, porque puede resolver identidades de
     * todos modos con {@code /realname}.
     *
     * <p>Se pregunta desde el hilo de red, al vuelo, por cada paquete que sale hacia el
     * cliente. Y {@code Player.hasPermission} no se puede llamar desde ahi: sin LuckPerms
     * delante, Bukkit resuelve contra un {@code HashMap} que el hilo principal reconstruye en
     * cada {@code recalculatePermissions}, y leerlo mientras se reescribe es el cuelgue clasico
     * del HashMap. El resto del plugin ya lo evita — {@code StaffAccess} usa la via asincrona
     * de LuckPerms —; esto era lo unico que quedaba preguntando en crudo.
     *
     * <p>Asi que se responde de memoria y se refresca en el hilo que le toca. Mientras no se
     * sepa, la respuesta es NO: al staff se le tapa un nombre de mas durante un instante, que
     * no rompe nada. Al reves — dejar pasar un nombre real por no saber — es exactamente la
     * fuga que todo esto existe para evitar.
     */
    public boolean seesRealNames(Player viewer) {
        if (viewer == null) return false;
        UUID id = viewer.getUniqueId();
        Boolean known = realNameViewers.get(id);
        long checked = realNameChecked.getOrDefault(id, 0L);
        if (known == null || System.currentTimeMillis() - checked > REALNAME_TTL_MILLIS) {
            // Una sola tarea en vuelo por jugador: sin esto, un jugador recibiendo action bars
            // cada tick programaria veinte comprobaciones por segundo.
            if (realNameChecking.add(id)) {
                dev.chatalyst.util.FoliaTasks.entity(plugin, viewer, online -> {
                    realNameViewers.put(id, online.hasPermission("chatalyst.command.realname"));
                    realNameChecked.put(id, System.currentTimeMillis());
                    realNameChecking.remove(id);
                });
            }
        }
        return known != null && known;
    }

    /** Se olvida al desconectar: el permiso puede ser otro la proxima vez que entre. */
    public void forgetRealNameViewer(UUID id) {
        realNameViewers.remove(id);
        realNameChecked.remove(id);
        realNameChecking.remove(id);
    }

    /** True si hay alguien disfrazado ahora mismo. Corte barato para los caminos calientes. */
    public boolean anyDisguised() {
        return !active.isEmpty();
    }

    /**
     * Nombre real -> alias de quien esta disfrazado AHORA, para tapar el nombre en texto que
     * este plugin no escribio (ver {@link AnonymousNameGuard}).
     *
     * <p>Se construye al vuelo y no se cachea: son como mucho un puñado de entradas, y el
     * unico coste real es cuando hay gente disfrazada — con el mapa vacio, quien llama ni
     * llega aqui. Un alias cacheado, en cambio, se queda viejo justo cuando importa, que es
     * en la rotacion y en el cambio de cara.
     */
    public java.util.Map<String, String> disguisedNames() {
        if (active.isEmpty()) return java.util.Map.of();
        java.util.Map<String, String> out = new java.util.HashMap<>();
        for (UUID id : active) {
            String real = realNames.get(id);
            if (real == null) continue;
            Disguise disguise = session.get(id);
            String alias = disguise != null ? disguise.alias() : nicks.getNick(id);
            if (alias == null || alias.isEmpty()) continue;
            String plain = dev.chatalyst.util.MM.strip(alias);
            if (!plain.equals(real)) out.put(real, plain);
        }
        return out.isEmpty() ? java.util.Map.of() : java.util.Map.copyOf(out);
    }

    /**
     * Rango con el que se muestra un anonimo en chat, tab, nametag y hovers: el tag literal
     * de {@code Anonymous.Tag} si esta puesto, y si no el prefijo del grupo
     * {@code Anonymous.Disguise_Group} (el de un miembro normal). Con ambos vacios el
     * anonimo sale sin tag, como antes.
     */
    public dev.chatalyst.hook.Placeholders.RankLook look() {
        var cached = look;
        if (cached != null) return cached;
        dev.chatalyst.hook.Placeholders.RankLook resolved;
        if (disguiseTag != null && !disguiseTag.isBlank()) {
            resolved = new dev.chatalyst.hook.Placeholders.RankLook(
                    emojis(disguiseTag), "", disguiseGroup == null ? "" : disguiseGroup);
        } else if (disguiseGroup == null || disguiseGroup.isBlank()) {
            resolved = dev.chatalyst.hook.Placeholders.RankLook.NONE;
        } else {
            // Un grupo que LuckPerms no conoce no da error: da un disfraz SIN rango, y eso no
            // se nota hasta que alguien se disfraza y sale sin nada delante del nombre. Se
            // avisa una vez y se cae al grupo que siempre existe, que es el de un miembro
            // normal — que es justo lo que el disfraz quiere aparentar.
            if (dev.chatalyst.hook.LuckPermsHook.isAvailable()
                    && !dev.chatalyst.hook.LuckPermsHook.groupExists(disguiseGroup)) {
                if (warnedMissingGroup.compareAndSet(false, true)) {
                    plugin.getLogger().warning("Anonymous.Disguise_Group is set to '"
                            + disguiseGroup + "', which LuckPerms does not have. Anonymous"
                            + " players would show no rank at all; using 'default' instead."
                            + " Set it to the group a normal member wears.");
                }
                disguiseGroup = "default";
            }
            String prefix = emojis(dev.chatalyst.hook.LuckPermsHook.groupPrefix(disguiseGroup));
            String suffix = emojis(dev.chatalyst.hook.LuckPermsHook.groupSuffix(disguiseGroup));
            resolved = new dev.chatalyst.hook.Placeholders.RankLook(prefix, suffix, disguiseGroup);
            // Un prefijo vacio con LuckPerms disponible suele significar "los grupos aun no
            // estaban cargados": no se cachea para reintentarlo en el proximo mensaje.
            if (prefix.isEmpty() && dev.chatalyst.hook.LuckPermsHook.isAvailable()) {
                return resolved;
            }
        }
        look = resolved;
        return resolved;
    }

    /**
     * Los :tokens: de ItemsAdder del tag (lo escribe el dueño: sin chequeo de permisos) se
     * resuelven AQUI, una vez y cacheados: el disfraz se lee en cada parte de cada formato,
     * y convertirlo alli lo repetiria 15-20 veces por mensaje.
     */
    private static String emojis(String text) {
        return dev.chatalyst.hook.ItemsAdderHook.replaceEmojisUnchecked(text);
    }

    /**
     * Perfil con la skin de incognito para las CABEZAS de las GUIs, o null si el jugador no
     * esta anonimo (o la skin aun no se descargo). Lleva un UUID aleatorio a proposito: el
     * perfil de una cabeza viaja al cliente, y con el UUID real cualquiera podria buscar la
     * cuenta. Solo la textura importa para renderizar.
     */
    public PlayerProfile disguisedProfile(UUID id) {
        // La MISMA skin que el jugador lleva esta sesion, para que su cabeza en las GUIs
        // coincida con su personaje (y no delate nada).
        Disguise disguise = session.get(id);
        ProfileProperty skin = disguise == null ? null : disguise.skin();
        if (skin == null || !isAnonymous(id)) return null;
        PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID(), "Anonymous");
        profile.setProperty(new ProfileProperty(TEXTURES, skin.getValue(), skin.getSignature()));
        return profile;
    }

    /** Una skin al azar del pool descargado, o null si aun no hay ninguna. */
    private ProfileProperty randomSkin() {
        java.util.List<PoolIdentity> snapshot = pool;
        return snapshot.isEmpty() ? null
                : snapshot.get(RANDOM.nextInt(snapshot.size())).skin();
    }

    /** Skin de la cuenta del pool cuyo nombre coincide (para re-emparejar tras un reload). */
    private ProfileProperty poolSkinFor(String name) {
        if (name == null || name.isEmpty()) return null;
        for (PoolIdentity pi : pool) {
            if (pi.name().equalsIgnoreCase(name)) return pi.skin();
        }
        return null;
    }

    /**
     * Una identidad del pool (cuenta real: nombre + su skin) que este LIBRE para disfrazarse:
     * su nombre no lo usa ya otro anonimo/conectado ni pertenece a un miembro del servidor, y
     * no es el alias de la sesion anterior de ESTE jugador. Null si el pool esta vacio o todas
     * estan ocupadas. Se recorre en orden aleatorio para no dar siempre la misma.
     */
    private PoolIdentity freePoolIdentity(UUID id) {
        java.util.List<PoolIdentity> snapshot = pool;
        if (snapshot.isEmpty()) return null;
        String previous = id == null ? null : lastAlias.get(id);
        // Disfraces que alguien ya GUARDO: quedan reservados, no se reparten al azar (asi dos
        // jugadores no acaban con la misma cara; el dueño la recupera con /anon set).
        java.util.Set<String> reserved = slots.reservedNames();
        java.util.List<PoolIdentity> shuffled = new java.util.ArrayList<>(snapshot);
        java.util.Collections.shuffle(shuffled, RANDOM);
        for (PoolIdentity pi : shuffled) {
            if (previous != null && pi.name().equalsIgnoreCase(previous)) continue;
            if (reserved.contains(pi.name().toLowerCase(java.util.Locale.ROOT))) continue;
            // isFree ya excluye a los anonimos activos (su nick esta puesto), a los conectados
            // y a cualquiera que haya jugado aqui: un disfraz nunca suplanta a un miembro real.
            if (isFree(pi.name())) return pi;
        }
        return null;
    }

    // La identidad por la via de Paper, o null si Paper no la resuelve (sin textura, cuenta
    // inexistente o servidor offline, que no busca perfiles).
    private static PoolIdentity identityFromPaper(String name) {
        PlayerProfile profile = Bukkit.createProfile(null, name);
        if (!profile.complete(true)) return null;
        String resolved = profile.getName() != null && !profile.getName().isBlank()
                ? profile.getName() : name;
        for (ProfileProperty property : profile.getProperties()) {
            if (TEXTURES.equals(property.getName()) && property.getSignature() != null) {
                return new PoolIdentity(resolved, property);
            }
        }
        return null;
    }

    // Descarga (async) las identidades FIRMADAS (nombre real + skin) de las cuentas del pool;
    // sin firma el cliente rechaza la skin, por eso se copian de perfiles reales de Mojang en
    // vez de aceptar URLs. El nombre se toma tal cual lo devuelve Mojang (caja correcta), para
    // que el modo "accounts" disfrace con una identidad autentica. Las cuentas inexistentes o
    // sin textura simplemente se saltan (con aviso en consola).
    private void fetchIdentities(java.util.List<String> accounts, String sources,
                                 long loadGeneration) {
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            java.util.List<PoolIdentity> fetched = new java.util.ArrayList<>();
            for (String name : accounts) {
                if (name == null || name.isBlank()) continue;
                try {
                    PoolIdentity identity = identityFromPaper(name.trim());
                    if (identity == null) {
                        // Paper no busca perfiles con online-mode=false (red no premium detras de
                        // Velocity): complete() devolvia false sin motivo y el pool se quedaba
                        // vacio aunque Mojang respondiera. Se pide la skin firmada directamente.
                        MojangSkinLookup.Skin skin = MojangSkinLookup.fetch(name.trim());
                        identity = new PoolIdentity(skin.name(),
                                new ProfileProperty(TEXTURES, skin.value(), skin.signature()));
                    }
                    fetched.add(identity);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable failure) {
                    plugin.getLogger().warning("Anonymous disguise pool: could not load '"
                            + name + "': " + failure.getMessage());
                }
            }
            // Una descarga lenta de la config anterior no puede reemplazar el pool nuevo
            // ni reactivarlo despues de deshabilitar Anonymous durante un reload.
            if (poolLoadGeneration.get() != loadGeneration || !enabled
                    || !sources.equals(skinSources)) return;
            if (fetched.isEmpty()) {
                plugin.getLogger().warning("Anonymous disguise pool: no accounts could be loaded"
                        + " (" + accounts.size() + " tried). /anonymous will keep each player's"
                        + " own skin until a reload succeeds.");
                return;
            }
            // Swap atomico: los que ya estan disfrazados conservan su identidad de sesion.
            pool = java.util.List.copyOf(fetched);
            plugin.getLogger().info("Anonymous disguise pool loaded: " + fetched.size()
                    + " of " + accounts.size() + " account(s).");
            // Quien activo el modo mientras Mojang respondia no debe quedarse con su skin
            // real visible hasta el siguiente reload.
            FoliaTasks.onlinePlayers(plugin,
                    online -> isAnonymous(online.getUniqueId()),
                    online -> applyAnonymousSkin(online, false));
            });
        } catch (Throwable rejected) {
            if (enabled && poolLoadGeneration.get() == loadGeneration) {
                plugin.getLogger().warning("Anonymous disguise pool could not start loading: "
                        + rejected.getMessage());
            }
        }
    }

    /** Activa o desactiva el modo para el jugador; devuelve el estado resultante. */
    public boolean toggle(Player player) {
        // Un disfraz PEDIDO pero todavia esperando su momento cuenta como encendido para
        // esto: quien vuelve a pulsar quiere deshacer lo que pidio, y tratarlo como "aun no
        // estas anonimo" lo encenderia por segunda vez en vez de cancelarlo.
        // Y el estado que se devuelve es el que hay, no el que se pedia: cancelar la espera
        // de un APAGADO te deja anonimo, y decir "desactivado" ahi seria mentir.
        if (cancelIfAwaiting(player)) return isAnonymous(player.getUniqueId());
        if (isAnonymous(player.getUniqueId())) {
            deactivate(player);
            return false;
        }
        activate(player);
        return true;
    }

    /**
     * Cancela un disfraz pedido que aun no se ha aplicado, si lo hay.
     *
     * <p>Sin esto, apagar el modo mientras espera respondia "ya estabas desactivado" y dejaba
     * la espera viva: el jugador leia que no pasaba nada y un minuto despues se encontraba
     * disfrazado, sin haber pedido nada dos veces.
     *
     * @return true si de verdad habia algo que cancelar (y ya se aviso al jugador).
     */
    public boolean cancelIfAwaiting(Player player) {
        if (player == null || !awaitingPrivacy(player.getUniqueId())) return false;
        UUID id = player.getUniqueId();
        cancelPrivacyWait(id);
        player.sendMessage(Lang.get(player, cancelledKey(isAnonymous(id))));
        return true;
    }

    /**
     * Que espera se esta anunciando. Mientras dura, el jugador sigue marcado como estaba: si
     * es anonimo lo pendiente es RECUPERAR su nombre, y si no lo es, ponerse el disfraz. Un
     * aviso al reves seria peor que ninguno — le diria que va a pasar justo lo contrario.
     */
    static String waitingKey(boolean alreadyAnonymous) {
        return alreadyAnonymous
                ? "anonymous.waiting-privacy-off" : "anonymous.waiting-privacy";
    }

    static String cancelledKey(boolean alreadyAnonymous) {
        return alreadyAnonymous
                ? "anonymous.waiting-cancelled-off" : "anonymous.waiting-cancelled";
    }

    /**
     * Nucleo puro de "¿la espera en curso lleva a donde el jugador acaba de pedir?".
     *
     * <p>Una espera puede durar minutos, y en ese rato el jugador vuelve a escribir. Si pide lo
     * MISMO que ya se esta esperando es impaciencia y hay que recordarselo; si pide lo
     * CONTRARIO es arrepentimiento y hay que cancelar. Confundir los dos casos es lo que hacia
     * que {@code /anon on} durante una espera de apagado contestara "ya estabas anonimo" y te
     * desnudara igual un minuto despues.
     *
     * <p>La direccion no se guarda porque no hace falta: mientras la espera vive el jugador
     * sigue marcado como estaba, asi que lo que se espera es siempre lo contrario de lo que es.
     */
    static boolean waitLeadsTo(boolean anonymousNow, boolean wantsAnonymous) {
        return !anonymousNow == wantsAnonymous;
    }

    public void activate(Player player) {
        // Un alias sorteado sustituye al personaje fijado: pediste una cara cualquiera.
        pinSlot(player.getUniqueId(), -1);
        whenUnobserved(player, () -> activateInternal(player, null));
    }

    /**
     * Runs the disguise swap at a moment when nobody can see it happen.
     *
     * <p>Hiding the player for minutes worked, but it solved the problem by taking them out of
     * the game: a ghost who cannot be seen, spoken to or bumped into, for as long as the fake
     * reconnect lasted. The swap does not need the player to be invisible — it needs the
     * change to be UNWITNESSED, and those are different things.
     *
     * <p>So the swap simply waits until no one else is close enough to see it, and then
     * happens in the open with nothing hidden. Alone in a cave, walking away, at night, on a
     * quiet server: instant and seamless. Only if the wait runs out — standing in a crowded
     * spawn — does it fall back to the old disappearing act, because at that point the choice
     * is between a moment of ghosting and being seen morphing on the spot.
     *
     * <p>Vanished staff do NOT count as watchers. They can see it, but they can see everything
     * by design, and letting them block it would leave a player waiting on something they have
     * no way to understand or fix.
     */
    private void whenUnobserved(Player player, Runnable swap) {
        if (!waitUntilUnobserved()) {
            swap.run();
            return;
        }
        observed(player, watched -> {
            if (!watched) {
                FoliaTasks.entity(plugin, player, ignored -> swap.run());
                return;
            }
            UUID id = player.getUniqueId();
            long token = transitionTokens.incrementAndGet();
            awaitingPrivacy.put(id, token);
            player.sendMessage(Lang.get(player, waitingKey(isAnonymous(id))));
            long wait = Math.max(1L, maxWaitSeconds());
            long deadline = System.currentTimeMillis() + wait * 1_000L;
            pollForPrivacy(id, token, swap, deadline);
            // Techo independiente del sondeo. El limite de espera solo se comprueba al empezar
            // cada vuelta, y cada vuelta la programa la anterior: un solo callback perdido
            // — una region que se retira, un plugin que se recarga — rompia la cadena y dejaba
            // al jugador esperando un disfraz que ya no iba a llegar nunca, sin error ninguno.
            FoliaTasks.globalLater(plugin, () -> {
                if (!Long.valueOf(token).equals(awaitingPrivacy.get(id))) return;
                awaitingPrivacy.remove(id);
                Player online = Bukkit.getPlayer(id);
                if (online != null) FoliaTasks.entity(plugin, online, ignored -> swap.run());
            }, wait * 20L + 20L);
        });
    }

    private void pollForPrivacy(UUID id, long token, Runnable swap, long deadline) {
        FoliaTasks.globalLater(plugin, () -> {
            if (!Long.valueOf(token).equals(awaitingPrivacy.get(id))) return; // cancelled
            Player online = Bukkit.getPlayer(id);
            if (online == null) {
                awaitingPrivacy.remove(id);
                return;
            }
            // Out of time counts as "good enough": the fallback hides them instead, so a
            // crowded spawn still gets the disguise rather than waiting for ever.
            if (System.currentTimeMillis() >= deadline) {
                awaitingPrivacy.remove(id);
                FoliaTasks.entity(plugin, online, ignored -> swap.run());
                return;
            }
            observed(online, watched -> {
                // Checked again on arrival: the answer took a moment to gather, and in that
                // moment the player may have turned the mode off or disconnected.
                if (!Long.valueOf(token).equals(awaitingPrivacy.get(id))) return;
                if (!watched) {
                    awaitingPrivacy.remove(id);
                    FoliaTasks.entity(plugin, online, ignored -> swap.run());
                    return;
                }
                pollForPrivacy(id, token, swap, deadline);
            });
        }, 20L);
    }

    /**
     * Asks whether anyone is near enough to watch this happen, and answers on the global
     * thread.
     *
     * <p>Deliberately asynchronous. Reading another player's position is only safe on THEIR
     * own thread: this server runs Folia, where players in different regions are ticked in
     * parallel, and reaching across for a location is a data race that gives a torn answer or
     * throws outright. So each candidate is visited on their own scheduler, compared against
     * a position captured beforehand, and the verdict is collected at the end.
     *
     * <p>The subject's position is a snapshot from a moment ago rather than live, which is
     * exactly right for the question being asked: "was anyone around when this was decided".
     */
    private void observed(Player player, java.util.function.Consumer<Boolean> answer) {
        int radius = observerRadius();
        if (radius <= 0) {
            answer.accept(false);
            return;
        }
        double squared = (double) radius * radius;
        UUID id = player.getUniqueId();
        FoliaTasks.entity(plugin, player, subject -> {
            org.bukkit.Location here = subject.getLocation();
            java.util.concurrent.atomic.AtomicBoolean seen =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            FoliaTasks.onlinePlayers(plugin,
                    other -> !other.getUniqueId().equals(id)
                            && !dev.chatalyst.util.Vanish.isVanished(other),
                    other -> {
                        if (!other.getWorld().equals(here.getWorld())) return;
                        if (other.getLocation().distanceSquared(here) <= squared) {
                            seen.set(true);
                        }
                    },
                    () -> answer.accept(seen.get()));
        });
    }

    private boolean waitUntilUnobserved() {
        return plugin.getConfig().getBoolean(
                "Anonymous.Realistic_Transition.Wait_Until_Unobserved", true);
    }

    private int observerRadius() {
        return plugin.getConfig().getInt(
                "Anonymous.Realistic_Transition.Observer_Radius", 64);
    }

    /**
     * Techo de la espera, saneado como los demas tramos: entre 1 segundo y una hora.
     *
     * <p>Era el unico plazo sin limite (los de {@link #randomSeconds} pasan por
     * {@link #randomBetween}). Un numero de mas en la config no daba error: programaba el
     * techo para dentro de horas y dejaba al jugador esperando un disfraz que no llegaba,
     * exactamente el sintoma que la tarea de techo existe para que no ocurra.
     */
    private long maxWaitSeconds() {
        return Math.max(1L, Math.min(3_600L, plugin.getConfig().getLong(
                "Anonymous.Realistic_Transition.Max_Wait_Seconds", 120L)));
    }

    /** True while this player's swap is waiting for a moment nobody is watching. */
    public boolean awaitingPrivacy(UUID id) {
        return id != null && awaitingPrivacy.containsKey(id);
    }

    /** Forgets a pending wait, e.g. the player turned the mode off before it fired. */
    public void cancelPrivacyWait(UUID id) {
        awaitingPrivacy.remove(id);
    }

    /** Olvida una transicion programada. Devuelve true si de verdad habia una. */
    public boolean cancelPendingTransition(UUID id) {
        boolean had = pendingTransition.remove(id) != null;
        // Si estaba escondido esperando su entrada, esa entrada ya no va a llegar: dejarlo
        // invisible lo borraria del servidor para todos menos para el.
        reveal(id);
        return had;
    }

    /**
     * Olvida la llegada pendiente SIN devolver la visibilidad, para quien esta a punto de
     * cambiarle la cara al jugador.
     *
     * <p>Revelarlo aqui lo devolveria a la lista de jugadores con la identidad VIEJA, y unas
     * lineas despues se le cambiaria el nombre delante de todos: quien tuviera el tab abierto
     * veria la entrada mutar de alias a nombre real. Es el cambio en sitio que toda esta
     * maquinaria existe para que nadie vea, colado por la puerta de atras.
     *
     * <p>Sigue oculto, entonces, hasta su proxima llegada — {@link #arrive} o
     * {@link #announceArrivalNow} —, que es justo cuando ya lleva la cara nueva puesta.
     */
    private boolean cancelPendingArrival(UUID id) {
        return pendingTransition.remove(id) != null;
    }

    /**
     * Deja de verse mientras dura la transicion, como una desconexion de verdad.
     *
     * <p>El aviso de salida ya se cuidaba — se anunciaba la marcha y la entrada del alias
     * llegaba minutos despues —, pero el disfraz se ponia AL INSTANTE, asi que quien estuviera
     * mirando veia al jugador transformarse en el sitio justo cuando el chat decia que se
     * habia ido. Todo el trabajo de hacer creible la reconexion lo tiraba ese detalle.
     *
     * <p>Ahora desaparece al irse y reaparece ya con el alias cuando el chat anuncia que
     * entra, que es exactamente lo que se ve cuando alguien se reconecta. Sigue jugando
     * mientras tanto: lo unico que se le quita es ser visible.
     */
    private void hideForTransition(Player player) {
        if (!plugin.getConfig().getBoolean(
                "Anonymous.Realistic_Transition.Hide_During_Transition", true)) {
            return;
        }
        UUID id = player.getUniqueId();
        hiddenForTransition.add(id);
        FoliaTasks.onlinePlayers(plugin, viewer -> {
            if (!viewer.getUniqueId().equals(id)) viewer.hidePlayer(plugin, player);
        });
        refreshNametag(player);
    }

    /**
     * Repaints the floating nametag now that visibility changed.
     *
     * <p>The tag is a display entity of its OWN, not part of the player, so hiding the player
     * leaves it hanging in the air with nobody under it — which draws the eye far more than
     * not hiding anything would have. Waiting for the periodic refresh would leave that name
     * floating for a whole cycle, so it is repainted here.
     */
    private void refreshNametag(Player player) {
        // This manager holds a plain JavaPlugin (it is usable without the rest of Chatalyst),
        // so the nametag system is reached through the running instance instead.
        var chatalyst = dev.chatalyst.ChatalystPlugin.get();
        var nametags = chatalyst == null ? null : chatalyst.getFancyNametags();
        if (nametags == null) return;
        FoliaTasks.entity(plugin, player, nametags::refresh);
    }

    /** Vuelve a ser visible. Idempotente: llamarlo de mas no cuesta nada. */
    private void reveal(UUID id) {
        if (!hiddenForTransition.remove(id)) return;
        Player player = Bukkit.getPlayer(id);
        if (player == null) return; // se desconecto: Bukkit reinicia la visibilidad solo
        FoliaTasks.onlinePlayers(plugin, viewer -> {
            if (viewer.getUniqueId().equals(id) || hiddenByVanish(viewer, player)) return;
            viewer.showPlayer(plugin, player);
        });
        refreshNametag(player);
    }

    /**
     * El vanish de staff oculta con la MISMA clave de plugin que esta transicion: un
     * showPlayer aqui deshacia tambien el vanish, y el staff reaparecia para todos al
     * terminar su /anonymous. Quien esta en vanish sigue oculto para quien no puede verlo.
     */
    private static boolean hiddenByVanish(Player viewer, Player target) {
        var chatalyst = dev.chatalyst.ChatalystPlugin.get();
        var staff = chatalyst == null ? null : chatalyst.getStaffModeration();
        if (staff == null || !staff.enabled() || staff.vanish() == null) return false;
        return staff.vanish().isOwned(target)
                && !dev.chatalyst.staff.StaffAccess.canSeeVanished(viewer);
    }

    /** Si este jugador esta invisible esperando a que se anuncie su entrada. */
    public boolean hiddenForTransition(UUID id) {
        return id != null && hiddenForTransition.contains(id);
    }

    /**
     * Anuncia la marcha de la identidad que el jugador lleva AHORA.
     *
     * <p>Salvo que su LLEGADA siga pendiente: entonces nadie sabe todavia que esa identidad
     * estaba ahi, y anunciar su salida enseñaria a alguien marchandose sin haber entrado
     * nunca. Las dos se anulan, y lo que queda en el chat es justo lo que un espectador
     * esperaria — que no ha pasado nada.
     */
    private boolean announceDeparture(Player player) {
        // La llegada anterior sigue en el aire: el server ya cree que este jugador no esta y
        // sigue oculto. La ilusion no se rehace, se continua — por eso cuenta como anunciada.
        if (cancelPendingArrival(player.getUniqueId())) return true;
        // Y si el adios no lo lee nadie — el jugador esta en vanish, o los mensajes de salida
        // estan apagados — no hay nada que fingir. Marcarlo igual era lo que dejaba al jugador
        // sin chat, sin privados y sin linea de muerte durante minutos, escondiendo una
        // correlacion que no existia porque nadie habia visto marcharse a nadie.
        if (!simulateQuit.test(player)) return false;
        departed.add(player.getUniqueId());
        return true;
    }

    /**
     * Entrada YA, sin retraso: los caminos donde no hay nada que hacer creible (la transicion
     * apagada) o donde el cambio fallo y hay que devolver al jugador al chat sin dejar a nadie
     * creyendo que se desconecto.
     */
    private void announceArrivalNow(Player player) {
        // Vuelve a verse aqui y no antes: si la llegada anterior se cancelo para cambiarle la
        // cara, siguio oculto justo para que el cambio no se viera. Ahora ya lleva la nueva.
        departed.remove(player.getUniqueId());
        // Reaparecer va DENTRO del anuncio, no antes (ver broadcastSimulatedJoin). Si no hubo
        // anuncio ninguno, se reaparece igual: quedarse invisible seria mucho peor.
        UUID arriving = player.getUniqueId();
        if (!simulateJoin.test(player, () -> reveal(arriving))) reveal(arriving);
    }

    /**
     * True mientras el server entero cree que este jugador ya se fue.
     *
     * <p>Entre la salida simulada y la entrada simulada hay un hueco en el que, para todos
     * menos para el, ese jugador no esta. Si se desconecta de verdad justo ahi, anunciar su
     * marcha la cuenta DOS veces — y en el peor caso con dos nombres distintos, que es la
     * unica pista que hacia falta para atar el alias a la persona.
     */
    public boolean departureAnnounced(UUID id) {
        return id != null && departed.contains(id);
    }

    /**
     * Anuncia la entrada de la identidad que el jugador lleva AHORA, unos minutos despues.
     *
     * <p>Lo que se retrasa es solo el aviso: el disfraz ya esta puesto y el jugador ya puede
     * jugar. Quien mira el chat ve una salida y, mas tarde, una entrada — que es exactamente
     * lo que se ve cuando alguien se reconecta de verdad.
     */
    private void announceArrival(Player player) {
        UUID id = player.getUniqueId();
        if (!plugin.getConfig().getBoolean(
                "Anonymous.Realistic_Transition.Enabled", true)) {
            announceArrivalNow(player);
            return;
        }
        // Nobody around to see the swap: the rejoin can be quick — a fast relog is the most
        // ordinary thing on a server. Only when the change had to happen in front of people is
        // the long absence needed, because that is what makes it believable.
        //
        // What proximity does NOT decide is the hiding. It used to: with nobody within
        // Observer_Radius the player stayed visible through the gap, and hiding is what makes
        // their PLAYER LIST entry leave and come back. Left visible, that entry is the same row
        // all along and only its text changes — DevKazuo turning into Kf3v9x, in place, for the
        // whole server at once, no matter how far away anyone is standing. Which is the exact
        // change everything here exists to keep from being seen, happening in the one place
        // that has no distance at all. So the wait is always spent out of sight; when nobody
        // saw it, that is only the few seconds of the short tier.
        observed(player, witnessed -> {
            long shortWait = randomSeconds(
                    "Anonymous.Realistic_Transition.Rejoin_Min_Seconds", 5,
                    "Anonymous.Realistic_Transition.Rejoin_Max_Seconds", 20);
            long seconds = witnessed
                    ? randomSeconds("Anonymous.Realistic_Transition.Min_Seconds", 60,
                            "Anonymous.Realistic_Transition.Max_Seconds", 300)
                    : shortWait;
            long token = transitionTokens.incrementAndGet();
            pendingTransition.put(id, token);
            // Se va de la vista AHORA, no cuando lo diga el chat: el aviso de salida ya salio.
            hideForTransition(player);
            dev.chatalyst.util.FoliaTasks.globalLater(plugin,
                    () -> arrive(id, player, token), seconds * 20L);
            // La espera larga es un TECHO, no una condena: en cuanto deje de haber gente
            // delante, la entrada se adelanta al tramo corto. Ver pollForArrival.
            if (witnessed) {
                pollForArrival(id, token, System.currentTimeMillis() + shortWait * 1_000L);
            }
        });
    }

    /** Revela al jugador y anuncia su entrada, si esa entrada sigue siendo la vigente. */
    private void arrive(UUID id, Player player, long token) {
        // El testigo es la cancelacion: si ya no es el nuestro, alguien lo sustituyo.
        if (!Long.valueOf(token).equals(pendingTransition.remove(id))) return;
        departed.remove(id);
        // Reaparecer y anunciarse son EL MISMO instante, ya con el alias puesto: el reveal
        // viaja como callback hasta que la linea sale de verdad al chat. Antes se reaparecia
        // aqui y el mensaje llegaba despues — por su propia cadena de tareas y, con el fancy
        // join, medio segundo mas tarde. En ese hueco se te veia aparecer sin que nadie
        // hubiera leido que entrabas.
        dev.chatalyst.util.FoliaTasks.entity(plugin, player, arrived -> {
            if (!simulateJoin.test(arrived, () -> reveal(id))) reveal(id);
        });
    }

    /**
     * Vuelve a preguntar si sigue habiendo alguien delante mientras la entrada esta pendiente.
     *
     * <p>La proximidad se miraba UNA sola vez, en el instante del cambio, y de esa foto salian
     * a la vez el retraso y el escondite. Quien pulsaba con alguien cerca se llevaba el tramo
     * largo — hasta cinco minutos — y ALEJARSE DESPUES no servia de nada: nadie volvia a
     * preguntar, asi que seguia oculto esperando por un testigo que ya no estaba. Justo el
     * "ghost time" que esta feature existe para evitar.
     *
     * <p>Ahora la espera larga es un techo. Cada segundo se repite la pregunta y, en cuanto
     * nadie mira, la entrada se adelanta — nunca antes del tramo corto, porque una salida y
     * una entrada en el mismo suspiro delatan el disfraz igual que no esperar nada.
     *
     * <p>El sondeo puede morirse sin consecuencias: la tarea del techo sigue programada y
     * anuncia igual. Es deliberado que el camino lento no dependa de este.
     */
    private void pollForArrival(UUID id, long token, long earliest) {
        dev.chatalyst.util.FoliaTasks.globalLater(plugin, () -> {
            // Ya anunciada (el techo gano) o sustituida por otra transicion: nada que hacer.
            if (!Long.valueOf(token).equals(pendingTransition.get(id))) return;
            Player online = Bukkit.getPlayer(id);
            if (online == null) return;
            observed(online, watched -> {
                if (!Long.valueOf(token).equals(pendingTransition.get(id))) return;
                if (arrivalDue(watched, System.currentTimeMillis(), earliest)) {
                    arrive(id, online, token);
                    return;
                }
                pollForArrival(id, token, earliest);
            });
        }, 20L);
    }

    /** Segundos entre dos claves de config, saneadas: nunca negativas ni absurdas. */
    private long randomSeconds(String minPath, long minDefault,
                               String maxPath, long maxDefault) {
        long min = plugin.getConfig().getLong(minPath, minDefault);
        long max = plugin.getConfig().getLong(maxPath, maxDefault);
        return randomBetween(min, max, RANDOM::nextDouble);
    }

    /** Nucleo puro y testeable del sorteo. */
    static long randomBetween(long min, long max, java.util.function.DoubleSupplier rng) {
        long low = Math.max(5L, Math.min(min, 3_600L));
        long high = Math.max(low, Math.min(max, 3_600L));
        return low + (long) (rng.getAsDouble() * (high - low + 1));
    }

    /**
     * Nucleo puro de "¿ya puede entrar?": las DOS condiciones tienen que cumplirse.
     *
     * <p>Quedarse solo no basta. El tramo corto no es una cortesia: una salida y una entrada
     * en el mismo suspiro no las hace nadie, y adelantar el aviso al segundo en que se va el
     * ultimo testigo delata el disfraz igual de bien que dejarse ver cambiando.
     */
    static boolean arrivalDue(boolean watched, long nowMs, long earliestMs) {
        return !watched && nowMs >= earliestMs;
    }

    static double clampChance(double configured) {
        if (!Double.isFinite(configured)) return 0.0;
        return Math.max(0.0, Math.min(1.0, configured));
    }

    /**
     * Activa el modo. Con {@code forced} distinto de null el disfraz es una identidad concreta
     * (un slot guardado): mismo nombre y misma skin cada vez. Con null se sortea del pool
     * (o se reutiliza la de esta sesion).
     */
    private void activateInternal(Player player, Disguise forced) {
        UUID id = player.getUniqueId();
        if (active.contains(id)) return;
        String originalNick = nicks.getNick(id);
        // El store va PRIMERO (guarda el apodo original), antes de marcar anonimo o simular
        // nada: si falla, no se ha alterado ningun estado visible.
        if (!store.putDurably(id.toString(), originalNick == null ? "" : originalNick)) {
            player.sendMessage(Lang.get(player, "anonymous.error"));
            return;
        }
        // Efecto "reconexion": se anuncia la SALIDA con la identidad REAL (aun no marcado
        // anonimo, para que el mensaje use su nombre y rango de verdad, no el disfraz). Asi la
        // gente ve que "se fue" y luego que "entro" el alias, en vez de un cambio de nombre en
        // sitio que delataria que son la misma persona.
        // Y solo hay reconexion que fingir si la despedida llego a alguien: si no, ni hueco,
        // ni escondite, ni silencio (ver announceDeparture).
        boolean simulate = simulateReconnect() && announceDeparture(player);
        active.add(id);
        String alias;
        if (forced != null) {
            session.put(id, forced);
            lastAlias.put(id, forced.alias());
            alias = forced.alias();
        } else {
            // Misma identidad si ya se disfrazo en esta sesion; nueva si es la primera vez.
            alias = sessionAlias(id);
        }
        if (!nicks.setNick(id, alias)) {
            // Sin el apodo no hay disfraz: se revierte TODO en vez de dejar un estado a
            // medias (marcado anonimo pero con su nombre visible).
            store.removeDurably(id.toString());
            active.remove(id);
            // Ya se anuncio la salida de la identidad real. Si el nick durable falla, se
            // anuncia su reentrada con la identidad que nunca llego a cambiar para no dejar
            // a todos creyendo que se desconecto cuando sigue online.
            if (simulate) announceArrivalNow(player);
            player.sendMessage(Lang.get(player, "anonymous.error"));
            return;
        }
        applyAnonymousSkin(player, true);
        // El cooldown anti-churn arranca AQUI, donde el disfraz ya existe, y no cuando se
        // pidio: entre las dos cosas puede haber minutos de espera a que nadie mire.
        markIdentityChange(id);
        // Entrada con el alias ya puesto, pero NO en el mismo segundo que la salida: eso
        // no lo hace nadie y delataria el disfraz por si solo.
        if (simulate) announceArrival(player);
        audit(player, alias);
        player.sendMessage(Lang.get(player, "anonymous.on", "name", alias));
        if (pool.isEmpty() && forced == null) {
            player.sendMessage(Lang.get(player, "anonymous.no-skin"));
        }
    }

    /** Resultado de {@link #useSaved}: para que el comando de el mensaje adecuado. */
    public enum SlotUse { NOT_FOUND, IN_USE, ACTIVATED, SWITCHED }

    /**
     * Guarda el disfraz ACTUAL (nombre + skin de esta sesion) en un slot. Requiere estar
     * anonimo (es la cara que llevas puesta la que se guarda). False si el backend fallo.
     */
    public boolean saveCurrent(Player player, int slot) {
        UUID id = player.getUniqueId();
        if (!isAnonymous(id)) return false;
        Disguise current = session.get(id);
        if (current == null || current.alias() == null || current.alias().isEmpty()) return false;
        return slots.save(id, slot, dev.chatalyst.util.MM.strip(current.alias()), current.skin());
    }

    /**
     * True si el disfraz que el jugador lleva puesto AHORA ya lo tiene guardado otro jugador:
     * no se puede reservar la misma cara dos veces. Normalmente el pool ni siquiera reparte un
     * disfraz reservado, pero esto lo blinda ante carreras y ante nombres generados que casen.
     */
    public boolean currentDisguiseTakenByOther(Player player) {
        Disguise current = session.get(player.getUniqueId());
        if (current == null || current.alias() == null) return false;
        return slots.savedByOther(
                dev.chatalyst.util.MM.strip(current.alias()), player.getUniqueId());
    }

    /**
     * Activa (o cambia EN CALIENTE, si ya esta anonimo) al disfraz guardado en un slot.
     * Si esa cara la lleva puesta OTRO jugador ahora mismo, se rechaza (dos disfraces
     * identicos delatarian que son distintos jugadores tras el mismo alias).
     */
    public SlotUse useSaved(Player player, int slot) {
        UUID id = player.getUniqueId();
        AnonymousSlots.Saved saved = slots.get(id, slot);
        if (saved == null) return SlotUse.NOT_FOUND;
        UUID holder = nicks.findByDisplay(saved.name());
        Player sameName = Bukkit.getPlayerExact(saved.name());
        boolean takenByOther = (holder != null && !holder.equals(id))
                || (sameName != null && !sameName.getUniqueId().equals(id));
        if (takenByOther) return SlotUse.IN_USE;
        Disguise identity = new Disguise(saved.name(), saved.skin());
        if (isAnonymous(id)) {
            // Elegir una cara concreta la fija: es un personaje, no un alias de usar y tirar.
            pinSlot(id, slot);
            // Cambio de cara sin salir del modo: nueva identidad, mismo estado anonimo.
            session.put(id, identity);
            lastAlias.put(id, saved.name());
            nicks.setNick(id, saved.name());
            applyAnonymousSkin(player, false);
            audit(player, saved.name());
            markIdentityChange(id);
            return SlotUse.SWITCHED;
        }
        whenUnobserved(player, () -> {
            activateInternal(player, identity);
            // Solo si de verdad quedo activo: activar puede fallar por almacenamiento, y un
            // fijado sin disfraz seria un personaje que nadie lleva puesto.
            if (isAnonymous(id)) pinSlot(id, slot);
        });
        return SlotUse.ACTIVATED;
    }

    /**
     * Quitarselo tambien es un cambio que se puede VER, asi que espera igual que ponerselo.
     *
     * <p>Ponerse el disfraz esperaba a que no mirara nadie; quitarselo devolvia la cara real
     * en el acto, delante de quien estuviera. Y eso no es medio problema, es el problema
     * entero al reves: quien vea al alias convertirse en ti aprende de golpe QUIEN era el
     * anonimo, que es justo lo que todo lo demas intenta que no pase. La unica forma de
     * apagarlo sin delatarse era esconderse a mano con /vanish antes de escribir el comando.
     *
     * <p>La direccion la deduce {@code isAnonymous}: mientras la espera vive el jugador
     * sigue marcado, asi que los avisos hablan de recuperar tu nombre y no de ponerte uno.
     */
    public void deactivate(Player player) {
        UUID id = player.getUniqueId();
        // Cambio de idea antes de que llegara el momento: no hay disfraz que quitar y la
        // espera debe morir aqui, o se aplicaria sola mas tarde sin que nadie lo pidiera.
        cancelPrivacyWait(id);
        whenUnobserved(player, () -> deactivateNow(player));
    }

    private void deactivateNow(Player player) {
        UUID id = player.getUniqueId();
        Disguise currentDisguise = session.get(id);
        String originalNick = store.get(id.toString());
        // Sin registro no hay disfraz que quitar: salir aqui evita que una llamada de mas
        // borre el apodo LEGITIMO que el jugador tuviera puesto con /nick.
        if (originalNick == null) {
            active.remove(id);
            return;
        }
        if (!store.removeDurably(id.toString())) {
            player.sendMessage(Lang.get(player, "anonymous.error"));
            return;
        }
        // Efecto "reconexion" inverso: se anuncia la SALIDA del ALIAS mientras el jugador
        // TODAVIA se ve como el disfraz (antes de restaurar), y luego la ENTRADA de su nombre
        // real. Se captura el flag ahora, con isAnonymous aun true, y solo cuenta como
        // reconexion si ese adios llego a alguien (ver announceDeparture).
        boolean simulate = simulateReconnect() && isAnonymous(id) && announceDeparture(player);
        active.remove(id);
        // Salir del modo cierra el ciclo: el alias que llevaba ya no es "el anterior" de
        // nada, y guardarlo solo restringiria sin motivo el sorteo de la proxima vez.
        lastAlias.remove(id);
        // Y suelta el personaje fijado: apagarlo es dejar de serlo, no aparcarlo.
        pinSlot(id, -1);
        boolean restored = originalNick.isEmpty()
                ? nicks.removeNick(id) : nicks.setNick(id, originalNick);
        if (!restored) {
            // El alias sigue intacto en NickManager. Recuperamos el journal anonimo y la
            // presencia visual para que el toggle falle cerrado, sin revelar una identidad
            // a medias ni simular una salida permanente.
            if (!store.putDurably(id.toString(), originalNick)) {
                plugin.getLogger().severe("Could not restore anonymous journal for "
                        + player.getUniqueId());
            }
            active.add(id);
            if (currentDisguise != null && currentDisguise.alias() != null) {
                lastAlias.put(id, currentDisguise.alias());
            }
            if (simulate) announceArrivalNow(player);
            player.sendMessage(Lang.get(player, "anonymous.error"));
            return;
        }
        restoreSkin(player);
        // Volver tambien es volver: el alias se fue y tu entras un rato despues.
        if (simulate) announceArrival(player);
        player.sendMessage(Lang.get(player, "anonymous.off"));
    }

    /** Reenganche desde un /cht reload con gente dentro: conserva el alias de la sesion. */
    public void restoreSession(Player player) {
        restoreSession(player, false);
    }

    /**
     * Reengancha una sesion ya anonima. Con {@code freshJoin} (una conexion nueva y
     * Rotate_Name_On_Join activo) el alias se RENUEVA: cada entrada es "otra persona",
     * y nadie puede rastrear al anonimo entre sesiones por su nombre. En un reload con
     * el jugador dentro NO se rota (renombrar a alguien a mitad de partida despista).
     */
    public void restoreSession(Player player, boolean freshJoin) {
        UUID id = player.getUniqueId();
        String originalNick = store.get(id.toString());
        if (originalNick == null) return;
        if (!enabled) {
            // El dueno apago el modo con gente aun disfrazada: se le devuelve su identidad
            // en vez de dejarlo ATRAPADO con el apodo aleatorio y el comando bloqueado.
            if (store.removeDurably(id.toString())) {
                if (originalNick.isEmpty()) {
                    nicks.removeNick(id);
                } else {
                    nicks.setNick(id, originalNick);
                }
                // Y la SKIN: en un re-enable con el jugador conectado (Bukkit /reload) el
                // disfraz de la sesion anterior sigue pegado a su perfil, asi que devolver
                // solo el nombre lo dejaba con cara falsa. En un join nuevo no hace falta —
                // la conexion ya trae su skin real— y ahorra una consulta a Mojang.
                lastAlias.remove(id);
                if (!freshJoin) restoreSkin(player);
            }
            return;
        }
        active.add(id);
        // Una cara FIJADA con /anon set no rota: el jugador la eligio y espera encontrarla
        // igual al volver, hasta que la cambie o apague el modo.
        AnonymousSlots.Saved pin = freshJoin ? savedPin(id) : null;
        if (pin != null) {
            session.put(id, new Disguise(pin.name(), pin.skin()));
            lastAlias.put(id, pin.name());
            nicks.setNick(id, pin.name());
            applyAnonymousSkin(player, true);
            audit(player, pin.name());
            return;
        }
        if (freshJoin && rotateOnJoin) {
            // Identidad nueva en cada entrada — nombre Y skin distintos (tambien resuelve
            // cualquier colision vieja de alias).
            session.remove(id); // conexion nueva = persona nueva
            String rotated = sessionAlias(id);
            nicks.setNick(id, rotated);
            // captureOriginal=true: la conexion nueva trae la skin REAL del jugador; hay que
            // fotografiarla ANTES de pisarla con el disfraz para poder restaurarla local al
            // salir del modo. Sin esto, restoreSkin caia a re-descargar de Mojang, que no
            // funciona para cuentas Bedrock/Floodgate y dejaba la skin de anon pegada.
            applyAnonymousSkin(player, true);
            audit(player, rotated); // cada rotacion es una identidad nueva que auditar
            return;
        } else {
            // Sin rotacion: el chequeo de colision de activate() solo vio a los CONECTADOS;
            // dos anonimos pueden haber acabado con el mismo alias estando uno offline.
            // Se re-verifica y, si ahora chocan, este recibe un alias nuevo.
            String alias = nicks.getNick(id);
            if (alias != null) {
                String plain = dev.chatalyst.util.MM.strip(alias);
                UUID holder = nicks.findByDisplay(plain);
                Player sameName = Bukkit.getPlayerExact(plain);
                if ((holder != null && !holder.equals(id))
                        || (sameName != null && !sameName.getUniqueId().equals(id))) {
                    nicks.setNick(id, uniqueRandomName(id));
                }
            }
        }
        // En un join fresco (freshJoin) fotografiamos la skin real que trajo la conexion; en un
        // reload con el jugador dentro NO, porque el disfraz de la sesion previa sigue pegado al
        // perfil y capturarlo guardaria la cara falsa como "original".
        applyAnonymousSkin(player, freshJoin);
    }

    /**
     * Quien acaba de "irse" del servidor no habla.
     *
     * <p>Entre la salida y la entrada simuladas hay un hueco en el que, para todos menos para
     * el, ese jugador no esta. Hablar ahi lo delata sin ayuda de nadie: el chat acaba de decir
     * que se fue A y aparece B diciendo algo antes de haber entrado. Y al reves es peor —
     * quitarse el disfraz despide al alias, y si hablas en el hueco lo siguiente que se lee es
     * tu NOMBRE REAL, pegado a la salida del alias. Esa es exactamente la correlacion que la
     * espera, el escondite y el retraso existen para romper; sin esto la deshacia una frase.
     *
     * <p>No es un mute ni un castigo, es lo que se pidio: un jugador desconectado no escribe.
     * El hueco se cierra solo, y se cierra ANTES si te alejas de la gente (ver
     * {@link #pollForArrival}), que es justo lo que dice el aviso.
     *
     * <p>LOWEST, como el silenciado: corta antes del formato, de los canales y del puente de
     * Discord, para que el mensaje no se cuele por ninguna de esas puertas.
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(io.papermc.paper.event.player.AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!departed.contains(player.getUniqueId())) return;
        event.setCancelled(true);
        player.sendMessage(Lang.get(player, "anonymous.transition-silent"));
    }

    /**
     * Ni se le oye ni se le felicita: un logro tambien lo anuncia el server con tu nombre.
     *
     * <p>El chat y la muerte ya se callan en el hueco, pero el logro llega por su propia
     * puerta — la vanilla, que nadie estaba mirando — y grita el nombre a todo el server justo
     * cuando ese jugador acaba de "desconectarse". Basta uno para atar las dos identidades.
     *
     * <p>Solo se cae el ANUNCIO. El logro se concede entero: se otorga, cuenta para los tags
     * y sigue en el libro de progreso del jugador. Lo unico que no ocurre es contarselo a
     * quien cree que ese jugador ya no esta.
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onAdvancement(org.bukkit.event.player.PlayerAdvancementDoneEvent event) {
        if (departed.contains(event.getPlayer().getUniqueId())) event.message(null);
    }

    // LOWEST: la rotacion del alias debe correr ANTES que el mensaje de entrada y el resto
    // de listeners del join, para que todos vean ya la identidad nueva de esta sesion.
    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        realNames.put(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        // Entrar deshace la ilusion: el server acaba de ver llegar a este jugador, asi que su
        // proxima salida es una salida de verdad y hay que anunciarla. Sin esto, una
        // reconexion rapida en mitad del hueco se quedaba con la marca puesta y silenciaba la
        // siguiente marcha, esa si real.
        departed.remove(event.getPlayer().getUniqueId());
        // Una reconexion rapida puede adelantarse a la restauracion async de la conexion
        // anterior. El perfil nuevo ya trae su skin real; invalida todo callback viejo.
        resetSkinSession(event.getPlayer().getUniqueId());
        restoreSession(event.getPlayer(), true);
        hideTransitioningFrom(event.getPlayer());
    }

    /**
     * Al recien llegado tampoco le aparece quien esta en mitad de una transicion.
     *
     * <p>La ocultacion se aplica a los que estaban conectados en ese momento; sin esto, quien
     * entrara despues veria a un jugador que para todos los demas se acaba de ir, y en unos
     * minutos leeria que ese mismo "entra".
     */
    private void hideTransitioningFrom(Player viewer) {
        if (hiddenForTransition.isEmpty()) return;
        for (UUID id : hiddenForTransition) {
            if (viewer.getUniqueId().equals(id)) continue;
            Player hidden = Bukkit.getPlayer(id);
            if (hidden != null) viewer.hidePlayer(plugin, hidden);
        }
    }

    // MONITOR: el ULTIMO en correr. Limpiar el estado antes de tiempo hacia que el mensaje
    // de salida se compusiera con el jugador ya "no anonimo", y anunciaba su nombre REAL —
    // justo al desconectar, que es cuando mas se nota.
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        // Se fue de verdad: una marcha programada ya no tiene sentido, y dejarla viva haria
        // que al reconectar se disfrazara solo sin haberlo pedido en esta sesion.
        cancelPendingTransition(event.getPlayer().getUniqueId());
        // El permiso de ver nombres reales se olvida: la proxima vez que entre puede ser otro,
        // y una respuesta cacheada de "si" sobreviviendo a un cambio de rango es una fuga.
        forgetRealNameViewer(event.getPlayer().getUniqueId());
        // Ya no hay a quien disfrazar: una espera viva volveria a intentarlo al reconectar.
        cancelPrivacyWait(event.getPlayer().getUniqueId());
        // El mensaje de salida NO se compone durante el evento: MessagesListener lo arma
        // dentro de una tarea por espectador (scheduler global -> scheduler de entidad), o
        // sea DOS ticks despues como minimo. Con el viejo aplazamiento de 1 tick el estado
        // ya estaba limpio para entonces y el adios anunciaba el nombre REAL del anonimo
        // ("Fulano left"). 40 ticks dan margen de sobra a esa cadena y a los demas plugins;
        // el jugador ya esta desconectado, asi que retenerlo dos segundos no cuesta nada.
        UUID id = event.getPlayer().getUniqueId();
        FoliaTasks.globalLater(plugin, () -> {
            // Si ya volvio a entrar, esto es el estado de la sesion NUEVA (con su alias
            // recien rotado): borrarlo lo dejaria marcado como no-anonimo con el disfraz
            // puesto. La limpieza es solo para quien sigue fuera.
            if (Bukkit.getPlayer(id) != null) return;
            // El perfil de la sesion muere con la conexion; el estado activo se relee del
            // store al volver a entrar, y la identidad de sesion se regenera.
            active.remove(id);
            realNames.remove(id);
            originalSkin.remove(id);
            session.remove(id);
            skinOperations.remove(id);
            restoringRealSkin.remove(id);
            lastIdentityChange.remove(id);
            // Ya se fue de verdad y su salida se silencio a proposito: la marca no sobrevive
            // a la sesion, o al volver silenciaria una marcha que si habria que contar.
            departed.remove(id);
        }, 40L);
    }

    // ---- Skin ------------------------------------------------------------------------

    /**
     * Aplica la skin del disfraz. La skin SIGUE a la identidad de sesion elegida en
     * {@link #sessionAlias}: en modo "accounts" es la de la MISMA cuenta que dio el nombre
     * (nombre y cara son la misma persona); en modo generado es una del pool al azar, ya
     * fijada para toda la sesion. Tras un reload la sesion se pierde: se RE-EMPAREJA la skin
     * a partir del apodo actual (misma cuenta del pool si el nombre casa) para no romper la
     * coherencia. Si el pool aun no se descargo, el resto del disfraz aplica sin skin.
     */
    private void applyAnonymousSkin(Player player, boolean captureOriginal) {
        UUID id = player.getUniqueId();
        Disguise current = session.get(id);
        ProfileProperty skin;
        if (current != null && current.skin() != null) {
            skin = current.skin(); // identidad de sesion (cuenta real en modo accounts)
        } else {
            // Sesion sin skin: reload (sesion vacia) o modo generado (skin aun por sortear).
            String alias = current != null ? current.alias() : plainNick(id);
            ProfileProperty matched = useAccountNames ? poolSkinFor(alias) : null;
            skin = matched != null ? matched : randomSkin();
            if (skin != null && alias != null) session.put(id, new Disguise(alias, skin));
        }
        if (skin == null) return; // pool aun sin descargar: el resto del disfraz aplica
        // Las cabezas cacheadas en GUIs mostrarian la skin REAL del anonimo: fuera.
        dev.chatalyst.gui.PlayerHeads.invalidate(player.getUniqueId());
        ProfileProperty chosen = skin;
        Object operation = beginSkinOperation(id);
        FoliaTasks.entity(plugin, player, p -> {
            if (!isCurrentSkinOperation(id, operation) || !isAnonymous(id)) return;
            PlayerProfile profile = p.getPlayerProfile();
            if (captureOriginal && !restoringRealSkin.contains(id)) {
                for (ProfileProperty property : profile.getProperties()) {
                    if (TEXTURES.equals(property.getName())) {
                        originalSkin.putIfAbsent(p.getUniqueId(), property);
                        break;
                    }
                }
            }
            profile.setProperty(new ProfileProperty(TEXTURES,
                    chosen.getValue(), chosen.getSignature()));
            p.setPlayerProfile(profile);
        });
    }

    private void restoreSkin(Player player) {
        UUID id = player.getUniqueId();
        dev.chatalyst.gui.PlayerHeads.invalidate(id);
        Object operation = beginSkinOperation(id);
        restoringRealSkin.add(id);
        // OJO: la identidad de sesion se CONSERVA — si vuelve a activar el modo sin salir
        // del servidor debe recuperar el mismo alias y la misma skin.
        ProfileProperty saved = originalSkin.get(id);
        if (saved != null) {
            FoliaTasks.entity(plugin, player, current -> {
                if (!isCurrentSkinOperation(id, operation) || isAnonymous(id)) return;
                PlayerProfile profile = current.getPlayerProfile();
                profile.setProperty(saved);
                current.setPlayerProfile(profile);
                originalSkin.remove(id, saved);
                finishSkinRestore(id, operation);
            });
            return;
        }
        // Activado en una sesion anterior (no hay snapshot): su skin real se re-descarga.
        String name = player.getName();
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            ProfileProperty fresh = null;
            try {
                PlayerProfile lookup = Bukkit.createProfile(id, name);
                if (lookup.complete(true)) {
                    for (ProfileProperty property : lookup.getProperties()) {
                        if (TEXTURES.equals(property.getName())) {
                            fresh = property;
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // Sin red o perfil no premium: se cae al perfil sin textura (skin default).
            }
            ProfileProperty result = fresh;
            FoliaTasks.entity(plugin, player, current -> {
                if (!isCurrentSkinOperation(id, operation) || isAnonymous(id)) return;
                PlayerProfile profile = current.getPlayerProfile();
                if (result != null) {
                    profile.setProperty(result);
                } else {
                    profile.removeProperty(TEXTURES);
                }
                current.setPlayerProfile(profile);
                finishSkinRestore(id, operation);
            });
            });
        } catch (Throwable rejected) {
            // Sin tarea async no puede quedar restoringRealSkin clavado para siempre.
            FoliaTasks.entity(plugin, player, current -> {
                if (!isCurrentSkinOperation(id, operation) || isAnonymous(id)) return;
                PlayerProfile profile = current.getPlayerProfile();
                profile.removeProperty(TEXTURES);
                current.setPlayerProfile(profile);
                finishSkinRestore(id, operation);
            }, () -> finishSkinRestore(id, operation));
        }
    }

    private Object beginSkinOperation(UUID id) {
        Object operation = new Object();
        skinOperations.put(id, operation);
        return operation;
    }

    private boolean isCurrentSkinOperation(UUID id, Object operation) {
        return skinOperations.get(id) == operation;
    }

    private void finishSkinRestore(UUID id, Object operation) {
        restoringRealSkin.remove(id);
        skinOperations.remove(id, operation);
    }

    private void resetSkinSession(UUID id) {
        originalSkin.remove(id);
        restoringRealSkin.remove(id);
        skinOperations.remove(id);
    }

    // ---- Utilidades --------------------------------------------------------------------

    /** Apodo tipo "illiliolil": parece un nick real pero es confuso e imposible de teclear. */
    String randomName() {
        return randomName(nameLength);
    }

    String randomName(int length) {
        char[] chars = nameChars;
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(chars[RANDOM.nextInt(chars.length)]);
        }
        return out.toString();
    }

    /**
     * Alias aleatorio segun el estilo configurado — "realistic" (default) genera nombres
     * con pinta de jugador real via {@link AnonymousNames}; "confusing" usa el alfabeto
     * ilegible de Name_Chars. En ambos casos el alias no choca con otro apodo en uso, con
     * un conectado, NI con el nombre de nadie que haya jugado en este servidor (suplantar
     * a un miembro real seria peor que no disfrazar).
     */
    /**
     * Alias del disfraz de esta sesion: el mismo si ya estuvo anonimo desde que entro (para
     * que /anonymous off + on no lo convierta en "otra persona" delante de todos), o uno
     * nuevo la primera vez. Se re-verifica que siga libre por si alguien lo ocupo mientras.
     */
    private String sessionAlias(UUID id) {
        Disguise current = session.get(id);
        if (current != null && isFree(current.alias())) return current.alias();
        // Modo "accounts": el disfraz es una cuenta real libre — su NOMBRE es el alias y su
        // SKIN la lleva la sesion, para que nombre y cara sean la misma persona de verdad.
        if (useAccountNames) {
            PoolIdentity pooled = freePoolIdentity(id);
            if (pooled != null) {
                lastAlias.put(id, pooled.name());
                session.put(id, new Disguise(pooled.name(), pooled.skin()));
                return pooled.name();
            }
            // Sin cuenta libre (todas en uso o pool sin descargar): se cae a un nombre generado.
        }
        String alias = uniqueRandomName(id);
        lastAlias.put(id, alias);
        session.put(id, new Disguise(alias, current == null ? null : current.skin()));
        return alias;
    }

    /**
     * ¿A este jugador se le va a dar un alias nuevo en cuanto entre? Lo consulta NickManager
     * para no mostrar antes el alias de la sesion anterior. Repite exactamente las
     * condiciones de la rama de rotacion de {@link #restoreSession(Player, boolean)}: si
     * alguna vez dejan de coincidir, el apodo guardado no se aplicaria y el jugador entraria
     * con su nombre real — asi que van juntas a proposito.
     */
    public boolean willRotateOnJoin(UUID id) {
        return enabled && rotateOnJoin && id != null && store.get(id.toString()) != null;
    }

    /**
     * Alias que llevaba cada jugador en su sesion ANTERIOR, para no repetirselo. Sobrevive
     * a la desconexion a proposito (es lo unico que lo hace): la gracia esta justo en el
     * salto entre una sesion y la siguiente. Se limpia al desactivar el modo.
     */
    private final Map<UUID, String> lastAlias = new ConcurrentHashMap<>();

    private String uniqueRandomName(UUID id) {
        // El alias de la sesion anterior se descarta a proposito: con un pool finito el azar
        // repetia el mismo de vez en cuando, y "otra persona" que resulta llamarse igual que
        // la de ayer no engaña a nadie — delata que hay rotacion y a quien esconde.
        String previous = id == null ? null : lastAlias.get(id);
        for (int attempt = 0; attempt < 24; attempt++) {
            String candidate = realisticNames
                    ? AnonymousNames.next(RANDOM) : randomName();
            if (previous != null && candidate.equalsIgnoreCase(previous)) continue;
            if (isFree(candidate)) return candidate;
        }
        // Sin hueco en 24 intentos (practicamente imposible en modo realistic): alias
        // ilegible alargado (tope 16, el maximo de un nombre de Minecraft).
        String longer = randomName(Math.min(16, nameLength + 4));
        if (isFree(longer)) return longer;
        // El viejo fallback anexaba un digito a un alias ya de 16 caracteres y producia
        // 17, por lo que NickManager rechazaba la activacion. El sufijo reemplaza el final.
        String stem = longer.substring(0, Math.min(15, longer.length()));
        for (int suffix = 0; suffix < 36; suffix++) {
            String candidate = stem + Character.forDigit(suffix, 36);
            if ((previous == null || !candidate.equalsIgnoreCase(previous))
                    && isFree(candidate)) return candidate;
        }
        // Ultimo respaldo de 16 caracteres, sin depender del alfabeto configurable.
        for (int attempt = 0; attempt < 64; attempt++) {
            String compact = UUID.randomUUID().toString().replace("-", "");
            String candidate = "a" + compact.substring(0, 15);
            if (isFree(candidate)) return candidate;
        }
        // Colision astronomicamente improbable; conserva el contrato de longitud incluso
        // si un proveedor externo afirma que todos los candidatos existen.
        return "a" + UUID.randomUUID().toString().replace("-", "").substring(0, 15);
    }

    /** Apodo actual del jugador en texto plano (para re-emparejar la skin tras un reload). */
    private String plainNick(UUID id) {
        String nick = nicks.getNick(id);
        return nick == null || nick.isEmpty() ? null : dev.chatalyst.util.MM.strip(nick);
    }

    private boolean isFree(String candidate) {
        return nicks.findByDisplay(candidate) == null
                && Bukkit.getPlayerExact(candidate) == null
                // Nadie que HAYA jugado aqui: el disfraz no debe suplantar a un miembro.
                && Bukkit.getOfflinePlayerIfCached(candidate) == null;
    }

    public void shutdown() {
        poolLoadGeneration.incrementAndGet();
        store.flushNow();
    }
}
