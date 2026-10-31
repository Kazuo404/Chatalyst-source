package dev.chatalyst.hologram;

import dev.chatalyst.command.RuntimeCommandRegistrar;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.staff.StaffAccess;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /cartel} (alias /holo, /holograma): crear, editar y administrar carteles flotantes.
 * Subcomandos en español con su alias en ingles; ver {@code holograms.help} en lang.
 */
final class HologramCommand implements RuntimeCommandRegistrar.Handler {

    static final String PERMISSION = "chatalyst.command.hologram";
    static final String ACTION_PERMISSION = "chatalyst.hologram.action";
    static final String CONSOLE_ACTION_PERMISSION = "chatalyst.hologram.action.console";

    private static final Map<String, String> SUBCOMMANDS = Map.ofEntries(
            Map.entry("crear", "create"), Map.entry("create", "create"),
            Map.entry("borrar", "delete"), Map.entry("eliminar", "delete"),
            Map.entry("delete", "delete"), Map.entry("remove", "delete"),
            Map.entry("lista", "list"), Map.entry("list", "list"),
            Map.entry("cerca", "near"), Map.entry("near", "near"),
            Map.entry("tp", "tp"), Map.entry("ir", "tp"), Map.entry("teleport", "tp"),
            Map.entry("mover", "move"), Map.entry("aqui", "move"), Map.entry("aquí", "move"),
            Map.entry("move", "move"), Map.entry("movehere", "move"),
            Map.entry("girar", "rotate"), Map.entry("rotate", "rotate"),
            Map.entry("copiar", "copy"), Map.entry("copy", "copy"), Map.entry("clone", "copy"),
            Map.entry("linea", "setline"), Map.entry("línea", "setline"),
            Map.entry("setline", "setline"),
            Map.entry("anadir", "addline"), Map.entry("añadir", "addline"),
            Map.entry("add", "addline"), Map.entry("addline", "addline"),
            Map.entry("insertar", "insertline"), Map.entry("insert", "insertline"),
            Map.entry("insertline", "insertline"),
            Map.entry("quitar", "removeline"), Map.entry("removeline", "removeline"),
            Map.entry("opcion", "set"), Map.entry("opción", "set"), Map.entry("set", "set"),
            Map.entry("option", "set"),
            Map.entry("opciones", "options"), Map.entry("options", "options"),
            Map.entry("animar", "animate"), Map.entry("animate", "animate"),
            Map.entry("accion", "action"), Map.entry("acción", "action"),
            Map.entry("action", "action"), Map.entry("click", "action"),
            Map.entry("espera", "cooldown"), Map.entry("cooldown", "cooldown"),
            Map.entry("preset", "preset"), Map.entry("estilo", "preset"),
            Map.entry("presets", "presets"), Map.entry("estilos", "presets"),
            Map.entry("editar", "edit"), Map.entry("edit", "edit"), Map.entry("menu", "edit"),
            Map.entry("info", "info"),
            Map.entry("recargar", "reload"), Map.entry("reload", "reload"),
            Map.entry("ayuda", "help"), Map.entry("help", "help"),
            Map.entry("grande", "big"), Map.entry("big", "big"), Map.entry("letras", "big"),
            Map.entry("importar", "import"), Map.entry("import", "import"),
            Map.entry("mural", "mural"), Map.entry("mapa", "mural"), Map.entry("murales", "mural"),
            Map.entry("imagen", "image"), Map.entry("image", "image"), Map.entry("img", "image"));

    /** Palabras que piden ensayar {@code /cartel importar} sin tocar nada. */
    private static final List<String> SIMULATE_WORDS = List.of("simular", "simulate", "sim",
            "prueba", "dry", "dryrun");

    /** Lo que se ofrece al autocompletar (en español; los alias en ingles tambien valen). */
    private static final List<String> SHOWN_SUBCOMMANDS = List.of("crear", "grande", "imagen", "mural", "importar", "borrar", "lista",
            "cerca", "tp", "mover", "girar", "copiar", "linea", "anadir", "insertar", "quitar",
            "opcion", "opciones", "animar", "accion", "espera", "preset", "presets", "editar",
            "info", "recargar", "ayuda");

    private final HologramModule module;

    HologramCommand(HologramModule module) {
        this.module = module;
    }

    private HologramService service() {
        return module.service();
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!StaffAccess.has(sender, PERMISSION)) {
            sender.sendMessage(Lang.get(sender, "common.no-permission"));
            return true;
        }
        if (service() == null) {
            sender.sendMessage(Lang.get(sender, "holograms.disabled"));
            return true;
        }
        if (args.length == 0) {
            if (sender instanceof Player player) {
                Menu.open(player, new HologramListMenu(module, 0));
            } else {
                help(sender, label);
            }
            return true;
        }
        String sub = SUBCOMMANDS.get(args[0].toLowerCase(Locale.ROOT));
        if (sub == null) {
            help(sender, label);
            return true;
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "create" -> create(sender, label, rest);
            case "big" -> big(sender, label, rest);
            case "import" -> importDisplays(sender, label, rest);
            case "mural" -> mural(sender, label, rest);
            case "image" -> image(sender, label, rest);
            case "delete" -> delete(sender, label, rest);
            case "list" -> list(sender, rest);
            case "near" -> near(sender, rest);
            case "tp" -> teleport(sender, label, rest);
            case "move" -> move(sender, label, rest);
            case "rotate" -> rotate(sender, label, rest);
            case "copy" -> copy(sender, label, rest);
            case "setline" -> setLine(sender, label, rest);
            case "addline" -> addLine(sender, label, rest);
            case "insertline" -> insertLine(sender, label, rest);
            case "removeline" -> removeLine(sender, label, rest);
            case "set" -> option(sender, label, rest);
            case "options" -> options(sender);
            case "animate" -> animate(sender, label, rest);
            case "action" -> action(sender, label, rest);
            case "cooldown" -> cooldown(sender, label, rest);
            case "preset" -> preset(sender, label, rest);
            case "presets" -> presets(sender);
            case "edit" -> edit(sender, label, rest);
            case "info" -> info(sender, label, rest);
            case "reload" -> reload(sender);
            default -> help(sender, label);
        }
        return true;
    }

    // ---- Alta, baja y sitio ---------------------------------------------------------------

    private void create(CommandSender sender, String label, String[] args) {
        Player player = player(sender);
        if (player == null) return;
        if (args.length < 1) {
            usage(sender, label, "crear <id> [texto]");
            return;
        }
        String id = args[0].toLowerCase(Locale.ROOT);
        if (!Hologram.validId(id)) {
            sender.sendMessage(Lang.get(sender, "holograms.invalid-id"));
            return;
        }
        if (muralTaken(sender, id)) return;
        if (service().full()) {
            sender.sendMessage(Lang.get(sender, "holograms.full",
                    "max", service().settings().maxHolograms()));
            return;
        }
        String text = join(args, 1);
        Location at = player.getLocation();
        Hologram hologram = Hologram.create(id, position(at, at.getYaw() + 180f), text)
                .withBedrock(service().settings().defaultBedrock());
        if (!service().create(hologram)) {
            sender.sendMessage(Lang.get(sender, "holograms.exists", "id", id));
            return;
        }
        sender.sendMessage(Lang.get(sender, "holograms.created", "id", id, "label", label));
    }

    /**
     * {@code /cartel grande <id> <color|degradado> <texto...> [escala:N]}: letras grandes de
     * fachada en una linea. Mira hacia donde mira quien lo crea (lo leen los que tiene delante).
     */
    private void big(CommandSender sender, String label, String[] args) {
        Player player = player(sender);
        if (player == null) return;
        String usage = "grande <id> <color|color-color> <texto> [escala:N]";
        if (args.length < 3) {
            usage(sender, label, usage);
            colors(sender);
            return;
        }
        String id = args[0].toLowerCase(Locale.ROOT);
        if (!Hologram.validId(id)) {
            sender.sendMessage(Lang.get(sender, "holograms.invalid-id"));
            return;
        }
        if (muralTaken(sender, id)) return;
        if (service().get(id) != null) {
            sender.sendMessage(Lang.get(sender, "holograms.exists", "id", id));
            return;
        }
        if (service().full()) {
            sender.sendMessage(Lang.get(sender, "holograms.full",
                    "max", service().settings().maxHolograms()));
            return;
        }
        String format = BigText.format(args[1]);
        if (format == null) {
            sender.sendMessage(Lang.get(sender, "holograms.big-color", "value", MM.escape(args[1])));
            colors(sender);
            return;
        }
        int end = args.length;
        Double scale = null;
        if (BigText.looksLikeScale(args[end - 1])) {
            scale = BigText.scale(args[end - 1]);
            if (scale == null) {
                sender.sendMessage(Lang.get(sender, "holograms.option-scale"));
                return;
            }
            end--;
        }
        String text = String.join(" ", Arrays.copyOfRange(args, 2, end)).strip();
        if (text.isEmpty()) {
            usage(sender, label, usage);
            return;
        }
        Location at = player.getLocation();
        Hologram hologram = Hologram.create(id, position(at, at.getYaw()),
                format.replace("{text}", text))
                .withBedrock(service().settings().defaultBedrock());
        Map<String, String> preset = service().settings().presets()
                .getOrDefault("fachada", BigText.FACADE);
        hologram = HoloPresets.apply(hologram, preset).hologram();
        if (scale != null) hologram = hologram.withLook(hologram.look().withScale(scale));
        if (!service().create(hologram)) {
            sender.sendMessage(Lang.get(sender, "holograms.exists", "id", id));
            return;
        }
        sender.sendMessage(Lang.get(sender, "holograms.big-created", "id", id,
                "scale", HoloOptions.format(hologram.look().scale()), "label", label));
    }

    private void colors(CommandSender sender) {
        sender.sendMessage(Lang.get(sender, "holograms.colors-list",
                "colors", String.join(", ", BigText.SUGGESTED_COLORS),
                "gradients", String.join(", ", BigText.SUGGESTED_GRADIENTS)));
    }

    /**
     * {@code /cartel importar [radio] [simular]}: los TextDisplay hechos a mano alrededor se
     * convierten en carteles gestionados (las parejas cara + copia girada, en uno de doble
     * cara) y se borran los originales. Con "simular" solo se enseña el plan.
     */
    private void importDisplays(CommandSender sender, String label, String[] args) {
        Player player = player(sender);
        if (player == null) return;
        double radius = 64;
        boolean simulate = false;
        for (String arg : args) {
            if (SIMULATE_WORDS.contains(arg.toLowerCase(Locale.ROOT))) {
                simulate = true;
                continue;
            }
            Double parsed = HoloOptions.number(arg);
            if (parsed == null || parsed < 1 || parsed > 256) {
                sender.sendMessage(Lang.get(sender, "holograms.bad-number"));
                return;
            }
            radius = parsed;
        }
        Map<UUID, TextDisplay> entities = new HashMap<>();
        List<DisplayImport.Found> found = new ArrayList<>();
        int tooLong = 0;
        for (TextDisplay display : player.getWorld().getNearbyEntitiesByType(
                TextDisplay.class, player.getLocation(), radius)) {
            if (!importable(display)) continue;
            DisplayImport.Found read = read(display);
            // Un texto que no cabe en una linea se cortaria a mitad de una etiqueta y el
            // original se borraria igual: se deja como esta en vez de perderlo.
            if (!DisplayImport.fits(read)) {
                tooLong++;
                continue;
            }
            entities.put(display.getUniqueId(), display);
            found.add(read);
        }
        List<DisplayImport.Planned> plan = DisplayImport.plan(DisplayImport.group(found),
                takenIds());
        String shownRadius = HoloOptions.format(radius);
        if (plan.isEmpty()) {
            sender.sendMessage(Lang.get(sender, "holograms.import-none", "radius", shownRadius));
            if (tooLong > 0) {
                sender.sendMessage(Lang.get(sender, "holograms.import-too-long", "count", tooLong));
            }
            return;
        }
        if (simulate) {
            sender.sendMessage(Lang.get(sender, "holograms.import-sim-header",
                    "count", plan.size(), "radius", shownRadius));
            for (DisplayImport.Planned planned : plan) {
                sender.sendMessage(importEntry(sender, planned));
            }
            sender.sendMessage(Lang.get(sender, "holograms.import-sim-hint", "label", label,
                    "radius", shownRadius));
            if (tooLong > 0) {
                sender.sendMessage(Lang.get(sender, "holograms.import-too-long", "count", tooLong));
            }
            return;
        }
        List<String> imported = new ArrayList<>();
        int skipped = 0;
        BedrockMode bedrock = service().settings().defaultBedrock();
        for (DisplayImport.Planned planned : plan) {
            if (service().full() || !service().create(planned.hologram().withBedrock(bedrock))) {
                skipped++;
                continue;
            }
            // Las originales solo se borran cuando el cartel nuevo ya existe.
            for (UUID uuid : planned.group().entities()) {
                TextDisplay original = entities.get(uuid);
                if (original != null && original.isValid()) original.remove();
            }
            imported.add(planned.hologram().id());
        }
        if (!imported.isEmpty()) {
            service().plugin().getLogger().info(sender.getName() + " imported "
                    + imported.size() + " hand-made text displays as holograms: "
                    + String.join(", ", imported));
        }
        sender.sendMessage(imported.isEmpty()
                ? Lang.get(sender, "holograms.import-none", "radius", shownRadius)
                : Lang.get(sender, "holograms.import-done", "count", imported.size(),
                        "ids", String.join(", ", imported)));
        if (skipped > 0) {
            sender.sendMessage(Lang.get(sender, "holograms.import-skipped", "count", skipped));
        }
        if (tooLong > 0) {
            sender.sendMessage(Lang.get(sender, "holograms.import-too-long", "count", tooLong));
        }
    }

    private Component importEntry(CommandSender viewer, DisplayImport.Planned planned) {
        Hologram hologram = planned.hologram();
        Hologram.Position p = hologram.position();
        return Lang.get(viewer, "holograms.import-entry", "id", hologram.id(),
                "text", MM.escape(planned.group().front().plain()),
                "x", HoloOptions.format(round(p.x())), "y", HoloOptions.format(round(p.y())),
                "z", HoloOptions.format(round(p.z())),
                "scale", HoloOptions.format(hologram.look().scale()),
                "kind", Lang.raw(viewer, planned.group().doubleSided()
                        ? "holograms.import-kind-double" : "holograms.import-kind-single"));
    }

    /**
     * Solo lo hecho a mano: persistente, suelto (un nametag va montado en un jugador), sin la
     * marca de Chatalyst ni etiquetas de Chatalyst, y con algo escrito.
     *
     * <p>Y sin NINGUN dato de plugin: un {@code /summon} no escribe en el contenedor de datos
     * de la entidad, y un plugin que gestiona sus propios TextDisplay (tiendas, NPCs, tablas de
     * clasificacion) casi siempre si. Importar uno de esos lo borraria del mundo mientras su
     * plugin sigue creyendo que existe, asi que ante la duda no se toca.
     */
    private boolean importable(TextDisplay display) {
        if (!display.isValid() || !display.isPersistent() || display.getVehicle() != null
                || !display.getPassengers().isEmpty()) {
            return false;
        }
        if (service().isOurs(display)) return false;
        if (!display.getPersistentDataContainer().isEmpty()) return false;
        for (String tag : display.getScoreboardTags()) {
            if (tag.toLowerCase(Locale.ROOT).startsWith("chatalyst")) return false;
        }
        return !PlainTextComponentSerializer.plainText().serialize(display.text()).isBlank();
    }

    private static DisplayImport.Found read(TextDisplay display) {
        Location at = display.getLocation();
        Transformation transformation = display.getTransformation();
        Vector3f translation = transformation.getTranslation();
        Vector3f scale = transformation.getScale();
        // Un desplazamiento propio de la entidad se lleva a la posicion: el cartel nuevo
        // queda donde se veia el viejo.
        double[] offset = DisplayImport.localToWorld(translation.x(), translation.z(), at.getYaw());
        Integer background = display.isDefaultBackground() || display.getBackgroundColor() == null
                ? null : display.getBackgroundColor().asARGB();
        Display.Brightness brightness = display.getBrightness();
        String plain = PlainTextComponentSerializer.plainText().serialize(display.text()).strip();
        HoloLook.Billboard billboard = switch (display.getBillboard()) {
            case FIXED -> HoloLook.Billboard.FIXED;
            case VERTICAL -> HoloLook.Billboard.VERTICAL;
            case HORIZONTAL -> HoloLook.Billboard.HORIZONTAL;
            case CENTER -> HoloLook.Billboard.CENTER;
        };
        HoloLook.Alignment alignment = switch (display.getAlignment()) {
            case LEFT -> HoloLook.Alignment.LEFT;
            case RIGHT -> HoloLook.Alignment.RIGHT;
            case CENTER -> HoloLook.Alignment.CENTER;
        };
        return new DisplayImport.Found(display.getUniqueId(), at.getWorld().getName(),
                at.getX() + offset[0], at.getY() + translation.y(), at.getZ() + offset[1],
                at.getYaw(), MM.serialize(display.text()), plain, display.getScoreboardTags(),
                Math.max(scale.x(), Math.max(scale.y(), scale.z())), billboard, background,
                display.isShadowed(), brightness == null ? -1
                        : Math.max(brightness.getBlockLight(), brightness.getSkyLight()),
                alignment, display.getLineWidth(), display.getTextOpacity(),
                display.getViewRange(), display.isSeeThrough());
    }

    // ---- Imagenes: mural de mapas y pixel art ---------------------------------------------

    static final String URL_PERMISSION = "chatalyst.hologram.image.url";

    private MuralService murals() {
        return module.murals();
    }

    /** Ids ocupados por carteles y por murales (comparten espacio de nombres). */
    private java.util.Set<String> takenIds() {
        java.util.Set<String> all = new java.util.TreeSet<>(service().ids());
        if (murals() != null) all.addAll(murals().ids());
        return all;
    }

    private boolean muralTaken(CommandSender sender, String id) {
        if (murals() == null || murals().get(id) == null) return false;
        sender.sendMessage(Lang.get(sender, "holograms.exists", "id", id));
        return true;
    }

    /** El mural de ese id, solo si no hay un cartel que se llame igual. */
    private Mural muralOnly(String[] args) {
        if (args.length < 1 || murals() == null || service().get(args[0]) != null) return null;
        return murals().get(args[0]);
    }

    /** La fuente escrita, con su permiso si es una URL; null (ya avisado) si no vale. */
    private ImageSource imageSource(CommandSender sender, String raw) {
        ImageSource source = ImageSource.parse(raw);
        if (source == null) {
            sender.sendMessage(Lang.get(sender, "holograms.image-invalid", "value", MM.escape(raw)));
            return null;
        }
        if (source.url()) {
            if (!module.images().settings().urlEnabled()) {
                sender.sendMessage(Lang.get(sender, "holograms.image-url-disabled"));
                return null;
            }
            if (!StaffAccess.has(sender, URL_PERMISSION)) {
                sender.sendMessage(Lang.get(sender, "holograms.image-url-permission"));
                return null;
            }
        }
        return source;
    }

    /**
     * {@code /cartel mural <id> <imagen> <ancho>x<alto> [ajustar|recortar|estirar] [brillo]
     * [dither|sindither]}: mirando a un bloque, la imagen en marcos con mapas.
     */
    private void mural(CommandSender sender, String label, String[] args) {
        Player player = player(sender);
        if (player == null) return;
        String usage = "mural <id> <imagen> <ancho>x<alto> [ajustar|recortar|estirar] [brillo] [dither|sindither]";
        if (args.length < 3 || murals() == null) {
            usage(sender, label, usage);
            return;
        }
        String id = args[0].toLowerCase(Locale.ROOT);
        if (!Hologram.validId(id)) {
            sender.sendMessage(Lang.get(sender, "holograms.invalid-id"));
            return;
        }
        if (service().get(id) != null || murals().get(id) != null) {
            sender.sendMessage(Lang.get(sender, "holograms.exists", "id", id));
            return;
        }
        ImageSource source = imageSource(sender, args[1]);
        if (source == null) return;
        ImageSettings limits = module.images().settings();
        MuralLayout.Size size = MuralLayout.parseSize(args[2], limits.muralMaxSide());
        if (size == null || size.tiles() > limits.muralMaxTiles()) {
            sender.sendMessage(Lang.get(sender, "holograms.mural-size",
                    "side", limits.muralMaxSide(), "maps", limits.muralMaxTiles()));
            return;
        }
        ImageResize.Fit fit = ImageResize.Fit.CONTAIN;
        boolean glow = false;
        boolean dither = limits.ditherDefault();
        for (int i = 3; i < args.length; i++) {
            String option = args[i].toLowerCase(Locale.ROOT);
            ImageResize.Fit parsed = ImageResize.Fit.parse(option);
            if (parsed != null) {
                fit = parsed;
            } else if (List.of("brillo", "glow", "brillante", "luminoso").contains(option)) {
                glow = true;
            } else if (List.of("dither", "tramado").contains(option)) {
                dither = true;
            } else if (List.of("sindither", "nodither", "plano").contains(option)) {
                dither = false;
            } else {
                usage(sender, label, usage);
                return;
            }
        }
        Mural mural = aimedMural(player, id, size, source, fit, dither, glow, List.of());
        if (mural == null) return;
        String shown = size.width() + "x" + size.height();
        boolean created = murals().create(mural, failure -> notifyMural(player, id, failure));
        if (!created) {
            sender.sendMessage(Lang.get(sender, "holograms.exists", "id", id));
            return;
        }
        sender.sendMessage(Lang.get(sender, "holograms.mural-created", "id", id,
                "size", shown, "maps", size.tiles(), "label", label));
    }

    /**
     * El mural que quedaria en el bloque al que mira el jugador (cara pulsada, mirando hacia
     * fuera), o null si no se puede (ya se le dijo por que).
     */
    private Mural aimedMural(Player player, String id, MuralLayout.Size size, ImageSource source,
                             ImageResize.Fit fit, boolean dither, boolean glow,
                             List<Integer> mapIds) {
        org.bukkit.block.Block target = player.getTargetBlockExact(10);
        org.bukkit.block.BlockFace face = player.getTargetBlockFace(10);
        MuralLayout.Facing facing = face == null ? null : MuralLayout.Facing.parse(face.name());
        if (target == null || facing == null) {
            player.sendMessage(Lang.get(player, "holograms.mural-no-target"));
            return null;
        }
        MuralLayout.Facing up = MuralLayout.Facing.parse(player.getFacing().name());
        org.bukkit.block.Block anchor = target.getRelative(face);
        Mural mural = new Mural(id, anchor.getWorld().getName(), anchor.getX(), anchor.getY(),
                anchor.getZ(), facing, up, size.width(), size.height(), source, fit, dither, glow,
                mapIds);
        int blocked = blockedTiles(mural, anchor.getWorld(), face.getOppositeFace());
        if (blocked > 0) {
            player.sendMessage(Lang.get(player, "holograms.mural-blocked", "count", blocked,
                    "maps", size.tiles()));
            return null;
        }
        return mural;
    }

    /** Marcos que no caben: hueco ocupado por un bloque solido o sin pared/suelo detras. */
    private static int blockedTiles(Mural mural, org.bukkit.World world,
                                    org.bukkit.block.BlockFace behind) {
        int blocked = 0;
        for (int[] b : mural.blocks()) {
            org.bukkit.block.Block spot = world.getBlockAt(b[0], b[1], b[2]);
            org.bukkit.block.Block support = spot.getRelative(behind);
            if (spot.getType().isSolid() || !support.getType().isSolid()) blocked++;
        }
        return blocked;
    }

    private void notifyMural(Player player, String id, String failure) {
        if (!player.isOnline()) return;
        java.util.function.Consumer<Player> send = p -> p.sendMessage(failure == null
                ? Lang.get(p, "holograms.mural-ready", "id", id)
                : Lang.get(p, "holograms.image-failed", "id", id, "reason", MM.escape(failure)));
        dev.chatalyst.util.FoliaTasks.entity(service().plugin(), player, send);
    }

    private void moveMural(Player player, Mural mural) {
        Mural aimed = aimedMural(player, mural.id(), mural.size(), mural.source(), mural.fit(),
                mural.dither(), mural.glow(), mural.mapIds());
        if (aimed == null) return;
        Mural moved = mural.movedTo(aimed.world(), aimed.x(), aimed.y(), aimed.z(), aimed.face(),
                aimed.up());
        murals().move(moved, failure -> notifyMural(player, mural.id(), failure));
        player.sendMessage(Lang.get(player, "holograms.moved", "id", mural.id()));
    }

    /** Delante del mural (o encima, si esta en el suelo), mirandolo. */
    private void teleportToMural(Player player, Mural mural) {
        Location at = murals().locationOf(mural);
        if (at == null) {
            player.sendMessage(Lang.get(player, "holograms.world-missing",
                    "world", MM.escape(mural.world())));
            return;
        }
        MuralLayout.Facing face = mural.face();
        double distance = 2 + Math.max(mural.width(), mural.height()) * 0.6;
        Location destination = at.clone().add(face.dx * distance, face.dy * 2.0, face.dz * distance);
        if (face.horizontal()) {
            destination.setDirection(at.toVector().subtract(destination.toVector()));
            destination.setPitch(0f);
        } else {
            destination.setPitch(face == MuralLayout.Facing.UP ? 70f : -70f);
        }
        player.teleportAsync(destination);
        player.sendMessage(Lang.get(player, "holograms.teleported", "id", mural.id()));
    }

    private void muralInfo(CommandSender sender, Mural mural) {
        String state = mural.mapIds().isEmpty() || !murals().ready(mural.id())
                ? (murals().failure(mural.id()) != null
                        ? MM.escape(murals().failure(mural.id()))
                        : Lang.raw(sender, "holograms.mural-loading"))
                : Lang.raw(sender, "holograms.mural-ok");
        sender.sendMessage(Lang.get(sender, "holograms.mural-info", "id", mural.id(),
                "world", MM.escape(mural.world()), "x", mural.x(), "y", mural.y(), "z", mural.z(),
                "size", mural.width() + "x" + mural.height(),
                "image", MM.escape(mural.source().shown()),
                "face", mural.face().name().toLowerCase(Locale.ROOT), "state", state));
    }

    private void listMurals(CommandSender sender) {
        if (murals() == null) return;
        var all = murals().all();
        if (all.isEmpty()) return;
        sender.sendMessage(Lang.get(sender, "holograms.murals-header", "count", all.size()));
        for (Mural mural : all) {
            sender.sendMessage(Lang.get(sender, "holograms.mural-entry", "id", mural.id(),
                            "world", MM.escape(mural.world()), "x", mural.x(), "y", mural.y(),
                            "z", mural.z(), "size", mural.width() + "x" + mural.height(),
                            "image", MM.escape(mural.source().shown()))
                    .hoverEvent(HoverEvent.showText(Lang.get(sender, "holograms.list-entry-hover",
                            "id", mural.id())))
                    .clickEvent(ClickEvent.runCommand("/chatalyst:" + module.label() + " tp "
                            + mural.id())));
        }
    }

    /**
     * {@code /cartel imagen <id> <imagen> [ancho]}: añade una linea de pixel art al cartel, o
     * crea el cartel con ella si no existe.
     */
    private void image(CommandSender sender, String label, String[] args) {
        String usage = "imagen <id> <imagen> [ancho]";
        if (args.length < 2) {
            usage(sender, label, usage);
            return;
        }
        String id = args[0].toLowerCase(Locale.ROOT);
        ImageSource source = imageSource(sender, args[1]);
        if (source == null) return;
        Integer width = null;
        if (args.length > 2) {
            Double parsed = HoloOptions.number(args[2]);
            if (parsed == null || parsed < 1 || parsed != Math.rint(parsed)) {
                sender.sendMessage(Lang.get(sender, "holograms.bad-number"));
                return;
            }
            width = parsed.intValue();
        }
        ImageSettings limits = module.images().settings();
        int shownWidth = limits.pixelWidth(width);
        HoloLine line = new HoloLine(HoloLine.Kind.IMAGE,
                new ImageSource.PixelLine(source, shownWidth).encode(), null);
        Hologram existing = service().get(id);
        if (existing != null) {
            Hologram next = existing.addLine(line);
            if (next == null) {
                sender.sendMessage(Lang.get(sender, "holograms.too-many-lines",
                        "max", Hologram.MAX_LINES));
                return;
            }
            service().update(id, h -> next);
            sender.sendMessage(Lang.get(sender, "holograms.image-added", "id", id,
                    "line", next.lines().size(), "width", shownWidth));
            return;
        }
        Player player = player(sender);
        if (player == null) return;
        if (!Hologram.validId(id)) {
            sender.sendMessage(Lang.get(sender, "holograms.invalid-id"));
            return;
        }
        if (muralTaken(sender, id)) return;
        if (service().full()) {
            sender.sendMessage(Lang.get(sender, "holograms.full",
                    "max", service().settings().maxHolograms()));
            return;
        }
        Location at = player.getLocation();
        Hologram hologram = Hologram.create(id, position(at, at.getYaw() + 180f), "")
                .withLines(List.of(line));
        if (!service().create(hologram)) {
            sender.sendMessage(Lang.get(sender, "holograms.exists", "id", id));
            return;
        }
        sender.sendMessage(Lang.get(sender, "holograms.image-created", "id", id,
                "width", shownWidth, "label", label));
    }

    private List<String> imageSuggestions(String input) {
        ImageLibrary library = module.images();
        return library == null ? List.of() : prefix(input, library.names());
    }

    private void delete(CommandSender sender, String label, String[] args) {
        Mural mural = muralOnly(args);
        if (mural != null) {
            murals().delete(mural.id());
            sender.sendMessage(Lang.get(sender, "holograms.deleted", "id", mural.id()));
            return;
        }
        Hologram hologram = target(sender, label, args, "borrar <id>");
        if (hologram == null) return;
        service().delete(hologram.id());
        sender.sendMessage(Lang.get(sender, "holograms.deleted", "id", hologram.id()));
    }

    private void teleport(CommandSender sender, String label, String[] args) {
        Player player = player(sender);
        if (player == null) return;
        Mural mural = muralOnly(args);
        if (mural != null) {
            teleportToMural(player, mural);
            return;
        }
        Hologram hologram = target(sender, label, args, "tp <id>");
        if (hologram == null) return;
        teleport(player, hologram, service());
    }

    /** Delante del cartel, mirandolo (y no dentro de el). */
    static void teleport(Player player, Hologram hologram, HologramService service) {
        Location at = service == null ? null : service.locationOf(hologram);
        if (at == null) {
            player.sendMessage(Lang.get(player, "holograms.world-missing",
                    "world", MM.escape(hologram.position().world())));
            return;
        }
        double distance = 2.5 + hologram.look().scale();
        double yaw = Math.toRadians(hologram.position().yaw());
        Location destination = at.clone().add(-Math.sin(yaw) * distance, 0,
                Math.cos(yaw) * distance);
        destination.setYaw(Hologram.normalizeYaw(hologram.position().yaw() + 180f));
        destination.setPitch(0f);
        player.teleportAsync(destination);
        player.sendMessage(Lang.get(player, "holograms.teleported", "id", hologram.id()));
    }

    private void move(CommandSender sender, String label, String[] args) {
        Player player = player(sender);
        if (player == null) return;
        Mural mural = muralOnly(args);
        if (mural != null) {
            moveMural(player, mural);
            return;
        }
        Hologram hologram = target(sender, label, args, "mover <id>");
        if (hologram == null) return;
        Location at = player.getLocation();
        service().update(hologram.id(), h -> h.withPosition(position(at, at.getYaw() + 180f)));
        sender.sendMessage(Lang.get(sender, "holograms.moved", "id", hologram.id()));
    }

    private void rotate(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            usage(sender, label, "girar <id> <grados|aqui>");
            return;
        }
        Hologram hologram = target(sender, label, args, "girar <id> <grados|aqui>");
        if (hologram == null) return;
        Float yaw;
        String value = args[1].toLowerCase(Locale.ROOT);
        if ((value.equals("aqui") || value.equals("aquí") || value.equals("here"))
                && sender instanceof Player player) {
            // Que mire hacia quien lo esta girando.
            yaw = player.getLocation().getYaw() + 180f;
        } else {
            Double parsed = HoloOptions.number(value);
            yaw = parsed == null ? null : parsed.floatValue();
        }
        if (yaw == null) {
            sender.sendMessage(Lang.get(sender, "holograms.bad-number"));
            return;
        }
        float next = Hologram.normalizeYaw(yaw);
        service().update(hologram.id(), h -> h.withPosition(h.position().withYaw(next)));
        sender.sendMessage(Lang.get(sender, "holograms.rotated", "id", hologram.id(),
                "yaw", HoloOptions.format(next)));
    }

    private void copy(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            usage(sender, label, "copiar <id> <nuevo>");
            return;
        }
        Hologram hologram = target(sender, label, args, "copiar <id> <nuevo>");
        if (hologram == null) return;
        String id = args[1].toLowerCase(Locale.ROOT);
        if (!Hologram.validId(id)) {
            sender.sendMessage(Lang.get(sender, "holograms.invalid-id"));
            return;
        }
        if (muralTaken(sender, id)) return;
        if (service().full()) {
            sender.sendMessage(Lang.get(sender, "holograms.full",
                    "max", service().settings().maxHolograms()));
            return;
        }
        Hologram copy = hologram.withId(id);
        // La copia aparece donde esta quien copia; desde la consola, encima del original.
        if (sender instanceof Player player) {
            Location at = player.getLocation();
            copy = copy.withPosition(position(at, at.getYaw() + 180f));
        }
        if (!service().create(copy)) {
            sender.sendMessage(Lang.get(sender, "holograms.exists", "id", id));
            return;
        }
        sender.sendMessage(Lang.get(sender, "holograms.copied", "id", hologram.id(), "new", id));
    }

    // ---- Lineas ------------------------------------------------------------------------------

    private void setLine(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            usage(sender, label, "linea <id> <n> <texto>");
            return;
        }
        Hologram hologram = target(sender, label, args, "linea <id> <n> <texto>");
        Integer n = hologram == null ? null : lineNumber(sender, args[1]);
        if (n == null) return;
        Hologram next = hologram.setLine(n, HoloLine.parse(join(args, 2)));
        if (next == null) {
            sender.sendMessage(Lang.get(sender, "holograms.bad-line", "max", hologram.lines().size()));
            return;
        }
        service().update(hologram.id(), h -> next);
        sender.sendMessage(Lang.get(sender, "holograms.line-set", "id", hologram.id(), "line", n));
    }

    private void addLine(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            usage(sender, label, "anadir <id> <texto|item:material>");
            return;
        }
        Hologram hologram = target(sender, label, args, "anadir <id> <texto>");
        if (hologram == null) return;
        Hologram next = hologram.addLine(HoloLine.parse(join(args, 1)));
        if (next == null) {
            sender.sendMessage(Lang.get(sender, "holograms.too-many-lines", "max", Hologram.MAX_LINES));
            return;
        }
        service().update(hologram.id(), h -> next);
        sender.sendMessage(Lang.get(sender, "holograms.line-added", "id", hologram.id(),
                "line", next.lines().size()));
    }

    private void insertLine(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            usage(sender, label, "insertar <id> <n> <texto>");
            return;
        }
        Hologram hologram = target(sender, label, args, "insertar <id> <n> <texto>");
        Integer n = hologram == null ? null : lineNumber(sender, args[1]);
        if (n == null) return;
        Hologram next = hologram.insertLine(n, HoloLine.parse(join(args, 2)));
        if (next == null) {
            sender.sendMessage(Lang.get(sender, "holograms.bad-line",
                    "max", hologram.lines().size() + 1));
            return;
        }
        service().update(hologram.id(), h -> next);
        sender.sendMessage(Lang.get(sender, "holograms.line-inserted", "id", hologram.id(),
                "line", n));
    }

    private void removeLine(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            usage(sender, label, "quitar <id> <n>");
            return;
        }
        Hologram hologram = target(sender, label, args, "quitar <id> <n>");
        Integer n = hologram == null ? null : lineNumber(sender, args[1]);
        if (n == null) return;
        if (hologram.lines().size() == 1) {
            sender.sendMessage(Lang.get(sender, "holograms.last-line", "label", label,
                    "id", hologram.id()));
            return;
        }
        Hologram next = hologram.removeLine(n);
        if (next == null) {
            sender.sendMessage(Lang.get(sender, "holograms.bad-line", "max", hologram.lines().size()));
            return;
        }
        service().update(hologram.id(), h -> next);
        sender.sendMessage(Lang.get(sender, "holograms.line-removed", "id", hologram.id(),
                "line", n));
    }

    // ---- Aspecto y movimiento ----------------------------------------------------------------

    private void option(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            usage(sender, label, "opcion <id> <opcion> <valor>");
            options(sender);
            return;
        }
        Hologram hologram = target(sender, label, args, "opcion <id> <opcion> <valor>");
        if (hologram == null) return;
        HoloOptions.Result result = HoloOptions.apply(hologram, args[1], join(args, 2));
        if (!result.ok()) {
            sender.sendMessage(Lang.get(sender, result.error(),
                    "option", MM.escape(args[1]), "value", MM.escape(join(args, 2))));
            return;
        }
        Hologram next = result.hologram();
        service().update(hologram.id(), h -> next);
        HoloOptions.Option option = HoloOptions.find(args[1]);
        sender.sendMessage(Lang.get(sender, "holograms.option-set", "id", hologram.id(),
                "option", option == null ? MM.escape(args[1]) : option.name(),
                "value", MM.escape(result.shown())));
        if (option != null && option.name().equals("doblecara") && next.look().doubleSided()
                && next.look().billboard() != HoloLook.Billboard.FIXED) {
            sender.sendMessage(Lang.get(sender, "holograms.double-sided-hint", "label", label,
                    "id", hologram.id()));
        }
    }

    private void options(CommandSender sender) {
        List<String> names = HoloOptions.OPTIONS.stream().map(HoloOptions.Option::name).toList();
        sender.sendMessage(Lang.get(sender, "holograms.options-list",
                "options", String.join(", ", names)));
    }

    private void animate(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            usage(sender, label, "animar <id> [linea] <animacion>");
            sender.sendMessage(Lang.get(sender, "holograms.animations-list",
                    "animations", String.join(", ", HoloAnimation.NAMES)));
            return;
        }
        Hologram hologram = target(sender, label, args, "animar <id> [linea] <animacion>");
        if (hologram == null) return;
        boolean perLine = args.length >= 3;
        String name = HoloAnimation.normalize(perLine ? args[2] : args[1]);
        if (name == null) {
            sender.sendMessage(Lang.get(sender, "holograms.option-animation",
                    "value", MM.escape(perLine ? args[2] : args[1])));
            return;
        }
        if (!perLine) {
            service().update(hologram.id(), h -> h.withMotion(h.motion().withAnimation(name)));
            sender.sendMessage(Lang.get(sender, "holograms.animated", "id", hologram.id(),
                    "animation", name));
            return;
        }
        Integer n = lineNumber(sender, args[1]);
        if (n == null) return;
        if (n > hologram.lines().size()) {
            sender.sendMessage(Lang.get(sender, "holograms.bad-line", "max", hologram.lines().size()));
            return;
        }
        HoloLine line = hologram.lines().get(n - 1);
        // "none" en una linea es "como el resto del cartel": se quita la propia.
        HoloLine next = line.withAnimation("none".equals(name) ? null : name);
        List<HoloLine> lines = new ArrayList<>(hologram.lines());
        lines.set(n - 1, next);
        service().update(hologram.id(), h -> h.withLines(lines));
        sender.sendMessage(Lang.get(sender, "holograms.animated-line", "id", hologram.id(),
                "line", n, "animation", name));
    }

    private void preset(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            usage(sender, label, "preset <id> <preset>");
            presets(sender);
            return;
        }
        Hologram hologram = target(sender, label, args, "preset <id> <preset>");
        if (hologram == null) return;
        String presetId = args[1].toLowerCase(Locale.ROOT);
        Map<String, String> preset = service().settings().presets().get(presetId);
        if (preset == null) {
            sender.sendMessage(Lang.get(sender, "holograms.preset-unknown",
                    "preset", MM.escape(presetId)));
            presets(sender);
            return;
        }
        HoloPresets.Applied applied = HoloPresets.apply(hologram, preset);
        service().update(hologram.id(), h -> applied.hologram());
        sender.sendMessage(Lang.get(sender, "holograms.preset-applied", "id", hologram.id(),
                "preset", presetId));
        if (!applied.rejected().isEmpty()) {
            sender.sendMessage(Lang.get(sender, "holograms.preset-rejected",
                    "preset", presetId, "options", MM.escape(String.join(", ", applied.rejected()))));
        }
    }

    private void presets(CommandSender sender) {
        var presets = service().settings().presets().keySet();
        sender.sendMessage(presets.isEmpty()
                ? Lang.get(sender, "holograms.presets-empty")
                : Lang.get(sender, "holograms.presets-list",
                        "presets", String.join(", ", new java.util.TreeSet<>(presets))));
    }

    // ---- Clic ------------------------------------------------------------------------------

    private void action(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            usage(sender, label, "accion <id> <ninguna|jugador|consola|mensaje> [valor]");
            return;
        }
        if (!StaffAccess.has(sender, ACTION_PERMISSION)) {
            sender.sendMessage(Lang.get(sender, "holograms.action-no-permission"));
            return;
        }
        Hologram hologram = target(sender, label, args, "accion <id> <tipo> [valor]");
        if (hologram == null) return;
        HoloAction.Type type = HoloAction.parseType(args[1]);
        if (type == null) {
            sender.sendMessage(Lang.get(sender, "holograms.action-type",
                    "value", MM.escape(args[1])));
            return;
        }
        if (type == HoloAction.Type.CONSOLE && !StaffAccess.has(sender, CONSOLE_ACTION_PERMISSION)) {
            sender.sendMessage(Lang.get(sender, "holograms.action-no-permission"));
            return;
        }
        String value = join(args, 2);
        if (type != HoloAction.Type.NONE && value.isBlank()) {
            sender.sendMessage(Lang.get(sender, "holograms.action-value"));
            return;
        }
        int cooldown = hologram.action().active() ? hologram.action().cooldown()
                : service().settings().defaultCooldown();
        HoloAction next = new HoloAction(type, value, cooldown);
        service().update(hologram.id(), h -> h.withAction(next));
        sender.sendMessage(next.active()
                ? Lang.get(sender, "holograms.action-set", "id", hologram.id(),
                        "type", type.name().toLowerCase(Locale.ROOT), "value", MM.escape(next.value()))
                : Lang.get(sender, "holograms.action-cleared", "id", hologram.id()));
    }

    private void cooldown(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            usage(sender, label, "espera <id> <segundos>");
            return;
        }
        Hologram hologram = target(sender, label, args, "espera <id> <segundos>");
        if (hologram == null) return;
        Double seconds = HoloOptions.number(args[1].replaceAll("(?i)s$", ""));
        if (seconds == null || seconds < 0 || seconds > 3600) {
            sender.sendMessage(Lang.get(sender, "holograms.bad-number"));
            return;
        }
        int value = seconds.intValue();
        service().update(hologram.id(), h -> h.withAction(h.action().withCooldown(value)));
        sender.sendMessage(Lang.get(sender, "holograms.cooldown-set", "id", hologram.id(),
                "seconds", value));
    }

    // ---- Consultas ----------------------------------------------------------------------------

    private void list(CommandSender sender, String[] args) {
        if (sender instanceof Player player) {
            int page = args.length > 0 && HoloOptions.number(args[0]) != null
                    ? Math.max(0, HoloOptions.number(args[0]).intValue() - 1) : 0;
            Menu.open(player, new HologramListMenu(module, page));
            listMurals(sender);
            return;
        }
        listMurals(sender);
        var all = service().all();
        if (all.isEmpty()) {
            sender.sendMessage(Lang.get(sender, "holograms.list-empty"));
            return;
        }
        sender.sendMessage(Lang.get(sender, "holograms.list-header", "count", all.size()));
        for (Hologram hologram : all) sender.sendMessage(entry(sender, hologram));
    }

    private void near(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) return;
        double radius = 30;
        if (args.length > 0) {
            Double parsed = HoloOptions.number(args[0]);
            if (parsed == null || parsed <= 0 || parsed > 1000) {
                sender.sendMessage(Lang.get(sender, "holograms.bad-number"));
                return;
            }
            radius = parsed;
        }
        List<Hologram> near = service().near(player.getLocation(), radius);
        if (near.isEmpty()) {
            sender.sendMessage(Lang.get(sender, "holograms.near-empty",
                    "radius", HoloOptions.format(radius)));
            return;
        }
        sender.sendMessage(Lang.get(sender, "holograms.near-header", "count", near.size(),
                "radius", HoloOptions.format(radius)));
        for (Hologram hologram : near) sender.sendMessage(entry(sender, hologram));
    }

    /** Una linea de lista: id, sitio y primera linea, con clic para ir y hover con info. */
    private Component entry(CommandSender viewer, Hologram hologram) {
        Hologram.Position p = hologram.position();
        Component line = Lang.get(viewer, "holograms.list-entry",
                "id", hologram.id(), "world", MM.escape(p.world()),
                "x", (int) Math.floor(p.x()), "y", (int) Math.floor(p.y()),
                "z", (int) Math.floor(p.z()), "lines", hologram.lines().size(),
                "preview", MM.escape(preview(hologram)));
        return line.hoverEvent(HoverEvent.showText(Lang.get(viewer, "holograms.list-entry-hover",
                        "id", hologram.id())))
                .clickEvent(ClickEvent.runCommand("/chatalyst:" + module.label() + " tp " + hologram.id()));
    }

    static String preview(Hologram hologram) {
        HoloLine first = hologram.lines().getFirst();
        String plain = first.isText() ? MM.strip(first.content()) : first.raw();
        return plain.length() > 32 ? plain.substring(0, 31) + "…" : plain;
    }

    private void info(CommandSender sender, String label, String[] args) {
        Mural mural = muralOnly(args);
        if (mural != null) {
            muralInfo(sender, mural);
            return;
        }
        Hologram hologram = target(sender, label, args, "info <id>");
        if (hologram == null) return;
        Hologram.Position p = hologram.position();
        HoloLook look = hologram.look();
        HoloMotion motion = hologram.motion();
        sender.sendMessage(Lang.get(sender, "holograms.info-header", "id", hologram.id(),
                "state", Lang.raw(sender, service().spawned(hologram.id())
                        ? "holograms.info-spawned" : "holograms.info-not-spawned")));
        sender.sendMessage(Lang.get(sender, "holograms.info-location",
                "world", MM.escape(p.world()), "x", HoloOptions.format(round(p.x())),
                "y", HoloOptions.format(round(p.y())), "z", HoloOptions.format(round(p.z())),
                "yaw", HoloOptions.format(round(p.yaw()))));
        for (int i = 0; i < hologram.lines().size(); i++) {
            HoloLine line = hologram.lines().get(i);
            int number = i + 1;
            Component row = Lang.get(sender, "holograms.info-line", "line", number,
                    "text", MM.escape(line.raw()),
                    "animation", line.animation() == null ? "-" : line.animation());
            sender.sendMessage(row.clickEvent(ClickEvent.suggestCommand(
                    "/" + label + " linea " + hologram.id() + " " + number + " " + line.raw())));
        }
        sender.sendMessage(Lang.get(sender, "holograms.info-look",
                "scale", HoloOptions.format(look.scale()), "background", MM.escape(look.background()),
                "shadow", onOff(look.shadow()), "opacity", look.opacity(),
                "brightness", look.brightness() < 0 ? "auto" : String.valueOf(look.brightness()),
                "alignment", look.alignment().name().toLowerCase(Locale.ROOT),
                "billboard", look.billboard().name().toLowerCase(Locale.ROOT),
                "double", onOff(look.doubleSided()), "range", look.viewRange()));
        sender.sendMessage(Lang.get(sender, "holograms.info-motion",
                "animation", motion.animation(), "speed", motion.speed(),
                "spin", HoloOptions.format(motion.spin()), "bob", HoloOptions.format(motion.bob()),
                "refresh", motion.refresh()));
        HoloAction action = hologram.action();
        sender.sendMessage(Lang.get(sender, "holograms.info-click",
                "type", action.type().name().toLowerCase(Locale.ROOT),
                "value", action.active() ? MM.escape(action.value()) : "-",
                "cooldown", action.cooldown(),
                "bedrock", hologram.bedrock().name().toLowerCase(Locale.ROOT)));
    }

    private void edit(CommandSender sender, String label, String[] args) {
        Player player = player(sender);
        if (player == null) return;
        Hologram hologram = target(sender, label, args, "editar <id>");
        if (hologram == null) return;
        Menu.open(player, new HologramEditorMenu(module, hologram.id()));
    }

    private void reload(CommandSender sender) {
        // Una imagen cambiada en images/ se vuelve a leer: se olvida la cache y los fallos.
        service().forgetImages();
        int count = service().reloadFromDisk()
                + (murals() == null ? 0 : murals().reloadFromDisk());
        sender.sendMessage(Lang.get(sender, "holograms.reloaded", "count", count));
    }

    private void help(CommandSender sender, String label) {
        for (String line : Lang.rawList(sender, "holograms.help", "label", label)) {
            sender.sendMessage(MM.full(line));
        }
    }

    // ---- Comunes ---------------------------------------------------------------------------

    private static Hologram.Position position(Location at, float facing) {
        return new Hologram.Position(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(),
                facing);
    }

    private Hologram target(CommandSender sender, String label, String[] args, String usage) {
        if (args.length < 1) {
            usage(sender, label, usage);
            return null;
        }
        Hologram hologram = service().get(args[0]);
        if (hologram == null) {
            sender.sendMessage(Lang.get(sender, "holograms.unknown", "id", MM.escape(args[0]),
                    "label", label));
        }
        return hologram;
    }

    private static Integer lineNumber(CommandSender sender, String raw) {
        Double number = HoloOptions.number(raw);
        if (number == null || number < 1 || number != Math.rint(number)) {
            sender.sendMessage(Lang.get(sender, "holograms.bad-number"));
            return null;
        }
        return number.intValue();
    }

    private static Player player(CommandSender sender) {
        if (sender instanceof Player player) return player;
        sender.sendMessage(Lang.get(sender, "common.players-only"));
        return null;
    }

    private static void usage(CommandSender sender, String label, String args) {
        sender.sendMessage(Lang.get(sender, "holograms.usage", "label", label,
                "args", MM.escape(args)));
    }

    private static String join(String[] args, int from) {
        return from >= args.length ? "" : String.join(" ", Arrays.copyOfRange(args, from, args.length));
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    // ---- Autocompletado ------------------------------------------------------------------------

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (!StaffAccess.has(sender, PERMISSION) || service() == null) return List.of();
        if (args.length == 1) return prefix(args[0], SHOWN_SUBCOMMANDS);
        String sub = SUBCOMMANDS.get(args[0].toLowerCase(Locale.ROOT));
        if (sub == null) return List.of();
        if (args.length == 2) {
            return switch (sub) {
                case "create", "big", "mural", "list", "near", "options", "presets", "reload", "help" -> List.of();
                case "import" -> prefix(args[1], List.of("64", "32", "128", "simular"));
                default -> prefix(args[1], List.copyOf(takenIds()));
            };
        }
        if (args.length == 3) {
            return switch (sub) {
                case "mural", "image" -> imageSuggestions(args[2]);
                case "big" -> colorSuggestions(args[2]);
                case "import" -> prefix(args[2], List.of("simular"));
                case "set" -> prefix(args[2], HoloOptions.OPTIONS.stream()
                        .map(HoloOptions.Option::name).toList());
                case "animate" -> prefix(args[2], HoloAnimation.NAMES);
                case "preset" -> prefix(args[2], List.copyOf(service().settings().presets().keySet()));
                case "action" -> prefix(args[2], List.of("ninguna", "jugador", "consola", "mensaje"));
                case "rotate" -> prefix(args[2], List.of("aqui", "0", "90", "180", "-90"));
                case "setline", "insertline", "removeline" -> {
                    Hologram hologram = service().get(args[1]);
                    List<String> numbers = new ArrayList<>();
                    if (hologram != null) {
                        for (int i = 1; i <= hologram.lines().size(); i++) numbers.add(String.valueOf(i));
                    }
                    yield prefix(args[2], numbers);
                }
                default -> List.of();
            };
        }
        if (args.length == 4 && sub.equals("mural")) {
            return prefix(args[3], List.of("1x1", "2x1", "2x2", "3x2", "4x3", "6x4"));
        }
        if (args.length >= 5 && sub.equals("mural")) {
            return prefix(args[args.length - 1], List.of("ajustar", "recortar", "estirar",
                    "brillo", "dither", "sindither"));
        }
        if (args.length == 4 && sub.equals("image")) {
            return prefix(args[3], List.of("16", "24", "32", "48", "64"));
        }
        if (args.length >= 5 && sub.equals("big")) {
            return prefix(args[args.length - 1], List.of("escala:7", "escala:4", "escala:12"));
        }
        if (args.length == 4 && sub.equals("set")) {
            return prefix(args[3], suggestions(args[2]));
        }
        if (args.length == 4 && sub.equals("animate")) return prefix(args[3], HoloAnimation.NAMES);
        if (args.length == 4 && sub.equals("setline")) {
            // El texto actual, para retocarlo en vez de reescribirlo entero.
            Hologram hologram = service().get(args[1]);
            Double n = HoloOptions.number(args[2]);
            if (hologram != null && n != null && n >= 1 && n <= hologram.lines().size()) {
                return List.of(hologram.lines().get(n.intValue() - 1).raw());
            }
        }
        return List.of();
    }

    /** Colores sueltos y, si ya se escribio "color-", el segundo color del degradado. */
    private static List<String> colorSuggestions(String input) {
        int dash = input.lastIndexOf('-');
        if (dash >= 0) {
            String head = input.substring(0, dash + 1);
            return prefix(input, BigText.SUGGESTED_COLORS.stream().map(c -> head + c).toList());
        }
        List<String> all = new ArrayList<>(BigText.SUGGESTED_COLORS);
        all.addAll(BigText.SUGGESTED_GRADIENTS);
        return prefix(input, all);
    }

    private static List<String> suggestions(String option) {
        HoloOptions.Option found = HoloOptions.find(option);
        if (found == null) return List.of();
        return switch (found.name()) {
            case "escala" -> List.of("0.5", "1", "2", "4", "gigante");
            case "fondo" -> List.of("default", "none", "#");
            case "sombra", "doblecara", "atraves" -> List.of("on", "off");
            case "opacidad" -> List.of("100", "75", "50");
            case "brillo" -> List.of("auto", "15", "10", "5");
            case "alineacion" -> List.of("centro", "izquierda", "derecha");
            case "orientacion" -> List.of("centro", "fijo", "vertical", "horizontal");
            case "animacion" -> HoloAnimation.NAMES;
            case "bedrock" -> List.of("geyser", "respaldo", "ocultar");
            case "rotacion" -> List.of("off", "lento", "rapido", "45");
            case "flotar" -> List.of("off", "suave", "0.2");
            case "separacion" -> List.of("2cm", "0.05");
            default -> List.of();
        };
    }

    private static List<String> prefix(String input, List<String> values) {
        String lower = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
