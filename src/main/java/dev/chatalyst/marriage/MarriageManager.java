package dev.chatalyst.marriage;

import dev.chatalyst.db.Store;
import dev.chatalyst.db.StorageManager;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.nick.NickManager;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Matrimonios entre jugadores (/marry, /divorce). Feature OPT-IN: el dueño la enciende en
 * {@code Marriage.Enabled}; apagada, los comandos ni siquiera responden.
 *
 * <p>El vinculo se guarda en el Store "marriages" con UNA fila por conyuge
 * ({@code pareja US nombre US epoch}), para que consultar "con quien esta casado X" sea
 * directo desde el hilo async del chat. Las dos filas se escriben con CAS durable: si la
 * segunda falla se revierte la primera, porque un matrimonio a medias (A casado con B pero
 * B soltero) es peor que no casarse.
 *
 * <p>Las peticiones viven solo en memoria y caducan solas: una propuesta no es un estado
 * que merezca sobrevivir a un reinicio.
 */
public final class MarriageManager implements org.bukkit.event.Listener {

    private static final char US = '';
    private static final int TRANSIENT_SOFT_CAP = 2_048;
    private static final int TRANSIENT_HARD_CAP = 4_096;

    /** Vinculo ya sellado: con quien y desde cuando. */
    public record Marriage(UUID partner, String partnerName, long since) {
    }

    /**
     * Peticion abierta: quien la mando, hasta cuando vale y si se pidio BODA PUBLICA. Lo
     * segundo viaja en la propuesta a proposito: quien acepta debe saber que esta diciendo
     * que si a una ceremonia delante de todo el servidor, no solo al matrimonio.
     */
    public record Proposal(UUID from, String fromName, long expiresAt, boolean ceremony) {
    }

    private final JavaPlugin plugin;
    private final Store store;
    private final Map<UUID, Marriage> cache = new ConcurrentHashMap<>();
    /** Clave = quien debe responder; solo una propuesta abierta por jugador. */
    private final Map<UUID, Proposal> pending = new ConcurrentHashMap<>();

    /** Ultimo /marry tp por jugador, para el cooldown del perk. */
    private final Map<UUID, Long> lastTeleport = new ConcurrentHashMap<>();

    private volatile MarriageCeremony ceremony;
    private volatile WeddingRite rite;
    private volatile boolean enabled;
    private volatile boolean teleportEnabled = true;
    private volatile long teleportCooldownMillis = 120_000L;
    private volatile boolean joinNotice = true;
    private volatile boolean announce = true;
    private volatile boolean announceDivorce;
    private volatile long requestTimeoutMillis = 60_000L;

    /**
     * Linea del hover por defecto — la MISMA que trae players.yml. Existe como constante
     * porque un default explicito en {@code getString} tiene prioridad sobre el valor
     * empaquetado en el jar: sin esto, una config a la que le falte {@code Marriage.Tag}
     * (por venir de una version anterior) deja el tag vacio y la linea del certificado
     * nunca aparece, aunque el matrimonio exista.
     */
    static final String DEFAULT_TAG =
            "<#C0B3D9>{$married} {rank_color}» <#FBF2FF>{partner} <#7B68A0>· <#C0B3D9>{since}";

    private volatile String tagTemplate = DEFAULT_TAG;

    public MarriageManager(JavaPlugin plugin, StorageManager storage) {
        // Por servidor, igual que el cofre y el saldo: un backend sin Marriage no debe ver ni
        // divorciar (dejando huerfanos cofre y saldo) los matrimonios de otro.
        this(plugin, storage.perServerStore("marriages"));
    }

    /** Para tests: mismo comportamiento con un Store directo. */
    public MarriageManager(JavaPlugin plugin, Store store) {
        this.plugin = plugin;
        this.store = store;
    }

    /** La ceremonia (musica + invitacion) se instala aparte para poder testear el nucleo. */
    public void setCeremony(MarriageCeremony ceremony) {
        this.ceremony = ceremony;
    }

    /** La boda por pasos, para saber quien esta a mitad de una ceremonia. */
    public void setRite(WeddingRite rite) {
        this.rite = rite;
    }

    public void load() {
        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("Marriage.Enabled", false);
        announce = cfg.getBoolean("Marriage.Announce", true);
        announceDivorce = cfg.getBoolean("Marriage.Announce_Divorce", false);
        // 10 s minimo: por debajo, la propuesta caduca antes de que le de tiempo a leerla.
        requestTimeoutMillis = Math.max(10L, Math.min(3_600L,
                cfg.getLong("Marriage.Request_Timeout", 60))) * 1000L;
        // OJO con el default: pasar "" aqui GANA sobre el valor empaquetado en el jar, asi
        // que una config antigua sin la clave Marriage.Tag dejaba el tag vacio y la linea
        // del hover desaparecia aun estando casado. Se usa la plantilla real como respaldo.
        tagTemplate = cfg.getString("Marriage.Tag", DEFAULT_TAG);
        teleportEnabled = cfg.getBoolean("Marriage.Perks.Teleport.Enabled", true);
        teleportCooldownMillis = Math.max(0L, Math.min(86_400L,
                cfg.getLong("Marriage.Perks.Teleport.Cooldown_Seconds", 120))) * 1000L;
        joinNotice = cfg.getBoolean("Marriage.Perks.Join_Notice", true);
        reloadCache();
    }

    /** Rellena el cache desde el store. Package-private: los tests la usan sin config. */
    void reloadCache() {
        cache.clear();
        for (Map.Entry<String, String> entry : store.all().entrySet()) {
            try {
                Marriage parsed = parse(entry.getValue());
                if (parsed != null) cache.put(UUID.fromString(entry.getKey()), parsed);
            } catch (IllegalArgumentException ignored) {
                // clave corrupta: se ignora, como en el resto de stores
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Para tests: encender la feature sin config. */
    void enabledForTest(boolean value) {
        enabled = value;
    }

    /** Vinculo del jugador, o null si esta soltero. */
    public Marriage marriage(UUID id) {
        return id == null ? null : cache.get(id);
    }

    public boolean isMarried(UUID id) {
        return marriage(id) != null;
    }

    /**
     * Nombre MOSTRADO del conyuge (apodo si tiene), en texto plano y listo para insertarse
     * en un mensaje: sin formato y con los tags MiniMessage escapados, porque un apodo lo
     * controla el jugador y acaba dentro de una plantilla que SI se parsea.
     */
    public String partnerName(UUID id) {
        // Con la feature apagada el matrimonio no se muestra en NINGUN sitio: el dueño la
        // apaga para que desaparezca, no para dejar hovers colgando. El vinculo se conserva
        // en el store por si la vuelve a encender.
        if (!enabled) return "";
        Marriage current = marriage(id);
        if (current == null) return "";
        // Si la PAREJA esta en modo anonimo, tampoco se la nombra aqui. Vaciar solo el hover
        // del anonimo no basta: "Bea esta casada con Ana" mas "no veo a Ana conectada"
        // señala al disfrazado igual de bien que decir su nombre.
        if (dev.chatalyst.hook.Placeholders.isAnonymous(current.partner())) return "";
        // Y al reves: si el disfrazado es ESTE jugador, su propio hover ({married_to},
        // {married_tag}) decia "casado con Bea" debajo del alias. Quien sabe que Ana esta
        // casada con Bea reconocia al anonimo en su primera linea de chat.
        if (dev.chatalyst.hook.Placeholders.isAnonymous(id)) return "";
        return NickManager.safeName(current.partner().toString(), current.partnerName());
    }

    /** Tag decorado del matrimonio ({married_tag}), o "" si esta soltero o falta plantilla. */
    public String tag(UUID id) {
        Marriage current = marriage(id);
        return tag(partnerName(id), current == null ? 0L : current.since());
    }

    /**
     * El hover funciona como un ACTA: ademas de la pareja, {since} pone cuanto llevan
     * casados. Se calcula al vuelo desde la fecha guardada al sellar la boda.
     */
    private String tag(String partner, long since) {
        if (partner.isEmpty() || tagTemplate.isEmpty()) return "";
        String elapsed = since <= 0 ? ""
                : dev.chatalyst.util.TimeUtil.formatElapsed(System.currentTimeMillis() - since);
        return tagTemplate.replace("{partner}", partner).replace("{since}", elapsed);
    }

    /**
     * Nombre de la pareja y tag en UNA sola pasada. Lo usa {@code Placeholders.base()}, que
     * necesita los dos por mensaje: pedirlos por separado repetia el strip/escape de
     * MiniMessage del apodo dos veces por cada linea de chat de un casado.
     */
    public dev.chatalyst.hook.Placeholders.MarriageView view(UUID id) {
        String partner = partnerName(id);
        if (partner.isEmpty()) return dev.chatalyst.hook.Placeholders.MarriageView.NONE;
        Marriage current = marriage(id);
        return new dev.chatalyst.hook.Placeholders.MarriageView(
                partner, tag(partner, current == null ? 0L : current.since()));
    }

    // ---- Propuesta -------------------------------------------------------------------

    /** Motivo por el que una propuesta no puede salir (null = adelante). */
    public String proposeError(UUID from, UUID to) {
        if (from.equals(to)) return "marriage.self";
        // Nadie se casa disfrazado. El anuncio de boda ata el ALIAS a una pareja concreta y
        // eso es permanente: al quitarse el disfraz, cualquiera cruza "el alias se caso con
        // Bea" con "Ana esta casada con Bea" y sabe quien era el anonimo de aquel dia. Es el
        // mismo motivo por el que /nick esta bloqueado mientras dura el disfraz.
        if (dev.chatalyst.hook.Placeholders.isAnonymous(from)
                || dev.chatalyst.hook.Placeholders.isAnonymous(to)) {
            return "marriage.anonymous";
        }
        if (isMarried(from)) return "marriage.already-married";
        if (isMarried(to)) return "marriage.target-married";
        // Durante una ceremonia los novios AUN no estan casados: sin esto, un tercero podria
        // colar una boda instantanea a mitad del "acepto" y el sello final fallaria.
        WeddingRite inRite = rite;
        if (inRite != null && (inRite.involves(from) || inRite.involves(to))) {
            return "marriage.ceremony-in-progress";
        }
        Proposal open = active(to);
        // Una propuesta abierta se respeta: si no, quien llega despues pisa la anterior y
        // el "si" del jugador acabaria casandolo con otra persona.
        if (open != null && !open.from().equals(from)) return "marriage.target-busy";
        return null;
    }

    /** Registra la propuesta (el mensaje al destinatario lo manda el comando). */
    public void propose(UUID from, String fromName, UUID to, boolean ceremony) {
        long now = System.currentTimeMillis();
        pruneProposals(now);
        pending.put(to, new Proposal(from, fromName,
                dev.chatalyst.util.TimeUtil.deadline(now, requestTimeoutMillis), ceremony));
    }

    /** Propuesta viva dirigida a este jugador, o null si no hay o ya caduco. */
    public Proposal active(UUID to) {
        Proposal proposal = pending.get(to);
        if (proposal == null) return null;
        if (proposal.expiresAt() < System.currentTimeMillis()) {
            pending.remove(to, proposal);
            return null;
        }
        return proposal;
    }

    /** Quien propuso, para que el comando pueda avisarle. */
    public UUID proposer(UUID to) {
        Proposal proposal = active(to);
        return proposal == null ? null : proposal.from();
    }

    public void cancel(UUID to) {
        pending.remove(to);
    }

    public long timeoutSeconds() {
        return requestTimeoutMillis / 1000L;
    }

    // ---- Boda y divorcio -------------------------------------------------------------

    /**
     * Sella el matrimonio entre los dos. Devuelve false si alguna de las dos filas no se
     * pudo persistir (y entonces NO queda nadie casado a medias).
     */
    public synchronized boolean marry(UUID idA, String nameA, UUID idB, String nameB) {
        long now = System.currentTimeMillis();
        String valueA = encode(idB, nameB, now);
        String valueB = encode(idA, nameA, now);
        // expected = null: solo se casa quien sigue soltero en el backend autoritativo, asi
        // dos bodas simultaneas en la red no pueden dejar a alguien con dos parejas.
        if (!store.compareAndPutDurably(idA.toString(), null, valueA)) {
            return false;
        }
        if (!store.compareAndPutDurably(idB.toString(), null, valueB)) {
            if (!removeExactDurably(idA.toString(), valueA)) {
                reloadCache();
                severe("CRITICAL: could not roll back the first marriage row for " + idA
                        + " after the second row failed; manual storage repair may be required.");
            }
            return false;
        }
        cache.put(idA, new Marriage(idB, nameB, now));
        cache.put(idB, new Marriage(idA, nameA, now));
        pending.remove(idA);
        pending.remove(idB);
        return true;
    }

    /**
     * Rompe el vinculo por ambos lados con compensacion. La fila de la pareja se elimina
     * solo si apunta reciprocamente al solicitante; asi una fila corrupta nunca divorcia a
     * una tercera persona. Si la segunda escritura falla, se restaura la primera antes de
     * confirmar y el cache se vuelve a cargar desde el estado durable.
     */
    public synchronized boolean divorce(UUID id) {
        if (id == null) return false;
        String idKey = id.toString();
        String currentValue = store.get(idKey);
        Marriage current = parse(currentValue);
        if (current == null) {
            cache.remove(id);
            return false;
        }

        UUID partner = current.partner();
        String partnerKey = partner.toString();
        String partnerValue = store.get(partnerKey);
        Marriage theirs = parse(partnerValue);
        boolean reciprocal = theirs != null && id.equals(theirs.partner());

        if (reciprocal && !removeExactDurably(partnerKey, partnerValue)) {
            reloadCache();
            return false;
        }

        if (!removeExactDurably(idKey, currentValue)) {
            if (reciprocal && !restoreExactDurably(partnerKey, partnerValue)) {
                severe("CRITICAL: could not compensate a partial divorce between " + id
                        + " and " + partner + "; manual storage repair may be required.");
            }
            reloadCache();
            return false;
        }

        cache.remove(id);
        // Una fila no reciproca pertenece a otro vinculo y nunca se toca.
        if (reciprocal) cache.remove(partner);
        return true;
    }

    private boolean removeExactDurably(String key, String expected) {
        if (expected == null) return true;
        for (int attempt = 0; attempt < dev.chatalyst.db.StoreCas.DEFAULT_ATTEMPTS; attempt++) {
            if (store.compareAndRemoveDurably(key, expected)) return true;
            if (!java.util.Objects.equals(store.get(key), expected)) return false;
        }
        return false;
    }

    private boolean restoreExactDurably(String key, String value) {
        if (value == null) return true;
        for (int attempt = 0; attempt < dev.chatalyst.db.StoreCas.DEFAULT_ATTEMPTS; attempt++) {
            if (store.compareAndPutDurably(key, null, value)) return true;
            String current = store.get(key);
            if (java.util.Objects.equals(current, value)) return true;
            if (current != null) return false;
        }
        return false;
    }

    private void severe(String message) {
        if (plugin != null) plugin.getLogger().severe(message);
    }

    // ---- Perks ------------------------------------------------------------------------

    public boolean teleportEnabled() {
        return teleportEnabled;
    }

    /** Milisegundos que faltan para poder usar /marry tp otra vez (0 = ya se puede). */
    public long teleportCooldownLeft(UUID id) {
        Long last = lastTeleport.get(id);
        if (last == null) return 0L;
        long left = teleportCooldownMillis - (System.currentTimeMillis() - last);
        return Math.max(0L, left);
    }

    public void markTeleport(UUID id) {
        long now = System.currentTimeMillis();
        if (lastTeleport.size() >= TRANSIENT_SOFT_CAP) {
            long cutoff = now - Math.max(teleportCooldownMillis, 60_000L);
            lastTeleport.values().removeIf(time -> time < cutoff);
            evictOldest(lastTeleport);
        }
        lastTeleport.put(id, now);
    }

    private void pruneProposals(long now) {
        if (pending.size() < TRANSIENT_SOFT_CAP) return;
        pending.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
        if (pending.size() < TRANSIENT_HARD_CAP) return;
        Map.Entry<UUID, Proposal> oldest = pending.entrySet().stream()
                .min(java.util.Comparator.comparingLong(entry -> entry.getValue().expiresAt()))
                .orElse(null);
        if (oldest != null) pending.remove(oldest.getKey(), oldest.getValue());
    }

    private static void evictOldest(Map<UUID, Long> values) {
        if (values.size() < TRANSIENT_HARD_CAP) return;
        Map.Entry<UUID, Long> oldest = values.entrySet().stream()
                .min(Map.Entry.comparingByValue()).orElse(null);
        if (oldest != null) values.remove(oldest.getKey(), oldest.getValue());
    }

    /**
     * Aviso de "tu pareja se ha conectado". LOWEST no hace falta: es un mensaje suelto que no
     * compite con nada. Un anonimo NO lo dispara — su pareja recibiendo el aviso justo cuando
     * entra un desconocido lo delataria.
     */
    @org.bukkit.event.EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        if (!enabled || !joinNotice) return;
        Player player = event.getPlayer();
        if (dev.chatalyst.hook.Placeholders.isAnonymous(player.getUniqueId())) return;
        // Tampoco quien entra en vanish: el aviso a la pareja anunciaba "se ha conectado",
        // justo lo que el vanish existe para ocultar.
        if (dev.chatalyst.util.Vanish.isVanished(player)) return;
        Marriage current = marriage(player.getUniqueId());
        if (current == null) return;
        Player partner = online(current.partner());
        if (partner == null) return;
        String name = shown(player);
        FoliaTasks.entity(plugin, partner,
                p -> p.sendMessage(Lang.get(p, "marriage.spouse-joined", "player", name)));
    }

    // ---- Anuncios ---------------------------------------------------------------------

    /** Anuncio de boda: bonito, en el idioma de cada espectador, con sonido y corazones. */
    public void announceWedding(Player a, Player b, boolean withCeremony) {
        // BODA PRIVADA (/marry <jugador>, sin "boda"): silencio absoluto. Ni anuncio, ni
        // titulo, ni sonido, ni particulas — solo se enteran los dos, por el mensaje que ya
        // les manda el comando. Quien quiera que el servidor se entere pide ceremonia.
        if (!withCeremony) return;
        // Publica: Announce sigue siendo del dueño y solo silencia el TEXTO del anuncio;
        // la ceremonia (marcha, invitacion, efectos) la pidio la pareja y se respeta.
        broadcast(a, b, true, announce ? "marriage.broadcast" : null, true);
    }

    /**
     * Cierre de una boda POR PASOS: marcha y anuncio, pero sin reabrir la invitacion — ya
     * lleva abierta desde la convocatoria, y repetirla mandaba el mensaje del boton dos veces.
     */
    public void announceSealed(Player a, Player b) {
        broadcast(a, b, true, "marriage.broadcast", false);
    }

    /**
     * Ceremonia repetida ({@code /marry ceremony}) de una pareja que YA esta casada. Texto
     * propio: reutilizar el anuncio de boda gritaba "acaban de casarse" sobre gente que
     * lleva casada semanas. Tampoco lo tapa Announce — aqui no hay automatismo, lo pidieron.
     */
    public void announceCeremony(Player a, Player b) {
        broadcast(a, b, true, "marriage.ceremony-broadcast", true);
    }

    private void broadcast(Player a, Player b, boolean withCeremony, String messageKey,
                           boolean openInvite) {
        String nameA = shown(a);
        String nameB = shown(b);
        MarriageCeremony rite = ceremony;
        boolean ceremonial = rite != null && withCeremony;
        if (ceremonial) {
            String coupleKey = MarriageStorage.key(a.getUniqueId(), b.getUniqueId());
            if (openInvite) {
                rite.celebrate(a, b, nameA + " & " + nameB, coupleKey);
            } else {
                rite.celebrateWithoutInvitation(coupleKey);
            }
        }
        boolean invite = openInvite && ceremonial && rite.guestTeleportEnabled();
        // La DURACION configurada, no lo que queda: la ventana se abre en el tick siguiente
        // (tarea de region), asi que preguntar por lo que queda aqui daria siempre 0.
        long seconds = invite ? rite.windowSeconds() : 0L;
        UUID idA = a.getUniqueId();
        UUID idB = b.getUniqueId();
        // UN solo recorrido de jugadores para chat y titulo: en dos pasadas cada una se
        // agenda por su cuenta y el titulo podia aparecer un tick antes o despues del texto.
        FoliaTasks.onlinePlayers(plugin, viewer -> {
            if (messageKey != null) {
                viewer.sendMessage(Lang.get(viewer, messageKey, "a", nameA, "b", nameB));
                showWeddingTitle(viewer, nameA, nameB);
            }
            // A los NOVIOS no se les invita a su propia boda: ya estan en ella, y el boton
            // de "viajar a la ceremonia" no tiene ningun sentido para ellos.
            boolean groom = viewer.getUniqueId().equals(idA) || viewer.getUniqueId().equals(idB);
            if (invite && !groom) {
                viewer.sendMessage(Lang.get(viewer, "marriage.ceremony-invite",
                        "seconds", String.valueOf(seconds)));
            }
        });
        if (rite != null) {
            // Sin efecto configurado (Ceremony.Style: none) playStyle no hace nada, asi que
            // no hacen falta dos interruptores para lo mismo.
            rite.playStyle(a, b);
        }
    }

    /** Titulo/subtitulo en el idioma del espectador; si el lang los deja vacios, no sale. */
    private void showWeddingTitle(Player viewer, String nameA, String nameB) {
        String title = Lang.raw(viewer, "marriage.title", "a", nameA, "b", nameB);
        String subtitle = Lang.raw(viewer, "marriage.subtitle", "a", nameA, "b", nameB);
        if (title.isBlank() && subtitle.isBlank()) return;
        viewer.showTitle(net.kyori.adventure.title.Title.title(
                MM.full(title), MM.full(subtitle),
                net.kyori.adventure.title.Title.Times.times(
                        java.time.Duration.ofMillis(400),
                        java.time.Duration.ofMillis(2600),
                        java.time.Duration.ofMillis(800))));
    }

    /**
     * Prepara el anuncio de divorcio y lo devuelve SIN enviarlo. Los nombres se resuelven
     * ahora, con el vinculo aun vivo (despues partnerName() ya esta vacio), pero el anuncio
     * solo debe salir si el divorcio se guarda: antes se emitia primero y, si el store
     * fallaba, todo el servidor leia "ya no estan casados" de una pareja que seguia casada.
     */
    public Runnable divorceAnnouncement(Player who) {
        Runnable none = () -> { };
        if (!enabled || !announceDivorce) return none;
        Marriage current = marriage(who.getUniqueId());
        // Con alguno de los dos disfrazado no se anuncia NADA. Nombrarlo lo delataria, y
        // anunciarlo en blanco ("X y  ya no estan casados") es un mensaje roto.
        if (current == null
                || dev.chatalyst.hook.Placeholders.isAnonymous(who.getUniqueId())
                || dev.chatalyst.hook.Placeholders.isAnonymous(current.partner())) {
            return none;
        }
        String name = shown(who);
        String partner = partnerName(who.getUniqueId());
        if (partner.isEmpty()) return none;
        return () -> FoliaTasks.onlinePlayers(plugin, viewer -> viewer.sendMessage(
                Lang.get(viewer, "marriage.broadcast-divorce", "a", name, "b", partner)));
    }

    /** Nombre mostrado, plano y escapado: acaba dentro de una plantilla MiniMessage. */
    private static String shown(Player player) {
        return NickManager.safeName(player);
    }



    // ---- Serializacion ----------------------------------------------------------------

    static String encode(UUID partner, String name, long since) {
        return partner + String.valueOf(US) + name + US + since;
    }

    static Marriage parse(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String[] parts = raw.split(String.valueOf(US), 3);
        if (parts.length < 3) return null;
        try {
            return new Marriage(UUID.fromString(parts[0]), parts[1], Long.parseLong(parts[2]));
        } catch (IllegalArgumentException ignored) {
            return null; // fila corrupta: el jugador cuenta como soltero
        }
    }

    /** Jugador online por UUID, o null (para avisar al proponente sin bloquear). */
    static Player online(UUID id) {
        return id == null ? null : Bukkit.getPlayer(id);
    }

    public void shutdown() {
        store.flushNow();
    }
}
