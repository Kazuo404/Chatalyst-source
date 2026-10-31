package dev.chatalyst.containerlog;

import dev.chatalyst.lang.Lang;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Registro de interacciones con contenedores y mecanismos: cofres, ender chests, shulkers,
 * hornos, palancas, puertas... con coordenadas exactas. Mantiene un buffer reciente en
 * memoria (para /containerlog con botones de teletransporte) y persiste cada entrada como
 * tipo CONTAINER en los archivos diarios del {@link dev.chatalyst.log.LogManager}.
 */
public final class ContainerLogManager {

    /** Los que llevan este permiso no dejan rastro (duenos/config del servidor). */
    public static final String EXEMPT_PERMISSION = "chatalyst.containerlog.exempt";
    /** Botones [Ir] y [TP] de los logs y del spy. */
    public static final String TELEPORT_PERMISSION = "chatalyst.containerlog.teleport";
    /** Boton [Ver]: abrir el contenido del contenedor desde la linea del spy. */
    public static final String PEEK_PERMISSION = "chatalyst.containerlog.peek";

    private static final int RECENT_MAX = 1000;

    private final JavaPlugin plugin;
    private final dev.chatalyst.log.LogManager log;
    private final Deque<ContainerRecord> recent = new ArrayDeque<>();
    private final Object recentLock = new Object();

    // Volatile: los eventos llegan desde hilos de region (Folia) mientras un reload reescribe.
    private volatile boolean containersEnabled;
    private volatile boolean mechanismsEnabled;
    private volatile boolean craftingEnabled;
    private volatile boolean tradingEnabled;
    private volatile long repeatWindowMillis = 60_000L;

    public ContainerLogManager(JavaPlugin plugin, dev.chatalyst.log.LogManager log) {
        this.plugin = plugin;
        this.log = log;
        load();
    }

    public void load() {
        containersEnabled = plugin.getConfig().getBoolean("Spy.Containers.Enabled", true);
        // Jerarquico: con Enabled apagado se apaga TODO.
        mechanismsEnabled = containersEnabled
                && plugin.getConfig().getBoolean("Spy.Containers.Mechanisms", true);
        craftingEnabled = containersEnabled
                && plugin.getConfig().getBoolean("Spy.Containers.Crafting", true);
        tradingEnabled = containersEnabled
                && plugin.getConfig().getBoolean("Spy.Containers.Trading", true);
        // 0 = sin agrupacion (cada repeticion su linea); tope de 1 hora.
        repeatWindowMillis = Math.max(0, Math.min(3600,
                plugin.getConfig().getInt("Spy.Containers.Repeat_Seconds", 60))) * 1000L;
    }

    /** Crafteos y encantamientos (mesa de trabajo). */
    public boolean craftingEnabled() {
        return craftingEnabled;
    }

    /** Tratos cerrados con aldeanos. */
    public boolean tradingEnabled() {
        return tradingEnabled;
    }

    /** Ventana de agrupacion de repeticiones, en milisegundos. */
    public long repeatWindowMillis() {
        return repeatWindowMillis;
    }

    public boolean containersEnabled() {
        return containersEnabled;
    }

    public boolean mechanismsEnabled() {
        return mechanismsEnabled;
    }

    /** Encola el registro en memoria y lo persiste en el archivo diario. */
    public void add(ContainerRecord record) {
        synchronized (recentLock) {
            recent.addLast(record);
            while (recent.size() > RECENT_MAX) recent.pollFirst();
        }
        if (log != null && log.logsContainers()) {
            log.log("CONTAINER", record.playerName(), record.fileContent());
        }
    }

    /**
     * Ultimas entradas que involucran a alguno de {@code players} (o todas con null/vacio),
     * cronologicas (mas nuevas al final).
     */
    public List<ContainerRecord> recent(Set<String> players, int limit) {
        List<ContainerRecord> out = new ArrayList<>();
        synchronized (recentLock) {
            var it = recent.descendingIterator();
            while (it.hasNext() && out.size() < limit) {
                ContainerRecord r = it.next();
                if (matches(r.playerName(), players)) out.add(r);
            }
        }
        java.util.Collections.reverse(out);
        return out;
    }

    private static boolean matches(String who, Set<String> players) {
        if (players == null || players.isEmpty()) return true;
        for (String p : players) {
            if (who.equalsIgnoreCase(p)) return true;
        }
        return false;
    }

    // ---- Etiquetas localizadas -------------------------------------------------------

    /**
     * Clave normalizada del objetivo: los 17 colores de shulker colapsan en shulker_box,
     * cualquier puerta en door, etc. Asi el lang no necesita una entrada por variante.
     */
    public static String targetKey(String rawTarget) {
        String n = rawTarget.toUpperCase(Locale.ROOT);
        if (n.endsWith("SHULKER_BOX")) return "shulker_box";
        if (n.endsWith("_DOOR")) return "door";
        if (n.endsWith("_TRAPDOOR")) return "trapdoor";
        if (n.endsWith("_BUTTON")) return "button";
        if (n.endsWith("_FENCE_GATE")) return "fence_gate";
        if (n.endsWith("_PRESSURE_PLATE")) return "pressure_plate";
        if (n.endsWith("_BOAT") && n.contains("CHEST")) return "chest_boat";
        return n.toLowerCase(Locale.ROOT);
    }

    /**
     * Nombre amigable del objetivo en el idioma del viewer, con fallback legible. Los
     * tratos llegan codificados como {@code 3xEMERALD/20xWHEAT+1xBOOK} (lo que se llevo /
     * lo que pago) y se traducen a "3x emerald por 20x wheat, 1x book".
     */
    public static String targetLabel(CommandSender viewer, String rawTarget) {
        int split = rawTarget.indexOf('/');
        if (split > 0) {
            return Lang.raw(viewer, "containerlog.trade-target",
                    "result", itemsLabel(viewer, rawTarget.substring(0, split)),
                    "cost", itemsLabel(viewer, rawTarget.substring(split + 1)));
        }
        // Un item suelto tambien puede venir con cantidad (crafteos, resultado de trato).
        return itemsLabel(viewer, rawTarget);
    }

    /** "3xEMERALD+1xBOOK" -> "3x emerald, 1x book" (cada material traducible por lang). */
    private static String itemsLabel(CommandSender viewer, String encoded) {
        StringBuilder out = new StringBuilder();
        for (String part : encoded.split("\\+")) {
            if (part.isEmpty()) continue;
            if (out.length() > 0) out.append(", ");
            String amount = "";
            String material = part;
            int x = part.indexOf('x');
            if (x > 0 && part.substring(0, x).chars().allMatch(Character::isDigit)) {
                int count = Integer.parseInt(part.substring(0, x));
                if (count > 1) amount = count + "x ";
                material = part.substring(x + 1);
            }
            out.append(amount).append(simpleLabel(viewer, material));
        }
        return out.toString();
    }

    /** Etiqueta de un material suelto: lang si existe, si no el nombre legible. */
    private static String simpleLabel(CommandSender viewer, String rawTarget) {
        String key = "containerlog.target." + targetKey(rawTarget);
        String label = Lang.raw(viewer, key);
        return key.equals(label) ? prettify(rawTarget) : label;
    }

    /** Verbo de la accion (abrio/coloco/rompio/uso) en el idioma del viewer. */
    public static String actionLabel(CommandSender viewer, String action) {
        String key = "containerlog.action-" + action.toLowerCase(Locale.ROOT);
        String label = Lang.raw(viewer, key);
        return key.equals(label) ? action.toLowerCase(Locale.ROOT) : label;
    }

    // OAK_DOOR -> "oak door": fallback legible cuando el lang no cubre el material.
    private static String prettify(String raw) {
        return raw.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /**
     * Linea completa y localizada de un registro para {@code viewer}, con los botones
     * <b>[Ir]</b> (teletransporte a las coordenadas exactas) y <b>[TP]</b> (al jugador) si
     * el viewer tiene {@link #TELEPORT_PERMISSION}. {@code entryKey} decide la plantilla:
     * la del comando ({@code containerlog.entry}) o la del spy en vivo ({@code spy.container}).
     */
    public static net.kyori.adventure.text.Component render(CommandSender viewer,
                                                            ContainerRecord record,
                                                            String entryKey) {
        boolean buttons = viewer instanceof org.bukkit.entity.Player p
                && (p.isOp() || p.hasPermission(TELEPORT_PERMISSION));
        String safeName = dev.chatalyst.util.MM.escape(record.playerName());
        String safeWorld = dev.chatalyst.util.MM.escape(record.world());
        String go = buttons ? Lang.raw(viewer, "containerlog.go-button",
                "world", safeWorld, "x", record.x(), "y", record.y(), "z", record.z()) : "";
        String tp = buttons ? Lang.raw(viewer, "containerlog.tp-button",
                "player", safeName) : "";
        // Repeticiones agrupadas: "+19" solo aparece cuando de verdad se ocultaron lineas.
        String repeat = record.repeats() > 0
                ? Lang.raw(viewer, "containerlog.repeats", "count", record.repeats()) : "";
        // [Ver]: solo en aperturas de algo que TIENE contenido inspeccionable.
        boolean canPeek = viewer instanceof org.bukkit.entity.Player peeker
                && (peeker.isOp() || peeker.hasPermission(PEEK_PERMISSION))
                && isPeekable(record);
        String view = canPeek ? Lang.raw(viewer, "containerlog.view-button",
                "player", safeName, "world", safeWorld,
                "x", record.x(), "y", record.y(), "z", record.z()) : "";
        return Lang.get(viewer, entryKey,
                "time", shortTime(record.time()),
                "player", safeName,
                "action", actionLabel(viewer, record.action()),
                "target", targetLabel(viewer, record.target()),
                "world", safeWorld,
                "x", record.x(), "y", record.y(), "z", record.z(),
                "go", go, "tp", tp, "repeat", repeat, "view", view);
    }

    /**
     * True si la linea corresponde a ABRIR algo con contenido inspeccionable. Colocar o
     * romper no deja nada que ver, los mecanismos no tienen inventario, y los crafteos y
     * tratos apuntan a un item, no a un bloque del mundo.
     */
    private static boolean isPeekable(ContainerRecord record) {
        if (!"OPEN".equals(record.action())) return false;
        if (record.target().equals("DOUBLE_CHEST")) return true;
        try {
            return ContainerSpyListener.isTrackedContainer(
                    org.bukkit.Material.valueOf(record.target()));
        } catch (IllegalArgumentException notABlock) {
            // Entidades (VILLAGER, CHEST_MINECART...) y objetivos codificados de trato.
            return false;
        }
    }

    /** Hora local del registro con formato HH:mm:ss (para las lineas del comando). */
    public static String shortTime(long epochMillis) {
        LocalDateTime time = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
        return String.format(Locale.ROOT, "%02d:%02d:%02d",
                time.getHour(), time.getMinute(), time.getSecond());
    }
}
