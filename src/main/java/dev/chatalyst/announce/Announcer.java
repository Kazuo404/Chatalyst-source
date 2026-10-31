package dev.chatalyst.announce;

import dev.chatalyst.toggle.ToggleManager;
import dev.chatalyst.toggle.ToggleType;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Anuncios automaticos rotativos. Cada cierto intervalo difunde un mensaje de la lista,
 * en orden o al azar. El temporizador corre async y cada envio se despacha al scheduler
 * correcto del jugador.
 *
 * <p>Modo inteligente (Announcer.Smart): el anuncio solo sale si el chat esta vivo — desde
 * el anuncio anterior tienen que haber escrito suficientes jugadores DISTINTOS (un minimo
 * fijo o un porcentaje de los conectados, lo que sea mayor). En un servidor callado o casi
 * vacio el anuncio simplemente espera al siguiente intervalo, en vez de spamear a los
 * mismos 3 jugadores cada pocos minutos.</p>
 */
public final class Announcer implements Listener {

    private final JavaPlugin plugin;
    private final ToggleManager toggles;
    // Volatile + lista inmutable: broadcastNext() corre en el scheduler async y un anuncio
    // en vuelo puede leer esto mientras /chatalyst reload lo reescribe.
    private volatile boolean enabled;
    private volatile int intervalSeconds;
    private volatile boolean random;
    private volatile String prefix;
    private volatile List<String> messages = List.of();
    // Variante en español (convencion _es del proyecto): paralela a Messages por indice.
    // Cada espectador ve la de su cliente; si falta una linea, cae a la inglesa (fuente).
    private volatile List<String> messagesEs = List.of();
    // Modo inteligente: umbrales de actividad para no spamear un chat muerto.
    private volatile boolean smart;
    private volatile int smartMinPlayers;
    private volatile int smartMinChatters;
    private volatile int smartChattersPercent;

    private ScheduledTask task;
    private final AtomicInteger index = new AtomicInteger(0);
    private final AtomicInteger onlinePlayers = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicLong generation =
            new java.util.concurrent.atomic.AtomicLong();
    // Jugadores distintos que escribieron en el chat desde el ultimo anuncio. Set
    // concurrente: se llena desde el chat async y se lee/limpia desde el scheduler.
    // Tope: si nunca se emite un anuncio (Messages vacio, Min_Players inalcanzable...)
    // el set no debe crecer sin limite con la rotacion de jugadores; muy por encima de
    // cualquier umbral realista de chatIsActive(), asi que no altera la decision.
    private static final int CHATTERS_CAP = 5000;
    private final Set<UUID> chattersSinceLast = ConcurrentHashMap.newKeySet();

    public Announcer(JavaPlugin plugin, ToggleManager toggles) {
        this.plugin = plugin;
        this.toggles = toggles;
    }

    public void load() {
        stop();
        enabled = plugin.getConfig().getBoolean("Announcer.Enabled", true);
        intervalSeconds = Math.max(5, plugin.getConfig().getInt("Announcer.Interval", 300));
        random = plugin.getConfig().getBoolean("Announcer.Random", false);
        prefix = dev.chatalyst.config.ConfigFiles.string(
                plugin.getConfig(), "Announcer.Prefix", "");
        messages = List.copyOf(plugin.getConfig().getStringList("Announcer.Messages"));
        messagesEs = List.copyOf(plugin.getConfig().getStringList("Announcer.Messages_es"));
        smart = plugin.getConfig().getBoolean("Announcer.Smart.Enabled", true);
        smartMinPlayers = Math.max(0, plugin.getConfig().getInt("Announcer.Smart.Min_Players", 2));
        smartMinChatters = Math.max(1, plugin.getConfig().getInt("Announcer.Smart.Min_Chatters", 3));
        smartChattersPercent = Math.max(0, plugin.getConfig().getInt("Announcer.Smart.Chatters_Percent", 10));
    }

    public void start() {
        if (!enabled || messages.isEmpty()) return;
        long run = generation.incrementAndGet();
        // start() se invoca desde el global scheduler/onEnable. El ticker async usa este
        // contador en vez de consultar la coleccion global de jugadores fuera de su hilo.
        onlinePlayers.set(plugin.getServer().getOnlinePlayers().size());
        task = plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, t -> {
                    if (generation.get() == run && enabled) broadcastNext();
                },
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    public void stop() {
        generation.incrementAndGet();
        enabled = false;
        onlinePlayers.set(0);
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /**
     * Registra actividad para el modo inteligente. MONITOR: solo cuenta chat que sobrevive —
     * incluido el entregado por la traduccion en vivo, que cancela el evento pero SI se ve.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        if (event.isCancelled()
                && !dev.chatalyst.chat.ChatListener.deliveredByTranslation(event)) return;
        if (smart && enabled && !messages.isEmpty() && chattersSinceLast.size() < CHATTERS_CAP) {
            chattersSinceLast.add(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (enabled) onlinePlayers.incrementAndGet();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (enabled) onlinePlayers.updateAndGet(current -> Math.max(0, current - 1));
    }

    // El chat esta lo bastante vivo como para que un anuncio aporte en vez de estorbar.
    private boolean chatIsActive() {
        int online = onlinePlayers.get();
        if (online < smartMinPlayers) return false;
        // Ej.: 100 conectados al 10% => hacen falta 10 jugadores distintos chateando;
        // con 5 conectados manda el minimo fijo (3), asi un duo AFK no dispara anuncios.
        int required = Math.max(smartMinChatters,
                (int) Math.ceil(online * (smartChattersPercent / 100.0)));
        return chattersSinceLast.size() >= required;
    }

    private void broadcastNext() {
        List<String> current = messages; // una sola lectura del volatile: size y get coherentes
        if (current.isEmpty()) return;
        // Modo inteligente: si el chat esta callado, no consumimos el turno; la actividad
        // se sigue acumulando y el anuncio saldra en un proximo intervalo.
        if (smart && !chatIsActive()) return;
        chattersSinceLast.clear();
        int i = random
                ? ThreadLocalRandom.current().nextInt(current.size())
                : Math.floorMod(index.getAndIncrement(), current.size());
        // Renderizado UNA vez por idioma; cada espectador recibe el de su cliente. El indice
        // se elige sobre la lista inglesa (fuente); la española es paralela y, si le falta esa
        // linea (lista mas corta o vacia), se cae al inglés en vez de callar.
        List<String> es = messagesEs;
        String englishLine = current.get(i);
        String spanishLine = (i < es.size() && !es.get(i).isBlank()) ? es.get(i) : englishLine;
        Component english = MM.full(prefix + englishLine);
        Component spanish = MM.full(prefix + spanishLine);
        // Respetamos a quienes desactivaron los anuncios con /toggle announcements.
        FoliaTasks.onlinePlayers(plugin,
                p -> toggles.isEnabled(p, ToggleType.ANNOUNCEMENTS),
                p -> p.sendMessage(dev.chatalyst.lang.Lang.isSpanish(p) ? spanish : english));
        FoliaTasks.global(plugin, () -> plugin.getServer().getConsoleSender().sendMessage(english));
    }
}
