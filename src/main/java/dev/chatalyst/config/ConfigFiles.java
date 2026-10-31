package dev.chatalyst.config;

import dev.chatalyst.util.YamlFiles;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Splits the configuration across per-domain files on disk (chat.yml, moderation.yml,
 * economy.yml, players.yml, bot.yml, network.yml, integrations.yml) while the rest of the
 * plugin keeps using a single merged {@code getConfig()} — no other class needs to know the
 * config is split.
 *
 * <ul>
 *   <li>{@link #merge} overlays the disk split files (as values) and the jar split resources
 *       (as defaults) onto the already-loaded main config; call it after super.reloadConfig().</li>
 *   <li>{@link #save} writes the in-memory config back to its files, preserving each file's
 *       comments (loads the file, overlays leaf values, saves).</li>
 *   <li>{@link #ensureSplit} runs once on enable: brand-new installs get the commented jar
 *       defaults; an existing monolithic config.yml is migrated (values kept, backed up).</li>
 * </ul>
 */
public final class ConfigFiles {

    private ConfigFiles() {
    }

    /** Split file -&gt; the top-level sections it owns. config.yml keeps everything else. */
    public static final Map<String, List<String>> LAYOUT = layout();
    private static final Map<String, String> SECTION_FILE = invert(LAYOUT);

    private static Map<String, List<String>> layout() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("chat.yml", List.of("Chat", "Channels", "Channel_Router", "Me", "Say",
                "Mentions", "Grammar", "Tab_Complete", "Vanilla_Messages", "Unknown_Command",
                "Message_Replacements", "Server_Brand"));
        m.put("moderation.yml", List.of("Staff_Item_Audit",
                "Anti_Spam", "Anti_Bot", "Chat_Security", "Command_Blocker",
                "Mute", "Staff_Moderation", "Warning_Points", "Staff_Security",
                "Command_Cooldowns", "Log", "Reports", "Item_Watch", "Autoclick", "Anti_Exploit"));
        m.put("economy.yml", List.of("Trade_Plugins", "Market", "Trade", "Balance_Top"));
        m.put("players.yml", List.of("Afk", "Private_Messages", "Mail", "Nick", "Tag",
                "Chat_Color", "Fancy_Signs", "Item_Name", "Newcomer", "Spy", "Anonymous",
                "Marriage", "Profile"));
        m.put("bot.yml", List.of("Announcer", "Auto_Responses"));
        m.put("network.yml", List.of("Staff_Mentions", "Proxy", "Network_Admin", "Redis"));
        // One-shot goods get their own file: they are delivered exactly once, which is the
        // opposite guarantee to the continuously repaired ranks in integrations.yml.
        m.put("store.yml", List.of("Store", "Store_Catalog", "Store_Sections"));
        // How a player is DRAWN (name above the head, tab list). Not an integration: it was
        // almost half of integrations.yml while the real third-party hooks were under a tenth
        // of it. The TAB hook comes along because it is what stops the two from fighting.
        // Listed BEFORE integrations.yml so that, if a relocation ever fails, the user's own
        // integrations.yml still wins the merge instead of factory defaults.
        // A giveaway is an event, not a purchase and not a chat setting: the prizes reference
        // the store and the rank catalogue rather than restating them, so it owns its own file
        // instead of growing a third catalogue inside somebody else's.
        m.put("giveaway.yml",
                List.of("Giveaway", "Giveaway_Categories", "Giveaway_Menu", "Giveaway_Prizes"));
        m.put("rentals.yml", List.of("Rentals", "Rental_Categories", "Rental_Offers"));
        // Una caja no es un producto ni un premio: es el ENVOLTORIO de cualquiera de los
        // dos. Su fichero describe que puede haber dentro, y quien la reparte (kit, rifa,
        // tienda) se escribe en el fichero de quien la reparte.
        m.put("gifts.yml", List.of("Gifts", "Gift_Tiers"));
        // Preguntas del chat con premio: un juego, no una regla de chat ni un anuncio del bot.
        m.put("chatgames.yml", List.of("Chat_Games", "Chat_Game_Questions"));
        // Candados de cofres: protegen un contenedor de cualquiera, tambien del propio clan.
        m.put("chestlocks.yml", List.of("Chest_Locks"));
        // Los ajustes de los carteles flotantes. Los carteles en si son de cada servidor y
        // viven en data/holograms.yml, no aqui.
        m.put("holograms.yml", List.of("Holograms", "Hologram_Presets"));
        m.put("nametag.yml", List.of("Nametag", "Tab_List", "TAB"));
        m.put("integrations.yml", List.of("Rank_Catalog", "Discord", "Tebex", "Voice",
                "Dynmap", "PacketEvents", "Scripting",
                "Variables", "Command_Aliases", "Translation"));
        return java.util.Collections.unmodifiableMap(m);
    }

    /**
     * Sections that used to live in another file. Applied on every startup and idempotent:
     * once the source no longer holds the section there is nothing left to do.
     */
    private static final List<Relocation> RELOCATIONS = List.of(
            new Relocation("integrations.yml", "nametag.yml",
                    List.of("Nametag", "Tab_List", "TAB")));

    private record Relocation(String from, String to, List<String> sections) {
    }

    private static Map<String, String> invert(Map<String, List<String>> layout) {
        Map<String, String> out = new LinkedHashMap<>();
        for (var e : layout.entrySet()) {
            for (String section : e.getValue()) out.put(section, e.getKey());
        }
        return out;
    }

    /** File a top-level section belongs to (config.yml when it is not mapped to a split file). */
    public static String fileFor(String section) {
        return SECTION_FILE.getOrDefault(section, "config.yml");
    }

    /**
     * Las secciones que son del propio config.yml. Todo lo demas o esta en {@link #LAYOUT}, o
     * no tiene casa — y no tener casa es como nace el residuo que reventaba las
     * actualizaciones (ver {@code migrateFromMonolithic}): {@link #save} manda a config.yml
     * cualquier seccion sin fichero declarado, y el dia que alguien la añade a LAYOUT quedan
     * dos copias vivas de lo mismo.
     */
    static final Set<String> CORE_SECTIONS = Set.of(
            "Config_Version", "License", "Permissions_List", "Updates", "Groups", "Database",
            "Locale", "Auto_Detect_Language", "Commands_Owned_Elsewhere");

    /** Una linea por seccion y por arranque: es un aviso para quien desarrolla, no un bucle. */
    private static final Set<String> HOMELESS_ALREADY_REPORTED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Avisa de una seccion que se esta escribiendo en config.yml sin ser suya.
     *
     * <p>Es el unico sitio donde el residuo puede nacer, asi que es el unico sitio donde se
     * puede ver venir: cuando aparece, la seccion todavia no le ha hecho daño a nadie.
     */
    private static void warnIfHomeless(JavaPlugin plugin, String section) {
        if (SECTION_FILE.containsKey(section) || CORE_SECTIONS.contains(section)) return;
        if (!HOMELESS_ALREADY_REPORTED.add(section)) return;
        plugin.getLogger().warning(section + " has no file of its own and is being written to"
                + " config.yml. It belongs in one of the per-domain files; leaving it here"
                + " means a future version that gives it a file would find two live copies.");
    }

    /**
     * Texto de configuracion con el default del JAR como respaldo.
     *
     * <p>Bukkit da prioridad al default EXPLICITO de {@code getString(path, def)} sobre el
     * empaquetado: pasar {@code ""} ahi deja la clave vacia en cuanto falta del archivo del
     * usuario — y falta en cualquier config generada antes de que la clave existiera. El
     * sintoma es una funcion que "no hace nada" sin ningun error (un hover que no sale, un
     * prefijo que desaparece). Esta variante mira el VALOR, luego el default del jar y solo
     * al final el respaldo del codigo.
     */
    public static String string(FileConfiguration cfg, String path, String fallback) {
        String value = cfg.getString(path);
        return value != null ? value : fallback;
    }

    // ---- reload (merge) --------------------------------------------------------------------

    public static void merge(JavaPlugin plugin, FileConfiguration cfg) {
        File dir = plugin.getDataFolder();
        // 1) Disk split files as VALUES (they win over the monolithic config.yml if both exist).
        for (String file : LAYOUT.keySet()) {
            File f = new File(dir, file);
            if (!f.exists()) continue;
            try {
                copyTopLevel(cfg, YamlFiles.load(f));
            } catch (Exception ex) {
                plugin.getLogger().severe("Refusing to merge invalid " + file + ": "
                        + ex.getMessage() + ". Run /cht doctor or restore its backup.");
            }
        }
        // 2) Jar split resources as DEFAULTS, so any missing key still resolves at runtime even
        //    if a user's split file is incomplete (or a future version adds keys).
        for (String file : LAYOUT.keySet()) {
            try (InputStream in = plugin.getResource(file)) {
                if (in == null) continue;
                cfg.addDefaults(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8)));
            } catch (IOException ignored) {
                // a missing/broken resource just means no extra defaults for that file
            }
        }
    }

    static void copyTopLevel(FileConfiguration target, ConfigurationSection src) {
        for (String key : src.getKeys(false)) {
            Object value = src.get(key);
            if (value instanceof ConfigurationSection section) {
                target.set(key, null);
                copySection(section, target.createSection(key));
            } else {
                target.set(key, value);
            }
        }
    }

    private static void copySection(ConfigurationSection from, ConfigurationSection to) {
        for (String key : from.getKeys(false)) {
            Object value = from.get(key);
            if (value instanceof ConfigurationSection section) {
                copySection(section, to.createSection(key));
            } else {
                to.set(key, value);
            }
        }
    }

    // ---- save (distribute, comment-preserving) --------------------------------------------

    public static synchronized boolean save(JavaPlugin plugin, FileConfiguration cfg) {
        File dir = plugin.getDataFolder();
        Map<String, YamlConfiguration> byFile = new LinkedHashMap<>();
        Map<String, File> paths = new LinkedHashMap<>();
        try {
            for (String section : cfg.getKeys(false)) {
                warnIfHomeless(plugin, section);
                String file = fileFor(section);
                File f = new File(dir, file);
                YamlConfiguration disk = byFile.get(file);
                if (disk == null) {
                    disk = f.exists() ? YamlFiles.load(f) : new YamlConfiguration();
                    byFile.put(file, disk);
                }
                paths.put(file, f);
                overlay(disk, cfg, section);
            }
            for (var e : byFile.entrySet()) {
                YamlFiles.saveAtomic(e.getValue(), paths.get(e.getKey()));
            }
            return true;
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not save split configuration: " + ex.getMessage());
            return false;
        }
    }

    /** Overlay a section's leaf values from memory onto the on-disk file (keeps its comments). */
    private static void overlay(YamlConfiguration disk, FileConfiguration cfg, String section) {
        Object value = cfg.get(section);
        if (value instanceof ConfigurationSection mem) {
            for (String leaf : mem.getKeys(true)) {
                Object leafValue = mem.get(leaf);
                String path = section + "." + leaf;
                if (leafValue instanceof ConfigurationSection) {
                    if (disk.getConfigurationSection(path) == null) disk.createSection(path);
                } else {
                    disk.set(path, leafValue);
                }
            }
        } else {
            disk.set(section, value);
        }
    }

    // ---- first-run split / migration ------------------------------------------------------

    public static void ensureSplit(JavaPlugin plugin) {
        File dir = plugin.getDataFolder();
        File main = new File(dir, "config.yml");
        // La limpieza del residuo ya NO va gateada por "falta algun fichero". Una seccion que
        // pertenece a un fichero de dominio y sigue escrita tambien en config.yml es una bomba
        // de relojeria: merge() lee las dos copias y cual gana lo decide el orden de los
        // ficheros, y el dia que una version estrene un fichero nuevo ese residuo se
        // interpretaba como "config monolitica" y disparaba el reparto entero. Mientras siga
        // ahi el peligro no desaparece solo, asi que se resuelve en cuanto se detecta.
        if (main.exists() && mainHasForeignSections(main)) {
            migrateFromMonolithic(plugin, main);
        } else {
            // Brand-new install (or already-split core): write the commented jar defaults.
            for (String file : LAYOUT.keySet()) {
                if (!new File(dir, file).exists()) plugin.saveResource(file, false);
            }
        }
        // Runs even when nothing was missing: a section can move between two files that both
        // already exist, and merge() reads every split file, so leaving the old copy behind
        // would let file order decide which one is live.
        for (Relocation relocation : RELOCATIONS) {
            relocate(plugin, relocation);
        }
    }

    /**
     * Moves sections that changed file, keeping the owner's values.
     *
     * <p>Reassigning a section in {@link #LAYOUT} is not enough on an existing install: the
     * jar default of the new file is written with factory values while the owner's customised
     * section is still sitting in the old file. Both are then read by {@link #merge}, and
     * whichever comes last silently wins — which is how someone's four hundred lines of
     * nametag configuration would quietly revert. So the values are carried across and the
     * old copy is removed, leaving exactly one home per section.
     */
    private static void relocate(JavaPlugin plugin, Relocation relocation) {
        File dir = plugin.getDataFolder();
        File source = new File(dir, relocation.from());
        if (!source.exists()) return;

        YamlConfiguration from;
        try {
            from = YamlFiles.load(source);
        } catch (Exception ex) {
            plugin.getLogger().severe("Refusing to relocate sections out of "
                    + relocation.from() + ": " + ex.getMessage()
                    + ". Run /cht doctor and repair it first.");
            return;
        }
        List<String> present = relocation.sections().stream()
                .filter(from::contains).toList();
        if (present.isEmpty()) return; // already relocated on an earlier startup

        try {
            Files.copy(source.toPath(),
                    new File(dir, relocation.from() + ".pre-move.bak").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            // Without a verified backup, leaving the working file untouched beats risking it.
            plugin.getLogger().severe("Could not back up " + relocation.from()
                    + "; the move to " + relocation.to() + " was skipped: " + ex.getMessage());
            return;
        }

        File target = new File(dir, relocation.to());
        boolean targetExisted = target.exists();
        if (!targetExisted) plugin.saveResource(relocation.to(), false);
        // The target is about to be overlaid too. If a previous attempt wrote it and then
        // failed before clearing the source, the owner may already have edited THIS file, so
        // it needs its own recoverable copy — not just the source.
        if (targetExisted) {
            try {
                Files.copy(target.toPath(),
                        new File(dir, relocation.to() + ".pre-move.bak").toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                plugin.getLogger().severe("Could not back up " + relocation.to()
                        + "; the move from " + relocation.from() + " was skipped: "
                        + ex.getMessage());
                return;
            }
        }
        try {
            YamlConfiguration to = YamlFiles.load(target);
            // Overlaid leaf by leaf onto the commented jar defaults, so the new file keeps
            // its documentation and the owner keeps every value they had set.
            for (String section : present) overlay(to, from, section);
            YamlFiles.saveAtomic(to, target);

            for (String section : present) {
                from.set(section, null);
                from.setComments(section, null);
                from.setInlineComments(section, null);
            }
            YamlFiles.saveAtomic(from, source);
            plugin.getLogger().info("Moved " + String.join(", ", present) + " from "
                    + relocation.from() + " to " + relocation.to()
                    + " (your values were carried over; backups: "
                    + relocation.from() + ".pre-move.bak"
                    + (targetExisted ? " and " + relocation.to() + ".pre-move.bak" : "")
                    + ").");
        } catch (Exception ex) {
            plugin.getLogger().severe("Could not finish moving sections to "
                    + relocation.to() + ": " + ex.getMessage()
                    + ". Restore " + relocation.from() + ".pre-move.bak if anything looks off.");
        }
    }

    /**
     * Reparte las secciones que aun vivan en config.yml a su fichero de dominio.
     *
     * <p>Solo config.yml se reescribe con el default comentado, y solo porque acaba de quedar
     * respaldado y sus valores se vuelven a poner encima acto seguido. Un fichero de dominio
     * que YA existe no se toca: antes se reescribian todos con {@code saveResource(file,
     * true)} y, como los valores que se volvian a poner encima salian unicamente del viejo
     * config.yml, la configuracion que el dueño tuviera en chat.yml, players.yml,
     * moderation.yml… se perdia entera y sin copia de seguridad de ninguno de ellos. Bastaba
     * con que una version estrenase un fichero nuevo (store.yml, nametag.yml, giveaway.yml,
     * rentals.yml, gifts.yml se fueron añadiendo uno a uno) teniendo residuo en config.yml.
     *
     * <p>Por eso el fichero de dominio que ya existe manda sobre su propia seccion: si esa
     * seccion aparece ademas en config.yml, la de config.yml es residuo viejo, no la copia
     * buena — y se descarta en vez de escribirse encima de la que esta viva.
     */
    private static void migrateFromMonolithic(JavaPlugin plugin, File main) {
        YamlConfiguration old;
        try {
            old = YamlFiles.load(main);
        } catch (Exception ex) {
            plugin.getLogger().severe("Refusing to split invalid config.yml: "
                    + ex.getMessage() + ". Run /cht doctor and repair it first.");
            return;
        }
        File dir = plugin.getDataFolder();
        // Se captura ANTES de crear ninguno: en cuanto se escriban los que faltan dejan de
        // distinguirse de los que el dueño ya tenia configurados.
        Set<String> authoritative;
        try {
            authoritative = sectionsPresentOnDisk(dir);
        } catch (Exception ex) {
            // Sin poder leer un fichero de dominio no se sabe que copia es la buena, y
            // elegir a ciegas es como se perdia configuracion. Se deja todo como esta.
            plugin.getLogger().severe("Split migration was skipped because a per-domain file"
                    + " could not be read: " + ex.getMessage()
                    + ". Run /cht doctor and repair it first.");
            return;
        }
        try {
            Files.copy(main.toPath(), new File(dir, "config.yml.pre-split.bak").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            // Splitting overwrites config.yml. Without a verified backup, keeping the working
            // monolith for this run is safer than risking owner data.
            plugin.getLogger().severe("Could not back up config.yml; split migration was skipped: "
                    + ex.getMessage());
            return;
        }
        // Fresh, commented defaults for config.yml and for the files that do not exist yet.
        plugin.saveResource("config.yml", true);
        for (String file : LAYOUT.keySet()) {
            if (!new File(dir, file).exists()) plugin.saveResource(file, false);
        }
        plugin.reloadConfig();
        List<String> dropped = new ArrayList<>();
        for (String leaf : old.getKeys(true)) {
            Object value = old.get(leaf);
            if (value instanceof ConfigurationSection) continue;
            String section = topSection(leaf);
            if (authoritative.contains(section)) {
                // El fichero de dominio ya tiene esta seccion viva; lo de config.yml es residuo.
                if (!dropped.contains(section)) dropped.add(section);
                continue;
            }
            plugin.getConfig().set(leaf, value);
        }
        plugin.saveConfig();
        if (dropped.isEmpty()) {
            plugin.getLogger().info("Split config.yml into per-domain files ("
                    + String.join(", ", LAYOUT.keySet()) + "). Your previous file was backed up"
                    + " as config.yml.pre-split.bak.");
        } else {
            plugin.getLogger().info("Removed a stale copy of " + String.join(", ", dropped)
                    + " from config.yml; the live values in "
                    + String.join(", ", filesOwning(dropped)) + " were left untouched."
                    + " Your previous file was backed up as config.yml.pre-split.bak.");
        }
    }

    /** Los ficheros de dominio que ya estan escritos en disco. */
    private static Set<String> existingSplitFiles(File dir) {
        Set<String> present = new LinkedHashSet<>();
        for (String file : LAYOUT.keySet()) {
            if (new File(dir, file).exists()) present.add(file);
        }
        return present;
    }

    /**
     * Secciones que de verdad ESTAN escritas en su fichero de dominio.
     *
     * <p>Que el fichero exista no basta. Una instalacion a medio repartir puede tener un
     * players.yml valido que todavia no trae {@code Mail}, con la unica copia personalizada
     * de {@code Mail} en config.yml: dar la seccion por "ya vive en players.yml" tiraba esa
     * copia y dejaba los valores de fabrica.
     */
    private static Set<String> sectionsPresentOnDisk(File dir) throws Exception {
        Set<String> present = new LinkedHashSet<>();
        for (var entry : LAYOUT.entrySet()) {
            File file = new File(dir, entry.getKey());
            if (!file.exists()) continue;
            YamlConfiguration yaml = YamlFiles.load(file);
            for (String section : entry.getValue()) {
                if (yaml.contains(section)) present.add(section);
            }
        }
        return present;
    }

    /**
     * Secciones cuyo fichero de dominio ya existe. Cota superior de
     * {@link #sectionsPresentOnDisk}: la migracion usa aquella, que mira dentro del fichero.
     */
    static Set<String> authoritativeSections(Set<String> splitFilesOnDisk) {
        Set<String> owned = new LinkedHashSet<>();
        for (var entry : LAYOUT.entrySet()) {
            if (splitFilesOnDisk.contains(entry.getKey())) owned.addAll(entry.getValue());
        }
        return owned;
    }

    /** Primer segmento de una ruta de configuracion: {@code "Chat.Format"} -&gt; {@code "Chat"}. */
    static String topSection(String path) {
        int dot = path.indexOf('.');
        return dot < 0 ? path : path.substring(0, dot);
    }

    /** Los ficheros a los que pertenecen esas secciones, sin repetir, para el log. */
    private static List<String> filesOwning(List<String> sections) {
        List<String> files = new ArrayList<>();
        for (String section : sections) {
            String file = fileFor(section);
            if (!files.contains(file)) files.add(file);
        }
        return files;
    }

    private static boolean mainHasForeignSections(File main) {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(main);
        for (String key : cfg.getKeys(false)) {
            if (SECTION_FILE.containsKey(key)) return true; // a section that now lives elsewhere
        }
        return false;
    }
}
