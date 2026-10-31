package dev.chatalyst.anonymous;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /anonymous [on|off] — modo incognito para streamers: apodo aleatorio ilegible, sin
 * rango ni tags y skin anonima. Permiso {@code chatalyst.command.anonymous} (op por
 * defecto; concedelo al rango streamer con LuckPerms).
 *
 * <p>Slots guardados (subcomandos save/set/list/delete): con
 * {@code chatalyst.anonymous.save} un jugador puede GUARDAR la cara que lleva puesta y
 * volver a ella cuando quiera. Cuantos slots caben lo decide
 * {@code chatalyst.anonymous.slots.<n>} (p. ej. 3 para el MVP++), con tope de seguridad
 * {@code Anonymous.Max_Saved_Slots}.
 */
public final class AnonymousCommand implements CommandExecutor, TabCompleter {

    private final AnonymousManager manager;

    public AnonymousCommand(AnonymousManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return true;
        }
        if (args.length >= 1) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "save", "guardar" -> {
                    return save(player, args);
                }
                case "set", "use", "usar", "load", "cargar" -> {
                    return use(player, args);
                }
                case "list", "slots", "lista" -> {
                    return list(player);
                }
                case "delete", "del", "remove", "borrar", "eliminar" -> {
                    return delete(player, args);
                }
                default -> {
                    // on/off y el fallback caen abajo (respeta el bloqueo con la feature apagada).
                }
            }
        }
        // Con el modo apagado en config, quien SIGUE anonimo (lo apagaron en caliente)
        // puede salir; lo que se bloquea es entrar.
        if (!manager.isEnabled() && !manager.isAnonymous(player.getUniqueId())) {
            player.sendMessage(Lang.get(player, "anonymous.disabled"));
            return true;
        }
        if (args.length >= 1) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "on", "si" -> {
                    if (handledPendingWait(player, true)) return true;
                    if (manager.isAnonymous(player.getUniqueId())) {
                        player.sendMessage(Lang.get(player, "anonymous.already-on"));
                    } else if (!blockedByCooldown(player)) {
                        manager.activate(player);
                    }
                    return true;
                }
                case "off", "no" -> {
                    if (handledPendingWait(player, false)) return true;
                    if (!manager.isAnonymous(player.getUniqueId())) {
                        player.sendMessage(Lang.get(player, "anonymous.already-off"));
                    } else {
                        manager.deactivate(player);
                    }
                    return true;
                }
                default -> {
                    player.sendMessage(Lang.get(player, "anonymous.usage", "label", label));
                    return true;
                }
            }
        }
        // Sin argumentos es un toggle: una espera en curso se DESHACE, sea la que sea. Y eso
        // no lo puede bloquear el cooldown — arrepentirse no es cambiar de cara.
        if (manager.cancelIfAwaiting(player)) return true;
        // Apagar siempre se permite; encender respeta el cooldown.
        if (!manager.isAnonymous(player.getUniqueId()) && blockedByCooldown(player)) {
            return true;
        }
        manager.toggle(player);
        return true;
    }

    /**
     * Resuelve el comando cuando el jugador ya tiene un cambio ESPERANDO a que nadie mire.
     *
     * <p>La espera puede durar minutos, y en ese rato el jugador vuelve a escribir. Lo que
     * significa depende de la direccion: pedir lo CONTRARIO de lo que se esta esperando es
     * arrepentirse, y hay que cancelar — antes {@code /anon on} durante una espera de apagado
     * respondia "ya estabas anonimo" y te desnudaba igual un minuto despues. Pedir lo MISMO
     * es impaciencia: se repite el aviso, porque volver a lanzarlo duplicaria la espera.
     *
     * <p>La direccion se deduce del estado: mientras la espera vive el jugador sigue marcado
     * como estaba, asi que un anonimo esta esperando a dejar de serlo y al reves.
     *
     * @param wantsAnonymous lo que pide este comando ({@code on} true, {@code off} false)
     * @return true si el comando queda resuelto aqui
     */
    private boolean handledPendingWait(Player player, boolean wantsAnonymous) {
        if (!manager.awaitingPrivacy(player.getUniqueId())) return false;
        boolean anonymous = manager.isAnonymous(player.getUniqueId());
        if (AnonymousManager.waitLeadsTo(anonymous, wantsAnonymous)) {
            // Ya va justo a donde pide: se le recuerda, sin abrir una segunda espera.
            player.sendMessage(Lang.get(player, AnonymousManager.waitingKey(anonymous)));
        } else {
            manager.cancelIfAwaiting(player);
        }
        return true;
    }

    /** True (y avisa) si el jugador esta en cooldown para cambiar de identidad. */
    private boolean blockedByCooldown(Player player) {
        long remaining = manager.switchCooldownRemainingMs(player);
        if (remaining <= 0) return false;
        player.sendMessage(Lang.get(player, "anonymous.switch-cooldown",
                "seconds", String.valueOf((remaining + 999) / 1000)));
        return true;
    }

    // ---- Slots guardados --------------------------------------------------------------

    /** True (y avisa) si el jugador NO puede usar slots. */
    private boolean noSlotAccess(Player player) {
        if (player.hasPermission(AnonymousManager.SLOTS_PERMISSION)) return false;
        player.sendMessage(Lang.get(player, "anonymous.slots-no-permission"));
        return true;
    }

    private boolean save(Player player, String[] args) {
        if (noSlotAccess(player)) return true;
        int limit = manager.savedSlotLimit(player);
        if (limit <= 0) {
            player.sendMessage(Lang.get(player, "anonymous.slots-none-allowed"));
            return true;
        }
        if (!manager.isAnonymous(player.getUniqueId())) {
            player.sendMessage(Lang.get(player, "anonymous.save-not-anon"));
            return true;
        }
        // Esa cara ya la tiene reservada otro jugador: no se guarda dos veces.
        if (manager.currentDisguiseTakenByOther(player)) {
            player.sendMessage(Lang.get(player, "anonymous.save-taken"));
            return true;
        }
        // Slot explicito o el primero libre.
        int slot;
        if (args.length >= 2) {
            slot = parseSlot(args[1]);
            if (slot < 1 || slot > limit) {
                player.sendMessage(Lang.get(player, "anonymous.slot-out-of-range",
                        "max", String.valueOf(limit)));
                return true;
            }
        } else {
            slot = manager.slots().firstFreeSlot(player.getUniqueId(), limit);
            if (slot < 0) {
                player.sendMessage(Lang.get(player, "anonymous.slots-full",
                        "max", String.valueOf(limit)));
                return true;
            }
        }
        if (manager.saveCurrent(player, slot)) {
            player.sendMessage(Lang.get(player, "anonymous.saved", "slot", String.valueOf(slot)));
        } else {
            player.sendMessage(Lang.get(player, "anonymous.error"));
        }
        return true;
    }

    private boolean use(Player player, String[] args) {
        if (noSlotAccess(player)) return true;
        if (!manager.isEnabled() && !manager.isAnonymous(player.getUniqueId())) {
            player.sendMessage(Lang.get(player, "anonymous.disabled"));
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(Lang.get(player, "anonymous.set-usage"));
            return true;
        }
        int slot = parseSlot(args[1]);
        if (slot < 1) {
            player.sendMessage(Lang.get(player, "anonymous.set-usage"));
            return true;
        }
        if (blockedByCooldown(player)) return true;
        // Elegir una cara es querer seguir anonimo: si habia una desactivacion esperando a
        // que nadie mire, muere aqui — si no, te arrancaria el personaje recien elegido.
        if (manager.isAnonymous(player.getUniqueId())) {
            manager.cancelPrivacyWait(player.getUniqueId());
        }
        switch (manager.useSaved(player, slot)) {
            case NOT_FOUND -> player.sendMessage(Lang.get(player, "anonymous.slot-empty",
                    "slot", String.valueOf(slot)));
            case IN_USE -> player.sendMessage(Lang.get(player, "anonymous.slot-in-use"));
            case ACTIVATED, SWITCHED -> {
                player.sendMessage(Lang.get(player, "anonymous.slot-loaded",
                        "slot", String.valueOf(slot)));
                // Se dice explicitamente porque es lo que diferencia un slot de un alias
                // sorteado: esta cara sobrevive a la reconexion.
                player.sendMessage(Lang.get(player, "anonymous.slot-pinned"));
            }
        }
        return true;
    }

    private boolean list(Player player) {
        if (noSlotAccess(player)) return true;
        List<AnonymousSlots.Saved> saved = manager.slots().list(player.getUniqueId());
        int limit = manager.savedSlotLimit(player);
        if (saved.isEmpty()) {
            player.sendMessage(Lang.get(player, "anonymous.list-empty",
                    "max", String.valueOf(limit)));
            return true;
        }
        player.sendMessage(Lang.get(player, "anonymous.list-header",
                "count", String.valueOf(saved.size()), "max", String.valueOf(limit)));
        for (AnonymousSlots.Saved s : saved) {
            player.sendMessage(Lang.get(player, "anonymous.list-entry",
                    "slot", String.valueOf(s.slot()), "name", MM.escape(s.name())));
        }
        return true;
    }

    private boolean delete(Player player, String[] args) {
        if (noSlotAccess(player)) return true;
        if (args.length < 2) {
            player.sendMessage(Lang.get(player, "anonymous.delete-usage"));
            return true;
        }
        int slot = parseSlot(args[1]);
        if (slot < 1 || manager.slots().get(player.getUniqueId(), slot) == null) {
            player.sendMessage(Lang.get(player, "anonymous.slot-empty", "slot", String.valueOf(slot)));
            return true;
        }
        if (manager.slots().delete(player.getUniqueId(), slot)) {
            player.sendMessage(Lang.get(player, "anonymous.deleted", "slot", String.valueOf(slot)));
        } else {
            player.sendMessage(Lang.get(player, "anonymous.error"));
        }
        return true;
    }

    private static int parseSlot(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return List.of();
        String pre = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String option : List.of("on", "off")) {
                if (option.startsWith(pre)) out.add(option);
            }
            if (player.hasPermission(AnonymousManager.SLOTS_PERMISSION)) {
                for (String option : List.of("save", "set", "list", "delete")) {
                    if (option.startsWith(pre)) out.add(option);
                }
            }
            return out;
        }
        if (args.length == 2 && player.hasPermission(AnonymousManager.SLOTS_PERMISSION)) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            // set/delete: sugerir los slots ocupados; save: los libres hasta el limite.
            if (sub.equals("set") || sub.equals("use") || sub.equals("usar")
                    || sub.equals("load") || sub.equals("cargar")
                    || sub.equals("delete") || sub.equals("del") || sub.equals("borrar")) {
                List<String> out = new ArrayList<>();
                for (AnonymousSlots.Saved s : manager.slots().list(player.getUniqueId())) {
                    if (String.valueOf(s.slot()).startsWith(pre)) out.add(String.valueOf(s.slot()));
                }
                return out;
            }
            if (sub.equals("save") || sub.equals("guardar")) {
                List<String> out = new ArrayList<>();
                int limit = manager.savedSlotLimit(player);
                for (int i = 1; i <= limit; i++) {
                    if (String.valueOf(i).startsWith(pre)) out.add(String.valueOf(i));
                }
                return out;
            }
        }
        return List.of();
    }
}
