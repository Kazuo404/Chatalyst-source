package dev.chatalyst.staff;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.List;
import java.util.function.Consumer;

/**
 * Compositor de baneos: el staff arma el castigo por piezas — tipo/duracion, motivo
 * (presets, o escrito por chat con estilos <rainbow>/&4/estilos del server) y un estilo
 * aplicado encima — con vista previa exacta de como lo vera el baneado. "Confirmar"
 * despacha el comando real, que re-verifica permisos, protege OPs, funciona offline y
 * deja el motivo en la auditoria. Para los que no quieren armar nada: un clic en
 * confirmar usa el motivo default del server tal cual. La mecanica de motivo/estilo/
 * duracion la comparte con el compositor de mutes en {@link ComposerMenu}.
 */
public final class BanComposerMenu extends ComposerMenu {

    private boolean ipScope; // true = cuenta + conexion (/banip)

    public BanComposerMenu(StaffModerationModule module, String targetName,
                           Consumer<Player> back) {
        this(module, targetName, false, back);
    }

    /** {@code ipScope} pre-marca "Cuenta + conexion" (boton anti-alts del menu offline). */
    public BanComposerMenu(StaffModerationModule module, String targetName,
                           boolean ipScope, Consumer<Player> back) {
        super(module, targetName, back);
        this.ipScope = ipScope;
        // Los baneos arrancan mostrando "permanente" (indice 0); los mutes, la 1a temporal.
        this.durationIndex = 0;
    }

    @Override
    protected ModerationFormatter.Screen screen() {
        return ModerationFormatter.Screen.BAN;
    }

    @Override
    protected String durationsKey() {
        return "Staff_Moderation.Ban.Gui_Durations";
    }

    @Override
    protected List<String> durationFallback() {
        return List.of("1h", "1d", "7d");
    }

    @Override
    public Component title() {
        return Lang.get("staff-moderation.composer-title", "player", MM.escape(targetName));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "staff-moderation.composer-title", "player", MM.escape(targetName));
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        // Un /cht reload puede encoger Gui_Durations o los estilos con el menu abierto:
        // se re-normalizan los indices para no leer fuera de rango al renderizar.
        durationIndex = Math.floorMod(durationIndex, durations().size());
        styleIndex = Math.floorMod(styleIndex, styles().size() + 1);

        String styled = styledReason(viewer);
        inventory.setItem(PREVIEW, MenuItems.item(Material.NAME_TAG,
                Lang.raw(viewer, "staff-moderation.composer-preview"),
                List.of(MM.serialize(module.formatter().preview(viewer, styled)),
                        Lang.raw(viewer, "staff-moderation.composer-preview-duration",
                                "duration", durationLabel(viewer)))));

        inventory.setItem(DURATION, MenuItems.item(Material.CLOCK,
                Lang.raw(viewer, "staff-moderation.composer-duration",
                        "duration", durationLabel(viewer)),
                List.of(Lang.raw(viewer, "staff-moderation.composer-duration-lore"))));

        inventory.setItem(REASON, MenuItems.item(Material.WRITABLE_BOOK,
                Lang.raw(viewer, "staff-moderation.composer-reason"),
                List.of(MM.serialize(module.formatter().preview(viewer, currentReason(viewer))),
                        Lang.raw(viewer, "staff-moderation.composer-reason-lore1"),
                        Lang.raw(viewer, "staff-moderation.composer-reason-lore2"))));

        inventory.setItem(STYLE, MenuItems.item(Material.BRUSH,
                Lang.raw(viewer, "staff-moderation.composer-style",
                        "style", styleLabel(viewer)),
                List.of(Lang.raw(viewer, "staff-moderation.composer-style-lore"))));

        if (StaffAccess.has(viewer, "chatalyst.command.banip")) {
            inventory.setItem(EXTRA, MenuItems.item(
                    ipScope ? Material.CHAIN : Material.PLAYER_HEAD,
                    Lang.raw(viewer, "staff-moderation.composer-scope", "scope",
                            Lang.raw(viewer, ipScope
                                    ? "staff-moderation.composer-scope-ip"
                                    : "staff-moderation.composer-scope-account")),
                    List.of(Lang.raw(viewer, "staff-moderation.composer-scope-lore"))));
        }

        inventory.setItem(CONFIRM, MenuItems.item(Material.LIME_CONCRETE,
                Lang.raw(viewer, "staff-moderation.composer-confirm"),
                List.of(Lang.raw(viewer, "staff-moderation.composer-confirm-lore"))));

        inventory.setItem(BACK, dev.chatalyst.gui.MenuNavigation.back(viewer));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        switch (slot) {
            case BACK -> {
                if (back != null) back.accept(player);
                else player.closeInventory();
            }
            case DURATION -> {
                durationIndex = (durationIndex + 1) % durations().size();
                Menu.open(player, this);
            }
            case REASON -> {
                if (click.isShiftClick()) {
                    module.reasonPrompt().beginText(player, targetName, (current, text) -> {
                        customReason = text;
                        Menu.open(current, this);
                    });
                    return;
                }
                customReason = null;
                List<String> presets = presets(player);
                reasonIndex = reasonIndex + 1 >= presets.size() ? -1 : reasonIndex + 1;
                Menu.open(player, this);
            }
            case STYLE -> {
                styleIndex = (styleIndex + 1) % (styles().size() + 1);
                Menu.open(player, this);
            }
            case EXTRA -> {
                if (!StaffAccess.has(player, "chatalyst.command.banip")) return;
                ipScope = !ipScope;
                Menu.open(player, this);
            }
            case CONFIRM -> {
                if (!StaffAccess.has(player, "chatalyst.command.ban")) {
                    player.sendMessage(Lang.get(player, "common.no-permission"));
                    return;
                }
                // Re-normaliza por si un reload encogio la lista sin re-render del menu.
                durationIndex = Math.floorMod(durationIndex, durations().size());
                long duration = durations().get(durationIndex);
                String styled = commandReason(player);
                player.closeInventory();
                // Namespaced: siempre los comandos DE CHATALYST, gane quien gane la etiqueta.
                if (ipScope && StaffAccess.has(player, "chatalyst.command.banip")) {
                    // /banip banea cuenta + conexion y expulsa a quien la comparta.
                    // Duracion SIEMPRE explicita: sin ella, un motivo que empezara por algo con
                    // pinta de tiempo se leia como la duracion del ban.
                    player.performCommand("chatalyst:banip " + targetName
                            + (duration == TimeUtil.PERMANENT
                                    ? " perm" : " " + rawDuration(durationIndex))
                            + " " + styled);
                } else if (duration == TimeUtil.PERMANENT) {
                    player.performCommand("chatalyst:ban " + targetName + " " + styled);
                } else {
                    player.performCommand("chatalyst:tempban " + targetName + " "
                            + rawDuration(durationIndex) + " " + styled);
                }
            }
            default -> { }
        }
    }
}
