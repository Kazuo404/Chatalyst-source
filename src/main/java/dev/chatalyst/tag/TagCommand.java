package dev.chatalyst.tag;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.rules.RuleContext;
import dev.chatalyst.rules.RuleEngine;
import dev.chatalyst.rules.RuleResult;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.FoliaTasks;
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
 * {@code /tag} abre el catalogo; {@code /tag <id>|off} equipa o quita un titulo y
 * {@code /tag admin} abre su editor. Las formas historicas
 * {@code /tag prefix|suffix [jugador] <texto|off>} se conservan para no romper servidores
 * existentes.
 *
 * <p>El texto historico pasa las mismas defensas que {@code /nick}: sanitizado, permisos de
 * color, reglas y filtro de insultos.
 */
public final class TagCommand implements CommandExecutor, TabCompleter {

    private final dev.chatalyst.ChatalystPlugin plugin;
    private final TagManager tags;
    private final int maxLength;
    private final RuleEngine rules;
    private final dev.chatalyst.swear.SwearFilter swear;

    public TagCommand(dev.chatalyst.ChatalystPlugin plugin, TagManager tags,
                      int maxLength, RuleEngine rules,
                      dev.chatalyst.swear.SwearFilter swear) {
        this.plugin = plugin;
        this.tags = tags;
        this.maxLength = Math.max(1, maxLength);
        this.rules = rules;
        this.swear = swear;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Lang.get(sender, "common.players-only"));
            } else if (!player.hasPermission("chatalyst.tag")) {
                player.sendMessage(Lang.get(player, "common.no-permission"));
            } else {
                dev.chatalyst.gui.Menu.open(player, new TagPlayerMenu(plugin, tags));
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("admin")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Lang.get(sender, "common.players-only"));
            } else if (!player.hasPermission("chatalyst.tag.admin")) {
                player.sendMessage(Lang.get(player, "common.no-permission"));
            } else {
                dev.chatalyst.gui.Menu.open(player, new TagAdminMenu(plugin, tags, 0));
            }
            return true;
        }
        if (args.length == 1 && (args[0].equalsIgnoreCase("off")
                || args[0].equalsIgnoreCase("reset"))) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Lang.get(sender, "common.players-only"));
            } else if (tags.equip(player.getUniqueId(), "",
                    dev.chatalyst.api.title.TitleMutationContext.player(
                            player.getUniqueId(), player.getName()))) {
                player.sendMessage(Lang.get(player, "tag.unequipped"));
                TagDisplay.refresh(plugin, player);
            } else {
                player.sendMessage(storageError(player));
            }
            return true;
        }
        if (args.length == 1 && tags.catalog() != null) {
            TagDefinition definition = tags.catalog().byId(args[0]);
            if (definition != null) {
                equip(sender, definition);
                return true;
            }
        }
        if (args.length < 2 || (!args[0].equalsIgnoreCase("prefix")
                && !args[0].equalsIgnoreCase("suffix"))) {
            sender.sendMessage(Lang.get(sender, "tag.usage"));
            return true;
        }
        boolean isPrefix = args[0].equalsIgnoreCase("prefix");

        // Forma de staff: /tag <tipo> <jugador> <texto|off> (acepta nombre real o apodo).
        if (args.length >= 3 && sender.hasPermission("chatalyst.tag.others")) {
            Player target = dev.chatalyst.nick.NickManager.online(args[1]);
            if (target != null) {
                String value = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
                applyOther(sender, target, isPrefix, value);
                return true;
            }
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return true;
        }
        if (!p.hasPermission("chatalyst.tag")) {
            p.sendMessage(Lang.get(p, "common.no-permission"));
            return true;
        }
        String value = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        ApplyResult result = apply(p, isPrefix, value, p.hasPermission("chatalyst.tag.color"));
        if (result.status() == ApplyStatus.SET || result.status() == ApplyStatus.REMOVED) {
            // Editar el tag heredado lo convierte de nuevo en la seleccion visible.
            result = deselect(result, tags.equip(p.getUniqueId(), "",
                    dev.chatalyst.api.title.TitleMutationContext.player(
                            p.getUniqueId(), p.getName())));
            TagDisplay.refresh(plugin, p);
        }
        respond(p, p.getName(), isPrefix, result, false);
        return true;
    }

    private void equip(CommandSender sender, TagDefinition definition) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return;
        }
        if (!player.hasPermission("chatalyst.tag")) {
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        TagUnlockProcessor.refresh(plugin, tags, player);
        if (!definition.enabled() || !tags.isUnlocked(player.getUniqueId(), definition)) {
            player.sendMessage(Lang.get(player, "tag.locked"));
            return;
        }
        if (!tags.equip(player.getUniqueId(), definition.id(),
                dev.chatalyst.api.title.TitleMutationContext.player(
                        player.getUniqueId(), player.getName()))) {
            player.sendMessage(storageError(player));
            return;
        }
        player.sendMessage(Lang.get(player, "tag.equipped",
                "title", definition.displayName()));
        TagDisplay.refresh(plugin, player);
    }

    private ApplyResult apply(Player target, boolean isPrefix, String rawValue,
                              boolean allowColor) {
        if (rawValue.equalsIgnoreCase("off") || rawValue.equalsIgnoreCase("reset")) {
            return new ApplyResult(tags.set(target.getUniqueId(), isPrefix, "")
                    ? ApplyStatus.REMOVED : ApplyStatus.STORAGE_FAILED, "");
        }
        // Mismas defensas que el /nick: sin invisibles, colores solo con permiso.
        String value = dev.chatalyst.util.Sanitize.stripInvisible(rawValue);
        if (allowColor) {
            // Solo color/decoracion: sin sanitizeInteraction un tag con <click>/<hover> se
            // inyectaria en el chat de todos via {tag_prefix}/{tag_suffix} -> MM.full.
            value = dev.chatalyst.util.MM.sanitizeInteraction(
                    dev.chatalyst.util.Legacy.toMini(value));
        } else {
            value = value.replace('§', ' ').replace("<", "").replace(">", "");
        }
        String plain = MM.strip(value);
        if (plain.length() > maxLength) {
            return new ApplyResult(ApplyStatus.TOO_LONG, plain);
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
        return new ApplyResult(tags.set(target.getUniqueId(), isPrefix, value)
                ? ApplyStatus.SET : ApplyStatus.STORAGE_FAILED, plain);
    }

    private void applyOther(CommandSender actor, Player target, boolean isPrefix,
                            String rawValue) {
        if (plugin == null) {
            actor.sendMessage(storageError(actor));
            return;
        }
        String actorName = actor.getName();
        Runnable offline = () -> deliver(plugin, actor,
                current -> current.sendMessage(Lang.get(current, "common.player-offline")));
        java.util.UUID actorId = actor instanceof Player player ? player.getUniqueId() : null;
        FoliaTasks.entity(plugin, target, selected -> {
            String targetName = selected.getName();
            ApplyResult applied = apply(selected, isPrefix, rawValue, true);
            // Quitar el titulo y refrescar el nametag se hace AQUI, en la region del objetivo.
            // Antes iba en la respuesta al actor: en Folia eso tocaba entidades de otra region
            // (el TextDisplay de FancyNametags) y el refresco quedaba a medias.
            if (applied.status() == ApplyStatus.SET
                    || applied.status() == ApplyStatus.REMOVED) {
                applied = deselect(applied, tags.equip(selected.getUniqueId(), "",
                        dev.chatalyst.api.title.TitleMutationContext.admin(
                                actorId, actorName, "Legacy tag changed")));
                TagDisplay.refresh(plugin, selected);
            }
            ApplyResult result = applied;
            deliver(plugin, actor, current -> {
                respond(current, targetName, isPrefix, result, true);
                if (result.status() == ApplyStatus.SET
                        && plugin.getStaffSecurityListener() != null
                        && !actorName.equalsIgnoreCase(targetName)) {
                    plugin.getStaffSecurityListener().audit(current, "tag",
                            targetName + " " + (isPrefix ? "prefix" : "suffix")
                                    + " -> " + result.plain());
                }
            });
        }, offline);
    }

    /**
     * El tag heredado solo se VE si se quita el titulo de catalogo equipado. Si esa segunda
     * escritura falla, decir "actualizado" era mentir: el jugador seguia viendo el titulo.
     */
    private static ApplyResult deselect(ApplyResult result, boolean unequipped) {
        return unequipped ? result : new ApplyResult(ApplyStatus.STORAGE_FAILED, result.plain());
    }

    private void respond(CommandSender actor, String targetName, boolean isPrefix,
                         ApplyResult result, boolean other) {
        switch (result.status()) {
            case SET -> actor.sendMessage(other
                    ? Lang.get(actor, "tag.updated-other", "player", targetName)
                    : Lang.get(actor, "tag.updated"));
            case REMOVED -> actor.sendMessage(Lang.get(actor,
                    isPrefix ? "tag.prefix-removed" : "tag.suffix-removed"));
            case TOO_LONG -> actor.sendMessage(Lang.get(actor, "tag.too-long", "max", maxLength));
            case BLOCKED -> actor.sendMessage(Lang.get(actor, "tag.blocked"));
            case STORAGE_FAILED -> actor.sendMessage(storageError(actor));
        }
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
            if ("admin".startsWith(pre) && sender.hasPermission("chatalyst.tag.admin")) {
                out.add("admin");
            }
            if ("off".startsWith(pre)) out.add("off");
            if ("prefix".startsWith(pre)) out.add("prefix");
            if ("suffix".startsWith(pre)) out.add("suffix");
            if (tags.catalog() != null) {
                for (TagDefinition definition : tags.catalog().all()) {
                    boolean concealed = sender instanceof Player player
                            && (definition.hiddenUntilUnlocked() || definition.secret())
                            && !tags.isUnlocked(player.getUniqueId(), definition);
                    if (definition.enabled() && !concealed
                            && definition.id().startsWith(pre)) {
                        out.add(definition.id());
                    }
                }
            }
        } else if (args.length == 2) {
            if ("off".startsWith(args[1].toLowerCase(Locale.ROOT))) out.add("off");
            if (sender.hasPermission("chatalyst.tag.others")) {
                String pre = args[1].toLowerCase(Locale.ROOT);
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
                    if (online.getName().toLowerCase(Locale.ROOT).startsWith(pre)) out.add(online.getName());
                }
            }
        }
        return out;
    }
}
