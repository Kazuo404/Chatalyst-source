package dev.chatalyst.nick;

import dev.chatalyst.db.Store;
import dev.chatalyst.db.StorageManager;
import dev.chatalyst.util.MM;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Apodos de jugadores. Los apodos de jugadores conectados se cachean en memoria (el chat
 * los consulta en cada mensaje via {player_nick}); el {@link Store} retiene tambien los offline
 * (en YAML o base de datos).
 *
 * <p>El apodo se aplica como IDENTIDAD completa: ademas del chat/tab de Chatalyst, se empuja
 * al display name de Bukkit (lo que leen otros plugins en sus mensajes) y, si EssentialsX
 * esta instalado, a su sistema de nicknames (para que /tpa &lt;nick&gt;, /whois, etc. lo
 * resuelvan). Configurable con Nick.Set_Display_Name y Nick.Sync_Essentials.
 */
public final class NickManager {

    private final Map<UUID, String> online = new ConcurrentHashMap<>();
    // Nombre real (minusculas) -> apodo PLANO, solo jugadores conectados con apodo. Lo lee
    // el tab-complete global (AsyncTabCompleteEvent, hilo async), por eso es concurrente.
    private final Map<String, String> aliasByRealName = new ConcurrentHashMap<>();
    private final Store store;
    private final JavaPlugin plugin;

    /** Quien esta conectado AQUI; inyectable para probar la invalidacion sin servidor. */
    private final java.util.function.Function<UUID, Player> onlineLookup;

    public NickManager(JavaPlugin plugin, StorageManager storage) {
        this(plugin, storage.store("nicks"), Bukkit::getPlayer);
    }

    NickManager(JavaPlugin plugin, Store store,
                java.util.function.Function<UUID, Player> onlineLookup) {
        this.plugin = plugin;
        this.store = store;
        this.onlineLookup = onlineLookup;
        // Con la base compartida, un /nick hecho en otro servidor llegaba al Store pero no a
        // esta cache: el chat seguia con el viejo hasta que el jugador volvia a entrar.
        store.addInvalidationListener(this::reloadChanged);
    }

    /** Ids que cambiaron en la base: solo importan los conectados en este servidor. */
    void reloadChanged(java.util.Collection<String> ids) {
        for (String raw : ids) {
            UUID id;
            try {
                id = UUID.fromString(raw);
            } catch (IllegalArgumentException notAPlayer) {
                continue;
            }
            Player p = onlineLookup.apply(id);
            // El alias de un anonimo con rotacion lo gestiona el modo anonimo, no el Store.
            if (p == null || rotationCheck.test(id)) continue;
            String stored = store.get(raw);
            String cached = online.get(id);
            boolean storedEmpty = stored == null || stored.isEmpty();
            if (storedEmpty ? cached == null : stored.equals(cached)) continue;
            reloadPlayer(p);
        }
    }

    /**
     * ¿Este jugador va a recibir un alias NUEVO nada mas entrar? Lo responde el modo
     * anonimo; sin el instalado, nadie rota nada.
     */
    private static volatile java.util.function.Predicate<UUID> rotationCheck = id -> false;

    public static void setRotationCheck(java.util.function.Predicate<UUID> check) {
        rotationCheck = check != null ? check : id -> false;
    }

    public void loadPlayer(Player p) {
        // Un anonimo con rotacion tiene GUARDADO el alias de la sesion ANTERIOR (el /nick
        // persiste el disfraz). Aplicarlo aqui lo mostraba unos instantes —en la tab list,
        // en el nametag, en cualquier plugin que leyera el display name— antes de que el
        // modo anonimo, un listener despues, lo sustituyera por el de esta sesion. De ahi
        // el nombre "cambiando 2 o 3 veces" al entrar; y peor: ese parpadeo ENLAZABA las
        // dos identidades, que es justo lo que la rotacion existe para impedir. Se salta:
        // el modo anonimo escribe el alias definitivo un momento despues.
        if (rotationCheck.test(p.getUniqueId())) return;
        String nick = permitido(p, store.get(p.getUniqueId().toString()));
        if (nick != null && !nick.isEmpty()) {
            online.put(p.getUniqueId(), nick);
            aliasByRealName.put(p.getName().toLowerCase(java.util.Locale.ROOT), MM.strip(nick));
            // Restaura el apodo en la tab list de TAB al entrar (la API de TAB es
            // temporal: se pierde al reconectar, asi que hay que re-aplicarla).
            dev.chatalyst.hook.TabHook.pushNick(p, nick);
            applyIdentity(p, nick);
        }
    }

    /**
     * El apodo guardado tal como puede llevarlo HOY: sin {@code chatalyst.nick} no se aplica, y sin
     * {@code chatalyst.nick.color} va sin colores. Un rango que vence (la prueba de Nova de 3 dias,
     * un rango de la tienda) dejaba el apodo de color para siempre (2026-10-16). Lo guardado no se
     * toca: si recupera el rango, al volver a entrar vuelve tal cual. El modo anonimo es del staff,
     * que tiene estos permisos.
     */
    static String permitido(org.bukkit.permissions.Permissible p, String nick) {
        if (nick == null || nick.isEmpty()) return nick;
        if (!p.hasPermission("chatalyst.nick")) return null;
        if (!p.hasPermission("chatalyst.nick.color")) return MM.strip(nick);
        return nick;
    }

    /**
     * Devuelve su nombre al que acaba de entrar: si alguien lo lleva puesto como apodo, se
     * lo quita.
     *
     * <p>El anti-suplantacion de {@code /nick} solo puede mirar a quien esta CONECTADO o en
     * el usercache. Cuando el dueño de un nombre no habia entrado nunca, nada impedia que
     * otro se lo pusiera — y al llegar el de verdad se encontraba con su nombre ocupado, que
     * es justo el escenario que la proteccion existe para evitar. Aqui se resuelve en el
     * unico momento en que se puede: cuando el dueño aparece.
     *
     * <p>Simetrico a proposito: tambien se le quita al que ENTRA si el apodo que traia
     * guardado se lee como el nombre de alguien que ya esta dentro. Da igual quien llegue
     * primero; el nombre acaba en su dueño.
     *
     * @return los UUID a los que se libero el apodo, para avisarles.
     */
    public java.util.List<UUID> reclaimNameFor(Player arrival) {
        String arrivalKey = NickText.spoofKey(arrival.getName());
        if (arrivalKey.isEmpty()) return java.util.List.of();
        java.util.List<UUID> released = new java.util.ArrayList<>();

        // 1) Quien lleve puesto el nombre del recien llegado lo pierde.
        for (Map.Entry<UUID, String> e : online.entrySet()) {
            if (e.getKey().equals(arrival.getUniqueId())) continue;
            if (!NickText.spoofKey(MM.strip(e.getValue())).equals(arrivalKey)) continue;
            if (removeNick(e.getKey())) released.add(e.getKey());
        }

        // 2) Y si el que entra traia guardado el nombre de otro que ya esta dentro, lo
        //    pierde el. Sin esto bastaba con reconectar para volver a llevarlo puesto.
        String own = online.get(arrival.getUniqueId());
        if (own != null && !own.isEmpty()) {
            String ownKey = NickText.spoofKey(MM.strip(own));
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (other.getUniqueId().equals(arrival.getUniqueId())) continue;
                if (!NickText.spoofKey(other.getName()).equals(ownKey)) continue;
                if (removeNick(arrival.getUniqueId())) released.add(arrival.getUniqueId());
                break;
            }
        }
        return released;
    }

    /** Relee el nick de un jugador conectado tras cambiar la conexion de storage. */
    public void reloadPlayer(Player p) {
        UUID id = p.getUniqueId();
        String key = p.getName().toLowerCase(java.util.Locale.ROOT);
        String nick = store.get(id.toString());
        if (nick == null || nick.isEmpty()) {
            online.remove(id);
            plainNicks.remove(id);
            aliasByRealName.remove(key);
            pushIdentity(p, null);
            return;
        }
        online.put(id, nick);
        aliasByRealName.put(key, MM.strip(nick));
        pushIdentity(p, nick);
    }

    public void unloadPlayer(Player p) {
        online.remove(p.getUniqueId());
        plainNicks.remove(p.getUniqueId());
        aliasByRealName.remove(p.getName().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Libera la cache un rato DESPUES de la desconexion. El detalle que importa: el mensaje
     * de salida NO se compone durante el PlayerQuitEvent, sino en una tarea diferida
     * (global -> entity, dos ticks como minimo) para poder darle a cada espectador su idioma.
     * El nombre de ese adios se resuelve con {@code getNick}, que lee SOLO esta cache en
     * memoria — no el store. Con un solo tick de margen la cache ya estaba vacia cuando el
     * mensaje se armaba, y el adios de un /nick o un /anonymous anunciaba el nombre REAL
     * (justo lo que no deben permitir). 60 ticks cubren de sobra esa cadena y se alinean con
     * la ventana del modo anonimo (40); el jugador ya esta fuera, retener su apodo 3 s no
     * cuesta nada.
     */
    public void unloadPlayerLater(Player p) {
        UUID id = p.getUniqueId();
        String key = p.getName().toLowerCase(java.util.Locale.ROOT);
        dev.chatalyst.util.FoliaTasks.globalLater(plugin, () -> {
            // Reconexion dentro de la ventana (el log real muestra reingresos a los 3 s): la
            // sesion NUEVA ya recargo el apodo, asi que esta limpieza tardia de la sesion
            // vieja borraria el apodo en uso. Solo se limpia a quien sigue fuera.
            if (Bukkit.getPlayer(id) != null) return;
            online.remove(id);
            plainNicks.remove(id);
            aliasByRealName.remove(key);
        }, 60L);
    }

    /** Apodo del jugador (con formato MiniMessage si lo tiene), o null si no tiene. */
    public String getNick(UUID id) {
        return online.get(id);
    }

    /**
     * Apodo ya sin etiquetas, cacheado junto al apodo del que salio. El chat lo pide por cada
     * espectador y mensaje, y limpiar un apodo son varias pasadas de MiniMessage. Se guarda
     * el apodo de origen: si el apodo cambio por cualquier camino, no coincide y se recalcula,
     * asi que nunca devuelve uno viejo.
     */
    private record PlainNick(String raw, String plain) {
    }

    private final Map<UUID, PlainNick> plainNicks = new ConcurrentHashMap<>();

    /** Apodo plano del jugador conectado, o null si no tiene apodo. Seguro en async. */
    public String plainNick(UUID id) {
        String raw = online.get(id);
        if (raw == null || raw.isEmpty()) return null;
        PlainNick cached = plainNicks.get(id);
        if (cached != null && cached.raw().equals(raw)) return cached.plain();
        String plain = MM.strip(raw);
        plainNicks.put(id, new PlainNick(raw, plain));
        return plain;
    }

    /**
     * Apodo PLANO a mostrar en tab-complete en lugar de un nombre real, o null si ese texto
     * no es el nombre real de un conectado con apodo. Clave en minusculas; seguro en async.
     */
    public String tabAlias(String realName) {
        if (realName == null || realName.isEmpty()) return null;
        return aliasByRealName.get(realName.toLowerCase(java.util.Locale.ROOT));
    }

    /** Busca el UUID de un jugador conectado cuyo apodo (sin formato) coincida con el texto. */
    public UUID findByDisplay(String display) {
        return findByDisplay(online, display);
    }

    /**
     * La busqueda pura, sobre un mapa de conectados. Separada del estado para poder probar
     * la regla de comparacion, que tiene una trampa que ya rompio cosas una vez.
     *
     * <p>{@code Locale.ROOT}, no el del sistema: en un JVM con locale turco/azeri
     * {@code "I".toLowerCase()} da {@code "ı"} (sin punto), asi que un nick "Ian" no casaria
     * con la busqueda "ian" y se romperia {@code /msg <nick>}, la resolucion de pareja y la
     * unicidad de {@code /anonymous} (isFree).
     */
    static UUID findByDisplay(Map<UUID, String> online, String display) {
        if (display == null || display.isEmpty()) return null;
        String target = display.toLowerCase(java.util.Locale.ROOT);
        for (Map.Entry<UUID, String> e : online.entrySet()) {
            if (dev.chatalyst.util.MM.strip(e.getValue())
                    .toLowerCase(java.util.Locale.ROOT).equals(target)) {
                return e.getKey();
            }
        }
        // Segunda pasada SIN espacios: un apodo puede llevarlos ("El Rey") y los comandos
        // reciben un solo argumento, asi que a ese jugador no se le podria escribir nunca.
        // Con esto /msg ElRey lo alcanza. Va despues de la exacta a proposito: si dos
        // jugadores colisionan al quitar los espacios, gana quien lo escribio tal cual.
        String collapsed = NickText.spoofKey(display);
        if (collapsed.isEmpty()) return null;
        for (Map.Entry<UUID, String> e : online.entrySet()) {
            if (NickText.spoofKey(dev.chatalyst.util.MM.strip(e.getValue())).equals(collapsed)) {
                return e.getKey();
            }
        }
        return null;
    }

    /**
     * Jugador CONECTADO por nombre real o por apodo, o null. Usar en cualquier comando que
     * reciba un jugador como argumento, para que el apodo funcione como un nombre mas.
     */
    public Player resolveOnline(String nameOrNick) {
        if (nameOrNick == null || nameOrNick.isEmpty()) return null;
        Player exact = Bukkit.getPlayerExact(nameOrNick);
        if (exact != null) return exact;
        UUID id = findByDisplay(nameOrNick);
        return id != null ? Bukkit.getPlayer(id) : null;
    }

    /**
     * Nombre a sugerir en tab-complete de comandos de Chatalyst: el apodo PLANO si el
     * jugador tiene uno, o su nombre real. Ambos se resuelven con {@link #online}.
     */
    public static String suggestedName(Player p) {
        dev.chatalyst.ChatalystPlugin pl = dev.chatalyst.ChatalystPlugin.get();
        if (pl == null || pl.getNickManager() == null) return p.getName();
        String plain = pl.getNickManager().plainNick(p.getUniqueId());
        return plain != null ? plain : p.getName();
    }

    /** Atajo estatico de {@link #resolveOnline} para los comandos (usa el manager del plugin). */
    public static Player online(String nameOrNick) {
        dev.chatalyst.ChatalystPlugin pl = dev.chatalyst.ChatalystPlugin.get();
        if (pl == null || pl.getNickManager() == null) return Bukkit.getPlayerExact(nameOrNick);
        return pl.getNickManager().resolveOnline(nameOrNick);
    }

    /**
     * Resolucion estandar de objetivo para comandos de moderacion: primero el cache de
     * offline players (nombre real), y si no, un conectado por nombre o apodo. Null si
     * no se encuentra. (Antes cada comando repetia este mismo bloque.)
     *
     * <p>El ultimo recurso es el indice propio ({@link OfflineNameIndex}): el usercache de
     * Paper caduca (~1000 entradas / 30 dias), asi que sin el no se podia sancionar por
     * nombre a quien entro hace semanas. Va el ULTIMO a proposito: usercache y conectados
     * son la verdad de ahora mismo; el indice solo recuerda quien fue el ultimo en entrar
     * con ese nombre.
     */
    public static org.bukkit.OfflinePlayer resolveOffline(String nameOrNick) {
        return resolveInOrder(nameOrNick,
                Bukkit::getOfflinePlayerIfCached,
                NickManager::online,
                OfflineNameIndex::find,
                Bukkit::getOfflinePlayer);
    }

    /** El orden de resolucion, separado de Bukkit para poder fijarlo con un test. */
    static <T> T resolveInOrder(String nameOrNick,
                                java.util.function.Function<String, ? extends T> cached,
                                java.util.function.Function<String, ? extends T> connected,
                                java.util.function.Function<String, UUID> index,
                                java.util.function.Function<UUID, ? extends T> byId) {
        T found = cached.apply(nameOrNick);
        if (found != null) return found;
        found = connected.apply(nameOrNick);
        if (found != null) return found;
        UUID id = index.apply(nameOrNick);
        return id != null ? byId.apply(id) : null;
    }

    /**
     * Como se muestra HOY un jugador conocido solo por su nombre real: su apodo con formato
     * si tiene uno (aunque este offline: se mira el Store), o el nombre tal cual. Para
     * listas como el inbox, donde el remitente se guardo por nombre.
     */
    public String displayFor(String realName) {
        if (realName == null || realName.isEmpty()) return realName;
        Player p = Bukkit.getPlayerExact(realName);
        if (p != null) {
            String nick = online.get(p.getUniqueId());
            return nick != null && !nick.isEmpty() ? nick : realName;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(realName);
        if (cached != null) {
            String nick = store.get(cached.getUniqueId().toString());
            if (nick != null && !nick.isEmpty()) return nick;
        }
        return realName;
    }

    /** Apodo actual por UUID aunque el jugador este OFFLINE (cache online -> Store), o null. */
    public String nickAnywhere(UUID id) {
        String nick = online.get(id);
        if (nick != null && !nick.isEmpty()) return nick;
        nick = store.get(id.toString());
        return nick != null && !nick.isEmpty() ? nick : null;
    }

    /**
     * Display de un remitente guardado (uuid como texto, puede venir vacio en datos viejos):
     * su apodo actual con formato, o el nombre real. Para el inbox y las notificaciones.
     */
    /**
     * Nombre mostrado en TEXTO PLANO (sin formato). Para pintarlo en pantalla tal cual;
     * si va a insertarse dentro de una plantilla MiniMessage, usar {@link #safeName}.
     */
    public static String plainName(org.bukkit.entity.Player p) {
        return dev.chatalyst.util.MM.strip(
                shownName(p.getUniqueId().toString(), p.getName()));
    }

    /**
     * Nombre mostrado plano Y ESCAPADO, listo para meterse en una plantilla que SI se
     * parsea (mensajes de lang, formatos del chat). Un apodo lo controla el jugador: sin el
     * escape, un nick con tags MiniMessage inyectaria formato — o peor — en esos mensajes.
     */
    public static String safeName(org.bukkit.entity.Player p) {
        return dev.chatalyst.util.MM.escape(plainName(p));
    }

    /** Igual que {@link #safeName(org.bukkit.entity.Player)} para alguien que puede estar desconectado. */
    public static String safeName(String uuidStr, String realName) {
        return dev.chatalyst.util.MM.escape(
                dev.chatalyst.util.MM.strip(shownName(uuidStr, realName)));
    }

    public static String shownName(String uuidStr, String realName) {
        dev.chatalyst.ChatalystPlugin pl = dev.chatalyst.ChatalystPlugin.get();
        if (pl == null || pl.getNickManager() == null) return realName;
        NickManager nm = pl.getNickManager();
        if (uuidStr != null && !uuidStr.isBlank()) {
            try {
                String nick = nm.nickAnywhere(UUID.fromString(uuidStr));
                if (nick != null) return nick;
            } catch (IllegalArgumentException ignored) {
                // Dato viejo sin uuid valido: caemos al nombre.
            }
        }
        return nm.displayFor(realName);
    }

    public boolean setNick(UUID id, String nick) {
        if (nick == null || nick.isEmpty()) {
            return removeNick(id);
        }
        if (!store.putDurably(id.toString(), nick)) return false;
        online.put(id, nick);
        plainNicks.remove(id);
        refreshIdentity(id, nick);
        return true;
    }

    public boolean removeNick(UUID id) {
        if (!store.removeDurably(id.toString())) return false;
        online.remove(id);
        plainNicks.remove(id);
        refreshIdentity(id, null);
        return true;
    }

    private void refreshIdentity(UUID id, String nick) {
        Player p = Bukkit.getPlayer(id);
        if (p == null) return;
        String key = p.getName().toLowerCase(java.util.Locale.ROOT);
        if (nick != null && !nick.isEmpty()) {
            aliasByRealName.put(key, MM.strip(nick));
        } else {
            aliasByRealName.remove(key);
        }
        pushIdentity(p, nick);
    }

    /** Tab, display name, Essentials y cartel, en la region del jugador. */
    private void pushIdentity(Player p, String nick) {
        dev.chatalyst.util.FoliaTasks.entity(plugin, p, current -> {
            dev.chatalyst.hook.TabHook.pushNick(current, nick);
            applyIdentity(current, nick);
        });
    }

    // Empuja el apodo fuera de Chatalyst: display name de Bukkit + nickname de Essentials.
    private void applyIdentity(Player p, String nickMini) {
        if (plugin.getConfig().getBoolean("Nick.Set_Display_Name", true)) {
            p.displayName(nickMini != null ? MM.safe(nickMini) : null);
        }
        if (plugin.getConfig().getBoolean("Nick.Sync_Essentials", true)) {
            dev.chatalyst.hook.EssentialsHook.syncNick(p,
                    nickMini != null ? MM.strip(nickMini) : null);
        }
        // El cartel fancy sobre la cabeza se actualiza al instante (no espera el ciclo).
        dev.chatalyst.ChatalystPlugin pl = dev.chatalyst.ChatalystPlugin.get();
        if (pl != null && pl.getFancyNametags() != null) {
            pl.getFancyNametags().refresh(p);
        }
    }

    public void shutdown() {
        store.flushNow();
    }
}
