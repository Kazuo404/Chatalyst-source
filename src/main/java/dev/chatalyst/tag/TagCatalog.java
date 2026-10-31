package dev.chatalyst.tag;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.api.title.TitleCatalogChangeType;
import dev.chatalyst.util.YamlFiles;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Catalogo durable de titulos administrables. Cada mutacion se guarda de forma atomica antes
 * de publicar el nuevo snapshot, de modo que una falla de disco nunca deja la GUI mostrando
 * cambios que no sobreviviran un reinicio.
 */
public final class TagCatalog {

    public static final int MAX_TITLES = 500;

    private final ChatalystPlugin plugin;
    private final File file;
    private volatile Map<String, TagDefinition> definitions = Map.of();
    private volatile Map<String, TagCollectionStyle> collectionStyles = Map.of();
    private volatile List<TagCollection> collectionSnapshot = List.of();
    private volatile Set<TagRequirementType> requirementTypes = Set.of();
    private volatile boolean notifyUnlock = true;
    private volatile boolean autoEquipFirst = false;
    // false = this server keeps the shared titles (unlocks, selection, points) but never shows
    // them: a network may equip titles on one server only.
    private volatile boolean showOnThisServer = true;
    // Settings.Rarity_Tooltip_Style: estilo de tooltip (componente tooltip_style, "ns:id" del
    // paquete de recursos) por rareza para los titulos en los menus. Vacio = tooltip vanilla.
    private volatile Map<TagRarity, String> rarityTooltipStyles = Map.of();
    // Titulos del archivo que la carga NO pudo usar (por encima del limite, invalidos o con
    // id repetido), tal cual estaban. save() reescribe tags.yml entero desde memoria, asi que
    // sin esto cualquier edicion desde la GUI los borraba del disco en silencio.
    private volatile Map<String, Map<String, Object>> unloadedTitles = Map.of();
    private volatile TagCatalogLifecycleListener lifecycle =
            TagCatalogLifecycleListener.NOOP;

    public TagCatalog(ChatalystPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "tags.yml");
    }

    /** Pure in-memory snapshot used by domain contract tests; catalog mutations are disabled. */
    TagCatalog(List<TagDefinition> initialDefinitions) {
        this.plugin = null;
        this.file = null;
        LinkedHashMap<String, TagDefinition> initial = new LinkedHashMap<>();
        if (initialDefinitions != null) {
            for (TagDefinition definition : initialDefinitions) {
                if (definition != null) initial.put(definition.id(), definition);
            }
        }
        this.definitions = immutable(initial);
        this.requirementTypes = requirementTypes(initial);
        this.collectionSnapshot = buildCollections(this.definitions, Map.of());
    }

    public synchronized void load() {
        if (!file.exists()) plugin.saveResource("tags.yml", false);
        YamlConfiguration yaml;
        try {
            yaml = YamlFiles.load(file);
        } catch (Exception broken) {
            plugin.getLogger().severe("tags.yml has a syntax error; keeping the catalog already"
                    + " in memory. Fix the file and reload: " + broken.getMessage());
            return;
        }

        LinkedHashMap<String, TagDefinition> loaded = new LinkedHashMap<>();
        LinkedHashMap<String, Map<String, Object>> skipped = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("Titles");
        if (section != null) {
            boolean overLimit = false;
            for (String rawId : section.getKeys(false)) {
                ConfigurationSection entry = section.getConfigurationSection(rawId);
                if (entry == null) continue;
                if (loaded.size() >= MAX_TITLES) {
                    if (!overLimit) {
                        plugin.getLogger().warning("tags.yml exceeds the safe limit of "
                                + MAX_TITLES + " titles; remaining entries were ignored (they"
                                + " stay in the file).");
                        overLimit = true;
                    }
                    skipped.put(rawId, toMap(entry));
                    continue;
                }
                try {
                    TagDefinition definition = read(rawId, entry,
                            problem -> plugin.getLogger().warning("Title '" + rawId + "': "
                                    + problem));
                    if (loaded.putIfAbsent(definition.id(), definition) != null) {
                        plugin.getLogger().warning("Duplicate normalized title id in tags.yml: "
                                + rawId);
                        skipped.put(rawId, toMap(entry));
                    }
                } catch (IllegalArgumentException invalid) {
                    plugin.getLogger().warning("Ignoring invalid title '" + rawId + "': "
                            + invalid.getMessage());
                    skipped.put(rawId, toMap(entry));
                }
            }
        }
        requirementTypes = requirementTypes(loaded);
        unloadedTitles = Collections.unmodifiableMap(skipped);
        Map<String, TagDefinition> loadedDefinitions = immutable(loaded);
        Map<String, TagCollectionStyle> loadedStyles = readCollectionStyles(yaml);
        definitions = loadedDefinitions;
        collectionStyles = loadedStyles;
        collectionSnapshot = buildCollections(loadedDefinitions, loadedStyles);
        if (TagDependencies.hasCycle(definitions)) {
            plugin.getLogger().severe("tags.yml contains a cyclic title dependency. "
                    + "Affected titles cannot complete until the cycle is corrected.");
        }
        Set<String> missing = TagDependencies.missingTargets(definitions);
        if (!missing.isEmpty()) {
            plugin.getLogger().warning("tags.yml contains missing title dependencies: "
                    + String.join(", ", missing));
        }
        notifyUnlock = yaml.getBoolean("Settings.Notify_Unlock", true);
        autoEquipFirst = yaml.getBoolean("Settings.Auto_Equip_First", false);
        showOnThisServer = yaml.getBoolean("Settings.Show_On_This_Server", true);
        rarityTooltipStyles = readTooltipStyles(yaml.getConfigurationSection("Settings.Rarity_Tooltip_Style"));
    }

    static Map<TagRarity, String> readTooltipStyles(ConfigurationSection section) {
        if (section == null) return Map.of();
        Map<TagRarity, String> styles = new java.util.EnumMap<>(TagRarity.class);
        for (TagRarity rarity : TagRarity.values()) {
            String raw = section.getString(rarity.name(), "").trim().toLowerCase(Locale.ROOT);
            if (raw.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) styles.put(rarity, raw);
        }
        return Map.copyOf(styles);
    }

    public List<TagDefinition> all() {
        return definitions.values().stream()
                .sorted(Comparator.comparingInt(TagDefinition::sortOrder)
                        .thenComparing(TagDefinition::category, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(TagDefinition::displayName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(TagDefinition::id))
                .toList();
    }

    void setLifecycleListener(TagCatalogLifecycleListener listener) {
        lifecycle = listener == null ? TagCatalogLifecycleListener.NOOP : listener;
    }

    public List<String> categories() {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        List<String> categories = new ArrayList<>();
        for (TagDefinition definition : all()) {
            if (normalized.add(definition.category().toLowerCase(Locale.ROOT))) {
                categories.add(definition.category());
            }
        }
        return List.copyOf(categories);
    }

    public List<TagCollection> collections() {
        return collectionSnapshot;
    }

    private static List<TagCollection> buildCollections(
            Map<String, TagDefinition> definitions,
            Map<String, TagCollectionStyle> styles) {
        LinkedHashMap<String, List<TagDefinition>> grouped = new LinkedHashMap<>();
        LinkedHashMap<String, String> categoryNames = new LinkedHashMap<>();
        List<TagDefinition> sortedDefinitions = definitions.values().stream()
                .sorted(Comparator.comparingInt(TagDefinition::sortOrder)
                        .thenComparing(TagDefinition::category,
                                String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(TagDefinition::displayName,
                                String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(TagDefinition::id))
                .toList();
        for (TagDefinition definition : sortedDefinitions) {
            String key = definition.category().toLowerCase(Locale.ROOT);
            categoryNames.putIfAbsent(key, definition.category());
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(definition);
        }
        List<TagCollection> result = new ArrayList<>();
        for (Map.Entry<String, List<TagDefinition>> entry : grouped.entrySet()) {
            String category = categoryNames.get(entry.getKey());
            String id = TagCollection.idFor(category);
            TagCollectionStyle style = styles.get(id);
            Material defaultIcon = entry.getValue().isEmpty()
                    ? Material.BUNDLE : entry.getValue().getFirst().icon();
            result.add(new TagCollection(id, category,
                    style == null ? category : style.displayName(),
                    style == null ? "" : style.description(),
                    style == null ? defaultIcon : style.icon(),
                    style == null ? 0 : style.bonusPoints(),
                    entry.getValue()));
        }
        result.sort(Comparator.comparing(TagCollection::displayName,
                String.CASE_INSENSITIVE_ORDER).thenComparing(TagCollection::id));
        return List.copyOf(result);
    }

    public TagCollection collectionById(String id) {
        String normalized = TagDefinition.normalizeId(id);
        for (TagCollection collection : collections()) {
            if (collection.id().equals(normalized)
                    || collection.category().equalsIgnoreCase(id)) {
                return collection;
            }
        }
        return null;
    }

    public TagDefinition byId(String id) {
        return definitions.get(TagDefinition.normalizeId(id));
    }

    public boolean notifyUnlock() {
        return notifyUnlock;
    }

    public boolean autoEquipFirst() {
        return autoEquipFirst;
    }

    /** Whether this server renders equipped titles ({tag_prefix}/{tag_suffix}, %chatalyst_title_*%). */
    public boolean showOnThisServer() {
        return showOnThisServer;
    }

    /** Estilo de tooltip ("ns:id") de los titulos de esa rareza en los menus, o null si no hay. */
    public String tooltipStyle(TagRarity rarity) {
        return rarity == null ? null : rarityTooltipStyles.get(rarity);
    }

    public boolean isFull() {
        return definitions.size() >= MAX_TITLES;
    }

    public boolean usesRequirement(TagRequirementType type) {
        return type != null && requirementTypes.contains(type);
    }

    public synchronized boolean setNotifyUnlock(boolean value) {
        if (notifyUnlock == value) return true;
        boolean previous = notifyUnlock;
        notifyUnlock = value;
        if (save(definitions)) return true;
        notifyUnlock = previous;
        return false;
    }

    public synchronized boolean setAutoEquipFirst(boolean value) {
        if (autoEquipFirst == value) return true;
        boolean previous = autoEquipFirst;
        autoEquipFirst = value;
        if (save(definitions)) return true;
        autoEquipFirst = previous;
        return false;
    }

    /** Persists both switches in one atomic catalog write. */
    public synchronized boolean setSettings(boolean nextNotifyUnlock,
                                            boolean nextAutoEquipFirst) {
        if (notifyUnlock == nextNotifyUnlock
                && autoEquipFirst == nextAutoEquipFirst) return true;
        boolean previousNotify = notifyUnlock;
        boolean previousAutoEquip = autoEquipFirst;
        notifyUnlock = nextNotifyUnlock;
        autoEquipFirst = nextAutoEquipFirst;
        if (save(definitions)) return true;
        notifyUnlock = previousNotify;
        autoEquipFirst = previousAutoEquip;
        return false;
    }

    public synchronized boolean toggleNotifyUnlock() {
        return setNotifyUnlock(!notifyUnlock);
    }

    public synchronized boolean toggleAutoEquipFirst() {
        return setAutoEquipFirst(!autoEquipFirst);
    }

    public synchronized boolean updateCollection(TagCollection updated) {
        if (updated == null || collectionById(updated.id()) == null) return false;
        Map<String, TagCollectionStyle> previous = collectionStyles;
        LinkedHashMap<String, TagCollectionStyle> next = new LinkedHashMap<>(previous);
        next.put(updated.id(), new TagCollectionStyle(
                updated.category(), updated.displayName(), updated.description(),
                updated.icon(), updated.bonusPoints()));
        collectionStyles = Collections.unmodifiableMap(next);
        if (save(definitions)) {
            collectionSnapshot = buildCollections(definitions, collectionStyles);
            return true;
        }
        collectionStyles = previous;
        return false;
    }

    public TagDefinition create() {
        return announce(createLocked());
    }

    private synchronized Committed createLocked() {
        if (definitions.size() >= MAX_TITLES) return null;
        String base = "title-" + Long.toHexString(System.currentTimeMillis());
        String id = base;
        for (int suffix = 2; definitions.containsKey(id); suffix++) {
            id = base + "-" + suffix;
        }
        TagDefinition created = new TagDefinition(id, "Nuevo título", "<#B56CFF>[Título]",
                "Edita todos sus campos desde este panel.", Material.NAME_TAG,
                TagPosition.LEFT, 1, false, false, "General", TagRarity.COMMON,
                definitions.size() * 10, TagRequirementGroup.free(), TagRewards.none());
        LinkedHashMap<String, TagDefinition> next = new LinkedHashMap<>(definitions);
        next.put(id, created);
        if (!publish(next)) return null;
        return new Committed(TitleCatalogChangeType.CREATED, null, created);
    }

    public TagDefinition duplicate(String sourceId) {
        return announce(duplicateLocked(sourceId));
    }

    private synchronized Committed duplicateLocked(String sourceId) {
        if (definitions.size() >= MAX_TITLES) return null;
        TagDefinition source = byId(sourceId);
        if (source == null) return null;
        String id = uniqueId(source.id() + "-copy");
        TagDefinition copy = new TagDefinition(id, source.displayName() + " copy",
                source.text(), source.description(), source.icon(), source.position(),
                source.spacing(), false, source.hiddenUntilUnlocked(), source.category(),
                source.rarity(), source.sortOrder() + 1, source.points(), source.secret(),
                source.season(), source.requirements(), source.rewards(),
                source.achievement());
        LinkedHashMap<String, TagDefinition> next = new LinkedHashMap<>(definitions);
        next.put(id, copy);
        if (!publish(next)) return null;
        return new Committed(TitleCatalogChangeType.DUPLICATED, source, copy);
    }

    /** Adds a caller-authored definition after full graph validation. */
    public boolean insert(TagDefinition created) {
        return announce(insertLocked(created)) != null;
    }

    private synchronized Committed insertLocked(TagDefinition created) {
        if (!canInsert(created)) return null;
        LinkedHashMap<String, TagDefinition> next = new LinkedHashMap<>(definitions);
        next.put(created.id(), created);
        if (!publish(next)) return null;
        return new Committed(TitleCatalogChangeType.CREATED, null, created);
    }

    public synchronized boolean canInsert(TagDefinition created) {
        if (created == null || definitions.size() >= MAX_TITLES
                || definitions.containsKey(created.id())) return false;
        LinkedHashMap<String, TagDefinition> next = new LinkedHashMap<>(definitions);
        next.put(created.id(), created);
        return !TagDependencies.hasCycle(next)
                && !TagDependencies.hasMissingTarget(created, next);
    }

    private String uniqueId(String preferred) {
        String base = TagDefinition.normalizeId(preferred);
        if (!definitions.containsKey(base)) return base;
        for (int suffix = 2; suffix < 10_000; suffix++) {
            String marker = "-" + suffix;
            int length = Math.min(base.length(), 48 - marker.length());
            String candidate = base.substring(0, length) + marker;
            if (!definitions.containsKey(candidate)) return candidate;
        }
        return "title-" + Long.toHexString(System.nanoTime());
    }

    public boolean update(TagDefinition updated) {
        return announce(updateLocked(updated)) != null;
    }

    private synchronized Committed updateLocked(TagDefinition updated) {
        if (!canUpdate(updated)) return null;
        TagDefinition previous = definitions.get(updated.id());
        LinkedHashMap<String, TagDefinition> next = new LinkedHashMap<>(definitions);
        next.put(updated.id(), updated);
        if (!publish(next)) return null;
        return new Committed(TitleCatalogChangeType.UPDATED, previous, updated);
    }

    public synchronized boolean canUpdate(TagDefinition updated) {
        if (updated == null || !definitions.containsKey(updated.id())) return false;
        LinkedHashMap<String, TagDefinition> next = new LinkedHashMap<>(definitions);
        next.put(updated.id(), updated);
        return !TagDependencies.hasCycle(next)
                && !TagDependencies.hasMissingTarget(updated, next);
    }

    public boolean wouldCreateDependencyCycle(TagDefinition updated) {
        if (updated == null || !definitions.containsKey(updated.id())) return true;
        LinkedHashMap<String, TagDefinition> next = new LinkedHashMap<>(definitions);
        next.put(updated.id(), updated);
        return TagDependencies.hasCycle(next);
    }

    public boolean contains(String id) {
        return byId(id) != null;
    }

    public int dependentCount(String id) {
        return TagDependencies.dependentCount(definitions, id);
    }

    public boolean remove(String id) {
        Committed change = removeLocked(id);
        if (change == null) return false;
        announceChange(change);
        return true;
    }

    private synchronized Committed removeLocked(String id) {
        String normalized = TagDefinition.normalizeId(id);
        if (!definitions.containsKey(normalized)) return null;
        if (TagDependencies.dependentCount(definitions, normalized) > 0) return null;
        TagDefinition previous = definitions.get(normalized);
        LinkedHashMap<String, TagDefinition> next = new LinkedHashMap<>(definitions);
        next.remove(normalized);
        if (!publish(next)) return null;
        return new Committed(TitleCatalogChangeType.DELETED, previous, null);
    }

    /** Cambio ya persistido y publicado, pendiente solo de avisar a los oyentes. */
    private record Committed(TitleCatalogChangeType type, TagDefinition previous,
                             TagDefinition current) {
    }

    /**
     * Avisa del cambio con el monitor ya liberado. El oyente publico acaba en
     * {@code callEvent}, y un plugin de terceros que se bloquee ahi dentro dejaria el
     * catalogo entero congelado si lo llamaramos dentro del bloque sincronizado.
     */
    private TagDefinition announce(Committed change) {
        if (change == null) return null;
        announceChange(change);
        return change.current();
    }

    private void announceChange(Committed change) {
        lifecycle.changed(change.type(), change.previous(), change.current());
    }

    private boolean publish(LinkedHashMap<String, TagDefinition> next) {
        if (!save(next)) return false;
        Map<String, TagDefinition> published = immutable(next);
        requirementTypes = requirementTypes(next);
        definitions = published;
        collectionSnapshot = buildCollections(published, collectionStyles);
        return true;
    }

    private boolean save(Map<String, TagDefinition> snapshot) {
        if (plugin == null || file == null) return false;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("Settings.Notify_Unlock", notifyUnlock);
        yaml.set("Settings.Auto_Equip_First", autoEquipFirst);
        yaml.set("Settings.Show_On_This_Server", showOnThisServer);
        for (Map.Entry<TagRarity, String> style : rarityTooltipStyles.entrySet()) {
            yaml.set("Settings.Rarity_Tooltip_Style." + style.getKey().name(), style.getValue());
        }
        for (Map.Entry<String, TagCollectionStyle> entry : collectionStyles.entrySet()) {
            String base = "Collections." + entry.getKey() + ".";
            TagCollectionStyle style = entry.getValue();
            yaml.set(base + "Category", style.category());
            yaml.set(base + "Display_Name", style.displayName());
            yaml.set(base + "Description", style.description());
            yaml.set(base + "Material", style.icon().name());
            yaml.set(base + "Bonus_Points", style.bonusPoints());
        }
        for (TagDefinition definition : snapshot.values()) {
            String base = "Titles." + definition.id() + ".";
            yaml.set(base + "Display_Name", definition.displayName());
            yaml.set(base + "Text", definition.text());
            yaml.set(base + "Description", definition.description());
            yaml.set(base + "Material", definition.icon().name());
            yaml.set(base + "Position", definition.position().name());
            yaml.set(base + "Spacing", definition.spacing());
            yaml.set(base + "Enabled", definition.enabled());
            yaml.set(base + "Hidden_Until_Unlocked", definition.hiddenUntilUnlocked());
            yaml.set(base + "Category", definition.category());
            yaml.set(base + "Rarity", definition.rarity().name());
            yaml.set(base + "Order", definition.sortOrder());
            yaml.set(base + "Points", definition.points());
            yaml.set(base + "Secret", definition.secret());
            yaml.set(base + "Season.Id", definition.season().id());
            yaml.set(base + "Season.Starts_At",
                    TagSeason.formatInstant(definition.season().startsAtMillis()));
            yaml.set(base + "Season.Ends_At",
                    TagSeason.formatInstant(definition.season().endsAtMillis()));
            yaml.set(base + "Requirements.Mode", definition.requirements().mode().name());
            yaml.set(base + "Requirements.Rules", encodeRules(definition.requirements()));
            yaml.set(base + "Rewards.Commands", definition.rewards().commands());
            TagAchievement achievement = definition.achievement();
            yaml.set(base + "Achievement.Toast.Enabled", achievement.toastEnabled());
            yaml.set(base + "Achievement.Toast.Title", achievement.toastTitle());
            yaml.set(base + "Achievement.Toast.Description", achievement.toastDescription());
            yaml.set(base + "Achievement.Toast.Icon", achievement.toastIcon().name());
            yaml.set(base + "Achievement.Toast.Frame", achievement.frame().name());
            yaml.set(base + "Achievement.Chat.Enabled", achievement.chatEnabled());
            yaml.set(base + "Achievement.Chat.Message", achievement.chatMessage());
            yaml.set(base + "Achievement.Sound.Enabled", achievement.soundEnabled());
            yaml.set(base + "Achievement.Sound.Key", achievement.soundKey());
            yaml.set(base + "Achievement.Sound.Volume", achievement.soundVolume());
            yaml.set(base + "Achievement.Sound.Pitch", achievement.soundPitch());
        }
        for (Map.Entry<String, Map<String, Object>> kept : unloadedTitles.entrySet()) {
            String path = "Titles." + kept.getKey();
            if (!yaml.contains(path)) yaml.createSection(path, kept.getValue());
        }
        try {
            YamlFiles.saveAtomic(yaml, file);
            return true;
        } catch (IOException failure) {
            plugin.getLogger().severe("Could not save tags.yml: " + failure.getMessage());
            return false;
        }
    }

    private static TagDefinition read(String rawId, ConfigurationSection entry,
                                      java.util.function.Consumer<String> warn) {
        Material icon = material(entry.getString("Material", "NAME_TAG"));
        TagRarity rarity = TagRarity.parse(entry.getString("Rarity", "COMMON"));
        TagSeason season = new TagSeason(
                entry.getString("Season.Id", ""),
                TagSeason.parseInstant(entry.getString("Season.Starts_At", "")),
                TagSeason.parseInstant(entry.getString("Season.Ends_At", "")));
        return new TagDefinition(rawId,
                entry.getString("Display_Name", rawId),
                entry.getString("Text", ""),
                entry.getString("Description", ""),
                icon,
                TagPosition.parse(entry.getString("Position", "LEFT")),
                entry.getInt("Spacing", 1),
                entry.getBoolean("Enabled", true),
                entry.getBoolean("Hidden_Until_Unlocked", false),
                entry.getString("Category", "General"),
                rarity,
                entry.getInt("Order", 0),
                entry.getInt("Points", rarity.defaultPoints()),
                entry.getBoolean("Secret", false),
                season,
                readRequirements(entry, warn),
                new TagRewards(entry.getStringList("Rewards.Commands")),
                readAchievement(entry, icon));
    }

    private static TagAchievement readAchievement(ConfigurationSection entry,
                                                   Material titleIcon) {
        return new TagAchievement(
                entry.getBoolean("Achievement.Toast.Enabled", true),
                entry.getString("Achievement.Toast.Title", ""),
                entry.getString("Achievement.Toast.Description", ""),
                material(entry.getString("Achievement.Toast.Icon", titleIcon.name())),
                TagAchievementFrame.parse(
                        entry.getString("Achievement.Toast.Frame", "TASK")),
                entry.getBoolean("Achievement.Chat.Enabled", true),
                entry.getString("Achievement.Chat.Message", ""),
                entry.getBoolean("Achievement.Sound.Enabled", true),
                entry.getString("Achievement.Sound.Key", TagAchievement.DEFAULT_SOUND),
                (float) entry.getDouble("Achievement.Sound.Volume", 0.8D),
                (float) entry.getDouble("Achievement.Sound.Pitch", 1.1D));
    }

    /**
     * Una regla que no se entiende CIERRA el titulo (MANUAL: solo lo da un admin), nunca lo
     * abre. Antes un {@code Type: PERMISSON} mal escrito se leia como NONE, y NONE es "libre":
     * el titulo premium quedaba al alcance de todos, y en modo ANY una sola regla rota anulaba
     * las demas.
     */
    private static TagRequirementGroup readRequirements(ConfigurationSection entry,
                                                        java.util.function.Consumer<String> warn) {
        List<TagRequirement> rules = new ArrayList<>();
        List<?> configured = entry.getList("Requirements.Rules", List.of());
        for (Object raw : configured) {
            if (rules.size() >= TagRequirementGroup.MAX_RULES) continue;
            if (!(raw instanceof Map<?, ?> map)) {
                warn.accept("a requirement rule is not a section (Type/Target/Amount); the title"
                        + " stays locked (MANUAL) until it is fixed.");
                rules.add(new TagRequirement(TagRequirementType.MANUAL, "", 1L));
                continue;
            }
            TagRequirementType type = strictType(string(map.get("Type")), warn);
            rules.add(new TagRequirement(type, string(map.get("Target")),
                    number(map.get("Amount"), 1L)));
        }
        if (!rules.isEmpty() || entry.contains("Requirements.Rules")) {
            return new TagRequirementGroup(TagRequirementMode.parse(
                    entry.getString("Requirements.Mode", "ALL")), rules);
        }
        // Migracion v1: una sola regla bajo Requirement.
        TagRequirementType legacyType = strictType(
                entry.getString("Requirement.Type", "NONE"), warn);
        TagRequirement legacy = new TagRequirement(legacyType,
                entry.getString("Requirement.Target", ""),
                entry.getLong("Requirement.Amount", 1L));
        return TagRequirementGroup.single(legacy);
    }

    /** Copia profunda de una seccion como mapas anidados, para volver a escribirla igual. */
    private static Map<String, Object> toMap(ConfigurationSection section) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            out.put(key, value instanceof ConfigurationSection child ? toMap(child) : value);
        }
        return out;
    }

    private static TagRequirementType strictType(String raw,
                                                 java.util.function.Consumer<String> warn) {
        if (raw == null || raw.isBlank()) return TagRequirementType.NONE;
        TagRequirementType type = TagRequirementType.parseStrict(raw);
        if (type != null) return type;
        warn.accept("unknown requirement type '" + raw + "'; the title stays locked (MANUAL)"
                + " until it is fixed.");
        return TagRequirementType.MANUAL;
    }

    private static Map<String, TagCollectionStyle> readCollectionStyles(
            YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("Collections");
        if (section == null) return Map.of();
        LinkedHashMap<String, TagCollectionStyle> styles = new LinkedHashMap<>();
        for (String rawId : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(rawId);
            String id = TagDefinition.normalizeId(rawId);
            if (entry == null || id.isEmpty()) continue;
            String category = entry.getString("Category", rawId);
            styles.put(id, new TagCollectionStyle(
                    category,
                    entry.getString("Display_Name", category),
                    entry.getString("Description", ""),
                    material(entry.getString("Material", "BUNDLE")),
                    Math.max(0, Math.min(TagCollection.MAX_BONUS_POINTS,
                            entry.getInt("Bonus_Points", 0)))));
        }
        return Collections.unmodifiableMap(styles);
    }

    private static List<Map<String, Object>> encodeRules(TagRequirementGroup requirements) {
        List<Map<String, Object>> encoded = new ArrayList<>();
        for (TagRequirement rule : requirements.rules()) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("Type", rule.type().name());
            if (!rule.target().isEmpty()) values.put("Target", rule.target());
            values.put("Amount", rule.amount());
            encoded.add(values);
        }
        return List.copyOf(encoded);
    }

    private static String string(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }

    private static long number(Object raw, long fallback) {
        if (raw instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(string(raw));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Material material(String raw) {
        Material material = raw == null ? null
                : Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
        return TagDefinition.isAir(material) ? Material.NAME_TAG : material;
    }

    private static Map<String, TagDefinition> immutable(
            LinkedHashMap<String, TagDefinition> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static Set<TagRequirementType> requirementTypes(
            Map<String, TagDefinition> values) {
        EnumSet<TagRequirementType> types = EnumSet.noneOf(TagRequirementType.class);
        for (TagDefinition definition : values.values()) {
            for (TagRequirement rule : definition.requirements().rules()) {
                types.add(rule.type());
            }
        }
        return Collections.unmodifiableSet(types);
    }

    private record TagCollectionStyle(String category, String displayName,
                                      String description, Material icon,
                                      int bonusPoints) {
    }
}
