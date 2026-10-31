package dev.chatalyst.staff.itemaudit;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Las piezas de texto que comparten los avisos y /itemaudit, cada una en el idioma de quien
 * la lee. Todo lo que viene del jugador (nombres, nombres de yunque, comandos) sale escapado.
 */
final class AuditText {

    private static final int MAX_COMMAND = 120;
    /** Detalle de un comando cuyo destinatario se miro y no recibio nada. */
    static final String NOTHING_ARRIVED = "nothing-arrived";

    private AuditText() {
    }

    /**
     * El objeto por su nombre: a un jugador se le manda el nombre traducible del juego
     * (lo lee en el idioma de SU cliente); a la consola y a Discord, un nombre legible.
     */
    static String item(CommandSender viewer, String key) {
        if (viewer instanceof Player) {
            Material material = key == null || key.isEmpty() ? null : Material.matchMaterial(key);
            if (material != null) return "<lang:" + material.translationKey() + ">";
        }
        return MM.escape(AuditFormat.prettyItem(key));
    }

    /** "Admin Fulano", o el nombre solo; la consola en el idioma de quien lee. */
    static String actor(CommandSender viewer, AuditEntry entry) {
        if (ItemAuditManager.CONSOLE.equals(entry.actorName()) && entry.actorId() == null) {
            return Lang.raw(viewer, "itemaudit.console");
        }
        String name = entry.actorRank().isEmpty()
                ? entry.actorName() : entry.actorRank() + " " + entry.actorName();
        return MM.escape(name);
    }

    static String target(AuditEntry entry) {
        return MM.escape(entry.targetName());
    }

    static String command(AuditEntry entry) {
        String line = entry.source();
        if (line.length() > MAX_COMMAND) line = line.substring(0, MAX_COMMAND) + "…";
        return MM.escape(line);
    }

    static String mode(CommandSender viewer, String mode) {
        return label(viewer, "itemaudit.mode-", mode);
    }

    static String cause(CommandSender viewer, String cause) {
        return label(viewer, "itemaudit.cause-", cause);
    }

    /** Por donde llego un traspaso: del suelo (y quien lo tiro), un contenedor, un soporte. */
    static String via(CommandSender viewer, String source) {
        if (source.startsWith("pickup:")) {
            return Lang.raw(viewer, "itemaudit.via-pickup-from",
                    "player", MM.escape(source.substring("pickup:".length())));
        }
        if (source.startsWith("container:")) {
            return Lang.raw(viewer, "itemaudit.via-container",
                    "container", MM.escape(AuditFormat.prettyItem(
                            source.substring("container:".length()).toLowerCase(Locale.ROOT))));
        }
        return switch (source) {
            case "pickup" -> Lang.raw(viewer, "itemaudit.via-pickup");
            case "armor_stand" -> Lang.raw(viewer, "itemaudit.via-armor-stand");
            default -> MM.escape(source);
        };
    }

    /** "SURVIVAL>CREATIVE" -&gt; los dos modos. */
    static String[] modes(AuditEntry entry) {
        String[] parts = entry.detail().split(">", 2);
        return parts.length == 2 ? parts : new String[]{"?", entry.detail()};
    }

    /** "64x Diamante, 32x Esmeralda +3 mas", para el resumen de una rafaga. */
    static String summary(CommandSender viewer, AuditFormat.Summary summary) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Long> top : summary.top()) {
            parts.add(Lang.raw(viewer, "itemaudit.summary-entry",
                    "amount", top.getValue(), "item", item(viewer, top.getKey())));
        }
        String text = String.join("<#C0B3D9>, ", parts);
        if (summary.others() > 0) {
            text += Lang.raw(viewer, "itemaudit.summary-more", "count", summary.others());
        }
        return text;
    }

    /** Las lineas del hover de una fila: objeto, detalle, sitio y fecha. */
    static List<String> hover(CommandSender viewer, AuditEntry entry, ZoneId zone) {
        List<String> lines = new ArrayList<>();
        if (!entry.item().isEmpty()) {
            lines.add(Lang.raw(viewer, "itemaudit.hover-item", "amount", entry.amount(),
                    "item", item(viewer, entry.item()), "key", MM.escape(entry.item())));
        }
        if (NOTHING_ARRIVED.equals(entry.detail())) {
            lines.add(Lang.raw(viewer, "itemaudit.hover-nothing-arrived"));
        } else if (!entry.detail().isEmpty() && !entry.type().isGamemode()) {
            lines.add(Lang.raw(viewer, "itemaudit.hover-detail", "detail", MM.escape(entry.detail())));
        }
        if (entry.type() == AuditType.COMMAND || entry.type() == AuditType.GIVE) {
            lines.add(Lang.raw(viewer, "itemaudit.hover-command", "command", command(entry)));
        }
        if (!entry.world().isEmpty()) {
            lines.add(Lang.raw(viewer, "itemaudit.hover-where", "world", MM.escape(entry.world()),
                    "x", entry.x(), "y", entry.y(), "z", entry.z()));
        }
        lines.add(Lang.raw(viewer, "itemaudit.hover-when",
                "date", AuditFormat.date(entry.time(), zone), "id", entry.id()));
        return lines;
    }

    private static String label(CommandSender viewer, String prefix, String value) {
        String key = prefix + value.toLowerCase(Locale.ROOT);
        String text = Lang.raw(viewer, key);
        // Una causa nueva de Paper que el lang aun no conoce se ensena tal cual, no la clave.
        return key.equals(text) ? MM.escape(value.toLowerCase(Locale.ROOT).replace('_', ' ')) : text;
    }
}
