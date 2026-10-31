package dev.chatalyst.staff;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Compositor de mutes: la misma experiencia que el compositor de baneos — duracion,
 * motivo (presets o escrito por chat con estilos) y estilo visual, con vista previa —
 * mas el interruptor de shadow mute (-s: el muteado no se entera). "Confirmar" despacha
 * /mute real, que re-verifica permisos, protege objetivos con bypass y deja auditoria.
 * La mecanica de motivo/estilo/duracion la comparte con {@link ComposerMenu}.
 */
public final class MuteComposerMenu extends ComposerMenu {

    private boolean soft; // true = shadow mute (-s)

    public MuteComposerMenu(StaffModerationModule module, String targetName,
                            Consumer<Player> back) {
        super(module, targetName, back);
        // Los mutes arrancan en la 1a duracion temporal, no en permanente.
        this.durationIndex = 1;
    }

    @Override
    protected ModerationFormatter.Screen screen() {
        return ModerationFormatter.Screen.MUTE;
    }

    @Override
    protected String durationsKey() {
        return "Staff_Moderation.Mute.Gui_Durations";
    }

    @Override
    protected List<String> durationFallback() {
        return List.of("10m", "1h", "1d", "7d");
    }

    @Override
    public Component title() {
        return Lang.get("staff-moderation.mute-composer-title", "player", MM.escape(targetName));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "staff-moderation.mute-composer-title",
                "player", MM.escape(targetName));
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        // Un /cht reload puede encoger Gui_Durations o los estilos con el menu abierto:
        // se re-normalizan los indices para no leer fuera de rango al renderizar.
        durationIndex = Math.floorMod(durationIndex, durations().size());
        styleIndex = Math.floorMod(styleIndex, styles().size() + 1);

        String styled = styledReason(viewer);
        List<String> previewLore = new ArrayList<>();
        previewLore.add(MM.serialize(module.formatter().preview(viewer, styled)));
        previewLore.add(Lang.raw(viewer, "staff-moderation.composer-preview-duration",
                "duration", durationLabel(viewer)));
        if (soft) previewLore.add(Lang.raw(viewer, "staff-moderation.mute-composer-soft-tag"));
        inventory.setItem(PREVIEW, MenuItems.item(Material.NAME_TAG,
                Lang.raw(viewer, "staff-moderation.composer-preview"), previewLore));

        inventory.setItem(DURATION, MenuItems.item(Material.CLOCK,
                Lang.raw(viewer, "staff-moderation.mute-composer-duration",
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

        inventory.setItem(EXTRA, MenuItems.item(
                soft ? Material.ENDER_PEARL : Material.SNOWBALL,
                Lang.raw(viewer, "staff-moderation.mute-composer-soft", "mode",
                        Lang.raw(viewer, soft
                                ? "staff-moderation.mute-composer-soft-on"
                                : "staff-moderation.mute-composer-soft-off")),
                List.of(Lang.raw(viewer, "staff-moderation.mute-composer-soft-lore"))));

        inventory.setItem(CONFIRM, MenuItems.item(Material.ORANGE_CONCRETE,
                Lang.raw(viewer, "staff-moderation.mute-composer-confirm"),
                List.of(Lang.raw(viewer, "staff-moderation.mute-composer-confirm-lore"))));

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
                soft = !soft;
                Menu.open(player, this);
            }
            case CONFIRM -> {
                if (!StaffAccess.has(player, "chatalyst.command.mute")) {
                    player.sendMessage(Lang.get(player, "common.no-permission"));
                    return;
                }
                String styled = commandReason(player);
                player.closeInventory();
                // Duracion SIEMPRE explicita ("perm" incluido): asi un motivo que empieza
                // pareciendo duracion ("10m de spam") nunca se interpreta como tiempo.
                // Namespaced: siempre el /mute de Chatalyst, gane quien gane la etiqueta.
                player.performCommand("chatalyst:mute " + (soft ? "-s " : "") + targetName
                        + " " + rawDuration(durationIndex) + " " + styled);
            }
            default -> { }
        }
    }
}
