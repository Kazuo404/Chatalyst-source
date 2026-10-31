package dev.chatalyst.tag;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Captura segura por chat para los campos de texto/numericos del editor administrativo. */
public final class TagAdminPrompt implements Listener {

    public enum Field {
        DISPLAY_NAME,
        TEXT,
        DESCRIPTION,
        MATERIAL,
        CATEGORY,
        SORT_ORDER,
        POINTS,
        SEASON_ID,
        SEASON_START,
        SEASON_END,
        COLLECTION_NAME,
        COLLECTION_DESCRIPTION,
        COLLECTION_ICON,
        COLLECTION_BONUS,
        ACHIEVEMENT_TOAST_TITLE,
        ACHIEVEMENT_TOAST_DESCRIPTION,
        ACHIEVEMENT_TOAST_ICON,
        ACHIEVEMENT_CHAT_MESSAGE,
        ACHIEVEMENT_SOUND_KEY,
        RULE_TARGET,
        RULE_AMOUNT,
        REWARD_COMMAND,
        PLAYER_ACCESS
    }

    private record Pending(String titleId, int returnPage, Field field, int itemIndex,
                           boolean returnAdmin, int adminReturnPage) {
    }

    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    private final ChatalystPlugin plugin;
    private final TagManager tags;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public TagAdminPrompt(ChatalystPlugin plugin, TagManager tags) {
        this.plugin = plugin;
        this.tags = tags;
    }

    public void begin(Player player, String titleId, int returnPage, Field field) {
        if (!player.hasPermission("chatalyst.tag.admin")) {
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        if (tags.catalog().byId(titleId) == null) {
            player.sendMessage(Lang.get(player, "tag.admin-gone"));
            return;
        }
        pending.put(player.getUniqueId(), new Pending(
                titleId, returnPage, field, -1, false, 0));
        player.closeInventory();
        player.sendMessage(Lang.get(player, "tag.admin-prompt-" + key(field)));
    }

    public void beginRequirement(Player player, String titleId, int returnPage,
                                 int ruleIndex, Field field) {
        beginIndexed(player, titleId, returnPage, ruleIndex, field);
    }

    public void beginReward(Player player, String titleId, int returnPage,
                            int commandIndex) {
        beginIndexed(player, titleId, returnPage, commandIndex, Field.REWARD_COMMAND);
    }

    public void beginCollection(Player player, String collectionId, int collectionsPage,
                                boolean returnAdmin, int adminReturnPage, Field field) {
        if (!player.hasPermission("chatalyst.tag.admin")) {
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        if (tags.catalog().collectionById(collectionId) == null) {
            player.sendMessage(Lang.get(player, "tag.collection-gone"));
            return;
        }
        pending.put(player.getUniqueId(), new Pending(collectionId, collectionsPage,
                field, -1, returnAdmin, adminReturnPage));
        player.closeInventory();
        player.sendMessage(Lang.get(player, "tag.admin-prompt-" + key(field)));
    }

    private void beginIndexed(Player player, String titleId, int returnPage,
                              int itemIndex, Field field) {
        if (!player.hasPermission("chatalyst.tag.admin")) {
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        if (tags.catalog().byId(titleId) == null) {
            player.sendMessage(Lang.get(player, "tag.admin-gone"));
            return;
        }
        pending.put(player.getUniqueId(), new Pending(
                titleId, returnPage, field, itemIndex, false, 0));
        player.closeInventory();
        player.sendMessage(Lang.get(player, "tag.admin-prompt-" + key(field)));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncChatEvent event) {
        Pending edit = pending.remove(event.getPlayer().getUniqueId());
        if (edit == null) return;
        event.setCancelled(true);
        String input = PLAIN.serialize(event.message()).trim();
        FoliaTasks.global(plugin, () -> apply(event.getPlayer(), edit, input));
    }

    private void apply(Player actor, Pending edit, String input) {
        FoliaTasks.entity(plugin, actor, player -> {
            if (!player.hasPermission("chatalyst.tag.admin")) {
                player.sendMessage(Lang.get(player, "common.no-permission"));
                return;
            }
            if (cancelled(input)) {
                player.sendMessage(Lang.get(player, "tag.admin-prompt-cancelled"));
                reopen(player, edit);
                return;
            }
            if (edit.field() == Field.PLAYER_ACCESS) {
                // Resolver perfiles conocidos se hace en el scheduler global, no en el hilo
                // async del chat ni mientras se itera el inventario del jugador.
                FoliaTasks.global(plugin, () -> applyAccess(player, edit, input));
                return;
            }
            if (edit.field().name().startsWith("COLLECTION_")) {
                applyCollection(player, edit, input);
                reopen(player, edit);
                return;
            }
            TagDefinition current = tags.catalog().byId(edit.titleId());
            if (current == null) {
                player.sendMessage(Lang.get(player, "tag.admin-gone"));
                reopen(player, edit);
                return;
            }
            TagDefinition updated = changed(player, current, edit, input);
            if (updated != null) {
                if (!tags.catalog().update(updated)) {
                    player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
                } else {
                    audit(player, "tag title edit", edit.titleId() + " " + edit.field());
                }
            }
            reopen(player, edit);
        });
    }

    private TagDefinition changed(Player player, TagDefinition current,
                                  Pending edit, String input) {
        return switch (edit.field()) {
            case DISPLAY_NAME -> current.withDisplayName(input);
            case TEXT -> current.withText(clearWord(input) ? "" : input);
            case DESCRIPTION -> current.withDescription(clearWord(input) ? "" : input);
            case MATERIAL -> {
                Material material = Material.matchMaterial(input);
                // WATER o NETHER_PORTAL son materiales pero no objetos: no hay icono que pintar
                // y el menu de edicion del titulo dejaba de abrirse.
                if (!TagAchievement.validIcon(material)) {
                    player.sendMessage(Lang.get(player, "tag.admin-invalid-material"));
                    yield null;
                }
                yield current.withIcon(material);
            }
            case CATEGORY -> current.withCategory(input);
            case SORT_ORDER -> order(player, current, input);
            case POINTS -> points(player, current, input);
            case SEASON_ID -> seasonId(player, current, input);
            case SEASON_START -> seasonDate(player, current, input, true);
            case SEASON_END -> seasonDate(player, current, input, false);
            case COLLECTION_NAME, COLLECTION_DESCRIPTION, COLLECTION_ICON,
                    COLLECTION_BONUS ->
                    throw new IllegalStateException("Handled separately");
            case ACHIEVEMENT_TOAST_TITLE -> current.withAchievement(
                    current.achievement().withToastTitle(
                            clearWord(input) ? "" : input));
            case ACHIEVEMENT_TOAST_DESCRIPTION -> current.withAchievement(
                    current.achievement().withToastDescription(
                            clearWord(input) ? "" : input));
            case ACHIEVEMENT_TOAST_ICON -> achievementIcon(player, current, input);
            case ACHIEVEMENT_CHAT_MESSAGE -> current.withAchievement(
                    current.achievement().withChatMessage(
                            clearWord(input) ? "" : input));
            case ACHIEVEMENT_SOUND_KEY -> achievementSound(player, current, input);
            case RULE_TARGET -> target(player, current, edit.itemIndex(), input);
            case RULE_AMOUNT -> amount(player, current, edit.itemIndex(), input);
            case REWARD_COMMAND -> reward(player, current, edit.itemIndex(), input);
            case PLAYER_ACCESS -> throw new IllegalStateException("Handled separately");
        };
    }

    private TagDefinition target(Player player, TagDefinition current,
                                 int index, String input) {
        TagRequirement requirement = rule(current, index);
        if (requirement == null) return null;
        if (!TagRequirement.validTarget(requirement.type(), input)) {
            player.sendMessage(Lang.get(player, "tag.admin-invalid-target"));
            return null;
        }
        if (requirement.type() == TagRequirementType.TITLE_UNLOCKED
                && !tags.catalog().contains(input)) {
            player.sendMessage(Lang.get(player, "tag.admin-dependency-missing"));
            return null;
        }
        TagDefinition updated = current.withRequirements(current.requirements()
                .update(index, requirement.withTarget(input)));
        if (tags.catalog().wouldCreateDependencyCycle(updated)) {
            player.sendMessage(Lang.get(player, "tag.admin-dependency-cycle"));
            return null;
        }
        return updated;
    }

    private TagDefinition amount(Player player, TagDefinition current,
                                 int index, String input) {
        TagRequirement requirement = rule(current, index);
        if (requirement == null) return null;
        if (!requirement.hasNumericProgress()) {
            player.sendMessage(Lang.get(player, "tag.admin-amount-not-used"));
            return null;
        }
        try {
            long value = Long.parseLong(input);
            if (value < 1L || value > TagRequirement.MAX_AMOUNT) throw new NumberFormatException();
            return current.withRequirements(current.requirements()
                    .update(index, requirement.withAmount(value)));
        } catch (NumberFormatException invalid) {
            player.sendMessage(Lang.get(player, "tag.admin-invalid-amount",
                    "max", TagRequirement.MAX_AMOUNT));
            return null;
        }
    }

    private TagDefinition order(Player player, TagDefinition current, String input) {
        try {
            int value = Integer.parseInt(input);
            if (Math.abs((long) value) > TagDefinition.MAX_SORT_ORDER) {
                throw new NumberFormatException();
            }
            return current.withSortOrder(value);
        } catch (NumberFormatException invalid) {
            player.sendMessage(Lang.get(player, "tag.admin-invalid-order",
                    "max", TagDefinition.MAX_SORT_ORDER));
            return null;
        }
    }

    private TagDefinition points(Player player, TagDefinition current, String input) {
        try {
            int value = Integer.parseInt(input);
            if (value < 0 || value > TagDefinition.MAX_POINTS) {
                throw new NumberFormatException();
            }
            return current.withPoints(value);
        } catch (NumberFormatException invalid) {
            player.sendMessage(Lang.get(player, "tag.admin-invalid-points",
                    "max", TagDefinition.MAX_POINTS));
            return null;
        }
    }

    private TagDefinition seasonId(Player player, TagDefinition current, String input) {
        if (clearWord(input)) return current.withSeason(TagSeason.none());
        String id = TagSeason.normalizeId(input);
        if (id.isEmpty()) {
            player.sendMessage(Lang.get(player, "tag.admin-season-invalid-id"));
            return null;
        }
        return current.withSeason(current.season().withId(id));
    }

    private TagDefinition seasonDate(Player player, TagDefinition current,
                                     String input, boolean start) {
        if (!current.season().enabled()) {
            player.sendMessage(Lang.get(player, "tag.admin-season-id-first"));
            return null;
        }
        Long parsed = clearWord(input) ? 0L : parseDate(input);
        if (parsed == null) {
            player.sendMessage(Lang.get(player, "tag.admin-season-invalid-date"));
            return null;
        }
        TagSeason season = start
                ? current.season().withStartsAt(parsed)
                : current.season().withEndsAt(parsed);
        if (!season.validWindow()) {
            player.sendMessage(Lang.get(player, "tag.admin-season-invalid-window"));
            return null;
        }
        return current.withSeason(season);
    }

    private static Long parseDate(String input) {
        try {
            return Instant.parse(input.trim()).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            for (DateTimeFormatter format : List.of(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))) {
                try {
                    return LocalDateTime.parse(input.trim(), format)
                            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                } catch (DateTimeParseException invalid) {
                    // Probar el siguiente formato.
                }
            }
            return null;
        }
    }

    private TagDefinition achievementIcon(Player player, TagDefinition current,
                                          String input) {
        Material material = Material.matchMaterial(input);
        if (!TagAchievement.validIcon(material)) {
            player.sendMessage(Lang.get(player, "tag.admin-invalid-material"));
            return null;
        }
        return current.withAchievement(
                current.achievement().withToastIcon(material));
    }

    private void applyCollection(Player player, Pending edit, String input) {
        TagCollection current = tags.catalog().collectionById(edit.titleId());
        if (current == null) {
            player.sendMessage(Lang.get(player, "tag.collection-gone"));
            return;
        }
        TagCollection updated = switch (edit.field()) {
            case COLLECTION_NAME -> current.withDisplayName(input);
            case COLLECTION_DESCRIPTION -> current.withDescription(
                    clearWord(input) ? "" : input);
            case COLLECTION_ICON -> {
                Material material = Material.matchMaterial(input);
                if (!TagAchievement.validIcon(material)) {
                    player.sendMessage(Lang.get(player, "tag.admin-invalid-material"));
                    yield null;
                }
                yield current.withIcon(material);
            }
            case COLLECTION_BONUS -> collectionBonus(player, current, input);
            default -> throw new IllegalStateException("Not a collection field");
        };
        if (updated == null) return;
        if (!tags.catalog().updateCollection(updated)) {
            player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
        } else {
            audit(player, "tag collection edit",
                    current.id() + " " + edit.field());
        }
    }

    private TagCollection collectionBonus(Player player, TagCollection current,
                                          String input) {
        try {
            int value = Integer.parseInt(input);
            if (value < 0 || value > TagCollection.MAX_BONUS_POINTS) {
                throw new NumberFormatException();
            }
            return current.withBonusPoints(value);
        } catch (NumberFormatException invalid) {
            player.sendMessage(Lang.get(player, "tag.admin-invalid-collection-bonus",
                    "max", TagCollection.MAX_BONUS_POINTS));
            return null;
        }
    }

    private TagDefinition achievementSound(Player player, TagDefinition current,
                                           String input) {
        if (!TagAchievement.validSound(input)) {
            player.sendMessage(Lang.get(player, "tag.admin-achievement-invalid-sound"));
            return null;
        }
        return current.withAchievement(
                current.achievement().withSoundKey(input));
    }

    private TagDefinition reward(Player player, TagDefinition current,
                                 int index, String input) {
        if (new TagRewards(List.of(input)).commands().isEmpty()) {
            player.sendMessage(Lang.get(player, "tag.admin-invalid-reward"));
            return null;
        }
        if (index < 0 && current.rewards().commands().size() >= TagRewards.MAX_COMMANDS) {
            player.sendMessage(Lang.get(player, "tag.admin-reward-limit",
                    "max", TagRewards.MAX_COMMANDS));
            return null;
        }
        TagRewards updated = index < 0
                ? current.rewards().add(input)
                : current.rewards().update(index, input);
        if (index >= current.rewards().commands().size()) {
            player.sendMessage(Lang.get(player, "tag.admin-reward-gone"));
            return null;
        }
        return current.withRewards(updated);
    }

    private TagRequirement rule(TagDefinition current, int index) {
        if (index < 0 || index >= current.requirements().rules().size()) return null;
        return current.requirements().rules().get(index);
    }

    private void applyAccess(Player actor, Pending edit, String input) {
        TagDefinition definition = tags.catalog().byId(edit.titleId());
        if (definition == null) {
            FoliaTasks.entity(plugin, actor, player -> {
                player.sendMessage(Lang.get(player, "tag.admin-gone"));
                reopen(player, edit);
            });
            return;
        }
        if (definition.requirements().isFree()) {
            FoliaTasks.entity(plugin, actor, player -> {
                player.sendMessage(Lang.get(player, "tag.admin-access-free"));
                reopen(player, edit);
            });
            return;
        }
        OfflinePlayer target = findKnownPlayer(input);
        if (target == null || target.getName() == null) {
            FoliaTasks.entity(plugin, actor, player -> {
                player.sendMessage(Lang.get(player, "common.unknown-player",
                        "player", input));
                reopen(player, edit);
            });
            return;
        }
        boolean revoke = tags.isPersistentlyUnlocked(target.getUniqueId(), definition.id());
        Set<String> completedBefore = revoke ? Set.of()
                : TagUnlockProcessor.completedCollectionIds(
                        tags, target.getUniqueId());
        dev.chatalyst.api.title.TitleMutationContext context =
                dev.chatalyst.api.title.TitleMutationContext.admin(
                        actor.getUniqueId(), actor.getName(),
                        revoke ? "Prompt access revoke" : "Prompt access grant");
        boolean persisted = revoke
                ? tags.revoke(target.getUniqueId(), definition.id(), context)
                : tags.grant(target.getUniqueId(), definition.id(), context);
        Player online = target.getPlayer();
        if (persisted && online != null) {
            FoliaTasks.entity(plugin, online, player -> {
                if (revoke) TagDisplay.refresh(plugin, player);
                else TagUnlockProcessor.manualGrant(
                        plugin, tags, player, definition, completedBefore);
            });
        }
        String targetName = target.getName();
        FoliaTasks.entity(plugin, actor, player -> {
            if (!persisted) {
                player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
            } else {
                player.sendMessage(Lang.get(player, revoke
                                ? "tag.admin-access-revoked" : "tag.admin-access-granted",
                        "player", targetName, "title", definition.displayName()));
                audit(player, revoke ? "tag title revoke" : "tag title grant",
                        targetName + " " + definition.id());
            }
            reopen(player, edit);
        });
    }

    private static OfflinePlayer findKnownPlayer(String name) {
        Player online = dev.chatalyst.nick.NickManager.online(name);
        if (online != null) return online;
        for (OfflinePlayer candidate : Bukkit.getOfflinePlayers()) {
            if (candidate.getName() != null && candidate.getName().equalsIgnoreCase(name)) {
                return candidate;
            }
        }
        return null;
    }

    private void reopen(Player player, Pending edit) {
        if (edit.field() == Field.RULE_TARGET || edit.field() == Field.RULE_AMOUNT) {
            Menu.open(player, new TagRequirementEditMenu(plugin, tags,
                    edit.titleId(), edit.returnPage(), edit.itemIndex()));
        } else if (edit.field() == Field.REWARD_COMMAND) {
            Menu.open(player, new TagRewardsMenu(
                    plugin, tags, edit.titleId(), edit.returnPage()));
        } else if (edit.field() == Field.PLAYER_ACCESS) {
            Menu.open(player, new TagAccessMenu(
                    plugin, tags, edit.titleId(), edit.returnPage()));
        } else if (edit.field().name().startsWith("ACHIEVEMENT_")) {
            Menu.open(player, new TagAchievementMenu(
                    plugin, tags, edit.titleId(), edit.returnPage()));
        } else if (edit.field().name().startsWith("SEASON_")) {
            Menu.open(player, new TagSeasonMenu(
                    plugin, tags, edit.titleId(), edit.returnPage()));
        } else if (edit.field().name().startsWith("COLLECTION_")) {
            Menu.open(player, new TagCollectionDetailMenu(plugin, tags,
                    edit.titleId(), 0, edit.returnPage(),
                    edit.returnAdmin(), edit.adminReturnPage()));
        } else {
            Menu.open(player, new TagAdminEditMenu(
                    plugin, tags, edit.titleId(), edit.returnPage()));
        }
    }

    private void audit(Player player, String action, String detail) {
        if (plugin.getStaffSecurityListener() != null) {
            plugin.getStaffSecurityListener().audit(player, action, detail);
        }
    }

    private static boolean cancelled(String input) {
        return input.isBlank() || input.equalsIgnoreCase("cancel")
                || input.equalsIgnoreCase("cancelar");
    }

    private static boolean clearWord(String input) {
        return input.equalsIgnoreCase("off") || input.equalsIgnoreCase("reset")
                || input.equalsIgnoreCase("quitar");
    }

    private static String key(Field field) {
        return field.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
