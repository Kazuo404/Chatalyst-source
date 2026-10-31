package dev.chatalyst.hologram;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Un cartel vivo en el mundo: sus entidades y como se repintan. Todo lo de aqui corre en el
 * hilo de la region del cartel (lo garantiza {@link HologramService}).
 *
 * <p>Un cartel tiene "vistas", cada una un juego de entidades para un publico:
 * <ul>
 *   <li>PUBLIC: lo ve todo el mundo (lo normal).</li>
 *   <li>BEDROCK: el respaldo de soportes de armadura, que solo ven los de Bedrock.</li>
 *   <li>Una por jugador cuando el texto lleva placeholders: cada uno ve sus valores.</li>
 * </ul>
 */
final class HologramInstance {

    /** Para quien es una vista. */
    enum Audience { PUBLIC, BEDROCK, PERSONAL }

    /** Un trozo del layout ya convertido en entidades. */
    private static final class Part {
        final HoloLayout.Segment segment;
        TextDisplay front;
        TextDisplay back;
        ItemDisplay item;
        /** Pixel art (linea de imagen) y su cara de atras. */
        TextDisplay image;
        TextDisplay imageBack;
        int lastImageFrame = -1;
        final List<ArmorStand> stands = new ArrayList<>();
        Component lastText;
        final List<Component> lastStandText = new ArrayList<>();
        /** Texto preparado y fotograma de cada linea en el ultimo pintado (null = nunca). */
        String[] lastPrepared;
        int[] lastFrame;
        /**
         * Fotogramas del ciclo de cada linea (0 = sin animacion). Solo depende del texto, asi
         * que se mide al cambiar este y no en cada tick: medirlo eran dos expresiones regulares
         * por linea animada y por repintado.
         */
        int[] cycle;

        Part(HoloLayout.Segment segment) {
            this.segment = segment;
        }
    }

    final class View {
        final Audience audience;
        final UUID viewer;
        final boolean bedrockViewer;
        final List<Part> parts = new ArrayList<>();
        /** Lineas ya preparadas (estilo + placeholders), recalculadas cada {@code refresh}. */
        String[] prepared;
        long preparedAt = -1_000_000L;

        View(Audience audience, UUID viewer, boolean bedrockViewer) {
            this.audience = audience;
            this.viewer = viewer;
            this.bedrockViewer = bedrockViewer;
        }

        void forEachEntity(java.util.function.Consumer<Entity> action) {
            for (Part part : parts) {
                if (part.front != null) action.accept(part.front);
                if (part.back != null) action.accept(part.back);
                if (part.item != null) action.accept(part.item);
                if (part.image != null) action.accept(part.image);
                if (part.imageBack != null) action.accept(part.imageBack);
                part.stands.forEach(action);
            }
        }
    }

    private final HologramService service;
    final Hologram hologram;
    final Location base;
    private final HoloLayout.Layout layout;
    private final boolean personal;
    /** Copia de hologram.animated(): se preguntaba cada tick recorriendo las lineas. */
    private final boolean animated;
    /** Ultimo tick en que se refrescaron placeholders (carteles personales sin animacion). */
    private volatile long lastRefresh = -1_000_000L;
    private final List<View> views = new ArrayList<>();
    private final Map<UUID, View> personalViews = new HashMap<>();
    private Interaction interaction;
    /** Hay alguien cerca: se anima. Lo decide la comprobacion de presencia. */
    volatile boolean active;
    volatile long lastPresence = -1_000_000L;
    /** Ultimo fotograma pintado de cada linea y del movimiento, para no reenviar lo mismo. */
    private long lastMotionTick = Long.MIN_VALUE;

    HologramInstance(HologramService service, Hologram hologram, World world) {
        this.service = service;
        this.hologram = hologram;
        Hologram.Position p = hologram.position();
        this.base = new Location(world, p.x(), p.y(), p.z(), p.yaw(), 0f);
        this.imageSettings = service.imageSettings();
        List<HoloLine.Kind> kinds = new ArrayList<>();
        List<Integer> lengths = new ArrayList<>();
        List<Integer> rows = new ArrayList<>();
        boolean anyPersonal = false;
        boolean anyAnimatedImage = false;
        this.images = new ImageLibrary.PixelArtImage[hologram.lines().size()];
        for (int i = 0; i < hologram.lines().size(); i++) {
            HoloLine line = hologram.lines().get(i);
            kinds.add(line.kind());
            if (line.isImage()) {
                // Ya cacheado, o null mientras carga (al llegar se reconstruye el cartel).
                ImageLibrary.PixelArtImage art = service.pixelArt(line);
                images[i] = art;
                lengths.add(art == null ? 8 : art.columns());
                rows.add(art == null ? 1 : art.rows());
                anyAnimatedImage |= art != null && art.animated();
                continue;
            }
            rows.add(0);
            String styled = line.isText()
                    ? HoloText.expandStyle(line.content(), service.settings().styles()) : "";
            lengths.add(line.isText() ? HoloText.visibleLength(styled) : 0);
            anyPersonal |= line.isText() && HoloText.personal(styled);
        }
        this.personal = anyPersonal;
        this.animated = hologram.animated() || anyAnimatedImage;
        this.layout = HoloLayout.of(kinds, lengths, rows, hologram.look().scale(),
                hologram.look().itemScale(), hologram.look().lineWidth(),
                imageSettings.pixelAdvance(), imageSettings.squareFactor());
    }

    /** Pixel art de cada linea de imagen (null en las demas, o si aun carga / fallo). */
    private final ImageLibrary.PixelArtImage[] images;
    private final ImageSettings imageSettings;

    boolean personal() {
        return personal;
    }

    // ---- Alta y baja ---------------------------------------------------------------------

    /** Crea las entidades compartidas. Llamar en el hilo de la region, con el chunk cargado. */
    void spawn(long tick) {
        if (!personal) {
            View main = buildView(Audience.PUBLIC, null, false);
            views.add(main);
            if (hologram.bedrock() == BedrockMode.STANDS && hasText()) {
                views.add(buildView(Audience.BEDROCK, null, true));
            }
        }
        if (hologram.action().active()) {
            interaction = base.getWorld().spawn(base, Interaction.class, entity -> {
                prepare(entity, "click", true);
                entity.setInteractionWidth((float) Math.max(0.5, layout.width()));
                entity.setInteractionHeight((float) layout.height());
                entity.setResponsive(true);
            });
            service.track(interaction, this, HologramService.Visibility.NORMAL);
        }
        render(tick, true);
    }

    /** Quita todas las entidades. Idempotente. */
    void remove() {
        for (View view : views) removeView(view);
        views.clear();
        personalViews.values().forEach(this::removeView);
        personalViews.clear();
        if (interaction != null) {
            service.untrack(interaction);
            interaction.remove();
            interaction = null;
        }
    }

    private void removeView(View view) {
        view.forEachEntity(entity -> {
            service.untrack(entity);
            entity.remove();
        });
        view.parts.clear();
    }

    // ---- Presencia (solo carteles con placeholders tienen vistas por jugador) ----------

    /** Ajusta las vistas personales a los jugadores que tiene cerca. */
    void syncViewers(List<Player> nearby, long tick) {
        if (!personal) return;
        java.util.Set<UUID> present = new java.util.HashSet<>();
        for (Player player : nearby) {
            UUID id = player.getUniqueId();
            present.add(id);
            if (personalViews.containsKey(id)) continue;
            boolean bedrock = HologramService.isBedrock(id);
            if (bedrock && hologram.bedrock() == BedrockMode.HIDDEN) continue;
            View view = buildView(Audience.PERSONAL, id, bedrock);
            personalViews.put(id, view);
            renderView(view, tick, true, true);
            view.forEachEntity(entity -> service.showTo(player, entity));
        }
        personalViews.entrySet().removeIf(entry -> {
            if (present.contains(entry.getKey())) return false;
            removeView(entry.getValue());
            return true;
        });
    }

    /**
     * El jugador se fue: su vista personal se quita YA. Esperar a la siguiente comprobacion de
     * presencia dejaba un hueco en el que, si volvia a entrar, la vista seguia registrada pero
     * el servidor ya habia olvidado que se la enseñaba (showEntity no sobrevive a la sesion):
     * no se le creaba otra y no veia el cartel hasta alejarse y volver.
     */
    void dropViewer(UUID viewer) {
        View view = personalViews.remove(viewer);
        if (view != null) removeView(view);
    }

    // ---- Construccion ----------------------------------------------------------------------

    private boolean hasText() {
        return hologram.lines().stream().anyMatch(HoloLine::isText);
    }

    private View buildView(Audience audience, UUID viewer, boolean bedrockViewer) {
        View view = new View(audience, viewer, bedrockViewer);
        boolean stands = bedrockViewer && hologram.bedrock() == BedrockMode.STANDS;
        boolean hiddenByDefault = audience != Audience.PUBLIC;
        for (HoloLayout.Segment segment : layout.segments()) {
            Part part = new Part(segment);
            if (segment.kind() == HoloLine.Kind.IMAGE) {
                // Pixel art: en Bedrock llega como un nombre flotante de bloques sin escala ni
                // interlineado (un borron), asi que a Bedrock no se le enseña nunca.
                if (audience == Audience.BEDROCK || bedrockViewer) continue;
                part.image = spawnImage(segment, false, hiddenByDefault);
                service.track(part.image, this, audience == Audience.PUBLIC
                        ? HologramService.Visibility.HIDE_FROM_BEDROCK
                        : HologramService.Visibility.NORMAL);
                if (hologram.look().doubleSided()
                        && hologram.look().billboard() == HoloLook.Billboard.FIXED) {
                    part.imageBack = spawnImage(segment, true, hiddenByDefault);
                    service.track(part.imageBack, this, audience == Audience.PUBLIC
                            ? HologramService.Visibility.HIDE_FROM_BEDROCK
                            : HologramService.Visibility.NORMAL);
                }
            } else if (segment.kind() == HoloLine.Kind.ITEM) {
                // Los objetos los pinta GeyserDisplayEntity tambien en Bedrock: la vista de
                // soportes no los repite.
                if (audience == Audience.BEDROCK) continue;
                part.item = spawnItem(segment, hiddenByDefault);
                service.track(part.item, this, audience == Audience.PUBLIC
                        && hologram.bedrock() == BedrockMode.HIDDEN
                        ? HologramService.Visibility.HIDE_FROM_BEDROCK
                        : HologramService.Visibility.NORMAL);
            } else if (stands) {
                for (int line = segment.from(); line < segment.to(); line++) {
                    ArmorStand stand = spawnStand(segment, line);
                    part.stands.add(stand);
                    service.track(stand, this, audience == Audience.BEDROCK
                            ? HologramService.Visibility.BEDROCK_ONLY
                            : HologramService.Visibility.NORMAL);
                }
            } else {
                part.front = spawnText(false, hiddenByDefault);
                boolean javaOnlyText = audience == Audience.PUBLIC
                        && hologram.bedrock() != BedrockMode.GEYSER;
                service.track(part.front, this, javaOnlyText
                        ? HologramService.Visibility.HIDE_FROM_BEDROCK
                        : HologramService.Visibility.NORMAL);
                // La cara de atras: a Bedrock le llega como un segundo nombre flotante encima
                // del primero, asi que a el nunca se le enseña.
                // Y solo con el cartel plano: si el texto mira siempre al jugador, las dos caras
                // le mirarian a la vez y se pisarian.
                if (hologram.look().doubleSided() && !bedrockViewer
                        && hologram.look().billboard() == HoloLook.Billboard.FIXED) {
                    part.back = spawnText(true, hiddenByDefault);
                    service.track(part.back, this, audience == Audience.PUBLIC
                            ? HologramService.Visibility.HIDE_FROM_BEDROCK
                            : HologramService.Visibility.NORMAL);
                }
            }
            view.parts.add(part);
        }
        return view;
    }

    private TextDisplay spawnText(boolean backFace, boolean hiddenByDefault) {
        HoloLook look = hologram.look();
        Location at = base.clone();
        if (backFace) at.setYaw(Hologram.normalizeYaw(base.getYaw() + 180f));
        return base.getWorld().spawn(at, TextDisplay.class, display -> {
            prepare(display, backFace ? "back" : "front", !hiddenByDefault);
            display.setBillboard(billboard(look.billboard()));
            display.setLineWidth(look.lineWidth());
            Integer argb = HoloLook.backgroundArgb(look.background());
            if (argb == null) {
                display.setDefaultBackground(true);
            } else {
                display.setDefaultBackground(false);
                display.setBackgroundColor(Color.fromARGB(argb));
            }
            display.setShadowed(look.shadow());
            display.setSeeThrough(look.seeThrough());
            display.setTextOpacity(HoloLook.opacityByte(look.opacity()));
            display.setAlignment(switch (look.alignment()) {
                case LEFT -> TextDisplay.TextAlignment.LEFT;
                case RIGHT -> TextDisplay.TextAlignment.RIGHT;
                case CENTER -> TextDisplay.TextAlignment.CENTER;
            });
            displayCommon(display, look);
        });
    }

    /**
     * El TextDisplay de un pixel art: sin fondo ni sombra (se verian como un recuadro y un
     * reborde oscuro), alineado a la izquierda (las filas recortan lo transparente del final y
     * centradas se desplazarian) y con ancho de linea para que ninguna fila se parta.
     */
    private TextDisplay spawnImage(HoloLayout.Segment segment, boolean backFace,
                                   boolean hiddenByDefault) {
        HoloLook look = hologram.look();
        ImageLibrary.PixelArtImage art = images[segment.from()];
        int columns = art == null ? 16 : art.columns();
        Location at = base.clone();
        if (backFace) at.setYaw(Hologram.normalizeYaw(base.getYaw() + 180f));
        return base.getWorld().spawn(at, TextDisplay.class, display -> {
            prepare(display, backFace ? "image-back" : "image", !hiddenByDefault);
            display.setBillboard(billboard(look.billboard()));
            display.setLineWidth(columns * imageSettings.pixelAdvance() + 16);
            display.setDefaultBackground(false);
            display.setBackgroundColor(Color.fromARGB(0));
            display.setShadowed(false);
            display.setSeeThrough(look.seeThrough());
            display.setTextOpacity(HoloLook.opacityByte(look.opacity()));
            display.setAlignment(TextDisplay.TextAlignment.LEFT);
            displayCommon(display, look);
        });
    }

    /** Lo que enseña una linea de imagen en este tick (su fotograma, o el aviso si fallo). */
    private Component imageFrame(int line, long tick) {
        ImageLibrary.PixelArtImage art = images[line];
        if (art == null) {
            String failure = service.imageFailure(hologram.lines().get(line));
            return failure == null ? Component.text("\u2026", IMAGE_NOTICE)
                    : Component.text("\u26A0 " + failure, IMAGE_NOTICE);
        }
        // Tiempo de juego (50 ms por tick): la animacion va con el reloj de los carteles.
        return art.frames().get(GifTiming.frameAt(tick * 50L, art.delays()));
    }

    private int imageFrameIndex(int line, long tick) {
        ImageLibrary.PixelArtImage art = images[line];
        return art == null ? 0 : GifTiming.frameAt(tick * 50L, art.delays());
    }

    /** Rojo de la paleta de la casa para "no se pudo cargar". */
    private static final net.kyori.adventure.text.format.TextColor IMAGE_NOTICE =
            net.kyori.adventure.text.format.TextColor.color(0xFF4D6D);

    private ItemDisplay spawnItem(HoloLayout.Segment segment, boolean hiddenByDefault) {
        HoloLine line = hologram.lines().get(segment.from());
        HoloLook look = hologram.look();
        return base.getWorld().spawn(base.clone(), ItemDisplay.class, display -> {
            prepare(display, "item", !hiddenByDefault);
            display.setItemStack(item(line.content()));
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            display.setBillboard(look.billboard() == HoloLook.Billboard.CENTER
                    ? Display.Billboard.VERTICAL : billboard(look.billboard()));
            displayCommon(display, look);
        });
    }

    private ArmorStand spawnStand(HoloLayout.Segment segment, int line) {
        // Una linea por soporte, de arriba abajo dentro de su trozo, con el interlineado de
        // los nombres de Bedrock (algo mas holgado que el del texto de Java).
        int fromBottom = segment.to() - 1 - line;
        double y = segment.y() + fromBottom * 0.28 * Math.max(1, hologram.look().scale() * 0.6);
        Location at = base.clone().add(0, y, 0);
        return base.getWorld().spawn(at, ArmorStand.class, stand -> {
            prepare(stand, "stand", false);
            stand.setMarker(true);
            stand.setInvisible(true);
            stand.setSmall(true);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setSilent(true);
            stand.setCustomNameVisible(true);
            stand.customName(Component.empty());
        });
    }

    private void displayCommon(Display display, HoloLook look) {
        display.setViewRange(look.viewRange() / 64f);
        if (look.brightness() >= 0) {
            display.setBrightness(new Display.Brightness(look.brightness(), look.brightness()));
        }
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(0);
    }

    /** Lo comun a toda entidad de cartel: no se guarda, se marca y no estorba. */
    private void prepare(Entity entity, String role, boolean visibleByDefault) {
        entity.setPersistent(false);
        entity.setSilent(true);
        entity.setInvulnerable(true);
        entity.setVisibleByDefault(visibleByDefault);
        entity.getPersistentDataContainer().set(service.key(), PersistentDataType.STRING,
                service.session() + "|" + hologram.id() + "|" + role);
    }

    private static Display.Billboard billboard(HoloLook.Billboard billboard) {
        return switch (billboard) {
            case FIXED -> Display.Billboard.FIXED;
            case VERTICAL -> Display.Billboard.VERTICAL;
            case HORIZONTAL -> Display.Billboard.HORIZONTAL;
            case CENTER -> Display.Billboard.CENTER;
        };
    }

    /** Material vanilla, o un objeto de ItemsAdder ({@code ia:espacio:id} o {@code espacio:id}). */
    static ItemStack item(String id) {
        String value = id.toLowerCase(Locale.ROOT);
        if (value.startsWith("ia:")) value = value.substring(3);
        if (value.contains(":") && !value.startsWith("minecraft:")) {
            ItemStack custom = dev.chatalyst.hook.ItemsAdderHook.item(value);
            if (custom != null) return custom;
        }
        Material material = Material.matchMaterial(value);
        return new ItemStack(material != null && material.isItem() ? material : Material.BARRIER);
    }

    // ---- Repintado ---------------------------------------------------------------------------

    /** Repinta lo que haya cambiado en este tick. {@code force} lo reenvia todo. */
    void render(long tick, boolean force) {
        // Se decide UNA vez para todas las vistas. Decidirlo dentro de cada vista hacia que la
        // primera marcase el tick como ya movido y las demas se quedaran quietas: en un cartel
        // con placeholders (una vista por jugador) solo giraba o flotaba para uno de ellos.
        boolean moveNow = force || (hologram.motion().moves() && tick != lastMotionTick);
        for (View view : views) renderView(view, tick, force, moveNow);
        for (View view : personalViews.values()) renderView(view, tick, force, moveNow);
        if (moveNow) lastMotionTick = tick;
    }

    private void renderView(View view, long tick, boolean force, boolean moveNow) {
        prepareLines(view, tick, force);
        HoloMotion motion = hologram.motion();
        float spin = HoloAnimation.spinRadians(tick, motion.spin());
        double bob = HoloAnimation.bobOffset(tick, motion.bob());
        int interpolation = force ? 0 : service.settings().updateTicks();
        for (Part part : view.parts) {
            if (part.image != null) {
                int line = part.segment.from();
                int frame = imageFrameIndex(line, tick);
                if (force || frame != part.lastImageFrame) {
                    // El componente de cada fotograma esta cacheado: aqui no se calcula nada,
                    // solo se elige cual mandar, y solo si cambio.
                    part.lastImageFrame = frame;
                    Component text = imageFrame(line, tick);
                    part.image.text(text);
                    if (part.imageBack != null) part.imageBack.text(text);
                }
            }
            if (part.front != null || !part.stands.isEmpty()) {
                // Antes de interpretar nada: si ninguna linea cambio de texto ni de fotograma,
                // este trozo se queda como esta (lo normal: un fotograma dura varios ticks).
                int from = part.segment.from();
                int count = part.segment.to() - from;
                boolean firstPaint = part.lastPrepared == null || part.lastPrepared.length != count;
                if (firstPaint) {
                    part.lastPrepared = new String[count];
                    part.lastFrame = new int[count];
                    part.cycle = new int[count];
                }
                boolean changed = force || firstPaint;
                for (int i = 0; i < count; i++) {
                    String prepared = view.prepared[from + i];
                    if (!prepared.equals(part.lastPrepared[i])) {
                        part.lastPrepared[i] = prepared;
                        part.cycle[i] = service.cycleOf(hologram, hologram.lines().get(from + i),
                                prepared);
                        changed = true;
                    }
                    int frame = HologramService.frameOf(part.cycle[i], motion.speed(), tick);
                    if (frame != part.lastFrame[i]) {
                        part.lastFrame[i] = frame;
                        changed = true;
                    }
                }
                if (!changed) {
                    if (moveNow) move(part, spin, bob, interpolation);
                    continue;
                }
                List<Component> lines = new ArrayList<>(count);
                for (int i = part.segment.from(); i < part.segment.to(); i++) {
                    lines.add(service.renderLine(hologram, hologram.lines().get(i),
                            view.prepared[i], tick));
                }
                if (part.front != null) {
                    Component text = Component.join(JoinConfiguration.newlines(), lines);
                    if (force || !text.equals(part.lastText)) {
                        part.lastText = text;
                        part.front.text(text);
                        if (part.back != null) part.back.text(text);
                    }
                }
                for (int i = 0; i < part.stands.size(); i++) {
                    Component text = lines.get(i);
                    if (part.lastStandText.size() <= i) part.lastStandText.add(null);
                    if (force || !text.equals(part.lastStandText.get(i))) {
                        part.lastStandText.set(i, text);
                        part.stands.get(i).customName(text);
                    }
                }
            }
            if (moveNow) move(part, spin, bob, interpolation);
        }
    }

    private void move(Part part, float spin, double bob, int interpolation) {
        double scale = hologram.look().scale();
        double y = part.segment.y() + bob;
        // Un texto que mira siempre al jugador no puede girar sobre si mismo: el giro le
        // aplastaria las letras. Solo gira el texto fijo; los objetos giran siempre.
        float textSpin = hologram.look().billboard() == HoloLook.Billboard.FIXED ? spin : 0f;
        if (part.front != null) {
            transform(part.front, y, textSpin, (float) scale, interpolation,
                    hologram.look().doubleSided());
        }
        if (part.back != null) transform(part.back, y, textSpin, (float) scale, interpolation, true);
        if (part.item != null) {
            float itemScale = (float) (scale * hologram.look().itemScale());
            transform(part.item, y, spin, itemScale, interpolation, false);
        }
        if (part.image != null) {
            // Mas bajo que ancho en la misma proporcion que la celda del caracter: pixel cuadrado.
            float squash = imageSettings.squareFactor();
            transform(part.image, y, textSpin, (float) scale, squash, interpolation,
                    hologram.look().doubleSided());
            if (part.imageBack != null) {
                transform(part.imageBack, y, textSpin, (float) scale, squash, interpolation, true);
            }
        }
    }

    private void transform(Display display, double y, float spin, float scale,
                           int interpolation, boolean face) {
        transform(display, y, spin, scale, 1f, interpolation, face);
    }

    private void transform(Display display, double y, float spin, float scale, float yFactor,
                           int interpolation, boolean face) {
        double[] offset = face ? HoloAnimation.faceOffset(hologram.look().sideGap(), spin)
                : new double[]{0, 0};
        Transformation next = new Transformation(
                new Vector3f((float) offset[0], (float) y, (float) offset[1]),
                new AxisAngle4f(spin, 0f, 1f, 0f),
                new Vector3f(scale, scale * yFactor, scale),
                new AxisAngle4f());
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(interpolation);
        display.setTransformation(next);
    }

    /** Prepara (estilo, placeholders, emojis) las lineas de una vista cuando toca. */
    private void prepareLines(View view, long tick, boolean force) {
        long refreshTicks = hologram.motion().refresh() * 20L;
        boolean due = view.prepared == null || force
                || (personal && tick - view.preparedAt >= refreshTicks);
        if (!due) return;
        Player viewer = view.viewer == null ? null : org.bukkit.Bukkit.getPlayer(view.viewer);
        String[] next = new String[hologram.lines().size()];
        for (int i = 0; i < next.length; i++) {
            HoloLine line = hologram.lines().get(i);
            next[i] = line.isText() ? service.prepareText(line.content(), viewer) : "";
        }
        view.prepared = next;
        view.preparedAt = tick;
    }

    /**
     * Si este tick hay algo que repintar: animacion o movimiento siempre; un cartel personal
     * sin animacion solo cuando toca refrescar sus placeholders (antes se repintaba cada tick).
     */
    boolean needsTick(long tick) {
        if (animated) return true;
        if (!personal) return false;
        if (tick - lastRefresh < hologram.motion().refresh() * 20L) return false;
        lastRefresh = tick;
        return true;
    }

    /** Entidades vivas de este cartel (para los listados de diagnostico). */
    int entityCount() {
        int[] count = {interaction == null ? 0 : 1};
        views.forEach(view -> view.forEachEntity(entity -> count[0]++));
        personalViews.values().forEach(view -> view.forEachEntity(entity -> count[0]++));
        return count[0];
    }

    /** Si alguna entidad murio por su cuenta (/kill, chunk descargado a medias...). */
    boolean broken() {
        boolean[] dead = {interaction != null && !interaction.isValid()};
        views.forEach(view -> view.forEachEntity(entity -> dead[0] |= !entity.isValid()));
        return dead[0];
    }
}
