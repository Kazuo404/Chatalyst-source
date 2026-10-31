package dev.chatalyst.chat;

import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Permite borrar un mensaje ya enviado (la X de moderacion, y la limpieza automatica al
 * mutear por spam). Guarda la firma de los mensajes recientes por un id corto y tambien por
 * jugador; al pedir el borrado usa la API de mensajes firmados de Minecraft
 * (Audience#deleteMessage) para quitarlo del chat de todos.
 *
 * <p>Si el mensaje llego SIN firma (servidor no premium: online-mode=false y
 * enforce-secure-profile=false) el cliente no puede quitarlo por firma. En ese caso se quita
 * de las ultimas lineas que Chatalyst mostro a cada espectador ({@link ViewerChatBuffer}) y a
 * quien lo tenia se le limpia el chat y se le reenvia lo que queda.
 */
public final class MessageDeleteManager {

    private static final int PER_PLAYER_HISTORY = 15;
    private static final long REVEAL_TTL_MS = 5L * 60L * 1000L;
    // Lineas recordadas por espectador para el redibujado (el cliente guarda 100).
    private static final int VIEWER_LINES = 100;
    // Lineas vacias que empujan el chat viejo fuera de la pantalla y del historial del cliente.
    private static final int CLEAR_LINES = 100;

    /** Mensaje rastreado; {@code signature} es null si llego sin firma. */
    private record TrackedMessage(String id, SignedMessage.Signature signature) {
        boolean signed() {
            return signature != null;
        }
    }

    private record RevealKey(String id, UUID viewer) {
    }

    private record RevealEntry(Component component, long expiresAt) {
    }

    private final JavaPlugin plugin;
    // Cache acotada de los ultimos mensajes (id -> mensaje). Se descartan los mas antiguos.
    private final Map<String, TrackedMessage> recent = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, TrackedMessage> eldest) {
                    return size() > 500;
                }
            });
    // Ultimos mensajes por jugador (para borrar en bloque al mutear por spam).
    private final Map<UUID, Deque<TrackedMessage>> byPlayer = new ConcurrentHashMap<>();
    // Contenido revelable ligado al espectador exacto. El id corto puede aparecer en botones
    // o logs, pero conocerlo no autoriza a otro jugador. Ademas expira aunque no llegue al LRU.
    private final Map<RevealKey, RevealEntry> revealByViewer = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<RevealKey, RevealEntry> eldest) {
                    return size() > 300;
                }
            });
    // Lo que cada espectador tiene en pantalla, para borrar lo que no va firmado.
    private final ViewerChatBuffer shown = new ViewerChatBuffer(VIEWER_LINES);

    public MessageDeleteManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Rastrea un mensaje y devuelve su id (o "" si no hay mensaje). Con firma se borrara por
     * firma; sin ella, por redibujado del chat de quien lo vio.
     */
    public String track(SignedMessage signed, UUID sender) {
        if (signed == null) return "";
        TrackedMessage tracked;
        synchronized (recent) {
            String id;
            do {
                id = UUID.randomUUID().toString().substring(0, 8);
            } while (recent.containsKey(id));
            tracked = new TrackedMessage(id, signed.signature());
            recent.put(id, tracked);
        }
        if (sender != null) {
            Deque<TrackedMessage> dq = byPlayer.computeIfAbsent(sender, u -> new ArrayDeque<>());
            synchronized (dq) {
                dq.addLast(tracked);
                while (dq.size() > PER_PLAYER_HISTORY) dq.removeFirst();
            }
        }
        return tracked.id();
    }

    /** Anota la linea tal como la ve ese espectador (renderer del chat, cualquier hilo). */
    public void recordShown(String id, UUID viewer, Component rendered) {
        shown.record(viewer, id, rendered);
    }

    /** Guarda el mensaje solo para el espectador al que se mostro el boton de revelar. */
    public void rememberReveal(String id, UUID viewer, Component rendered) {
        if (id == null || id.isEmpty() || viewer == null || rendered == null) return;
        revealByViewer.putIfAbsent(new RevealKey(id, viewer),
                new RevealEntry(rendered, System.currentTimeMillis() + REVEAL_TTL_MS));
    }

    /** El mensaje revelable para ESE espectador, o null si no era suyo o ya expiro. */
    public Component reveal(String id, UUID viewer) {
        if (id == null || viewer == null) return null;
        RevealKey key = new RevealKey(id, viewer);
        RevealEntry entry = revealByViewer.get(key);
        if (entry == null) return null;
        if (entry.expiresAt() <= System.currentTimeMillis()) {
            revealByViewer.remove(key, entry);
            return null;
        }
        return entry.component();
    }

    /** Borra el mensaje de todos los que lo tengan. Devuelve true si existia. */
    public boolean delete(String id) {
        forgetReveal(id);
        TrackedMessage tracked = id == null ? null : recent.remove(id);
        if (tracked == null) return false;
        erase(List.of(tracked));
        return true;
    }

    /** Borra los ultimos {@code count} mensajes de un jugador (limpieza al mutear por spam). */
    public void deleteRecentFrom(UUID sender, int count) {
        Deque<TrackedMessage> dq = byPlayer.get(sender);
        if (dq == null) return;
        List<TrackedMessage> removed = new ArrayList<>();
        synchronized (dq) {
            while (!dq.isEmpty() && removed.size() < count) {
                TrackedMessage tracked = dq.removeLast();
                recent.remove(tracked.id(), tracked);
                forgetReveal(tracked.id());
                removed.add(tracked);
            }
        }
        erase(removed);
    }

    /**
     * Borra TODOS los mensajes recientes del jugador del chat de todos (purga de moderacion,
     * hasta {@value PER_PLAYER_HISTORY} mensajes rastreados). Devuelve cuantos borro.
     */
    public int purge(UUID sender) {
        Deque<TrackedMessage> dq = byPlayer.remove(sender);
        if (dq == null) return 0;
        List<TrackedMessage> removed = new ArrayList<>();
        synchronized (dq) {
            while (!dq.isEmpty()) {
                TrackedMessage tracked = dq.removeLast();
                recent.remove(tracked.id(), tracked);
                forgetReveal(tracked.id());
                removed.add(tracked);
            }
        }
        erase(removed);
        return removed.size();
    }

    public void forget(UUID sender) {
        byPlayer.remove(sender);
        shown.forget(sender);
    }

    /**
     * Olvida el historial con un margen de 5 minutos: asi el staff puede purgar (/cht purge)
     * a quien dijo algo y se desconecto al momento. Si el jugador vuelve antes, no se toca.
     * Sus lineas de espectador se sueltan ya: al salir, su cliente pierde ese chat.
     */
    public void forgetLater(UUID sender) {
        shown.forget(sender);
        dev.chatalyst.util.FoliaTasks.globalLater(plugin, () -> {
            if (plugin.getServer().getPlayer(sender) == null) forget(sender);
        }, 5L * 60L * 20L);
    }

    private void forgetReveal(String id) {
        if (id == null) return;
        synchronized (revealByViewer) {
            revealByViewer.keySet().removeIf(key -> key.id().equals(id));
        }
    }

    /**
     * Quita los mensajes del chat: los firmados por firma (como siempre) y los sin firma
     * redibujando, una sola vez por espectador, el chat de quien los tenia.
     */
    private void erase(List<TrackedMessage> messages) {
        if (messages.isEmpty()) return;
        List<String> signedIds = new ArrayList<>();
        List<String> unsignedIds = new ArrayList<>();
        for (TrackedMessage tracked : messages) {
            if (tracked.signed()) {
                broadcastDelete(tracked.signature());
                signedIds.add(tracked.id());
            } else {
                unsignedIds.add(tracked.id());
            }
        }
        // El cliente ya quito los firmados: solo que un redibujado posterior no los resucite.
        shown.remove(signedIds);
        redraw(shown.remove(unsignedIds));
    }

    private void broadcastDelete(SignedMessage.Signature sig) {
        // Puede llamarse desde el hilo async del chat (auto-mute de SpamPunisher): la
        // iteracion de jugadores se despacha por entidad via FoliaTasks (Folia-safe).
        dev.chatalyst.util.FoliaTasks.onlinePlayers(plugin, p -> p.deleteMessage(sig));
        dev.chatalyst.util.FoliaTasks.global(plugin,
                () -> plugin.getServer().getConsoleSender().deleteMessage(sig));
    }

    /**
     * Limpia el chat de esos espectadores y les reenvia sus lineas restantes, en el hilo de
     * cada jugador. Lo que no paso por Chatalyst (avisos de otros plugins) no se repone.
     */
    private void redraw(Set<UUID> viewers) {
        if (viewers.isEmpty() || plugin == null) return;
        dev.chatalyst.util.FoliaTasks.onlinePlayers(plugin,
                p -> viewers.contains(p.getUniqueId()), this::redrawFor);
    }

    private void redrawFor(Player player) {
        for (int i = 0; i < CLEAR_LINES; i++) player.sendMessage(Component.empty());
        for (Component line : shown.lines(player.getUniqueId())) player.sendMessage(line);
    }
}
