package dev.chatalyst.tag;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * Regla de obtencion de un titulo. El progreso se apoya en estadisticas vanilla, por lo que
 * sobrevive reinicios y no duplica contadores que Minecraft ya mantiene de forma durable.
 */
public record TagRequirement(TagRequirementType type, String target, long amount) {

    public static final long MAX_AMOUNT = 1_000_000_000L;

    public TagRequirement {
        type = type == null ? TagRequirementType.NONE : type;
        target = normalizeTarget(type, target);
        amount = switch (type) {
            case NONE, ADVANCEMENT, PERMISSION, TITLE_UNLOCKED, MANUAL -> 1L;
            default -> Math.max(1L, Math.min(MAX_AMOUNT, amount));
        };
    }

    public static TagRequirement free() {
        return new TagRequirement(TagRequirementType.NONE, "", 1L);
    }

    public static TagRequirement defaults(TagRequirementType type) {
        TagRequirementType safe = type == null ? TagRequirementType.NONE : type;
        return switch (safe) {
            case NONE -> free();
            case MOB_KILLS -> new TagRequirement(safe, "ANY", 100L);
            case PLAYER_KILLS -> new TagRequirement(safe, "", 10L);
            case PLAYTIME_MINUTES -> new TagRequirement(safe, "", 60L);
            case BLOCKS_MINED -> new TagRequirement(safe, "STONE", 100L);
            case ITEMS_CRAFTED -> new TagRequirement(safe, "CRAFTING_TABLE", 10L);
            case ITEMS_PICKED_UP -> new TagRequirement(safe, "DIAMOND", 1L);
            case ITEMS_OWNED -> new TagRequirement(safe, "DIAMOND", 1L);
            case FISH_CAUGHT -> new TagRequirement(safe, "", 10L);
            case ANIMALS_BRED -> new TagRequirement(safe, "", 10L);
            case ADVANCEMENT -> new TagRequirement(
                    safe, "minecraft:story/mine_diamond", 1L);
            case PERMISSION -> new TagRequirement(safe, "chatalyst.title.example", 1L);
            case TITLE_UNLOCKED -> new TagRequirement(safe, "example-title", 1L);
            case PLACEHOLDER -> new TagRequirement(safe, "%ecojobs_minero_level%", 10L);
            case COUNTER -> new TagRequirement(safe, "jefes", 1L);
            case MANUAL -> new TagRequirement(safe, "", 1L);
        };
    }

    public TagRequirement withType(TagRequirementType next) {
        return defaults(next);
    }

    public TagRequirement withTarget(String next) {
        return new TagRequirement(type, next, amount);
    }

    public TagRequirement withAmount(long next) {
        return new TagRequirement(type, target, next);
    }

    /** Progreso actual, acotado a valores no negativos. Debe ejecutarse en el hilo del jugador. */
    public long progress(Player player) {
        return progress(player, ignored -> false);
    }

    /** Progreso con resolucion explicita de dependencias entre titulos. */
    public long progress(Player player, Predicate<String> titleUnlocked) {
        Predicate<String> resolver = titleUnlocked == null ? ignored -> false : titleUnlocked;
        if (type == TagRequirementType.NONE) return 1L;
        if (type == TagRequirementType.TITLE_UNLOCKED) {
            return resolver.test(target) ? 1L : 0L;
        }
        if (player == null) return 0L;
        try {
            return switch (type) {
                case NONE -> 1L;
                case MOB_KILLS -> mobKills(player);
                case PLAYER_KILLS -> nonNegative(player.getStatistic(Statistic.PLAYER_KILLS));
                case PLAYTIME_MINUTES ->
                        nonNegative(player.getStatistic(Statistic.PLAY_ONE_MINUTE)) / 1_200L;
                case BLOCKS_MINED -> materialStatistic(
                        player, Statistic.MINE_BLOCK);
                case ITEMS_CRAFTED -> materialStatistic(
                        player, Statistic.CRAFT_ITEM);
                case ITEMS_PICKED_UP -> materialStatistic(
                        player, Statistic.PICKUP);
                case ITEMS_OWNED -> ownedItems(player);
                case FISH_CAUGHT -> nonNegative(
                        player.getStatistic(Statistic.FISH_CAUGHT));
                case ANIMALS_BRED -> nonNegative(
                        player.getStatistic(Statistic.ANIMALS_BRED));
                case ADVANCEMENT -> advancement(player) ? 1L : 0L;
                case PERMISSION -> player.hasPermission(target) ? 1L : 0L;
                case TITLE_UNLOCKED -> resolver.test(target) ? 1L : 0L;
                case PLACEHOLDER -> firstNumber(
                        dev.chatalyst.hook.Placeholders.papiRaw(player, target));
                case COUNTER -> TitleCounters.current(player.getUniqueId(), target);
                case MANUAL -> 0L;
            };
        } catch (IllegalArgumentException ignored) {
            // Una entidad eliminada/renombrada entre versiones no rompe el menu ni el listener.
            return 0L;
        }
    }

    public boolean satisfied(Player player) {
        return satisfied(player, ignored -> false);
    }

    public boolean satisfied(Player player, Predicate<String> titleUnlocked) {
        return type != TagRequirementType.MANUAL
                && progress(player, titleUnlocked) >= amount;
    }

    public boolean hasNumericProgress() {
        return switch (type) {
            case MOB_KILLS, PLAYER_KILLS, PLAYTIME_MINUTES, BLOCKS_MINED,
                    ITEMS_CRAFTED, ITEMS_PICKED_UP, ITEMS_OWNED,
                    FISH_CAUGHT, ANIMALS_BRED, PLACEHOLDER, COUNTER -> true;
            default -> false;
        };
    }

    /** Placeholder de PAPI aceptado como objetivo: {@code %algo%}, sin espacios. */
    private static final java.util.regex.Pattern PLACEHOLDER_TARGET =
            java.util.regex.Pattern.compile("%[^%\\s]{1,126}%");
    /** Codigos de color legacy (&a, §a, &#RRGGBB, §x§R§R...) y etiquetas MiniMessage. */
    private static final java.util.regex.Pattern COLOR_NOISE = java.util.regex.Pattern.compile(
            "[&§]#[0-9a-fA-F]{6}|[&§][0-9a-fk-orxA-FK-ORX]|<[^>]*>");
    /** Un numero: digitos con comas de miles bien puestas, o digitos sueltos. */
    private static final java.util.regex.Pattern NUMBER =
            java.util.regex.Pattern.compile("\\d{1,3}(?:,\\d{3})+(?!\\d)|\\d+");

    /**
     * Primer numero entero del texto que devuelve un placeholder. Solo digitos y comas de
     * miles ({@code 1,234}); los decimales se cortan ({@code 12.7} = 12) y los sufijos no se
     * entienden ({@code 1.5k} = 1). Sin numero, o con un signo menos delante, cuenta 0.
     * Antes de buscar se quitan los colores para que el {@code 00} de {@code &#FF0000} no cuente.
     */
    public static long firstNumber(String raw) {
        if (raw == null || raw.isEmpty()) return 0L;
        String clean = COLOR_NOISE.matcher(raw).replaceAll("");
        java.util.regex.Matcher m = NUMBER.matcher(clean);
        if (!m.find()) return 0L;
        if (m.start() > 0 && clean.charAt(m.start() - 1) == '-') return 0L;
        String digits = m.group().replace(",", "");
        if (digits.length() > 18) return MAX_AMOUNT;
        return Math.min(MAX_AMOUNT, Long.parseLong(digits));
    }

    private long mobKills(Player player) {
        if (target.equals("ANY")) {
            return nonNegative(player.getStatistic(Statistic.MOB_KILLS));
        }
        EntityType entity = entityTarget(target);
        return entity == null ? 0L
                : nonNegative(player.getStatistic(Statistic.KILL_ENTITY, entity));
    }

    public static EntityType entityTarget(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("ANY")) return null;
        try {
            EntityType type = EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            return type.isAlive() && type != EntityType.PLAYER ? type : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static boolean validTarget(TagRequirementType type, String raw) {
        if (type == TagRequirementType.MOB_KILLS) {
            return raw != null && (raw.equalsIgnoreCase("ANY") || entityTarget(raw) != null);
        }
        if (type == TagRequirementType.BLOCKS_MINED) {
            Material material = materialTarget(raw);
            return materialKind(material, true);
        }
        if (type == TagRequirementType.ITEMS_CRAFTED
                || type == TagRequirementType.ITEMS_PICKED_UP
                || type == TagRequirementType.ITEMS_OWNED) {
            Material material = materialTarget(raw);
            return materialKind(material, false);
        }
        if (type == TagRequirementType.ADVANCEMENT) {
            return raw != null && raw.length() <= 128
                    && NamespacedKey.fromString(raw.trim().toLowerCase(Locale.ROOT)) != null;
        }
        if (type == TagRequirementType.PERMISSION) {
            return raw != null && raw.matches("[A-Za-z0-9._-]{1,128}");
        }
        if (type == TagRequirementType.TITLE_UNLOCKED) {
            return !TagDefinition.normalizeId(raw).isEmpty();
        }
        if (type == TagRequirementType.PLACEHOLDER) {
            return raw != null && PLACEHOLDER_TARGET.matcher(raw.trim()).matches();
        }
        if (type == TagRequirementType.COUNTER) {
            return TitleCounters.validKey(TitleCounters.normalizeKey(raw));
        }
        return true;
    }

    public static Material materialTarget(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
    }

    private static boolean materialKind(Material material, boolean block) {
        if (material == null || TagDefinition.isAir(material)) return false;
        try {
            return block ? material.isBlock() : material.isItem();
        } catch (Throwable registryUnavailable) {
            // Los tests de dominio no arrancan el registro Paper. En servidor, el registro
            // existe y la validacion anterior es estricta; sin el, al menos se valida el ID.
            return true;
        }
    }

    private static String normalizeTarget(TagRequirementType type, String raw) {
        String safe = raw == null ? "" : raw.trim();
        if (type == TagRequirementType.MOB_KILLS) {
            if (safe.isEmpty() || safe.equalsIgnoreCase("ANY")) return "ANY";
            return safe.toUpperCase(Locale.ROOT);
        }
        if (type == TagRequirementType.BLOCKS_MINED
                || type == TagRequirementType.ITEMS_CRAFTED
                || type == TagRequirementType.ITEMS_PICKED_UP
                || type == TagRequirementType.ITEMS_OWNED) {
            return safe.toUpperCase(Locale.ROOT);
        }
        if (type == TagRequirementType.ADVANCEMENT) {
            return safe.length() <= 128
                    ? safe.toLowerCase(Locale.ROOT)
                    : safe.substring(0, 128).toLowerCase(Locale.ROOT);
        }
        if (type == TagRequirementType.PERMISSION) {
            return safe.length() <= 128 ? safe : safe.substring(0, 128);
        }
        if (type == TagRequirementType.TITLE_UNLOCKED) {
            return TagDefinition.normalizeId(safe);
        }
        if (type == TagRequirementType.PLACEHOLDER) {
            return safe.length() <= 128 ? safe : safe.substring(0, 128);
        }
        if (type == TagRequirementType.COUNTER) {
            // Una clave mal escrita deja la regla en 0 (el titulo no se abre), nunca otra clave.
            String key = TitleCounters.normalizeKey(safe);
            return TitleCounters.validKey(key) ? key : "";
        }
        return "";
    }

    private long materialStatistic(Player player, Statistic statistic) {
        Material material = materialTarget(target);
        return material == null ? 0L
                : nonNegative(player.getStatistic(statistic, material));
    }

    private long ownedItems(Player player) {
        Material material = materialTarget(target);
        if (material == null) return 0L;
        long count = 0L;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) count += item.getAmount();
        }
        return count;
    }

    private boolean advancement(Player player) {
        NamespacedKey key = NamespacedKey.fromString(target);
        if (key == null) return false;
        org.bukkit.advancement.Advancement value = Bukkit.getAdvancement(key);
        return value != null && player.getAdvancementProgress(value).isDone();
    }

    private static long nonNegative(int value) {
        return Math.max(0L, value);
    }
}
