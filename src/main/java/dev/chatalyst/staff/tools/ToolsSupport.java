package dev.chatalyst.staff.tools;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.nick.NickManager;
import dev.chatalyst.staff.StaffAccess;
import dev.chatalyst.staff.StaffModerationModule;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Lo comun a los comandos de staff de este paquete: permisos, resolver jugadores conectados
 * o no, trabajar fuera del hilo del servidor y volver a el, y la barra de paginas.
 */
final class ToolsSupport {

    private ToolsSupport() {
    }

    static boolean available(StaffModerationModule module, CommandSender sender) {
        if (module.enabled()) return true;
        sender.sendMessage(Lang.get(sender, "staff-moderation.disabled"));
        return false;
    }

    static boolean require(CommandSender sender, String permission) {
        if (StaffAccess.has(sender, permission)) return true;
        sender.sendMessage(Lang.get(sender, "common.no-permission"));
        return false;
    }

    static void unknown(CommandSender sender, String input) {
        sender.sendMessage(Lang.get(sender, "common.unknown-player", "player", MM.escape(input)));
    }

    /**
     * Un jugador por nombre, ESTE o no conectado: apodo de un conectado, cache del servidor o
     * el indice de nombres de Chatalyst (que recuerda a quien entro hace meses). null si el
     * servidor no lo conoce de nada: no se inventan perfiles offline nuevos.
     */
    static OfflinePlayer resolve(String input) {
        if (input == null || input.isBlank()) return null;
        OfflinePlayer found = NickManager.resolveOffline(input);
        if (found == null) return null;
        if (found.isOnline() || found.hasPlayedBefore()) return found;
        return null;
    }

    /** El nombre que se enseña de un UUID: el de ahora si el servidor lo sabe. */
    static String nameOf(UUID id, String fallback) {
        if (id == null) return fallback;
        Player online = Bukkit.getPlayer(id);
        if (online != null) return online.getName();
        String cached = Bukkit.getOfflinePlayer(id).getName();
        return cached == null || cached.isBlank() ? fallback : cached;
    }

    /** Trabajo de almacen fuera del hilo del servidor (en red el Store es MySQL). */
    static void async(StaffModerationModule module, Runnable work) {
        module.plugin().getServer().getAsyncScheduler().runNow(module.plugin(), task -> {
            try {
                work.run();
            } catch (RuntimeException failure) {
                module.plugin().getLogger().warning("Staff tool failed: " + failure);
            }
        });
    }

    /** De vuelta al hilo del que pidio el comando (su region, o el global para la consola). */
    static void reply(StaffModerationModule module, CommandSender sender,
                      Consumer<CommandSender> action) {
        if (sender instanceof Player player) {
            FoliaTasks.entity(module.plugin(), player, action::accept);
        } else {
            FoliaTasks.global(module.plugin(), () -> action.accept(sender));
        }
    }

    static String join(String[] args, int start) {
        if (start >= args.length) return "";
        return String.join(" ", Arrays.copyOfRange(args, start, args.length));
    }

    /** Palabras que confirman una accion destructiva, en los dos idiomas. */
    static boolean isConfirm(String word) {
        if (word == null) return false;
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "confirm", "confirmar", "-confirm", "--confirm" -> true;
            default -> false;
        };
    }

    /**
     * "« Anterior · Pagina 2/5 · Siguiente »" con flechas clicables que ejecutan
     * {@code command} con el numero de pagina. Sin flecha donde no hay pagina a la que ir.
     */
    static Component pager(CommandSender viewer, String command, int page, int pages) {
        Component bar = Component.empty();
        if (page > 1) {
            bar = bar.append(Lang.get(viewer, "staff-tools.page-previous")
                    .clickEvent(ClickEvent.runCommand(command + " " + (page - 1)))
                    .hoverEvent(HoverEvent.showText(Lang.get(viewer, "staff-tools.page-hover",
                            "page", page - 1))))
                    .append(Component.space());
        }
        bar = bar.append(Lang.get(viewer, "staff-tools.page-info", "page", page,
                "pages", pages));
        if (page < pages) {
            bar = bar.append(Component.space())
                    .append(Lang.get(viewer, "staff-tools.page-next")
                            .clickEvent(ClickEvent.runCommand(command + " " + (page + 1)))
                            .hoverEvent(HoverEvent.showText(Lang.get(viewer,
                                    "staff-tools.page-hover", "page", page + 1))));
        }
        return bar;
    }

    /** Nombre localizado de un tipo de castigo del historial. */
    static String typeLabel(CommandSender viewer, String type) {
        String key = "staff-moderation.history-type-" + type;
        String label = Lang.raw(viewer, key);
        if (!label.equals(key)) return label;
        String own = "staff-tools.history-type-" + type;
        String ownLabel = Lang.raw(viewer, own);
        return ownLabel.equals(own) ? type : ownLabel;
    }

    /** Lista de nombres para autocompletar: lo mismo que ve el staff en el tab. */
    static List<String> prefix(String input, List<String> values) {
        String lower = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
                .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    /**
     * Mismo criterio que el resto del staff: sin los vanish que el espectador no ve, y un
     * anonimo por su alias salvo para quien tiene /realname (el tab era la forma mas rapida
     * de desenmascararlo).
     */
    static List<String> onlineNames(CommandSender viewer) {
        boolean realNames = StaffAccess.has(viewer, "chatalyst.command.realname");
        List<String> names = new java.util.ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (dev.chatalyst.util.Vanish.isVanished(online)
                    && !StaffAccess.canSeeVanished(viewer)) continue;
            if (!realNames && dev.chatalyst.hook.Placeholders.isAnonymous(online.getUniqueId())) {
                String alias = NickManager.suggestedName(online);
                if (alias != null && !alias.isBlank()) names.add(alias);
                continue;
            }
            names.add(online.getName());
        }
        return names;
    }
}
