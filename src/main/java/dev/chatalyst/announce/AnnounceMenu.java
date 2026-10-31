package dev.chatalyst.announce;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI del /announce: componer un anuncio espectacular sin escribir comandos. Selectores
 * de display (chat, title, bossbar, toast o EPIC = todo a la vez), sonido y estilo, el
 * mensaje se escribe por chat, y botones de vista previa (solo tu) y envio a todos.
 */
public final class AnnounceMenu implements Menu {

    // Fila superior: par espejado (columnas 3 y 5, como preview/send abajo). Antes scope
    // quedaba en la esquina (8) y rompia la simetria.
    private static final int SLOT_ANIMATION = 3;
    private static final int SLOT_SCOPE = 5;
    private static final int SLOT_DISPLAY = 10;
    private static final int SLOT_SOUND = 12;
    private static final int SLOT_STYLE = 14;
    private static final int SLOT_MESSAGE = 16;
    private static final int SLOT_PREVIEW = 21;
    private static final int SLOT_SEND = 23;

    private final AnnounceDraftManager drafts;

    public AnnounceMenu(AnnounceDraftManager drafts) {
        this.drafts = drafts;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("announce-gui.title"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "announce-gui.title");
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        var draft = drafts.draft(viewer.getUniqueId());

        boolean networkAvailable = drafts.networkAvailable(viewer);
        inv.setItem(SLOT_SCOPE, item(draft.network ? Material.ENDER_CHEST : Material.CHEST,
                Lang.raw(viewer, "announce-gui.scope"),
                List.of(Lang.raw(viewer, draft.network
                                ? "announce-gui.scope-network" : "announce-gui.scope-local"),
                        Lang.raw(viewer, networkAvailable
                                ? "announce-gui.scope-hint" : "announce-gui.scope-unavailable"))));

        inv.setItem(SLOT_DISPLAY, item(displayIcon(draft.display),
                Lang.raw(viewer, "announce-gui.display"),
                cycleLore(viewer, AnnounceDraftManager.DISPLAYS.stream()
                                .map(d -> Lang.raw(viewer, "announce-gui.display-" + d)).toList(),
                        draft.display)));
        List<String> animLore = cycleLore(viewer, AnnounceDraftManager.ANIMATIONS.stream()
                        .map(a -> Lang.raw(viewer, "announce-gui.animation-" + a)).toList(),
                Math.floorMod(draft.anim, AnnounceDraftManager.ANIMATIONS.size()));
        animLore.add(Lang.raw(viewer, "announce-gui.animation-note"));
        inv.setItem(SLOT_ANIMATION, item(animationIcon(draft.anim),
                Lang.raw(viewer, "announce-gui.animation"), animLore));
        inv.setItem(SLOT_SOUND, item(Material.NOTE_BLOCK,
                Lang.raw(viewer, "announce-gui.sound"),
                cycleLore(viewer, soundNames(viewer), draft.sound)));
        // Estilos: toda la paleta de /chatcolor y /nickcolor (30+). Se muestra el actual
        // resaltado y con su tag aplicado; se navega con click y click derecho.
        var styles = drafts.styles();
        var current = drafts.style(draft);
        List<String> styleLore = new ArrayList<>();
        styleLore.add("<#3DFFA8>> " + current.template().replace("{msg}", styleName(viewer, current)));
        styleLore.add("<#7B68A0>" + (Math.floorMod(draft.style, styles.size()) + 1) + "/" + styles.size());
        styleLore.add(Lang.raw(viewer, "announce-gui.style-navigate"));
        inv.setItem(SLOT_STYLE, item(current.material(),
                Lang.raw(viewer, "announce-gui.style"), styleLore));

        List<String> messageLore = new ArrayList<>();
        messageLore.add(draft.message.isBlank()
                ? Lang.raw(viewer, "announce-gui.message-empty")
                : "<#FBF2FF>" + MM.escape(preview(draft.message)));
        messageLore.add(Lang.raw(viewer, "announce-gui.message-hint"));
        inv.setItem(SLOT_MESSAGE, item(Material.WRITABLE_BOOK,
                Lang.raw(viewer, "announce-gui.message"), messageLore));

        inv.setItem(SLOT_PREVIEW, dev.chatalyst.gui.MenuNavigation.preview(viewer));
        inv.setItem(SLOT_SEND, item(Material.BEACON,
                Lang.raw(viewer, "announce-gui.send"),
                List.of(Lang.raw(viewer, "announce-gui.send-hint"))));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!drafts.canUse(player)) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        var draft = drafts.draft(player.getUniqueId());
        switch (slot) {
            case SLOT_SCOPE -> {
                if (!drafts.networkAvailable(player)) {
                    draft.network = false;
                    player.sendMessage(Lang.get(player, "announce-gui.network-unavailable"));
                } else {
                    draft.network = !draft.network;
                }
                Menu.open(player, this);
            }
            case SLOT_DISPLAY -> {
                draft.display = (draft.display + 1) % AnnounceDraftManager.DISPLAYS.size();
                Menu.open(player, this);
            }
            case SLOT_ANIMATION -> {
                draft.anim = (draft.anim + 1) % AnnounceDraftManager.ANIMATIONS.size();
                Menu.open(player, this);
            }
            case SLOT_SOUND -> {
                draft.sound = (draft.sound + 1) % AnnounceDraftManager.SOUNDS.size();
                // Muestra del sonido elegido al pasar a el.
                String key = AnnounceDraftManager.SOUNDS.get(draft.sound);
                if (!key.isEmpty()) {
                    dev.chatalyst.util.SoundUtil.play(
                            dev.chatalyst.ChatalystPlugin.get(), player,
                            dev.chatalyst.util.SoundUtil.sound(key, key, 1f, 1f));
                }
                Menu.open(player, this);
            }
            case SLOT_STYLE -> {
                int size = drafts.styles().size();
                int step = click.isRightClick() ? -1 : 1;
                draft.style = Math.floorMod(draft.style + step, size);
                Menu.open(player, this);
            }
            case SLOT_MESSAGE -> {
                drafts.startComposing(player.getUniqueId());
                player.closeInventory();
                player.sendMessage(Lang.get(player, "announce-gui.compose-prompt"));
            }
            case SLOT_PREVIEW -> {
                if (requireMessage(player, draft)) return;
                player.closeInventory();
                drafts.send(player, true);
            }
            case SLOT_SEND -> {
                if (requireMessage(player, draft)) return;
                player.closeInventory();
                boolean sent = drafts.send(player, false);
                player.sendMessage(Lang.get(player, sent
                        ? (draft.network ? "announce-gui.sent-network" : "announce-gui.sent")
                        : "announce-gui.network-unavailable"));
            }
            default -> {
            }
        }
    }

    private boolean requireMessage(Player player, AnnounceDraftManager.Draft draft) {
        if (!draft.message.isBlank()) return false;
        player.sendMessage(Lang.get(player, "announce-gui.message-required"));
        return true;
    }

    private static Material animationIcon(int index) {
        return switch (AnnounceDraftManager.ANIMATIONS.get(
                Math.floorMod(index, AnnounceDraftManager.ANIMATIONS.size()))) {
            case "typewriter" -> Material.FEATHER;
            case "expand" -> Material.AMETHYST_CLUSTER;
            case "glitch" -> Material.OBSERVER;
            case "wave" -> Material.HEART_OF_THE_SEA;
            case "frames" -> Material.PRISMARINE_CRYSTALS;
            case "pulse" -> Material.REDSTONE;
            case "flash" -> Material.REDSTONE_TORCH;
            case "marquee" -> Material.NAME_TAG;
            case "countdown" -> Material.TNT;
            default -> Material.GUNPOWDER; // none
        };
    }

    private static Material displayIcon(int index) {
        return switch (AnnounceDraftManager.DISPLAYS.get(index)) {
            case "chat" -> Material.PAPER;
            case "title" -> Material.GLOW_ITEM_FRAME;
            case "actionbar" -> Material.CLOCK;
            case "bossbar" -> Material.WITHER_SKELETON_SKULL;
            case "toast" -> Material.GOLD_INGOT;
            default -> Material.NETHER_STAR; // epic
        };
    }

    /** Nombre visible de un estilo: etiqueta de la paleta, o clave lang para los fijos. */
    private static String styleName(Player viewer, AnnounceDraftManager.Style style) {
        if (style.label() == null) {
            return Lang.raw(viewer, "announce-gui.style-" + style.id());
        }
        String key = "color.palette." + style.id();
        String localized = Lang.raw(viewer, key);
        return key.equals(localized) ? style.label() : localized;
    }

    private List<String> soundNames(Player viewer) {
        List<String> out = new ArrayList<>();
        for (String key : AnnounceDraftManager.SOUNDS) {
            String name = switch (key) {
                case "minecraft:ui.toast.challenge_complete" -> "toast";
                case "minecraft:entity.ender_dragon.growl" -> "dragon";
                case "minecraft:entity.lightning_bolt.thunder" -> "thunder";
                case "minecraft:block.bell.use" -> "bell";
                case "minecraft:entity.player.levelup" -> "levelup";
                default -> "none";
            };
            out.add(Lang.raw(viewer, "announce-gui.sound-" + name));
        }
        return out;
    }

    /** Lore de un selector ciclico: cada opcion, con la actual resaltada. */
    private List<String> cycleLore(Player viewer, List<String> options, int selected) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            out.add((i == selected ? "<#3DFFA8>> <#FBF2FF>" : "<#7B68A0>  ") + options.get(i));
        }
        out.add(Lang.raw(viewer, "announce-gui.click-cycle"));
        return out;
    }

    private static String preview(String message) {
        return message.length() <= 40 ? message : message.substring(0, 37) + "...";
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        return dev.chatalyst.gui.MenuItems.item(material, name,
                lore.stream().map(line -> "<#C0B3D9>" + line).toList());
    }
}
