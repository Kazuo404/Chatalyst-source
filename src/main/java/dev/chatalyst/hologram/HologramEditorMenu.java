package dev.chatalyst.hologram;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.staff.StaffAccess;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Editor de un cartel: cada boton es una opcion de {@code /cartel opcion} (clic izquierdo y
 * derecho la suben/bajan o pasan a la siguiente/anterior), asi el menu y el comando nunca
 * pueden discrepar — los dos pasan por {@link HoloOptions}. Lo que hay que escribir (texto de
 * las lineas, comando del clic) se deja preparado en el chat para completarlo.
 */
final class HologramEditorMenu implements Menu {

    // Simetrico (KZ): fila 0 completa de acciones, fila 1 completa de aspecto, filas 2 y 3 con
    // cinco opciones centradas, fila 4 las lineas centradas y "volver" en el centro de abajo.
    private static final int INFO = 0;
    private static final int TELEPORT = 1;
    private static final int MOVE = 2;
    private static final int FACE = 3;
    private static final int PRESET = 4;
    private static final int ADD_LINE = 5;
    private static final int ACTION = 6;
    private static final int COOLDOWN = 7;
    private static final int DELETE = 8;
    private static final int LINES_ROW = 4;
    private static final int BACK = 49;

    /** Un boton de opcion: que valor muestra y que valor escribe cada clic. */
    private record Button(int slot, Material material, String option,
                          Function<Hologram, String> current,
                          BiFunction<Hologram, Boolean, String> next) {
    }

    private static final List<Button> BUTTONS = List.of(
            new Button(9, Material.SLIME_BALL, "escala",
                    h -> HoloOptions.format(h.look().scale()),
                    (h, up) -> HoloOptions.format(step(h.look().scale(), up,
                            h.look().scale() < 1 || (!up && h.look().scale() <= 1) ? 0.1
                                    : h.look().scale() < 4 || (!up && h.look().scale() <= 4) ? 0.5 : 1,
                            HoloLook.MIN_SCALE, HoloLook.MAX_SCALE))),
            new Button(10, Material.BLACK_STAINED_GLASS, "fondo", h -> h.look().background(),
                    (h, up) -> h.look().background().equals("default") ? "none" : "default"),
            new Button(11, Material.INK_SAC, "sombra", h -> onOff(h.look().shadow()),
                    (h, up) -> onOff(!h.look().shadow())),
            new Button(12, Material.GLASS, "opacidad", h -> h.look().opacity() + "%",
                    (h, up) -> String.valueOf((int) step(h.look().opacity(), up, 10, 10, 100))),
            new Button(13, Material.GLOWSTONE_DUST, "brillo",
                    h -> h.look().brightness() < 0 ? "auto" : String.valueOf(h.look().brightness()),
                    (h, up) -> cycle(List.of("auto", "15", "12", "8", "4", "0"),
                            h.look().brightness() < 0 ? "auto" : String.valueOf(h.look().brightness()), up)),
            new Button(14, Material.LECTERN, "alineacion", h -> lower(h.look().alignment()),
                    (h, up) -> cycle(List.of("center", "left", "right"), lower(h.look().alignment()), up)),
            new Button(15, Material.STRING, "ancho", h -> String.valueOf(h.look().lineWidth()),
                    (h, up) -> String.valueOf((int) step(h.look().lineWidth(), up, 20, 20, 2000))),
            new Button(16, Material.ITEM_FRAME, "orientacion", h -> lower(h.look().billboard()),
                    (h, up) -> cycle(List.of("center", "fixed", "vertical", "horizontal"),
                            lower(h.look().billboard()), up)),
            new Button(17, Material.GLASS_PANE, "doblecara", h -> onOff(h.look().doubleSided()),
                    (h, up) -> onOff(!h.look().doubleSided())),
            new Button(20, Material.PAPER, "separacion", h -> HoloOptions.format(h.look().sideGap()),
                    (h, up) -> HoloOptions.format(step(h.look().sideGap(), up, 0.01, 0, 0.5))),
            new Button(21, Material.SPYGLASS, "distancia", h -> String.valueOf(h.look().viewRange()),
                    (h, up) -> String.valueOf((int) step(h.look().viewRange(), up, 16, 4, 512))),
            new Button(22, Material.TINTED_GLASS, "atraves", h -> onOff(h.look().seeThrough()),
                    (h, up) -> onOff(!h.look().seeThrough())),
            new Button(23, Material.ARMOR_STAND, "objeto", h -> HoloOptions.format(h.look().itemScale()),
                    (h, up) -> HoloOptions.format(step(h.look().itemScale(), up, 0.25, 0.25, 10))),
            new Button(24, Material.GRASS_BLOCK, "bedrock", h -> lower(h.bedrock()),
                    (h, up) -> cycle(List.of("geyser", "stands", "hidden"), lower(h.bedrock()), up)),
            new Button(29, Material.FIREWORK_ROCKET, "animacion", h -> h.motion().animation(),
                    (h, up) -> cycle(HoloAnimation.NAMES, h.motion().animation(), up)),
            new Button(30, Material.CLOCK, "velocidad", h -> String.valueOf(h.motion().speed()),
                    (h, up) -> String.valueOf((int) step(h.motion().speed(), up, 1, 1, 40))),
            new Button(31, Material.RECOVERY_COMPASS, "rotacion",
                    h -> HoloOptions.format(h.motion().spin()),
                    (h, up) -> cycle(List.of("0", "15", "30", "60", "90", "-30"),
                            HoloOptions.format(h.motion().spin()), up)),
            new Button(32, Material.FEATHER, "flotar", h -> HoloOptions.format(h.motion().bob()),
                    (h, up) -> cycle(List.of("0", "0.05", "0.1", "0.2"),
                            HoloOptions.format(h.motion().bob()), up)),
            new Button(33, Material.REPEATER, "refresco", h -> h.motion().refresh() + "s",
                    (h, up) -> String.valueOf((int) step(h.motion().refresh(), up, 1, 1, 600))));

    private final HologramModule module;
    private final String id;

    HologramEditorMenu(HologramModule module, String id) {
        this.module = module;
        this.id = id;
    }

    @Override
    public Component title() {
        return Lang.get("holograms.menu-editor-title", "id", id);
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "holograms.menu-editor-title", "id", id);
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        HologramService service = module.service();
        Hologram hologram = service == null ? null : service.get(id);
        if (hologram == null) return;
        Hologram.Position p = hologram.position();
        inventory.setItem(INFO, MenuItems.item(Material.BOOK,
                Lang.raw(viewer, "holograms.menu-info", "id", id),
                Lang.rawList(viewer, "holograms.menu-info-lore",
                        "world", MM.escape(p.world()), "x", (int) Math.floor(p.x()),
                        "y", (int) Math.floor(p.y()), "z", (int) Math.floor(p.z()),
                        "yaw", HoloOptions.format(Math.round(p.yaw())),
                        "lines", hologram.lines().size(),
                        "state", Lang.raw(viewer, service.spawned(id)
                                ? "holograms.info-spawned" : "holograms.info-not-spawned"))));
        inventory.setItem(TELEPORT, simple(viewer, Material.ENDER_PEARL, "teleport"));
        inventory.setItem(MOVE, simple(viewer, Material.COMPASS, "move"));
        inventory.setItem(FACE, simple(viewer, Material.ARROW, "face"));
        inventory.setItem(ADD_LINE, simple(viewer, Material.WRITABLE_BOOK, "add-line"));
        inventory.setItem(DELETE, simple(viewer, Material.BARRIER, "delete"));

        for (Button button : BUTTONS) {
            inventory.setItem(button.slot(), MenuItems.item(button.material(),
                    Lang.raw(viewer, "holograms.editor-option-" + button.option()),
                    Lang.rawList(viewer, "holograms.menu-option-lore",
                            "value", MM.escape(button.current().apply(hologram)),
                            "description", Lang.raw(viewer,
                                    "holograms.editor-help-" + button.option()))));
        }

        HoloAction action = hologram.action();
        inventory.setItem(ACTION, MenuItems.item(Material.STONE_BUTTON,
                Lang.raw(viewer, "holograms.menu-action"),
                Lang.rawList(viewer, "holograms.menu-action-lore",
                        "type", action.type().name().toLowerCase(Locale.ROOT),
                        "value", action.active() ? MM.escape(action.value()) : "-")));
        inventory.setItem(COOLDOWN, MenuItems.item(Material.HOPPER,
                Lang.raw(viewer, "holograms.menu-cooldown"),
                Lang.rawList(viewer, "holograms.menu-option-lore",
                        "value", action.cooldown() + "s",
                        "description", Lang.raw(viewer, "holograms.editor-help-cooldown"))));
        List<String> presets = new ArrayList<>(service.settings().presets().keySet());
        presets.sort(String::compareTo);
        inventory.setItem(PRESET, MenuItems.item(Material.PAINTING,
                Lang.raw(viewer, "holograms.menu-preset"),
                Lang.rawList(viewer, "holograms.menu-preset-lore",
                        "presets", presets.isEmpty() ? "-" : String.join(", ", presets))));

        int[] lineSlots = dev.chatalyst.gui.MenuLayout.row(Math.min(9, hologram.lines().size()), LINES_ROW);
        for (int i = 0; i < lineSlots.length; i++) {
            HoloLine line = hologram.lines().get(i);
            Material material = line.isText() ? Material.PAPER
                    : line.isImage() ? Material.PAINTING
                    : HologramInstance.item(line.content()).getType();
            inventory.setItem(lineSlots[i], MenuItems.item(material,
                    Lang.raw(viewer, "holograms.menu-line", "line", i + 1),
                    Lang.rawList(viewer, "holograms.menu-line-lore",
                            "text", MM.escape(line.raw()),
                            "animation", line.animation() == null ? "-" : line.animation())));
        }
        inventory.setItem(BACK, MenuNavigation.back(viewer));
    }

    private static org.bukkit.inventory.ItemStack simple(Player viewer, Material material,
                                                         String key) {
        return MenuItems.item(material, Lang.raw(viewer, "holograms.menu-" + key),
                Lang.rawList(viewer, "holograms.menu-" + key + "-lore"));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        HologramService service = module.service();
        Hologram hologram = service == null ? null : service.get(id);
        if (hologram == null || !StaffAccess.has(player, HologramCommand.PERMISSION)) {
            player.closeInventory();
            return;
        }
        String label = module.label();
        // Los comandos que se ejecutan van con espacio de nombres: si otro plugin tiene la
        // etiqueta corta, /chatalyst:<label> sigue siendo el nuestro.
        String own = "chatalyst:" + label;
        switch (slot) {
            case BACK -> Menu.open(player, new HologramListMenu(module, 0));
            case TELEPORT -> {
                player.closeInventory();
                HologramCommand.teleport(player, hologram, service);
            }
            case MOVE -> {
                player.performCommand(own + " mover " + id);
                reopen(player);
            }
            case FACE -> {
                player.performCommand(own + " girar " + id + " aqui");
                reopen(player);
            }
            case ADD_LINE -> suggest(player, "holograms.menu-suggest-line",
                    "/" + label + " anadir " + id + " ");
            case ACTION -> suggest(player, "holograms.menu-suggest-action",
                    "/" + label + " accion " + id + " ");
            case DELETE -> {
                // Borrar pide mayusculas: un clic despistado no se lleva un cartel por delante.
                if (!click.isShiftClick()) {
                    player.sendMessage(Lang.get(player, "holograms.menu-delete-confirm"));
                    return;
                }
                player.closeInventory();
                player.performCommand(own + " borrar " + id);
            }
            case COOLDOWN -> {
                int next = (int) step(hologram.action().cooldown(), click.isLeftClick(), 1, 0, 3600);
                service.update(id, h -> h.withAction(h.action().withCooldown(next)));
                reopen(player);
            }
            case PRESET -> cyclePreset(player, service, hologram, click.isLeftClick());
            default -> {
                int[] lineSlots = dev.chatalyst.gui.MenuLayout.row(
                        Math.min(9, hologram.lines().size()), LINES_ROW);
                for (int i = 0; i < lineSlots.length; i++) {
                    if (lineSlots[i] == slot) {
                        clickLine(player, hologram, i + 1, click, label);
                        return;
                    }
                }
                for (Button button : BUTTONS) {
                    if (button.slot() != slot) continue;
                    String value = button.next().apply(hologram, click.isLeftClick());
                    HoloOptions.Result result = HoloOptions.apply(hologram, button.option(), value);
                    if (result.ok()) service.update(id, h -> result.hologram());
                    reopen(player);
                    return;
                }
            }
        }
    }

    private void clickLine(Player player, Hologram hologram, int line, ClickType click,
                           String label) {
        if (line > hologram.lines().size()) return;
        if (click.isShiftClick() && click.isRightClick()) {
            player.performCommand("chatalyst:" + label + " quitar " + id + " " + line);
            reopen(player);
            return;
        }
        suggest(player, "holograms.menu-suggest-edit-line",
                "/" + label + " linea " + id + " " + line + " " + hologram.lines().get(line - 1).raw());
    }

    private void cyclePreset(Player player, HologramService service, Hologram hologram,
                             boolean forward) {
        List<String> presets = new ArrayList<>(service.settings().presets().keySet());
        if (presets.isEmpty()) return;
        presets.sort(String::compareTo);
        // Recorre los presets en orden; el que toca se elige por el ultimo aplicado.
        int index = Math.floorMod(lastPreset + (forward ? 1 : -1), presets.size());
        lastPreset = index;
        String preset = presets.get(index);
        HoloPresets.Applied applied = HoloPresets.apply(hologram,
                service.settings().presets().get(preset));
        service.update(id, h -> applied.hologram());
        player.sendMessage(Lang.get(player, "holograms.preset-applied", "id", id, "preset", preset));
        HologramEditorMenu next = new HologramEditorMenu(module, id);
        next.lastPreset = index;
        Menu.open(player, next);
    }

    private int lastPreset = -1;

    private void suggest(Player player, String key, String command) {
        player.closeInventory();
        player.sendMessage(Lang.get(player, key).clickEvent(ClickEvent.suggestCommand(command)));
    }

    private void reopen(Player player) {
        Menu.open(player, new HologramEditorMenu(module, id));
    }

    // ---- Valores ---------------------------------------------------------------------------

    private static double step(double value, boolean up, double step, double min, double max) {
        double next = up ? value + step : value - step;
        next = Math.round(next * 100.0) / 100.0;
        return Math.max(min, Math.min(max, next));
    }

    private static String cycle(List<String> values, String current, boolean forward) {
        int index = values.indexOf(current);
        if (index < 0) return values.getFirst();
        return values.get(Math.floorMod(index + (forward ? 1 : -1), values.size()));
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
