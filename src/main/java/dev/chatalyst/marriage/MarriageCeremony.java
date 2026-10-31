package dev.chatalyst.marriage;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.SoundUtil;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * La ceremonia: la marcha nupcial que suena para todo el servidor y la invitacion con boton
 * para que quien quiera se teletransporte a la boda.
 *
 * <p>Minecraft no trae ninguna musica de boda, asi que se toca NOTA A NOTA con sonidos de
 * note block: cada nota es un indice 0-24 (F#3 a F#5, el rango de un note block) y su
 * momento en ticks. El pitch sale de {@code 2^((nota-12)/12)}, que es la formula que usa el
 * propio juego.
 *
 * <p>Quien decide si una boda es publica NO es el dueño del servidor, son los novios: se
 * pide al proponer ({@code /marry <jugador> boda}) o despues con {@code /marry ceremony}.
 * La config solo pone los limites de seguridad (si el boton de viajar existe siquiera) y la
 * partitura, no la eleccion.
 */
public final class MarriageCeremony {

    /** Una nota de la melodia: que suena y en que tick desde el inicio. */
    record Note(int note, long tick) {
    }

    /** Boda en curso a la que se puede viajar. */
    private record Active(Location where, String couple, long expiresAt) {
    }

    private static final int MAX_NOTE = 24;
    private static final int MAX_MELODY_NOTES = 128;
    private static final long MAX_MELODY_TICK = 20L * 60L * 10L;
    private static final String DEFAULT_INSTRUMENT = "block.note_block.bell";
    /**
     * Coro nupcial de Wagner ("Here comes the bride") en Do, las DOS secciones (~16 s):
     * la frase conocida y la respuesta que sube hasta F5 y resuelve. El note block solo
     * llega a F#5 (24), asi que la melodia se queda entre G4 y F5.
     * 20 ticks = 1 segundo; corchea 5, negra 10, negra con puntillo 15, blanca 20.
     */
    static final List<String> DEFAULT_MELODY = List.of(
            "13:0", "18:5", "18:20", "18:25", "13:40",
            "20:45", "18:55", "17:60", "18:65", "13:90",
            "18:95", "18:110", "18:115", "22:130", "20:140",
            "18:145", "17:150", "18:160", "13:190", "18:195",
            "18:210", "18:215", "23:230", "22:240", "20:245",
            "18:250", "20:265", "22:270", "23:275", "22:290",
            "20:295", "18:300");

    private final JavaPlugin plugin;

    private volatile String instrument = DEFAULT_INSTRUMENT;
    private volatile String style = "hearts";
    private volatile List<Note> melody = List.of();
    private volatile boolean guestTeleport = true;
    private volatile long windowMillis = 60_000L;
    private volatile long cooldownMillis = 600_000L;
    private volatile Active active;
    /** Ultima ceremonia por pareja: sin esto, /marry ceremony es un altavoz para spamear. */
    private final java.util.Map<String, Long> lastCeremony = new java.util.concurrent.ConcurrentHashMap<>();

    public MarriageCeremony(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        var cfg = plugin.getConfig();
        instrument = cfg.getString("Marriage.Ceremony.Instrument", DEFAULT_INSTRUMENT);
        style = cfg.getString("Marriage.Ceremony.Style", "hearts");
        List<String> raw = cfg.getStringList("Marriage.Ceremony.Melody");
        melody = parseMelody(raw.isEmpty() ? DEFAULT_MELODY : raw, plugin);
        guestTeleport = cfg.getBoolean("Marriage.Ceremony.Guest_Teleport", true);
        windowMillis = Math.max(5L, Math.min(3_600L,
                cfg.getLong("Marriage.Ceremony.Window_Seconds", 60))) * 1000L;
        cooldownMillis = Math.max(0L, Math.min(43_200L,
                cfg.getLong("Marriage.Ceremony.Cooldown_Minutes", 10))) * 60_000L;
    }

    /** Si el boton de viajar a la boda existe siquiera (interruptor de SEGURIDAD del dueño). */
    public boolean guestTeleportEnabled() {
        return guestTeleport;
    }

    /** Minutos que faltan para que esta pareja pueda volver a convocar (0 = ya puede). */
    public long cooldownMinutesLeft(String coupleKey) {
        Long last = lastCeremony.get(coupleKey);
        if (last == null) return 0L;
        long left = cooldownMillis - (System.currentTimeMillis() - last);
        return left <= 0 ? 0L : (left + 59_999L) / 60_000L;
    }

    /**
     * Lee la melodia de la config. Una entrada mal escrita se SALTA con aviso en vez de
     * tumbar la boda: que el dueño se equivote en una nota no puede impedir que nadie se case.
     */
    static List<Note> parseMelody(List<String> raw, JavaPlugin plugin) {
        List<Note> out = new ArrayList<>();
        for (String entry : raw) {
            if (out.size() >= MAX_MELODY_NOTES) break;
            if (entry == null || entry.isBlank()) continue;
            String[] parts = entry.trim().split(":");
            if (parts.length != 2) {
                warn(plugin, entry, "expected \"<note 0-24>:<tick>\"");
                continue;
            }
            try {
                int note = Integer.parseInt(parts[0].trim());
                long tick = Long.parseLong(parts[1].trim());
                if (note < 0 || note > MAX_NOTE || tick < 0 || tick > MAX_MELODY_TICK) {
                    warn(plugin, entry, "note must be 0-" + MAX_NOTE + " and tick 0-"
                            + MAX_MELODY_TICK);
                    continue;
                }
                out.add(new Note(note, tick));
            } catch (NumberFormatException ex) {
                warn(plugin, entry, "not a number");
            }
        }
        return List.copyOf(out);
    }

    private static void warn(JavaPlugin plugin, String entry, String why) {
        if (plugin != null) {
            plugin.getLogger().warning("Marriage ceremony: skipping melody entry \""
                    + entry + "\" (" + why + ").");
        }
    }

    /**
     * Pinta el efecto de la boda sobre los novios, reutilizando los mismos estilos que las
     * entradas fancy (hearts, firework-show, halo...). Se ejecuta en la region de cada uno.
     */
    public void playStyle(Player a, Player b) {
        dev.chatalyst.messages.FancyJoinStyle chosen =
                dev.chatalyst.messages.FancyJoinStyle.byId(style);
        if (chosen == dev.chatalyst.messages.FancyJoinStyle.NONE) return;
        FoliaTasks.entity(plugin, a, chosen::play);
        FoliaTasks.entity(plugin, b, chosen::play);
    }

    /** Pitch de un note block: la nota 12 es el pitch 1.0 (F#4). */
    static float pitch(int note) {
        return (float) Math.pow(2.0, (note - 12) / 12.0);
    }

    /**
     * Celebra la boda: abre la ventana de invitacion y lanza la marcha nupcial. El anuncio de
     * texto lo manda {@link MarriageManager}; aqui va lo que suena y a donde se viaja.
     */
    public void celebrate(Player a, Player b, String couple, String coupleKey) {
        markCeremony(coupleKey);
        openInvitation(b, couple, windowMillis);
        playMarch();
    }

    /** Solo la marcha: la invitacion ya la abrio la convocatoria de la boda por pasos. */
    public void celebrateWithoutInvitation(String coupleKey) {
        markCeremony(coupleKey);
        playMarch();
    }

    private void markCeremony(String coupleKey) {
        long now = System.currentTimeMillis();
        if (lastCeremony.size() >= 2_048) {
            long cutoff = now - Math.max(cooldownMillis, 60_000L);
            lastCeremony.values().removeIf(time -> time < cutoff);
            if (lastCeremony.size() >= 4_096) {
                Map.Entry<String, Long> oldest = lastCeremony.entrySet().stream()
                        .min(Map.Entry.comparingByValue()).orElse(null);
                if (oldest != null) lastCeremony.remove(oldest.getKey(), oldest.getValue());
            }
        }
        lastCeremony.put(coupleKey, now);
    }

    /**
     * Abre (o renueva) la invitacion en la posicion de {@code host}. La boda por pasos la
     * abre en la CONVOCATORIA, para que los invitados lleguen antes del "si"; la ceremonia
     * suelta la abre al celebrar.
     */
    public void openInvitation(Player host, String couple, long millis) {
        if (!guestTeleport) return;
        // La posicion se lee en la region del anfitrion (Folia) y se guarda ya clonada:
        // guardar la Location viva seguiria al jugador cuando se mueva.
        FoliaTasks.entity(plugin, host, at ->
                active = new Active(at.getLocation().clone(), couple,
                        System.currentTimeMillis() + millis));
    }

    /**
     * Toca la marcha para TODO el servidor. Cada nota se agenda por separado; son ~10 tareas
     * por boda, y una boda no es algo que pase cada segundo.
     */
    private void playMarch() {
        List<Note> notes = melody;
        String key = instrument;
        for (Note note : notes) {
            Sound sound = SoundUtil.sound(key, DEFAULT_INSTRUMENT, 1.0f, pitch(note.note()));
            if (note.tick() <= 0) {
                FoliaTasks.onlinePlayers(plugin, player -> player.playSound(sound));
            } else {
                FoliaTasks.globalLater(plugin,
                        () -> FoliaTasks.onlinePlayers(plugin, player -> player.playSound(sound)),
                        note.tick());
            }
        }
    }

    /**
     * Duracion CONFIGURADA de la invitacion, no lo que le queda: la ventana se abre dentro
     * de una tarea de region (tick siguiente), asi que consultar el tiempo restante justo
     * despues de convocar devolvia 0 y el mensaje decia "se cierra en 0 segundos".
     */
    public long windowSeconds() {
        return windowMillis / 1000L;
    }


    /** Nombres de la pareja de la boda abierta, o "" si no hay ninguna. */
    public String couple() {
        Active current = active;
        return current == null || current.expiresAt() < System.currentTimeMillis()
                ? "" : current.couple();
    }

    /** Lleva a un invitado a la boda. Devuelve false si ya no hay ceremonia abierta. */
    public boolean teleportGuest(Player guest) {
        Active current = active;
        if (current == null || current.expiresAt() < System.currentTimeMillis()) {
            active = null;
            return false;
        }
        Location destination = current.where();
        FoliaTasks.entity(plugin, guest, traveller ->
                traveller.teleportAsync(destination).thenAccept(success -> {
                    if (success) {
                        traveller.sendMessage(Lang.get(traveller, "marriage.ceremony-arrived"));
                    } else {
                        traveller.sendMessage(Lang.get(traveller, "marriage.ceremony-failed"));
                    }
                }));
        return true;
    }

    /** Cierra la invitacion (divorcio, reload o apagado). */
    public void clear() {
        active = null;
    }

}
