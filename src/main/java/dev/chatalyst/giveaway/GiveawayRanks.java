package dev.chatalyst.giveaway;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.hook.LuckPermsHook;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.TimeUtil;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ranks as prizes, on a server that may or may not sell them.
 *
 * <p>A rank giveaway must work in both worlds. With Tebex switched on, the grant has to go
 * through the purchase ledger, or the reconciler — which repairs any group it does not have a
 * receipt for — would quietly take the prize back within the minute. With Tebex off, which is
 * how a server looks while it is still being built locally, there is no ledger and the group is
 * simply written to LuckPerms. Same prize, two doors, and the right one is chosen per draw
 * rather than being a setting somebody has to remember to flip.
 *
 * <p>Ranks are also <b>detected</b>, not only configured. Almost every server calls them some
 * arrangement of vip / vip+ / mvp++, so those are matched against the groups LuckPerms actually
 * has and offered in the menu with no catalogue entry at all — while {@code Never} keeps the
 * staff ladder off that list, because "give away a rank" must never be one click from giving
 * away owner.
 */
final class GiveawayRanks {

    private final ChatalystPlugin plugin;

    GiveawayRanks(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Everything that can be given away: the prizes written in {@code Giveaway_Prizes}, plus the
     * ranks this server turns out to have.
     *
     * <p>Lives here rather than on the manager because delivery needs it too — a prize held for
     * a winner who logged off has to be resolvable when they come back, and a detected rank
     * exists in no config file to look it up in.
     */
    List<GiveawayPrize> catalogue() {
        List<GiveawayPrize> out = new java.util.ArrayList<>(
                GiveawayPrize.merge(GiveawayPrize.all(plugin.getConfig()), detected()));
        // Los hechos en el juego (/sorteo crear). Si giveaway.yml ya usa ese id gana el del
        // fichero, para no dejar dos premios con el mismo id en la lista.
        GiveawayCustomPrizes.get().ifPresent(custom -> {
            for (GiveawayCustomPrizes.Custom entry : custom.all()) {
                if (out.stream().noneMatch(prize -> prize.id().equals(entry.id))) {
                    out.add(GiveawayPrize.custom(entry));
                }
            }
        });
        return List.copyOf(out);
    }

    /** One prize by id, from that same merged catalogue. */
    java.util.Optional<GiveawayPrize> prize(String id) {
        return GiveawayPrize.resolveIn(catalogue(), id);
    }

    // ---- detection --------------------------------------------------------------------

    /** Synthetic prizes for the ranks this server has and is willing to give away. */
    List<GiveawayPrize> detected() {
        List<GiveawayPrize> out = new ArrayList<>();
        for (String name : offeredGroups(false)) {
            String id = GiveawayPrize.normalizeId(name);
            // A group whose name cannot survive being typed into a command is skipped rather
            // than offered as a prize nobody could start.
            if (id == null) continue;
            out.add(GiveawayPrize.detectedRank(id, name, label(name)));
        }
        return List.copyOf(out);
    }

    /**
     * Grupos sorteables. Con {@code Names} vacio (o "*") se escanean TODOS los grupos de
     * LuckPerms, menos default, los de staff (por permisos) y los de {@code Never}: asi no hace
     * falta escribir los rangos del servidor a mano. Con {@code includeDisabled} salen tambien
     * los apagados desde el juego (para listarlos en /sorteo rangos).
     */
    List<String> offeredGroups(boolean includeDisabled) {
        ConfigurationSection ranks = plugin.getConfig()
                .getConfigurationSection("Giveaway.Ranks");
        if (ranks != null && !ranks.getBoolean("Auto_Detect", true)) return List.of();
        if (!LuckPermsHook.isAvailable()) return List.of();
        Set<String> never = lower(ranks == null ? List.of() : ranks.getStringList("Never"));
        Set<String> wanted = lower(ranks == null ? List.of() : ranks.getStringList("Names"));
        Set<String> staff = lower(new ArrayList<>(staffGroups()));
        GiveawayRankSettings settings = GiveawayRankSettings.get().orElse(null);
        java.util.function.Predicate<String> disabled = includeDisabled || settings == null
                ? group -> false : group -> !settings.enabled(group);
        return GiveawayRankSettings.selectGroups(new ArrayList<>(LuckPermsHook.loadedGroups()),
                wanted, never, staff, disabled);
    }

    /**
     * Groups that hold staff power, <b>whatever they happen to be called</b>.
     *
     * <p>The {@code Never} list can only refuse names somebody thought to write down, and it
     * ships in English while real servers name their staff in their own language and their own
     * theme — a themed server may well call its administrators {@code arconte} and
     * {@code centurion}, and no hardcoded list of "owner, admin, mod" will ever catch those. So the ladder is
     * recognised by what it can DO instead: any group that grants one of the staff permissions
     * in {@code Participants.Staff_Permissions} is a staff group, and a staff group is never
     * offered as a prize.
     *
     * <p>One list, used twice and consistently: the same permissions that keep a moderator out
     * of the draw keep their rank out of the prize menu. LuckPerms resolves inheritance and
     * wildcards for us, so a group holding {@code *} is caught without naming it anywhere.
     */
    private Set<String> staffGroups() {
        Set<String> out = new LinkedHashSet<>();
        for (String permission : plugin.getConfig()
                .getStringList("Giveaway.Participants.Staff_Permissions")) {
            if (permission == null || permission.isBlank()) continue;
            try {
                out.addAll(LuckPermsHook.groupsWithPermission(permission.trim()));
            } catch (RuntimeException unavailable) {
                // LuckPerms answered badly; the name list below still applies.
            }
        }
        return out;
    }

    /**
     * What the menu shows for a detected rank: the group's LuckPerms prefix when it has one, so
     * the card looks like the rank does in chat, and the plain group name otherwise.
     */
    private String label(String group) {
        String prefix = safe(() -> LuckPermsHook.groupPrefix(group));
        if (prefix != null && !prefix.isBlank()) return prefix;
        String display = safe(() -> LuckPermsHook.groupDisplayName(group));
        String shown = display != null && !display.isBlank() ? display : group;
        return "<gradient:#B56CFF:#E4A8FF><bold>" + MM.escape(shown) + "</bold></gradient>";
    }

    // ---- delivery ---------------------------------------------------------------------

    /** True when the rank should be handed over through the Tebex ledger rather than direct. */
    boolean usesTebex(GiveawayPrize prize) {
        if (prize.rank() == null) return false;
        if (!plugin.getConfig().getBoolean("Tebex.Enabled", false)) return false;
        ConfigurationSection catalog = plugin.getConfig()
                .getConfigurationSection("Rank_Catalog");
        if (catalog == null) return false;
        for (String key : catalog.getKeys(false)) {
            if (!key.equalsIgnoreCase(prize.rank())) continue;
            ConfigurationSection entry = catalog.getConfigurationSection(key);
            // Tebex_Enabled false means the owner sells this rank nowhere; the ledger would
            // refuse the grant, so the local door is the only one that works.
            return entry != null && entry.getBoolean("Tebex_Enabled", false);
        }
        return false;
    }

    /** The LuckPerms group a rank prize maps to, or null when nothing on this server matches. */
    String group(GiveawayPrize prize) {
        if (prize.rank() == null) return null;
        ConfigurationSection catalog = plugin.getConfig()
                .getConfigurationSection("Rank_Catalog");
        if (catalog != null) {
            for (String key : catalog.getKeys(false)) {
                if (!key.equalsIgnoreCase(prize.rank())) continue;
                ConfigurationSection entry = catalog.getConfigurationSection(key);
                String group = entry == null ? null : entry.getString("LuckPerms_Group");
                if (group != null && !group.isBlank()) return group;
            }
        }
        // No catalogue entry: the prize names the group directly, which is the whole point of
        // a local setup. The detected name is used verbatim so "vip+" stays "vip+".
        return prize.rankGroup() != null ? prize.rankGroup() : prize.rank();
    }

    /** Absolute expiry for a locally granted rank, or 0 for permanent (duracion de la config). */
    long expiryMillis() {
        return expiryMillis(null);
    }

    /**
     * Caducidad de un rango concedido: la que se le puso en el juego (/sorteo rango) y si no,
     * la de {@code Giveaway.Ranks.Duration}. 0 = permanente.
     */
    long expiryMillis(String group) {
        String own = group == null ? null
                : GiveawayRankSettings.get().map(s -> s.duration(group)).orElse(null);
        String configured = own != null ? own
                : plugin.getConfig().getString("Giveaway.Ranks.Duration", "permanent");
        long duration = GiveawayRankSettings.parse(configured);
        if (duration == 0L) {
            plugin.getLogger().warning("Giveaway rank duration '" + configured
                    + "' is not a duration; the rank will be granted permanently.");
            return 0L;
        }
        if (duration == TimeUtil.PERMANENT) return 0L;
        long deadline = TimeUtil.deadline(System.currentTimeMillis(), duration);
        return deadline == TimeUtil.PERMANENT ? 0L : deadline;
    }

    /** Duracion que se aplicara a ese rango, tal como se escribio (para ensenarla). */
    String durationLabel(String group) {
        String own = GiveawayRankSettings.get().map(s -> s.duration(group)).orElse(null);
        return own != null ? own : plugin.getConfig().getString("Giveaway.Ranks.Duration", "permanent");
    }

    /** Why a rank prize cannot be handed over right now, or null when it can. */
    String problem(GiveawayPrize prize) {
        if (usesTebex(prize)) return null;
        if (!LuckPermsHook.isAvailable()) {
            return "LuckPerms is not installed, so a rank cannot be granted";
        }
        String group = group(prize);
        if (group == null || group.isBlank()) {
            return "this prize names no LuckPerms group";
        }
        return LuckPermsHook.groupExists(group)
                ? null : "LuckPerms has no group '" + group + "'";
    }

    static Material icon() {
        return Material.NETHER_STAR;
    }

    /** Mutable on purpose: the caller folds the detected staff groups into it. */
    private static Set<String> lower(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) out.add(value.trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** LuckPerms is optional and its lookups touch another plugin's cache; never let one throw. */
    private static String safe(java.util.function.Supplier<String> lookup) {
        try {
            return lookup.get();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
