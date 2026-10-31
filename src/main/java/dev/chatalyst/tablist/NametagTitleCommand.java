package dev.chatalyst.tablist;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.rules.RuleContext;
import dev.chatalyst.rules.RuleEngine;
import dev.chatalyst.rules.RuleResult;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * /nametagtitle title <texto|off>              Tu titulo flotante (encima del tag).
 * /nametagtitle iconleft|iconright <texto|off> Un icono a un lado del tag.
 * /nametagtitle &lt;campo&gt; &lt;jugador&gt; &lt;texto|off&gt;  (staff) El de otro jugador.
 *
 * <p>Necesita Nametag.Fancy activo para verse. Con chatalyst.nametagtitle.color el texto
 * admite MiniMessage/legacy e iconos {@code :de_itemsadder:}; sin el, se limpia a texto plano.
 * Pasa por las mismas defensas que /nick y /tag: sanitizado, reglas y filtro de insultos, y se
 * bloquea click/hover (el titulo lo ven los demas sobre tu cabeza).
 */
public final class NametagTitleCommand implements CommandExecutor, TabCompleter {

    public static final String PERMISSION = "chatalyst.nametagtitle";
    public static final String OTHERS_PERMISSION = "chatalyst.nametagtitle.others";
    public static final String COLOR_PERMISSION = "chatalyst.nametagtitle.color";

    private final NametagTitleManager titles;
    private final int maxLength;
    private final RuleEngine rules;
    private final dev.chatalyst.swear.SwearFilter swear;

    public NametagTitleCommand(NametagTitleManager titles, int maxLength, RuleEngine rules,
                               dev.chatalyst.swear.SwearFilter swear) {
        this.titles = titles;
        this.maxLength = Math.max(1, maxLength);
        this.rules = rules;
        this.swear = swear;
    }

    private static int fieldOf(String arg) {
        return switch (arg.toLowerCase(Locale.ROOT)) {
            case "title", "titulo" -> NametagTitleManager.TITLE;
            case "iconleft", "left", "izquierda", "izq" -> NametagTitleManager.ICON_LEFT;
            case "iconright", "right", "derecha", "der" -> NametagTitleManager.ICON_RIGHT;
            default -> -1;
        };
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        int field = args.length >= 1 ? fieldOf(args[0]) : -1;
        if (field < 0 || args.length < 2) {
            sender.sendMessage(Lang.get(sender, "nametag-title.usage"));
            return true;
        }

        // Forma de staff: /nametagtitle <campo> <jugador> <texto|off>.
        if (args.length >= 3 && sender.hasPermission(OTHERS_PERMISSION)) {
            Player target = dev.chatalyst.nick.NickManager.online(args[1]);
            if (target != null) {
                String value = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                applyOther(sender, target, field, value);
                return true;
            }
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return true;
        }
        if (!p.hasPermission(PERMISSION)) {
            p.sendMessage(Lang.get(p, "common.no-permission"));
            return true;
        }
        String value = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        ApplyResult result = apply(p, field, value, p.hasPermission(COLOR_PERMISSION));
        respond(p, p.getName(), field, result, false);
        return true;
    }

    /**
     * Editor por GUI: aplica un campo al PROPIO jugador reusando las mismas defensas del comando
     * (sanitizado, permiso de color, reglas, filtro de insultos). No pasa por la forma de
     * staff-a-otro, asi que un titulo cuyo primer token sea un nombre online no se desvia.
     */
    public void applyFromEditor(Player player, int field, String rawValue) {
        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        ApplyResult result = apply(player, field, rawValue, player.hasPermission(COLOR_PERMISSION));
        respond(player, player.getName(), field, result, false);
    }

    private ApplyResult apply(Player target, int field, String rawValue, boolean allowColor) {
        if (rawValue.equalsIgnoreCase("off") || rawValue.equalsIgnoreCase("reset")) {
            return new ApplyResult(titles.set(target.getUniqueId(), field, "")
                    ? ApplyStatus.REMOVED : ApplyStatus.STORAGE_FAILED, "");
        }
        // Mismas defensas que /nick y /tag: sin invisibles, colores/iconos solo con permiso.
        // La politica de color es literalmente la del apodo, asi que se reusa NickText en vez
        // de mantener dos copias que puedan separarse — y el titulo hereda gratis su forma
        // leida y su clave de choque.
        dev.chatalyst.nick.NickText text = dev.chatalyst.nick.NickText.of(
                dev.chatalyst.util.Sanitize.stripInvisible(rawValue), allowColor);
        String value = text.stored();
        String plain = text.plain();
        if (text.longerThan(maxLength)) {
            return new ApplyResult(ApplyStatus.TOO_LONG, plain);
        }
        // Solo etiquetas ("<bold>"): se guardaria un titulo que no se lee, dejando un renglon
        // vacio flotando sobre la cabeza. Quitarlo se hace con "off".
        if (plain.isBlank()) {
            return new ApplyResult(ApplyStatus.BLOCKED, plain);
        }
        if (rules != null && rules.appliesToNicks()) {
            RuleResult res = rules.check(target, plain, RuleContext.NICK);
            if (res.cancelled) {
                return new ApplyResult(ApplyStatus.BLOCKED, plain);
            }
        }
        if (swear != null && swear.isEnabled() && swear.apply(plain).matched()) {
            return new ApplyResult(ApplyStatus.BLOCKED, plain);
        }
        // Un titulo se pinta junto al nombre, asi que sirve igual de bien para hacerse pasar
        // por otro: hasta ahora era la unica via de identidad SIN chequeo de suplantacion.
        if (!target.hasPermission("chatalyst.nick.impersonate")
                && impersonates(target, text)) {
            return new ApplyResult(ApplyStatus.BLOCKED, plain);
        }
        return new ApplyResult(titles.set(target.getUniqueId(), field, value)
                ? ApplyStatus.SET : ApplyStatus.STORAGE_FAILED, plain);
    }

    /** Mismo criterio que /nick: nombre real de otro, apodo de otro o nombre reservado. */
    private boolean impersonates(Player target, dev.chatalyst.nick.NickText text) {
        dev.chatalyst.ChatalystPlugin plugin = dev.chatalyst.ChatalystPlugin.get();
        for (String candidate : java.util.List.of(text.plain(), text.spoofKey())) {
            if (candidate.isEmpty()) continue;
            Player online = org.bukkit.Bukkit.getPlayerExact(candidate);
            if (online != null && !online.getUniqueId().equals(target.getUniqueId())) return true;
            org.bukkit.OfflinePlayer known = org.bukkit.Bukkit.getOfflinePlayerIfCached(candidate);
            if (known != null && !known.getUniqueId().equals(target.getUniqueId())) return true;
            if (plugin != null && plugin.getNickManager() != null) {
                java.util.UUID sameName = plugin.getNickManager().findByDisplay(candidate);
                if (sameName != null && !sameName.equals(target.getUniqueId())) return true;
            }
            // Igual que /nick: el usercache caduca y el indice propio no.
            java.util.UUID remembered = dev.chatalyst.nick.OfflineNameIndex.find(candidate);
            if (remembered != null && !remembered.equals(target.getUniqueId())) return true;
        }
        // blocks y no covers: un nombre reservado lo es para todos MENOS para su dueño.
        return plugin != null && dev.chatalyst.nick.ProtectedNames.blocks(
                plugin.getConfig().getStringList("Nick.Protected_Names"),
                text.spoofKey(), target.getName());
    }

    private void applyOther(CommandSender actor, Player target, int field, String rawValue) {
        dev.chatalyst.ChatalystPlugin plugin = dev.chatalyst.ChatalystPlugin.get();
        if (plugin == null) {
            actor.sendMessage(storageError(actor));
            return;
        }
        String actorName = actor.getName();
        Runnable offline = () -> deliver(plugin, actor,
                current -> current.sendMessage(Lang.get(current, "common.player-offline")));
        FoliaTasks.entity(plugin, target, selected -> {
            String targetName = selected.getName();
            ApplyResult result = apply(selected, field, rawValue, true);
            deliver(plugin, actor, current -> {
                respond(current, targetName, field, result, true);
                if (result.status() == ApplyStatus.SET
                        && plugin.getStaffSecurityListener() != null
                        && !actorName.equalsIgnoreCase(targetName)) {
                    plugin.getStaffSecurityListener().audit(current, "nametagtitle",
                            targetName + " " + fieldName(field) + " -> " + result.plain());
                }
            });
        }, offline);
    }

    private void respond(CommandSender actor, String targetName, int field,
                         ApplyResult result, boolean other) {
        switch (result.status()) {
            case SET -> actor.sendMessage(other
                    ? Lang.get(actor, "nametag-title.updated-other", "player", targetName)
                    : Lang.get(actor, "nametag-title.updated"));
            case REMOVED -> actor.sendMessage(other
                    ? Lang.get(actor, "nametag-title.removed-other", "player", targetName)
                    : Lang.get(actor, "nametag-title.removed"));
            case TOO_LONG -> actor.sendMessage(
                    Lang.get(actor, "nametag-title.too-long", "max", maxLength));
            case BLOCKED -> actor.sendMessage(Lang.get(actor, "nametag-title.blocked"));
            case STORAGE_FAILED -> actor.sendMessage(storageError(actor));
        }
    }

    private static String fieldName(int field) {
        return switch (field) {
            case NametagTitleManager.ICON_LEFT -> "iconleft";
            case NametagTitleManager.ICON_RIGHT -> "iconright";
            default -> "title";
        };
    }

    private static void deliver(dev.chatalyst.ChatalystPlugin plugin, CommandSender actor,
                                java.util.function.Consumer<CommandSender> action) {
        if (actor instanceof Player player) {
            FoliaTasks.entity(plugin, player, action::accept);
        } else {
            FoliaTasks.global(plugin, () -> action.accept(actor));
        }
    }

    private static net.kyori.adventure.text.Component storageError(CommandSender sender) {
        return Lang.get(sender, "plugin.storage-unavailable");
    }

    private enum ApplyStatus { SET, REMOVED, TOO_LONG, BLOCKED, STORAGE_FAILED }

    private record ApplyResult(ApplyStatus status, String plain) {
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String pre = args[0].toLowerCase(Locale.ROOT);
            for (String s : new String[]{"title", "iconleft", "iconright"}) {
                if (s.startsWith(pre)) out.add(s);
            }
        } else if (args.length == 2) {
            if ("off".startsWith(args[1].toLowerCase(Locale.ROOT))) out.add("off");
            if (sender.hasPermission(OTHERS_PERMISSION)) {
                String pre = args[1].toLowerCase(Locale.ROOT);
                dev.chatalyst.ChatalystPlugin plugin = dev.chatalyst.ChatalystPlugin.get();
                boolean realNames = dev.chatalyst.staff.StaffAccess.has(sender,
                        "chatalyst.command.realname");
                for (Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
                    if (dev.chatalyst.util.Vanish.isVanished(online)
                            && !dev.chatalyst.staff.StaffAccess.canSeeVanished(sender)) continue;
                    // Ofrecer el nombre de cuenta de un anonimo es desenmascararlo.
                    if (!realNames && plugin != null && plugin.getAnonymousManager() != null
                            && plugin.getAnonymousManager().isAnonymous(online.getUniqueId())) {
                        continue;
                    }
                    if (online.getName().toLowerCase(Locale.ROOT).startsWith(pre)) {
                        out.add(online.getName());
                    }
                }
            }
        }
        return out;
    }
}
