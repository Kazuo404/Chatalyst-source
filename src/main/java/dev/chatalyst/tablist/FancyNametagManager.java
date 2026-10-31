package dev.chatalyst.tablist;

import dev.chatalyst.hook.Placeholders;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.Legacy;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.Vanish;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nametag "fancy": oculta el nombre vanilla sobre la cabeza y monta en el jugador uno o dos
 * {@link TextDisplay} flotantes (sin fondo) que SI renderizan el estilo completo — gradientes,
 * negrita, cursiva, iconos de ItemsAdder — saltandose el limite de UN color plano del nametag
 * de scoreboard.
 *
 * <ul>
 *   <li><b>Tag</b>: el renglon principal (prefix + apodo). Puede llevar iconos a los lados con
 *       {@code {nametag_icon_left}} / {@code {nametag_icon_right}} y <b>animarse</b>:
 *       {@code Animation.Mode: frames} rota una lista de cuadros; {@code wave} desliza un
 *       gradiente. Con {@code Lines} pasa a ser un bloque de VARIAS lineas en el mismo cartel,
 *       y {@code Groups} elige lineas y aspecto por permiso ({@link NametagLines}).</li>
 *   <li><b>Title</b>: un renglon opcional ENCIMA del tag ({@code {nametag_title}}, ej.
 *       "REY DE TODO"), por jugador ({@link NametagTitleManager}) o por rango via el
 *       {@code Default_Format}.</li>
 * </ul>
 *
 * <p>Ocultar el nombre vanilla: sin TAB lo hace Chatalyst con un team propio (cf_...,
 * NAME_TAG_VISIBILITY=NEVER). Con TAB instalado los teams son suyos: hay que poner
 * scoreboard-teams -> invisible-nametags: true en su config (se avisa por consola).
 */
public final class FancyNametagManager {

    private static final String TEAM_PREFIX = "cf_";
    private final JavaPlugin plugin;
    private final TabListManager tabList;
    /** Cartel del tag principal, por jugador. */
    private final Map<UUID, TextDisplay> displays = new ConcurrentHashMap<>();
    /** Cartel del titulo (encima), por jugador; ausente si el jugador no tiene titulo. */
    private final Map<UUID, TextDisplay> titles = new ConcurrentHashMap<>();
    /** Vista previa de un titulo (TitlesApi.preview): texto y hasta cuando (ms). */
    private final Map<UUID, Preview> previews = new ConcurrentHashMap<>();

    private record Preview(String text, long until) {
    }
    /**
     * Aspecto y altura YA aplicados a cada cartel, por UUID de la ENTIDAD (no del jugador): asi
     * un cartel recreado nunca hereda el estado del anterior. Evita reenviar metadatos que no
     * cambiaron en cada refresco.
     */
    private final Map<UUID, Applied> applied = new ConcurrentHashMap<>();

    private record Applied(NametagLines.Look look, float y) {
    }

    /**
     * Ultimo texto puesto en cada cartel, por UUID de la ENTIDAD (igual que {@link #applied}).
     * Poner el mismo texto otra vez reenvia los metadatos a todos los que lo ven; con
     * animacion eso era un paquete por jugador y cuadro aunque nada cambiara.
     */
    private final Map<UUID, Component> lastText = new ConcurrentHashMap<>();

    /**
     * Marca donde va el cuadro animado del apodo dentro de una linea ya resuelta. Un caracter
     * de uso privado: ni los placeholders, ni PAPI, ni la conversion legacy lo tocan.
     */
    private static final String NICK_FRAME_MARK = "";

    /**
     * Lineas y titulo ya resueltos (con {@link #NICK_FRAME_MARK} en lugar del apodo animado),
     * por jugador. Con animacion el reloj corre cada pocos ticks pero los placeholders solo
     * cambian al ritmo de Update_Interval: se reutilizan durante {@link #framesPerResolve}
     * cuadros y en cada cuadro solo se recalcula el apodo animado.
     */
    private final Map<UUID, Resolved> resolvedCache = new ConcurrentHashMap<>();

    private record Resolved(long frameAt, NametagLines.Settings cfg, NametagLines.Group group,
                            NametagAnimation.Settings anim, Map<String, String> variables,
                            List<String> lines, String title) {
    }

    /** Cuadros que vale {@link #resolvedCache}; 1 = recalcular siempre (modo estatico). */
    private volatile long framesPerResolve = 1;

    private boolean enabled;
    private String format;
    private double height;
    private int intervalSeconds;
    private boolean hideSneaking;
    /** Cada jugador ve su propio cartel (en F5), sin necesitar el permiso de auto-vista. */
    private volatile boolean showOwn;

    // Titulo
    private boolean titleEnabled;
    private String titleFormat;
    private String titleDefaultFormat;
    private double titleHeight;

    // Varias lineas, grupos por permiso y aspecto. Snapshot inmutable: lo lee el hilo de region
    // de cada jugador mientras un reload lo cambia.
    private volatile NametagLines.Settings lines = NametagLines.Settings.classic();

    // Snapshot inmutable: tambien lo lee el hilo async del chat durante un reload.
    private volatile NametagAnimation.Settings animation = NametagAnimation.Settings.off();

    // Animacion elegida POR JUGADOR (via el GUI /nickcolor -> Animados). Cuando Per_Player esta
    // activo, cada jugador puede elegir uno de los Presets; presetFor() cae a la global si no
    // eligio ninguno, asi el comportamiento con Per_Player: false es identico al historico.
    private final dev.chatalyst.db.StorageManager storage;
    // volatile: load()/reload lo escribe (hilo global) y el click del GUI lo lee (hilo de region).
    // El apodo (tag) y el titulo tienen su PROPIA eleccion: son animaciones independientes.
    private volatile dev.chatalyst.db.Store animationStore;
    private volatile dev.chatalyst.db.Store titleStore;
    private final Map<UUID, String> playerAnimations = new ConcurrentHashMap<>();
    private final Map<UUID, String> titleAnimations = new ConcurrentHashMap<>();
    private volatile Map<String, NametagAnimation.Settings> presets = Map.of();
    private volatile boolean perPlayer;

    /** Fases independientes: TAB puede ir mas despacio para no multiplicar paquetes. */
    private volatile long frame;
    private volatile long tabFrame;
    /** Invalida callbacks de entidad que quedaron en cola durante reload/disable. */
    private volatile long generation;
    private ScheduledTask displayTask;
    private ScheduledTask tabTask;

    public FancyNametagManager(JavaPlugin plugin) {
        this(plugin, null, null);
    }

    FancyNametagManager(JavaPlugin plugin, TabListManager tabList) {
        this(plugin, tabList, null);
    }

    FancyNametagManager(JavaPlugin plugin, TabListManager tabList,
                        dev.chatalyst.db.StorageManager storage) {
        this.plugin = plugin;
        this.tabList = tabList;
        this.storage = storage;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** True si los jugadores pueden elegir su propia animacion de nametag (Per_Player + presets). */
    public boolean animationChoiceEnabled() {
        return perPlayer;
    }

    /** Ids de los presets de animacion disponibles, en el orden de la config. */
    public List<String> animationPresetIds() {
        return List.copyOf(presets.keySet());
    }

    /** El id del preset que eligio el jugador para el APODO, o {@code null} si ninguno. */
    public String playerAnimation(UUID id) {
        return playerAnimations.get(id);
    }

    /** El id del preset que eligio el jugador para el TITULO, o {@code null} si ninguno. */
    public String titleAnimation(UUID id) {
        return titleAnimations.get(id);
    }

    /**
     * Apodo envuelto en el DEGRADADO estatico del preset elegido, o {@code null} si el jugador no
     * eligio animacion. El TAB no anima (reenviar el nombre a todos cada tick laguea): muestra el
     * degradado fijo. Se quita el color propio del apodo para que el degradado se vea. Lo llama el
     * placeholder %chatalyst_nick% de TAB, que lo refresca por su cuenta (sin empujar paquetes).
     */
    public String tabGradientNick(UUID id, String nickMini) {
        if (!perPlayer || nickMini == null) return null;
        String presetId = playerAnimations.get(id);
        if (presetId == null) return null;
        NametagAnimation.Settings preset = presets.get(presetId);
        if (preset == null) return null;
        return NametagAnimation.chatGradient(preset, MM.stripColors(nickMini));
    }

    /**
     * Fija (o quita, con {@code null}/vacio) el preset de animacion del APODO, lo persiste y
     * refresca. Devuelve false si Per_Player esta apagado o el preset no existe.
     */
    public boolean setPlayerAnimation(Player p, String presetId) {
        return setAnimationChoice(p, presetId, playerAnimations, animationStore);
    }

    /** Igual que {@link #setPlayerAnimation} pero para el TITULO (animacion independiente). */
    public boolean setTitleAnimation(Player p, String presetId) {
        return setAnimationChoice(p, presetId, titleAnimations, titleStore);
    }

    private boolean setAnimationChoice(Player p, String presetId, Map<UUID, String> cache,
                                       dev.chatalyst.db.Store store) {
        if (!perPlayer) return false;
        UUID id = p.getUniqueId();
        if (presetId == null || presetId.isBlank()) {
            cache.remove(id);
            if (store != null) store.removeDurably(id.toString());
            FoliaTasks.entity(plugin, p, this::refresh);
            return true;
        }
        String key = presetId.toLowerCase(Locale.ROOT);
        if (!presets.containsKey(key)) return false;
        cache.put(id, key);
        if (store != null) store.putDurably(id.toString(), key);
        FoliaTasks.entity(plugin, p, this::refresh);
        return true;
    }

    public void load() {
        stop();
        var cfg = plugin.getConfig();
        // {rank_anim}: animaciones del prefijo del rango (nametag.yml -> Nametag.Rank_Animations; puede importar de TAB)
        org.bukkit.plugin.Plugin tab = plugin.getServer().getPluginManager().getPlugin("TAB");
        RankTagAnimator.setResolvedor(dev.chatalyst.hook.ItemsAdderHook::replaceEmojisUnchecked);
        RankTagAnimator.load(cfg.getConfigurationSection("Nametag.Rank_Animations"), tab == null ? null : tab.getDataFolder());
        if (RankTagAnimator.enabled()) {
            plugin.getLogger().info("Rank animations: " + RankTagAnimator.grupos() + " groups ({rank_anim}).");
        }
        enabled = cfg.getBoolean("Nametag.Fancy.Enabled", false);
        format = cfg.getString("Nametag.Fancy.Format",
                "{nametag_icon_left}{player_prefix}{player_nick}{afk_tag}{nametag_icon_right}");
        height = normalizeHeight(cfg.getDouble("Nametag.Fancy.Height", 0.3), 0.3);
        intervalSeconds = Math.max(1, cfg.getInt("Nametag.Fancy.Update_Interval", 2));
        hideSneaking = cfg.getBoolean("Nametag.Fancy.Hide_Sneaking", true);
        showOwn = cfg.getBoolean("Nametag.Fancy.Show_Own", true);

        titleEnabled = cfg.getBoolean("Nametag.Fancy.Title.Enabled", false);
        titleFormat = cfg.getString("Nametag.Fancy.Title.Format", "{nametag_title}");
        titleDefaultFormat = cfg.getString("Nametag.Fancy.Title.Default_Format", "");
        titleHeight = normalizeHeight(cfg.getDouble("Nametag.Fancy.Title.Height", 0.55), 0.55);

        lines = NametagLines.load(cfg.getConfigurationSection("Nametag.Fancy"));

        animation = NametagAnimation.load(cfg);
        presets = NametagAnimation.loadPresets(cfg);
        // Per_Player solo tiene sentido con nametag fancy activo y al menos un preset valido.
        perPlayer = enabled && NametagAnimation.perPlayer(cfg) && !presets.isEmpty();
        loadAnimationChoices();
        frame = 0;
        tabFrame = 0;
    }

    /** Recarga desde los Stores las animaciones (apodo y titulo) de cada jugador (solo Per_Player). */
    private void loadAnimationChoices() {
        playerAnimations.clear();
        titleAnimations.clear();
        animationStore = null;
        titleStore = null;
        if (!perPlayer || storage == null) return;
        animationStore = storage.store("nametag_anim");
        titleStore = storage.store("nametag_anim_title");
        loadChoices(animationStore, playerAnimations);
        loadChoices(titleStore, titleAnimations);
    }

    private void loadChoices(dev.chatalyst.db.Store store, Map<UUID, String> into) {
        for (Map.Entry<String, String> e : store.all().entrySet()) {
            String id = e.getValue() == null ? "" : e.getValue().toLowerCase(Locale.ROOT);
            if (!presets.containsKey(id)) continue; // preset renombrado/eliminado: se ignora
            try {
                into.put(UUID.fromString(e.getKey()), id);
            } catch (IllegalArgumentException ignore) {
                // clave corrupta: se ignora, como en el resto de stores
            }
        }
    }

    public void start() {
        NametagAnimation.Settings settings = animation;
        if (!enabled && !settings.enabled()) return;
        long runGeneration = ++generation;
        if (enabled && dev.chatalyst.hook.TabHook.isAvailable() && tabShowsVanillaNames()) {
            plugin.getLogger().warning("Fancy nametags: TAB still draws the vanilla name. Set "
                    + "scoreboard-teams -> invisible-nametags: true in TAB's config.yml, or it "
                    + "shows behind the fancy one.");
        }
        if (enabled) scheduleClearStaleTeams();

        // Reloj del flotante y del snapshot de chat. Si solo TAB usa la animacion, su reloj
        // independiente basta y evitamos recorrer jugadores dos veces. Con Per_Player el reloj
        // rapido tambien corre (aunque la global este OFF) para animar el preset de cada jugador.
        // El flotante SI anima por jugador (su preset). El TAB NO: mostrar el degradado estatico
        // via el placeholder %chatalyst_nick% evita reenviar el nombre a todos cada tick (lag).
        boolean floatAnimates = (settings.enabled() && (enabled || settings.applyToChat())) || perPlayer;
        boolean tabAnimates = settings.enabled() && settings.applyToTab();
        resolvedCache.clear();
        if (floatAnimates) {
            long period = floatInterval();
            // Los placeholders se refrescan al mismo ritmo que sin animacion (Update_Interval);
            // solo el apodo animado avanza en cada cuadro.
            framesPerResolve = framesPerResolve(intervalSeconds, period);
            displayTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, t -> {
                if (generation != runGeneration) return;
                frame++;
                if (enabled) FoliaTasks.onlinePlayers(plugin, p -> {
                    if (generation == runGeneration) refreshFloating(p, true);
                });
            }, 20L, period);
        } else if (enabled) {
            framesPerResolve = 1;
            displayTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin,
                    t -> FoliaTasks.onlinePlayers(plugin, p -> {
                        if (generation == runGeneration) refreshFloating(p, true);
                    }),
                    20L, intervalSeconds * 20L);
        }

        if (tabAnimates) {
            if (tabList != null) tabList.setAnimatedOverride(true);
            tabTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, t -> {
                if (generation != runGeneration) return;
                tabFrame++;
                FoliaTasks.onlinePlayers(plugin, p -> {
                    if (generation == runGeneration) refreshTab(p);
                });
            }, 20L, tabInterval());
            FoliaTasks.onlinePlayers(plugin, p -> {
                if (generation == runGeneration) refreshTab(p);
            });
        }
        if (enabled) FoliaTasks.onlinePlayers(plugin, p -> {
            if (generation == runGeneration) refreshFloating(p, false);
        });
    }

    /**
     * Si TAB sigue pintando el nombre vanilla: sus teams estan activos y no los oculta. Se lee
     * de su config.yml para avisar SOLO cuando hace falta — el aviso salia en cada arranque
     * aunque ya estuviera bien puesto, y un aviso que siempre sale nadie lo lee.
     */
    private boolean tabShowsVanillaNames() {
        org.bukkit.plugin.Plugin tab = plugin.getServer().getPluginManager().getPlugin("TAB");
        if (tab == null) return false;
        java.io.File config = new java.io.File(tab.getDataFolder(), "config.yml");
        if (!config.isFile()) return true;
        try {
            return showsVanillaNames(dev.chatalyst.util.YamlFiles.load(config));
        } catch (Exception unreadable) {
            return true; // no se pudo comprobar: mejor avisar de mas
        }
    }

    static boolean showsVanillaNames(org.bukkit.configuration.ConfigurationSection tabConfig) {
        return tabConfig.getBoolean("scoreboard-teams.enabled", true)
                && !tabConfig.getBoolean("scoreboard-teams.invisible-nametags", false);
    }

    /** Ritmo del reloj flotante: el mas rapido entre la global (si anima) y los presets. */
    /** Cuadros del reloj animado que caben en un Update_Interval (minimo 1). */
    static long framesPerResolve(int intervalSeconds, long periodTicks) {
        if (periodTicks <= 0) return 1;
        return Math.max(1L, (intervalSeconds * 20L) / periodTicks);
    }

    private long floatInterval() {
        long min = animation.enabled() ? animation.intervalTicks() : Long.MAX_VALUE;
        if (perPlayer) for (NametagAnimation.Settings s : presets.values()) {
            min = Math.min(min, s.intervalTicks());
        }
        return min == Long.MAX_VALUE ? 6L : min;
    }

    /** Ritmo del reloj de TAB (solo la animacion GLOBAL anima el tab; los presets van estaticos). */
    private long tabInterval() {
        return animation.enabled() && animation.applyToTab() ? animation.tabIntervalTicks() : 10L;
    }

    static double normalizeHeight(double configured, double fallback) {
        return Double.isFinite(configured)
                ? Math.max(-2.0, Math.min(5.0, configured)) : fallback;
    }

    public void stop() {
        ++generation;
        boolean restoreTab = tabTask != null;
        enabled = false;
        if (displayTask != null) {
            displayTask.cancel();
            displayTask = null;
        }
        if (tabTask != null) {
            tabTask.cancel();
            tabTask = null;
        }
        if (tabList != null) tabList.setAnimatedOverride(false);
        if (restoreTab) FoliaTasks.onlinePlayers(plugin, p -> {
            NametagAnimation.Settings current = animation;
            // load()+start() incrementa la generacion antes de que llegue este callback.
            // Si la config nueva ya no anima TAB hay que restaurar el nombre normal.
            if (!current.enabled() || !current.applyToTab()) restoreTab(p);
        });
        removeAll(displays);
        removeAll(titles);
        applied.clear();
        lastText.clear();
        resolvedCache.clear();
        if (plugin.isEnabled()) {
            scheduleClearStaleTeams();
        } else if (!FoliaTasks.isFolia()) {
            // Paper llama onDisable en su hilo principal. En Folia el scheduler regional
            // ya esta detenido; los displays son non-persistent y no se guardaran.
            clearStaleTeams();
        }
    }

    private void removeAll(Map<UUID, TextDisplay> map) {
        List<TextDisplay> snapshot = List.copyOf(map.values());
        map.clear();
        for (TextDisplay display : snapshot) {
            if (plugin.isEnabled()) {
                try {
                    display.getScheduler().run(plugin, task -> safeRemove(display), null);
                } catch (RuntimeException ignored) {
                    // La entidad o el plugin se retiro entre el snapshot y el schedule.
                }
            } else if (!FoliaTasks.isFolia()) {
                safeRemove(display);
            }
        }
    }

    private static void safeRemove(TextDisplay display) {
        try {
            if (display.isValid()) display.remove();
        } catch (RuntimeException ignored) {
        }
    }

    /** Pone {@code text} en el renglon del titulo durante {@code millis}; false si el titulo flotante esta apagado. */
    public boolean preview(Player p, String text, long millis) {
        if (!titleEnabled || text == null || text.isBlank()) return false;
        previews.put(p.getUniqueId(), new Preview(text, System.currentTimeMillis() + millis));
        return true;
    }

    /** Refresca (o crea) los carteles del jugador. Debe correr en el hilo de la entidad. */
    public void refresh(Player p) {
        // Refresco pedido por un cambio (apodo, titulo, AFK...): siempre resuelve de nuevo.
        refreshFloating(p, false);
        // El TAB por jugador lo pinta el placeholder %chatalyst_nick% (estatico); aqui solo se
        // refresca cuando anima la GLOBAL.
        if (animation.enabled() && animation.applyToTab()) refreshTab(p);
    }

    /** La animacion del APODO del jugador: su preset elegido (Per_Player) o la global si no eligio. */
    private NametagAnimation.Settings presetFor(Player p) {
        if (perPlayer) {
            String id = playerAnimations.get(p.getUniqueId());
            if (id != null) {
                NametagAnimation.Settings preset = presets.get(id);
                if (preset != null) return preset;
            }
        }
        return animation;
    }

    /**
     * La animacion del TITULO del jugador, INDEPENDIENTE del apodo: su propio preset elegido, o
     * estatico (OFF) si no eligio ninguno. Sin Per_Player el titulo sigue la animacion global.
     */
    private NametagAnimation.Settings presetForTitle(Player p) {
        if (perPlayer) {
            String id = titleAnimations.get(p.getUniqueId());
            if (id != null) {
                NametagAnimation.Settings preset = presets.get(id);
                if (preset != null) return preset;
            }
            return NametagAnimation.Settings.off(); // el titulo solo anima con SU propia eleccion
        }
        return animation;
    }

    /**
     * Whether this player is invisible because their anonymous transition is under way.
     *
     * <p>The floating tag is a {@link TextDisplay} of its own, NOT part of the player, so
     * {@code hidePlayer} never touches it: the player vanished and their name stayed hanging
     * in the air — worse than not hiding them at all, because a name with nobody under it is
     * exactly what makes someone walk over and look.
     */
    private boolean hiddenForAnonymousTransition(Player p) {
        var plugin = dev.chatalyst.ChatalystPlugin.get();
        var anonymous = plugin == null ? null : plugin.getAnonymousManager();
        return anonymous != null && anonymous.hiddenForTransition(p.getUniqueId());
    }

    private void refreshFloating(Player p, boolean fromClock) {
        if (!enabled || !p.isOnline()) return;
        // Grupo por permiso: se reevalua en cada refresco, asi un permiso nuevo cambia lineas y
        // aspecto sin reconectar. Sin Groups la lista esta vacia y no cuesta nada.
        NametagLines.Settings cfg = lines;
        NametagLines.Group group = NametagLines.pick(cfg.groups(), p::hasPermission);
        NametagLines.Look look = NametagLines.lookFor(cfg, group);
        TextDisplay tag = ensure(p, displays, height, look);
        if (tag == null) return;
        applyLook(tag, look, height);
        applySelfVisibility(p, tag);
        NametagAnimation.Settings anim = presetFor(p);
        boolean hidden = Vanish.isVanished(p) || p.getGameMode() == GameMode.SPECTATOR
                || dev.chatalyst.hook.DisguiseHook.isDisguised(p)
                || hiddenForAnonymousTransition(p);
        // Vanish, espectador o DISFRAZADO: carteles vacios. Lo tercero importa tanto como el
        // vanish — con Morphy convertido en animal, un cartel con tu rango encima del mob te
        // delata y el disfraz no sirve de nada.
        // La animacion colorea SOLO el apodo: se inserta el cuadro donde va {player_nick} DENTRO
        // del formato, asi el prefijo del rango y los iconos se conservan. En estatico (OFF) el
        // cuadro es "{player_nick}" y el formato queda intacto. Con Lines pasa lo mismo en cada
        // linea que lleve {player_nick}.
        int shown = 1;
        Resolved base = null;
        if (hidden) {
            resolvedCache.remove(p.getUniqueId());
            setText(tag, Component.empty());
        } else {
            base = resolvedFor(p, cfg, group, anim, fromClock);
            // El cuadro animado del apodo es lo unico que cambia en cada cuadro: se resuelve
            // aparte y se pone donde quedo la marca.
            String nickFrame = resolve(p, NametagAnimation.template(anim, frame, "{player_nick}"),
                    base.variables());
            List<String> resolved = new ArrayList<>(base.lines().size());
            for (String line : base.lines()) resolved.add(line.replace(NICK_FRAME_MARK, nickFrame));
            List<String> visible = NametagLines.visible(resolved, cfg.removeEmpty());
            shown = visible.size();
            setText(tag, joinLines(visible));
        }

        // Titulo: solo si esta activado Y el jugador (o su rango) tiene uno. Si queda vacio se
        // RETIRA el cartel en vez de dejar un renglon invisible ocupando sitio.
        if (!titleEnabled || hidden) {
            removeTitle(p);
            return;
        }
        // Se decide por el texto YA resuelto: si no queda nada visible, no hay titulo. Primero
        // el titulo propio del jugador; si no tiene, el respaldo por rango (Default_Format).
        Preview preview = previews.get(p.getUniqueId());
        if (preview != null && preview.until() < System.currentTimeMillis()) {
            previews.remove(p.getUniqueId(), preview);
            preview = null;
        }
        String titleStr = preview != null ? preview.text() : base.title();
        if (preview != null && MM.strip(titleStr).isBlank()) titleStr = resolveTitle(p, "");
        if (titleStr.isEmpty()) {
            removeTitle(p);
            return;
        }
        // El bloque crece hacia ARRIBA desde su origen: el titulo sube un renglon por cada linea
        // extra para seguir encima. Con una sola linea el desplazamiento es 0 (como siempre).
        double titleY = titleHeight + NametagLines.titleLift(shown, look.scale());
        TextDisplay title = ensure(p, titles, titleY, look);
        if (title != null) {
            applyLook(title, look, titleY);
            // El titulo anima con SU PROPIO preset (independiente del apodo): FLOW/onda deslizan
            // su color; sin eleccion propia queda estatico. Comparte el contador 'frame'.
            // <flujo>/<brillo> del propio titulo (TitleEffects) avanzan con el mismo contador.
            setText(title, MM.full(NametagAnimation.animateTitle(presetForTitle(p), frame,
                    dev.chatalyst.util.TitleEffects.animate(titleStr, frame))));
            applySelfVisibility(p, title);
        }
    }

    /**
     * Lo resuelto del jugador: el de la cache si lo pide el reloj, sigue vigente y nada de lo
     * que lo define (config, grupo por permiso, animacion elegida) cambio; si no, se resuelve
     * de nuevo. Las variables se calculan UNA vez para todo el bloque, no una por linea.
     */
    private Resolved resolvedFor(Player p, NametagLines.Settings cfg, NametagLines.Group group,
                                 NametagAnimation.Settings anim, boolean fromClock) {
        UUID id = p.getUniqueId();
        long now = frame;
        Resolved cached = resolvedCache.get(id);
        if (fromClock && cached != null && cached.cfg() == cfg && cached.group() == group
                && cached.anim() == anim && now - cached.frameAt() >= 0
                && now - cached.frameAt() < framesPerResolve) {
            return cached;
        }
        // El color del apodo solo se quita si este jugador anima: antes se quitaba siempre, y
        // con la animacion apagada el nametag perdia el color de /nickcolor (salia en blanco).
        Map<String, String> variables = NametagAnimation.variablesFor(anim,
                Placeholders.merged(p, null));
        List<String> lines = new ArrayList<>();
        for (String line : NametagLines.templates(cfg, group, format)) {
            lines.add(resolve(p, NametagLines.animate(line, NICK_FRAME_MARK), variables));
        }
        String title = titleEnabled ? resolveTitle(p, null) : "";
        // Sin Map.copyOf/List.copyOf: un placeholder puede valer null y esos lo rechazan. Las
        // dos colecciones son nuevas y nadie las modifica despues.
        Resolved fresh = new Resolved(now, cfg, group, anim,
                java.util.Collections.unmodifiableMap(variables),
                java.util.Collections.unmodifiableList(lines), title);
        resolvedCache.put(id, fresh);
        return fresh;
    }

    /**
     * Titulo resuelto (propio o el de respaldo por rango), o "" si no queda nada visible.
     * {@code first} permite empezar por un texto ya conocido; null = el formato del titulo.
     */
    private String resolveTitle(Player p, String first) {
        String titleStr = first != null ? first : resolve(p, titleFormat);
        if (!MM.strip(titleStr).isBlank()) return titleStr;
        if (titleDefaultFormat.isEmpty()) return "";
        titleStr = resolve(p, titleDefaultFormat);
        return MM.strip(titleStr).isBlank() ? "" : titleStr;
    }

    /** Pone el texto solo si cambio: cada cambio se reenvia a todos los que ven el cartel. */
    private void setText(TextDisplay display, Component text) {
        if (text.equals(lastText.put(display.getUniqueId(), text))) return;
        display.text(text);
    }

    /**
     * Une las lineas ya resueltas en un solo componente. Cada una se parsea POR SEPARADO: una
     * etiqueta sin cerrar en una linea no tiñe ni se come la siguiente.
     */
    private static Component joinLines(List<String> resolved) {
        if (resolved.isEmpty()) return Component.empty();
        if (resolved.size() == 1) return MM.full(resolved.get(0));
        List<Component> parts = new ArrayList<>(resolved.size());
        for (String line : resolved) parts.add(MM.full(line));
        return Component.join(JoinConfiguration.newlines(), parts);
    }

    /**
     * Lleva el cartel al aspecto y la altura pedidos SOLO si cambiaron (grupo nuevo por un
     * permiso, o el titulo que sube/baja con el numero de lineas). Sin respawnear.
     */
    private void applyLook(TextDisplay d, NametagLines.Look look, double y) {
        Applied wanted = new Applied(look, (float) y);
        if (wanted.equals(applied.put(d.getUniqueId(), wanted))) return;
        style(d, wanted);
    }

    private static void style(TextDisplay td, Applied a) {
        NametagLines.Look look = a.look();
        td.setBillboard(look.billboard());
        td.setShadowed(look.shadow());
        td.setSeeThrough(look.seeThrough());
        td.setDefaultBackground(false);
        // ARGB 0 = sin fondo: texto flotante.
        td.setBackgroundColor(Color.fromARGB(look.backgroundArgb()));
        td.setViewRange(look.viewRange());
        Transformation t = td.getTransformation();
        td.setTransformation(new Transformation(new Vector3f(0f, a.y(), 0f),
                t.getLeftRotation(), new Vector3f(look.scale()), t.getRightRotation()));
    }

    /**
     * Si el jugador ve su PROPIO cartel: con {@code Show_Own} (el default) todos, y con el
     * apagado solo quien tenga {@code chatalyst.nametag.seeself}.
     *
     * <p>Antes dependia solo del permiso, que es de OP: el dueño veia su cartel completo, se
     * quitaba el OP para probar como jugador y el cartel desaparecia entero — rango, color y
     * linea de vida/dinero — sin nada que lo explicara. El nombre vanilla tampoco se ve a uno
     * mismo, asi que parecia que el nametag habia dejado de funcionar.
     */
    static boolean seesOwn(boolean showOwn, boolean hasPermission) {
        return showOwn || hasPermission;
    }

    private boolean seesOwn(Player p) {
        return seesOwn(showOwn, p.hasPermission("chatalyst.nametag.seeself"));
    }

    /**
     * Reconcila si el jugador ve su PROPIO cartel ({@link #seesOwn}), visible sobre todo en
     * tercera persona (F5). Solo togglea cuando el estado cambia, asi un cambio de permiso o de
     * config se aplica en el siguiente refresco sin reenviar paquetes en cada ciclo.
     */
    private void applySelfVisibility(Player p, TextDisplay d) {
        if (d == null || !d.isValid()) return;
        boolean shouldSee = seesOwn(p);
        if (p.canSee(d) == shouldSee) return;
        if (shouldSee) {
            p.showEntity(plugin, d);
        } else {
            p.hideEntity(plugin, d);
        }
    }

    /** Aplica el cuadro continuo de la animacion GLOBAL al nombre del TAB (interno o plugin TAB). */
    private void refreshTab(Player p) {
        NametagAnimation.Settings settings = animation;
        if (!p.isOnline() || !settings.enabled() || !settings.applyToTab()) return;
        String rendered = resolveAnimation(p,
                NametagAnimation.template(settings, tabFrame, format));
        if (dev.chatalyst.hook.TabHook.isAvailable()) {
            dev.chatalyst.hook.TabHook.pushAnimatedName(p, rendered);
        } else if (tabList != null) {
            tabList.showAnimatedName(p, rendered);
        }
    }

    /** Restaura el formato normal al apagar/reconfigurar la animacion. */
    private void restoreTab(Player p) {
        if (!p.isOnline()) return;
        if (dev.chatalyst.hook.TabHook.isAvailable()) {
            dev.chatalyst.ChatalystPlugin chatalyst = dev.chatalyst.ChatalystPlugin.get();
            String nick = chatalyst != null && chatalyst.getNickManager() != null
                    ? chatalyst.getNickManager().getNick(p.getUniqueId()) : null;
            dev.chatalyst.hook.TabHook.pushNick(p, nick);
        } else if (tabList != null) {
            tabList.update(p);
        }
    }

    /**
     * Chat solo puede capturar el cuadro vigente cuando se construye la linea; el protocolo
     * no permite editar despues una linea ya enviada. Seguro para llamar desde chat async.
     */
    public void applyChatSnapshot(Player p, Map<String, String> variables) {
        // Preset elegido por el jugador: el chat muestra su DEGRADADO estatico (no anima; cada
        // linea es fija). Se aplica aunque la animacion global este apagada.
        if (perPlayer && playerAnimations.containsKey(p.getUniqueId())) {
            NametagAnimation.Settings preset = presetFor(p);
            String rendered = resolve(p, NametagAnimation.chatGradient(preset, "{player_nick}"),
                    NametagAnimation.animationVariables(variables));
            NametagAnimation.applyChatSnapshot(variables, rendered);
            return;
        }
        // Animacion GLOBAL con Apply_To.Chat: snapshot del cuadro vigente (comportamiento historico).
        NametagAnimation.Settings settings = animation;
        if (!settings.enabled() || !settings.applyToChat()) return;
        String rendered = resolve(p, NametagAnimation.template(settings, frame, format),
                NametagAnimation.animationVariables(variables));
        NametagAnimation.applyChatSnapshot(variables, rendered);
    }

    /**
     * Resuelve una plantilla a una cadena MiniMessage lista para {@link MM#full}: ItemsAdder
     * ({@code :tokens:}) -> placeholders -> legacy (&amp;-codes) -> MiniMessage. Devolver el
     * String (no el Component) permite decidir con {@link MM#strip} si el titulo quedo vacio
     * sin volver a serializar.
     */
    private String resolve(Player p, String template) {
        if (template == null || template.isEmpty()) return "";
        String withEmojis = dev.chatalyst.hook.ItemsAdderHook.replaceEmojisUnchecked(template);
        return Legacy.toMini(Placeholders.apply(p, withEmojis));
    }

    private String resolve(Player p, String template, Map<String, String> variables) {
        if (template == null || template.isEmpty()) return "";
        String withEmojis = dev.chatalyst.hook.ItemsAdderHook.replaceEmojisUnchecked(template);
        return Legacy.toMini(Placeholders.applyPrepared(p, withEmojis, variables));
    }

    private String resolveAnimation(Player p, String template) {
        Map<String, String> variables = NametagAnimation.animationVariables(
                Placeholders.merged(p, null));
        return resolve(p, template, variables);
    }

    /** Quita ambos carteles (y el team que oculta el vanilla) al salir el jugador. */
    public void remove(Player p) {
        removeDisplay(displays, p.getUniqueId());
        removeDisplay(titles, p.getUniqueId());
        previews.remove(p.getUniqueId());
        resolvedCache.remove(p.getUniqueId());
        MountedTags.clear(p.getUniqueId());
        if (!dev.chatalyst.hook.TabHook.isAvailable()) {
            String name = teamName(p.getUniqueId());
            FoliaTasks.global(plugin, () -> {
                Team team = mainBoard().getTeam(name);
                if (team != null) team.unregister();
            });
        }
    }

    /**
     * Quita los carteles antes de un teleport; {@link #refresh} los crea de nuevo en el destino.
     * Re-montar el MISMO TextDisplay tras moverlo deja al cliente con la posicion vieja: el
     * cartel se queda flotando donde estaba el jugador. Uno recien creado llega al cliente ya
     * montado (el spawn trae los pasajeros).
     */
    public void detachForTeleport(Player p) {
        removeDisplay(displays, p.getUniqueId());
        removeDisplay(titles, p.getUniqueId());
    }

    private void removeTitle(Player p) {
        removeDisplay(titles, p.getUniqueId());
    }

    private void removeDisplay(Map<UUID, TextDisplay> map, UUID id) {
        TextDisplay d = map.remove(id);
        if (d != null) {
            Integer vehicle = MountedTags.vehicleOf(id);
            if (vehicle != null) publishMounts(id, vehicle);
            applied.remove(d.getUniqueId());
            lastText.remove(d.getUniqueId());
            try {
                d.remove();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Atenua los carteles al agacharse (imita el nametag vanilla). */
    public void onSneak(Player p, boolean sneaking) {
        if (!enabled || !hideSneaking) return;
        byte opacity = sneaking ? lines.sneakOpacity() : (byte) -1;
        for (Map<UUID, TextDisplay> map : List.of(displays, titles)) {
            TextDisplay d = map.get(p.getUniqueId());
            if (d != null && d.isValid()) d.setTextOpacity(opacity);
        }
    }

    /**
     * Devuelve el cartel del jugador en {@code map} a la altura {@code y}, creandolo (ya con su
     * aspecto) o re-enganchandolo si hizo falta (teleport/respawn desmonta el passenger). Null
     * si no se pudo crear.
     */
    private TextDisplay ensure(Player p, Map<UUID, TextDisplay> map, double y,
                               NametagLines.Look look) {
        TextDisplay d = mount(p, map, y, look);
        publishMounts(p.getUniqueId(), p.getEntityId());
        return d;
    }

    /** Lo que el listener de red tiene que mantener montado en este jugador. */
    private void publishMounts(UUID owner, int vehicleId) {
        int[] ids = java.util.stream.Stream.of(displays.get(owner), titles.get(owner))
                .filter(d -> d != null && d.isValid())
                .mapToInt(TextDisplay::getEntityId)
                .toArray();
        MountedTags.publish(owner, vehicleId, ids);
    }

    private TextDisplay mount(Player p, Map<UUID, TextDisplay> map, double y,
                              NametagLines.Look look) {
        TextDisplay d = map.get(p.getUniqueId());
        if (d == null || !d.isValid()) {
            if (d != null) applied.remove(d.getUniqueId());
            d = spawn(p, map, y, look);
            return d;
        }
        if (d.getVehicle() != p) {
            if (d.getWorld().equals(p.getWorld())) {
                d.teleport(p.getLocation());
                p.addPassenger(d);
            } else {
                applied.remove(d.getUniqueId());
                d.remove();
                d = spawn(p, map, y, look);
            }
        }
        return d;
    }

    private TextDisplay spawn(Player p, Map<UUID, TextDisplay> map, double y,
                              NametagLines.Look look) {
        try {
            Applied initial = new Applied(look, (float) y);
            TextDisplay d = p.getWorld().spawn(p.getLocation(), TextDisplay.class, td -> {
                td.text(Component.empty());
                td.setPersistent(false); // nunca se guarda en el mundo (sin huerfanos tras crash)
                // El aspecto va ANTES de que la entidad exista para los clientes: nace ya con
                // el, sin un segundo paquete de metadatos.
                style(td, initial);
                td.addScoreboardTag("chatalyst_nametag");
            });
            applied.put(d.getUniqueId(), initial);
            p.addPassenger(d);
            // Con Show_Own apagado el jugador no ve sus carteles salvo que tenga el permiso de
            // auto-vista; applySelfVisibility lo reconcilia despues en cada refresco.
            if (!seesOwn(p)) {
                p.hideEntity(plugin, d);
            }
            map.put(p.getUniqueId(), d);
            hideVanilla(p);
            return d;
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not spawn fancy nametag for " + p.getName()
                    + ": " + t.getMessage());
            return null;
        }
    }

    // Oculta el nombre vanilla con un team propio. Con TAB no tocamos teams (son suyos):
    // el dueño debe activar invisible-nametags en la config de TAB.
    private void hideVanilla(Player p) {
        if (dev.chatalyst.hook.TabHook.isAvailable()) return;
        long expectedGeneration = generation;
        UUID id = p.getUniqueId();
        String entry = p.getName();
        FoliaTasks.global(plugin, () -> {
            if (!enabled || generation != expectedGeneration) return;
            Scoreboard board = mainBoard();
            String name = teamName(id);
            Team team = board.getTeam(name);
            if (team == null) team = board.registerNewTeam(name);
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            if (!team.hasEntry(entry)) team.addEntry(entry);
        });
    }

    // El scoreboard principal PERSISTE entre reinicios: limpia teams cf_ huerfanos.
    private void clearStaleTeams() {
        for (Team t : mainBoard().getTeams()) {
            if (t.getName().startsWith(TEAM_PREFIX)) {
                t.unregister();
            }
        }
    }

    private void scheduleClearStaleTeams() {
        FoliaTasks.global(plugin, this::clearStaleTeams);
    }

    private Scoreboard mainBoard() {
        return plugin.getServer().getScoreboardManager().getMainScoreboard();
    }

    // Nombre de team POR UUID, no por nombre de jugador. Antes se truncaba el nombre a 13
    // chars (limite de 16 del team menos el prefijo "cf_"), asi que dos jugadores cuyos
    // nombres compartian los primeros 13 caracteres caian en el MISMO team: al salir uno,
    // unregister se llevaba el del otro y le reaparecia el nombre vanilla. El UUID (sin
    // guiones, 13 primeros hex) es unico y estable, y el team name nunca se muestra.
    private static String teamName(UUID id) {
        return TEAM_PREFIX + id.toString().replace("-", "").substring(0, 13);
    }
}
