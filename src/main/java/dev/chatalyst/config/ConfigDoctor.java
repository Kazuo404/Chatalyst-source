package dev.chatalyst.config;

import dev.chatalyst.util.YamlFiles;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Revisa config.yml al arrancar y, en vez de fallar en silencio, ayuda al dueño:
 *  - Si el YAML esta roto (sintaxis): respalda su archivo, regenera uno valido por defecto
 *    para que el server SIGA funcionando, y reporta EN QUE LINEA esta el error con contexto.
 *  - Si el YAML es valido: avisa de claves de primer nivel mal escritas (typos) sugiriendo
 *    la correcta, y de valores claramente invalidos (p. ej. Database.Type desconocido).
 * Nunca adivina "que quiso poner" el dueño (eso es peligroso): degrada seguro y da un
 * reporte accionable para que lo arregle el mismo.
 */
public final class ConfigDoctor {

    private static final Pattern LINE_IN_MSG = Pattern.compile("line (\\d+)");

    private ConfigDoctor() {
    }

    /** True si el config estaba sano; false si estaba roto y se recupero con defaults. */
    public static boolean check(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.exists()) return true; // saveDefaultConfig ya lo creo bien
        boolean healthy = true;
        YamlConfiguration test = new YamlConfiguration();
        try {
            test.load(file);
        } catch (InvalidConfigurationException e) {
            handleBrokenYaml(plugin, file, e);
            healthy = false;
        } catch (IOException e) {
            plugin.getLogger().severe("Could not read config.yml: " + e.getMessage());
            healthy = false;
        }
        if (healthy) {
            // YAML valido: avisos no fatales (el server arranca igual).
            warnUnknownKeys(plugin, test);
            warnInvalidValues(plugin, test);
        }
        for (String resource : knownYamlResources()) {
            File split = new File(plugin.getDataFolder(), resource);
            if (split.exists() && !checkResourceYaml(plugin, split, resource)) healthy = false;
        }
        return healthy;
    }

    /**
     * Diagnostico bajo demanda (/chatalyst doctor): revisa el config.yml en disco y devuelve
     * los problemas encontrados SIN tocar nada. Lista vacia = todo bien.
     */
    public static List<String> diagnose(JavaPlugin plugin) {
        List<String> out = new ArrayList<>();
        // Which of our commands another plugin actually answers. Decided by load order, so
        // it is invisible from configuration and worth stating outright.
        for (CommandConflictReport.Lost lost : CommandConflictReport.evaluate(plugin)) {
            out.add("/" + lost.label() + " is answered by " + lost.owner()
                    + ", not Chatalyst (use /chatalyst:" + lost.command()
                    + " for ours, or remove the other plugin's command)");
        }
        File file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.exists()) {
            out.add("config.yml not found");
            return out;
        }
        YamlConfiguration test = new YamlConfiguration();
        boolean mainValid = true;
        try {
            test.load(file);
        } catch (InvalidConfigurationException e) {
            int line = extractLine(e.getMessage());
            out.add("config.yml: YAML syntax error" + (line > 0 ? " at line " + line : "")
                    + ": " + firstLine(e.getMessage()));
            mainValid = false;
        } catch (IOException e) {
            out.add("Cannot read config.yml: " + e.getMessage());
            mainValid = false;
        }
        if (mainValid) {
            Set<String> known = defaultTopKeys(plugin);
            if (!known.isEmpty()) {
                for (String key : test.getKeys(false)) {
                    if (known.contains(key)) continue;
                    String s = closest(key, known);
                    out.add("Unknown setting '" + key + "'"
                            + (s != null ? " - did you mean '" + s + "'?" : ""));
                }
            }
            String dbType = test.getString("Database.Type");
            if (dbType != null && !dbType.equalsIgnoreCase("SQLITE")
                    && !dbType.equalsIgnoreCase("MYSQL")) {
                out.add("Database.Type '" + dbType + "' is not valid (use SQLITE or MYSQL)");
            }
        }
        for (String resource : knownYamlResources()) {
            File candidate = new File(plugin.getDataFolder(), resource);
            if (!candidate.exists()) continue;
            try {
                YamlFiles.load(candidate);
            } catch (InvalidConfigurationException ex) {
                int line = extractLine(ex.getMessage());
                out.add(resource + ": YAML syntax error"
                        + (line > 0 ? " at line " + line : "") + ": "
                        + firstLine(ex.getMessage()));
            } catch (IOException ex) {
                out.add("Cannot read " + resource + ": " + ex.getMessage());
            }
        }
        // EssentialsX Chat formatea el chat por el evento legacy y PISA el renderer moderno
        // de Chatalyst (mensajes planos pegados, sin boton de borrar ni hovers). Es un
        // reemplazo, no un complemento: hay que quitar su jar.
        if (org.bukkit.Bukkit.getPluginManager().getPlugin("EssentialsChat") != null) {
            out.add("EssentialsXChat is installed: it overrides Chatalyst's chat format"
                    + " (plain glued messages, no delete button/hover). Remove"
                    + " EssentialsXChat.jar - Chatalyst replaces it completely");
        }
        // Traductor en vivo: encendido sin endpoint queda SILENCIOSAMENTE apagado; avisa aqui.
        var merged = plugin.getConfig();
        if (merged.getBoolean("Store.Enabled", false)) {
            var catalog = merged.getConfigurationSection("Store_Catalog");
            if (catalog == null || catalog.getKeys(false).isEmpty()) {
                out.add("Store.Enabled is true but Store_Catalog is empty (store.yml)"
                        + " - every delivery command will be refused as an unknown product");
            } else {
                for (String productId : catalog.getKeys(false)) {
                    if (dev.chatalyst.store.StoreProduct.resolve(merged, productId)
                            .isEmpty()) {
                        out.add("Store product '" + productId + "' delivers nothing"
                                + " (no valid Items, Commands or Money) and is refused");
                    }
                }
            }
        }
        if (merged.getBoolean("Discord.Linking.Required.Enabled", false)
                && !merged.getBoolean("Discord.Enabled", false)) {
            out.add("Discord.Linking.Required.Enabled is true but Discord.Enabled is false"
                    + " - mandatory linking stays off, since players would have no bot to"
                    + " link against");
        }
        if (merged.getBoolean("Discord.Enabled", false)) {
            if (merged.getString("Discord.Bot_Token", "").isBlank()) {
                out.add("Discord.Enabled is true but Discord.Bot_Token is empty"
                        + " (integrations.yml) - the embedded bot cannot start");
            }
            String guildId = merged.getString("Discord.Guild_Id", "").trim();
            if (!dev.chatalyst.discord.DiscordBot.isSnowflake(guildId)) {
                out.add("Discord.Guild_Id is missing or invalid"
                        + " (copy the numeric server ID with Discord Developer Mode)");
            }
            String channelId = merged.getString("Discord.Channel_Id", "").trim();
            if (!dev.chatalyst.discord.DiscordBot.isSnowflake(channelId)) {
                out.add("Discord.Channel_Id is missing or invalid"
                        + " (copy the numeric text-channel ID with Discord Developer Mode)");
            }
            if (merged.getBoolean("Discord.Linking.Required.Enabled", false)
                    && !merged.getBoolean("Discord.Linking.Enabled", true)) {
                out.add("Discord.Linking.Required.Enabled is true but"
                        + " Discord.Linking.Enabled is false - nobody could ever link,"
                        + " so the requirement stays disabled (fail-open)");
            }
            if (merged.getBoolean("Discord.Rank_Sync.Enabled", false)) {
                var central = merged.getConfigurationSection("Rank_Catalog");
                boolean hasCentral = central != null && !central.getKeys(false).isEmpty();
                if (hasCentral) {
                    java.util.Map<String, String> groupRoles = new java.util.HashMap<>();
                    java.util.Map<String, String> roleGroups = new java.util.HashMap<>();
                    int usable = 0;
                    for (String rankId : central.getKeys(false)) {
                        var rank = central.getConfigurationSection(rankId);
                        if (!rankId.matches("[a-z0-9_\\-]{1,64}") || rank == null) {
                            out.add("Rank_Catalog." + rankId
                                    + " is not a valid Rank ID");
                            continue;
                        }
                        String group = rank.getString(
                                "LuckPerms_Group", rankId).trim()
                                .toLowerCase(java.util.Locale.ROOT);
                        String role = rank.getString("Discord_Role_Id", "").trim();
                        if (!group.matches("[a-z0-9_\\-]{1,64}")) {
                            out.add("Rank '" + rankId
                                    + "' has an invalid LuckPerms_Group");
                        }
                        if (!role.isBlank()
                                && !dev.chatalyst.discord.DiscordBot.isSnowflake(role)) {
                            out.add("Discord rank '" + rankId
                                    + "' has an invalid Discord_Role_Id");
                        } else if (!role.isBlank()) {
                            usable++;
                            String previousRole = groupRoles.putIfAbsent(group, role);
                            if (previousRole != null && !previousRole.equals(role)) {
                                out.add("LuckPerms group '" + group
                                        + "' points to multiple Discord roles");
                            }
                            String previousGroup = roleGroups.putIfAbsent(role, group);
                            if (previousGroup != null && !previousGroup.equals(group)) {
                                out.add("Discord role " + role
                                        + " points to multiple LuckPerms groups");
                            }
                        }
                    }
                    if (usable == 0) {
                        out.add("Discord.Rank_Sync.Enabled is true but no Rank_Catalog"
                                + " entry has a valid Discord_Role_Id");
                    }
                } else {
                    var mappings = merged.getConfigurationSection(
                            "Discord.Rank_Sync.Mappings");
                    var legacyMappings = merged.getConfigurationSection(
                            "Discord.Rank_Sync.Groups");
                    if ((mappings == null || mappings.getKeys(false).isEmpty())
                            && (legacyMappings == null
                            || legacyMappings.getKeys(false).isEmpty())) {
                        out.add("Discord.Rank_Sync.Enabled is true but Rank_Catalog is"
                                + " empty - no roles can be synchronized");
                    } else if (mappings != null && !mappings.getKeys(false).isEmpty()) {
                        java.util.Set<String> seenRoles = new java.util.HashSet<>();
                        for (String rankId : mappings.getKeys(false)) {
                            var rank = mappings.getConfigurationSection(rankId);
                            String role = rank == null ? ""
                                    : rank.getString("Discord_Role_Id", "").trim();
                            if (!dev.chatalyst.discord.DiscordBot.isSnowflake(role)) {
                                out.add("Legacy Discord mapping '" + rankId
                                        + "' has an invalid Discord_Role_Id");
                            } else if (!seenRoles.add(role)) {
                                out.add("Discord role " + role
                                        + " is mapped by more than one Rank ID");
                            }
                        }
                    } else {
                        java.util.Set<String> seenRoles = new java.util.HashSet<>();
                        for (String group : legacyMappings.getKeys(false)) {
                            String role = legacyMappings.getString(group, "").trim();
                            if (!dev.chatalyst.discord.DiscordBot.isSnowflake(role)) {
                                out.add("Discord.Rank_Sync.Groups." + group
                                        + " is not a valid numeric Discord role ID");
                            } else if (!seenRoles.add(role)) {
                                out.add("Discord role " + role
                                        + " is mapped to more than one LuckPerms group");
                            }
                        }
                    }
                }
                String direction = merged.getString(
                        "Discord.Rank_Sync.Direction", "BOTH");
                if (!java.util.Set.of("BOTH", "MINECRAFT_TO_DISCORD",
                                "LUCKPERMS_TO_DISCORD", "DISCORD_TO_MINECRAFT",
                                "DISCORD_TO_LUCKPERMS")
                        .contains(direction.toUpperCase(java.util.Locale.ROOT)
                                .replace('-', '_'))) {
                    out.add("Discord.Rank_Sync.Direction '" + direction
                            + "' is invalid (use BOTH, MINECRAFT_TO_DISCORD or"
                            + " DISCORD_TO_MINECRAFT)");
                }
            }
        } else if (merged.getBoolean("Discord.Rank_Sync.Enabled", false)) {
            out.add("Discord.Rank_Sync.Enabled is true but Discord.Enabled is false"
                    + " - LuckPerms remains normal, but role synchronization is inactive");
        }
        // A combination that is individually valid and together does nothing. Silence here
        // reads as "mandatory linking is on" when in practice it holds nobody, and the owner
        // only finds out by watching players walk past a gate they believe is closed.
        if (merged.getBoolean("Discord.Linking.Required.Enabled", false)
                && merged.getBoolean("Discord.Linking.Required.Only_Buyers", true)
                && !merged.getBoolean("Discord.Rank_Sync.Enabled", false)) {
            out.add("Discord.Linking.Required is on with Only_Buyers, but Rank_Sync is off:"
                    + " nothing is owed on Discord, so nobody is ever held. Enable Rank_Sync,"
                    + " or set Only_Buyers: false to hold everyone.");
        }
        if (merged.getBoolean("Tebex.Enabled", false)) {
            if (org.bukkit.Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                out.add("Tebex rank delivery is enabled but LuckPerms is not installed"
                        + " - purchase commands will fail safely");
            }
            if (!merged.getBoolean("Tebex.Console_Only", true)) {
                out.add("Tebex.Console_Only is false; keep it true unless trusted admins"
                        + " genuinely need to run purchase-delivery commands");
            }
            var central = merged.getConfigurationSection("Rank_Catalog");
            boolean hasCentral = central != null && !central.getKeys(false).isEmpty();
            var ranks = hasCentral ? central
                    : merged.getConfigurationSection("Tebex.Ranks");
            if (ranks == null || ranks.getKeys(false).isEmpty()) {
                out.add("Tebex.Enabled is true but Rank_Catalog is empty; configure stable"
                        + " Rank IDs before adding package commands");
            } else {
                int enabledRanks = 0;
                int publishedShopRanks = 0;
                for (String rankId : ranks.getKeys(false)) {
                    var rank = ranks.getConfigurationSection(rankId);
                    if (!rankId.matches("[a-z0-9_\\-]{1,64}") || rank == null) {
                        out.add((hasCentral ? "Rank_Catalog." : "Tebex.Ranks.")
                                + rankId + " is not a valid Rank ID");
                        continue;
                    }
                    String group = rank.getString("LuckPerms_Group", "").trim()
                            .toLowerCase(java.util.Locale.ROOT);
                    if (!group.matches("[a-z0-9_\\-]{1,64}")) {
                        out.add("Tebex rank '" + rankId
                                + "' has an invalid LuckPerms_Group");
                    }
                    if (hasCentral && !rank.getBoolean("Tebex_Enabled", false)) continue;
                    enabledRanks++;
                    String duration = rank.getString(
                            hasCentral ? "Tebex_Duration" : "Duration", "permanent");
                    if (dev.chatalyst.util.TimeUtil.parseDuration(duration) == 0L) {
                        out.add("Tebex rank '" + rankId
                                + "' has an invalid Duration (use permanent, 30d, 12h...)");
                    }
                    if (hasCentral) {
                        var shop = rank.getConfigurationSection("Shop");
                        if (shop != null && shop.getBoolean("Visible", false)) {
                            publishedShopRanks++;
                            validateTebexShopEntry(out, merged, rankId, shop);
                        }
                    }
                }
                if (hasCentral && enabledRanks == 0) {
                    out.add("Tebex.Enabled is true but no Rank_Catalog entry has"
                            + " Tebex_Enabled: true");
                }
                if (hasCentral && merged.getBoolean("Tebex.Shop.Enabled", false)
                        && publishedShopRanks == 0) {
                    out.add("Tebex.Shop.Enabled is true but no Tebex-enabled Rank_Catalog"
                            + " entry has Shop.Visible: true");
                }
            }
            String homeUrl = merged.getString("Tebex.Shop.Home_Url", "").trim();
            if (!homeUrl.isEmpty()
                    && dev.chatalyst.tebex.TebexShopEntry.resolvePurchaseUrl(
                    homeUrl, "store", "Player", new java.util.UUID(0L, 0L)).isEmpty()) {
                out.add("Tebex.Shop.Home_Url must be a valid HTTPS URL");
            }
            validateTebexPresentation(out, "Tebex.Presentation",
                    merged.getConfigurationSection("Tebex.Presentation"));
            if (hasCentral) {
                for (String rankId : central.getKeys(false)) {
                    var presentation = central.getConfigurationSection(
                            rankId + ".Presentation");
                    if (presentation != null
                            && !presentation.getBoolean("Use_Global", true)) {
                        validateTebexPresentation(out,
                                "Rank_Catalog." + rankId + ".Presentation",
                                presentation);
                    }
                }
            }
        }
        if (merged.getBoolean("Translation.Enabled", false)) {
            String url = merged.getString("Translation.Provider_Url", "").trim();
            if (url.isEmpty()) {
                out.add("Translation.Enabled is true but Translation.Provider_Url is empty"
                        + " (integrations.yml) - live chat translation stays OFF until you set it");
            } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
                out.add("Translation.Provider_Url '" + url
                        + "' does not look like an http(s) URL - live chat translation will fail open");
            }
        }
        if (merged.getBoolean("Proxy.Enabled", false)) {
            String mode = merged.getString("Proxy.Mode", "legacy");
            if (!dev.chatalyst.proxy.ProxyManager.isSupportedMode(mode)) {
                out.add("Proxy.Mode '" + mode
                        + "' is invalid (use native, legacy, both or redis); forwarding is blocked");
            } else if ("redis".equalsIgnoreCase(mode)
                    && !merged.getBoolean("Redis.Enabled", false)) {
                out.add("Proxy.Mode is redis but Redis.Enabled is false; forwarding is blocked");
            }
        }
        return out;
    }

    private static void validateTebexShopEntry(
            List<String> out,
            org.bukkit.configuration.file.FileConfiguration merged,
            String rankId,
            org.bukkit.configuration.ConfigurationSection shop) {
        String path = "Rank_Catalog." + rankId + ".Shop";
        String url = shop.getString("Purchase_Url", "").trim();
        if (url.isEmpty()) {
            url = merged.getString("Tebex.Shop.Rank_Url_Template", "").trim();
        }
        if (dev.chatalyst.tebex.TebexShopEntry.resolvePurchaseUrl(
                url, rankId, "Player", new java.util.UUID(0L, 0L)).isEmpty()) {
            out.add(path + " needs an exact HTTPS Purchase_Url or a valid"
                    + " Tebex.Shop.Rank_Url_Template");
        }
        String iconName = shop.getString("Icon", "EMERALD");
        if (iconName.indexOf(':') >= 0) {
            if (!iconName.matches("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,64}")) {
                out.add(path + ".Icon '" + iconName
                        + "' is not a valid ItemsAdder namespaced ID");
            } else if (!dev.chatalyst.hook.ItemsAdderHook.isAvailable()) {
                out.add(path + ".Icon uses ItemsAdder but ItemsAdder is not installed"
                        + " (the shop safely falls back to EMERALD)");
            } else if (dev.chatalyst.hook.ItemsAdderHook.item(iconName) == null) {
                out.add(path + ".Icon '" + iconName
                        + "' does not exist in the ItemsAdder registry");
            }
        } else {
            org.bukkit.Material icon = org.bukkit.Material.matchMaterial(iconName);
            if (icon == null || icon.isAir() || !icon.isItem()) {
                out.add(path + ".Icon '" + iconName + "' is not a valid item material");
            }
        }
        int order = shop.getInt("Order", 0);
        if (order < -10_000 || order > 10_000) {
            out.add(path + ".Order must be between -10000 and 10000");
        }
        String permission = shop.getString("View_Permission", "").trim();
        if (!permission.isEmpty()
                && !permission.matches("[a-z0-9_.\\-*]{1,128}")) {
            out.add(path + ".View_Permission is invalid");
        }
        List<String> description = shop.getStringList("Description");
        if (description.size() > 12
                || description.stream().anyMatch(line -> line.length() > 256)) {
            out.add(path + ".Description supports at most 12 lines of 256 characters");
        }
        for (String leaf : java.util.List.of(
                "Display_Name", "Price", "Selection_Message",
                "Button_Text", "Button_Hover")) {
            String value = shop.getString(leaf, "");
            int max = leaf.equals("Selection_Message") ? 1_024
                    : leaf.equals("Display_Name") ? 160 : 256;
            if (value.length() > max) {
                out.add(path + "." + leaf + " exceeds " + max + " characters");
            }
        }
    }

    private static void validateTebexPresentation(List<String> out, String path,
                                                   org.bukkit.configuration.ConfigurationSection section) {
        if (section == null) {
            out.add(path + " is missing");
            return;
        }
        String audience = section.getString("Audience", "PURCHASER")
                .trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("PURCHASER", "GLOBAL").contains(audience)) {
            out.add(path + ".Audience '" + audience
                    + "' is invalid (use PURCHASER or GLOBAL)");
        }
        String preset = section.getString("Preset", "SUBTLE")
                .trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("SUBTLE", "FANCY", "SILENT", "CUSTOM")
                .contains(preset)) {
            out.add(path + ".Preset '" + preset
                    + "' is invalid (use SUBTLE, FANCY, SILENT or CUSTOM)");
        }
        int fadeIn = section.getInt("Title.Fade_In_Ticks", 10);
        int stay = section.getInt("Title.Stay_Ticks", 60);
        int fadeOut = section.getInt("Title.Fade_Out_Ticks", 20);
        if (fadeIn < 0 || fadeIn > 200 || stay < 1 || stay > 1200
                || fadeOut < 0 || fadeOut > 200) {
            out.add(path + " has invalid Title timing values");
        }
        double volume = section.getDouble("Sound.Volume", 1.0);
        double pitch = section.getDouble("Sound.Pitch", 1.0);
        if (!Double.isFinite(volume) || volume < 0.0 || volume > 10.0
                || !Double.isFinite(pitch) || pitch < 0.5 || pitch > 2.0) {
            out.add(path + " has invalid Sound volume/pitch values");
        }
        String iconName = section.getString("Toast.Icon", "EMERALD");
        org.bukkit.Material icon = org.bukkit.Material.matchMaterial(iconName);
        if (icon == null || icon.isAir() || !icon.isItem()) {
            out.add(path + ".Toast.Icon '" + iconName
                    + "' is not a valid item material");
        }
        String frame = section.getString("Toast.Frame", "goal")
                .trim().toLowerCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("task", "goal", "challenge").contains(frame)) {
            out.add(path + ".Toast.Frame '" + frame
                    + "' is invalid (use task, goal or challenge)");
        }
        for (String textPath : java.util.List.of(
                "Chat.Message", "Toast.Title", "Toast.Description",
                "Title.Title", "Title.Subtitle", "ActionBar.Message")) {
            // La traduccion se revisa igual que el original: un texto de 2000 caracteres se
            // recorta en silencio, y enterarse de eso solo en español es peor que no avisar.
            for (String localized : java.util.List.of(textPath, textPath + "_es")) {
                String value = section.getString(localized, "");
                if (value != null && value.length() > 1_024) {
                    out.add(path + "." + localized + " exceeds 1024 characters");
                }
            }
        }
    }

    // ---- YAML roto ------------------------------------------------------------------

    private static void handleBrokenYaml(JavaPlugin plugin, File file, InvalidConfigurationException e) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        File backup = new File(plugin.getDataFolder(), "config-broken-" + stamp + ".yml");
        int errorLine = extractLine(e.getMessage());
        List<String> lines = readLines(file);

        try {
            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException backupFailure) {
            plugin.getLogger().severe("config.yml is invalid and could not be backed up; refusing"
                    + " to overwrite it: " + backupFailure.getMessage());
            return;
        }

        // Donde estan los DATOS no se puede adivinar. Regenerar el archivo con los valores de
        // fabrica dejaba Database.Enabled en false: un servidor con MySQL arrancaba contra un
        // YAML local vacio, sin sanciones, sin correo y sin depositos del mercado, y nada en
        // el chat lo delataba. Se rescata el bloque Database del archivo roto; si es justo
        // ese bloque el ilegible, no se arranca.
        SectionRescue database = rescueSection(lines, "Database");
        if (database.unreadable()) {
            storageConfigUnreadable = true;
            var log = plugin.getLogger();
            log.severe("==============================================================");
            log.severe(" Your config.yml has a YAML syntax error inside (or around) the");
            log.severe(" Database section, so Chatalyst cannot know where your data lives.");
            log.severe(" Details: " + firstLine(e.getMessage()));
            log.severe(" Starting on default storage would hide every ban, mail and market");
            log.severe(" deposit you have, so Chatalyst is NOT starting. Nothing was changed:");
            log.severe(" fix config.yml (a copy is in " + backup.getName() + ") and restart.");
            log.severe("==============================================================");
            return;
        }

        // Rescata la License.Key del archivo roto (aunque el YAML no parsee, la linea suele
        // ser legible) para que el server pueda ARRANCAR igual en vez de morir por la licencia.
        String rescuedKey = rescueValue(lines, "Key");

        // Regenera un config valido para que el plugin funcione con defaults.
        boolean recoveredKey = false;
        if (file.delete()) {
            plugin.saveDefaultConfig();
            boolean hasKey = rescuedKey != null && !rescuedKey.isEmpty();
            if (hasKey || database.section() != null) {
                try {
                    YamlConfiguration recovered = YamlFiles.load(file);
                    if (hasKey) recovered.set("License.Key", rescuedKey);
                    if (database.section() != null) {
                        for (String leaf : database.section().getKeys(true)) {
                            if (database.section().isConfigurationSection(leaf)) continue;
                            recovered.set("Database." + leaf, database.section().get(leaf));
                        }
                    }
                    YamlFiles.saveAtomic(recovered, file);
                    recoveredKey = hasKey;
                } catch (Exception saveFailure) {
                    plugin.getLogger().severe("Could not restore License.Key / Database: "
                            + saveFailure.getMessage());
                    // Sin el bloque Database restaurado el arranque iria contra otro almacen.
                    if (database.section() != null) storageConfigUnreadable = true;
                }
            }
        }

        var log = plugin.getLogger();
        log.severe("==============================================================");
        log.severe(" Your config.yml has a YAML syntax error and could not be read.");
        if (errorLine > 0) {
            log.severe(" Problem around line " + errorLine + ":");
            for (String ctx : contextAround(lines, errorLine)) log.severe("   " + ctx);
        }
        log.severe(" Details: " + firstLine(e.getMessage()));
        log.severe("");
        log.severe(" What Chatalyst did so you don't lose anything:");
        log.severe("   - Saved your broken file as " + backup.getName());
        log.severe("   - Started with default settings so the server keeps working");
        if (recoveredKey) {
            log.severe("   - Recovered your License.Key so the server still starts");
        }
        log.severe(" Fix the line above in the backup, copy it back to config.yml,");
        log.severe(" then run /chatalyst reload. Tip: /chatalyst doctor re-checks it.");
        log.severe("==============================================================");
    }

    /** True si no se pudo saber donde vive el almacenamiento: el plugin no debe arrancar. */
    private static volatile boolean storageConfigUnreadable;

    public static boolean storageConfigUnreadable() {
        return storageConfigUnreadable;
    }

    /**
     * Un bloque de primer nivel rescatado de un YAML que no parsea entero.
     * {@code section == null && !unreadable} = el bloque no estaba en el archivo.
     */
    record SectionRescue(org.bukkit.configuration.ConfigurationSection section,
                         boolean unreadable) {
    }

    /**
     * Saca el bloque {@code name:} de primer nivel leyendo lineas, y lo parsea SOLO.
     *
     * <p>El error de sintaxis suele estar en otra parte del archivo, asi que el bloque casi
     * siempre se lee bien aunque el archivo entero no. Si el bloque mismo no parsea se dice,
     * en vez de devolver "no estaba": quien llama tiene que distinguir "usa los valores de
     * fabrica" de "aqui habia algo y no lo entiendo".
     */
    static SectionRescue rescueSection(List<String> lines, String name) {
        StringBuilder block = new StringBuilder();
        boolean inside = false;
        for (String line : lines) {
            if (!inside) {
                if (line.startsWith(name + ":")) {
                    inside = true;
                    block.append(line).append('\n');
                }
                continue;
            }
            // Otra clave de primer nivel cierra el bloque; lineas vacias y comentarios no.
            boolean topLevel = !line.isEmpty() && !Character.isWhitespace(line.charAt(0))
                    && line.charAt(0) != '#';
            if (topLevel) break;
            block.append(line).append('\n');
        }
        if (!inside) return new SectionRescue(null, false);
        YamlConfiguration parsed = new YamlConfiguration();
        try {
            parsed.loadFromString(block.toString());
        } catch (InvalidConfigurationException broken) {
            return new SectionRescue(null, true);
        }
        org.bukkit.configuration.ConfigurationSection section =
                parsed.getConfigurationSection(name);
        // "Database:" seguido de algo que no es un mapa tampoco se puede interpretar.
        return section == null ? new SectionRescue(null, true)
                : new SectionRescue(section, false);
    }

    private static boolean checkResourceYaml(JavaPlugin plugin, File file, String resource) {
        try {
            YamlFiles.load(file);
            return true;
        } catch (InvalidConfigurationException ex) {
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            String safe = resource.replace('/', '-').replace('\\', '-');
            File backup = new File(plugin.getDataFolder(), safe + ".broken-" + stamp + ".bak");
            try {
                Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException backupFailure) {
                plugin.getLogger().severe(resource + " is invalid and backup failed; refusing to"
                        + " overwrite it: " + backupFailure.getMessage());
                return false;
            }
            try (var bundled = plugin.getResource(resource)) {
                if (bundled == null) {
                    plugin.getLogger().severe(resource + " is invalid. Preserved as "
                            + backup.getName() + "; no bundled default exists, so repair it manually.");
                    return false;
                }
            } catch (IOException resourceFailure) {
                plugin.getLogger().severe("Could not inspect bundled " + resource + ": "
                        + resourceFailure.getMessage());
                return false;
            }
            try {
                plugin.saveResource(resource, true);
                plugin.getLogger().severe(resource + " was invalid and was restored from defaults."
                        + " The original is preserved as " + backup.getName() + ".");
            } catch (RuntimeException restoreFailure) {
                plugin.getLogger().severe("Could not restore " + resource + ": "
                        + restoreFailure.getMessage());
            }
            return false;
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not read " + resource + ": " + ex.getMessage());
            return false;
        }
    }

    private static List<String> knownYamlResources() {
        List<String> files = new ArrayList<>(ConfigFiles.LAYOUT.keySet());
        files.addAll(List.of("messages.yml", "rules.yml", "events.yml",
                "formats/chat.yml", "formats/staff.yml", "formats/trade.yml",
                "formats/auction.yml", "formats/pm-sender.yml", "formats/pm-receiver.yml",
                "formats/spy.yml", "lang/en_US.yml", "lang/es_ES.yml"));
        return List.copyOf(files);
    }

    /** Extrae "line N" del mensaje de SnakeYAML (1-indexado). 0 si no se encuentra. */
    private static int extractLine(String msg) {
        if (msg == null) return 0;
        Matcher m = LINE_IN_MSG.matcher(msg);
        int line = 0;
        while (m.find()) line = Integer.parseInt(m.group(1)); // el ultimo suele ser el util
        return line;
    }

    /** Muestra las lineas alrededor del error con una flecha en la culpable. */
    private static List<String> contextAround(List<String> lines, int errorLine) {
        List<String> out = new ArrayList<>();
        int from = Math.max(1, errorLine - 2);
        int to = Math.min(lines.size(), errorLine + 1);
        for (int i = from; i <= to; i++) {
            String prefix = i == errorLine ? " >> " : "    ";
            out.add(prefix + i + " | " + lines.get(i - 1));
        }
        return out;
    }

    private static List<String> readLines(File file) {
        try {
            return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Lee el valor de la primera linea "clave: valor" del archivo roto (sin parsear YAML). */
    // Forma de una clave de licencia del owner: "<base64url>.<base64url>" (Ed25519 firmada).
    private static final Pattern LICENSE_TOKEN =
            Pattern.compile("[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}");

    static String rescueValue(List<String> lines, String key) {
        // Primero: cualquier cosa con forma de clave de licencia, en cualquier parte del archivo.
        // Asi un pegado chueco (Key: duplicado, o un "License:" metido dentro del valor) recupera
        // el token real en vez de la basura de una linea anterior.
        for (String line : lines) {
            Matcher token = LICENSE_TOKEN.matcher(line);
            if (token.find()) return token.group();
        }
        // Fallback: el primer valor "key:", sin comillas ni comentario (comportamiento previo).
        Pattern p = Pattern.compile("^\\s*" + Pattern.quote(key) + ":\\s*(.+?)\\s*$");
        for (String line : lines) {
            Matcher m = p.matcher(line);
            if (m.matches()) {
                String v = m.group(1).trim();
                // Quita comillas y un posible comentario al final.
                int hash = v.indexOf(" #");
                if (hash > 0) v = v.substring(0, hash).trim();
                if (v.length() >= 2 && (v.charAt(0) == '"' || v.charAt(0) == '\'')
                        && v.charAt(v.length() - 1) == v.charAt(0)) {
                    v = v.substring(1, v.length() - 1);
                }
                return v;
            }
        }
        return null;
    }

    private static String firstLine(String msg) {
        if (msg == null) return "unknown";
        int nl = msg.indexOf('\n');
        return nl > 0 ? msg.substring(0, nl) : msg;
    }

    // ---- Avisos no fatales (YAML valido) --------------------------------------------

    /** Claves de primer nivel que no existen en el config por defecto -> posible typo. */
    private static void warnUnknownKeys(JavaPlugin plugin, YamlConfiguration user) {
        Set<String> known = defaultTopKeys(plugin);
        if (known.isEmpty()) return;
        for (String key : user.getKeys(false)) {
            if (known.contains(key)) continue;
            String suggestion = closest(key, known);
            if (suggestion != null) {
                plugin.getLogger().warning("config.yml: unknown setting '" + key
                        + "' - did you mean '" + suggestion + "'? (ignored)");
            } else {
                plugin.getLogger().warning("config.yml: unknown top-level setting '" + key + "' (ignored)");
            }
        }
    }

    /** Valores con un conjunto cerrado de opciones que estan fuera de ese conjunto. */
    private static void warnInvalidValues(JavaPlugin plugin, YamlConfiguration user) {
        String dbType = user.getString("Database.Type");
        if (dbType != null && !dbType.equalsIgnoreCase("SQLITE") && !dbType.equalsIgnoreCase("MYSQL")) {
            plugin.getLogger().warning("config.yml: Database.Type '" + dbType
                    + "' is not valid (use SQLITE or MYSQL). Falling back to SQLITE.");
        }
        String proxyMode = plugin.getConfig().getString("Proxy.Mode", "legacy");
        if (plugin.getConfig().getBoolean("Proxy.Enabled", false)
                && !dev.chatalyst.proxy.ProxyManager.isSupportedMode(proxyMode)) {
            plugin.getLogger().warning("Proxy.Mode '" + proxyMode
                    + "' is invalid; cross-server forwarding will remain disabled.");
        }
    }

    private static Set<String> defaultTopKeys(JavaPlugin plugin) {
        Set<String> keys = new LinkedHashSet<>();
        try (InputStreamReader r = new InputStreamReader(
                plugin.getResource("config.yml"), StandardCharsets.UTF_8)) {
            YamlConfiguration def = YamlConfiguration.loadConfiguration(r);
            keys.addAll(def.getKeys(false));
        } catch (Exception ignore) {
            // sin recurso base no podemos comparar; no pasa nada
        }
        return keys;
    }

    /** Sugerencia mas cercana por distancia de edicion (solo si es razonablemente parecida). */
    private static String closest(String input, Set<String> options) {
        String lc = input.toLowerCase(Locale.ROOT);
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String opt : options) {
            int d = levenshtein(lc, opt.toLowerCase(Locale.ROOT));
            if (d < bestDist) {
                bestDist = d;
                best = opt;
            }
        }
        // Acepta la sugerencia solo si el cambio es pequeño respecto al largo de la palabra.
        return best != null && bestDist <= Math.max(2, input.length() / 3) ? best : null;
    }

    private static int levenshtein(String a, String b) {
        return dev.chatalyst.util.TextDistance.levenshtein(a, b);
    }
}
