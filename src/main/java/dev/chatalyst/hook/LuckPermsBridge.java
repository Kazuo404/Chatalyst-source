package dev.chatalyst.hook;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.user.User;

import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Aislado a proposito: solo se carga cuando LuckPerms esta presente (lo comprueba
 * {@link LuckPermsHook}). Convierte los prefijos/sufijos (que LuckPerms guarda con codigos
 * legacy '&') a una cadena MiniMessage para que encajen en los formatos de Chatalyst.
 */
final class LuckPermsBridge {

    private LuckPermsBridge() {
    }

    private static CachedMetaData meta(UUID uuid) {
        LuckPerms lp = LuckPermsProvider.get();
        User user = lp.getUserManager().getUser(uuid);
        return user != null ? user.getCachedData().getMetaData() : null;
    }

    static String prefix(UUID uuid) {
        CachedMetaData m = meta(uuid);
        return m != null ? legacyToMini(m.getPrefix()) : "";
    }

    /**
     * Prefijo, sufijo, grupo y color del rango en UNA sola consulta. Pedirlos por separado
     * abria cuatro CachedMetaData por cada mensaje de chat, y rankColor ademas volvia a
     * pedir el prefijo y a reconvertirlo de legacy a MiniMessage.
     */
    static String[] rankData(UUID uuid) {
        CachedMetaData m = meta(uuid);
        if (m == null) return new String[] {"", "", "", ""};
        String prefix = legacyToMini(m.getPrefix());
        return new String[] {
                prefix,
                legacyToMini(m.getSuffix()),
                m.getPrimaryGroup() != null ? m.getPrimaryGroup() : "",
                rankColorOf(prefix)
        };
    }

    static CompletableFuture<Boolean> hasPermission(UUID uuid, String permission) {
        LuckPerms lp = LuckPermsProvider.get();
        User loaded = lp.getUserManager().getUser(uuid);
        if (loaded != null) return CompletableFuture.completedFuture(
                loaded.getCachedData().getPermissionData()
                        .checkPermission(permission).asBoolean());
        return lp.getUserManager().loadUser(uuid).thenApply(user ->
                user.getCachedData().getPermissionData()
                        .checkPermission(permission).asBoolean());
    }

    static Set<String> groupsWithPermission(String permission) {
        LuckPerms lp = LuckPermsProvider.get();
        return lp.getGroupManager().getLoadedGroups().stream()
                .filter(group -> group.getCachedData().getPermissionData()
                        .checkPermission(permission).asBoolean())
                .map(group -> group.getName().toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Grupos actualmente conocidos por LuckPerms, normalizados para mostrarlos en la GUI. */
    static Set<String> loadedGroups() {
        LuckPerms lp = LuckPermsProvider.get();
        return lp.getGroupManager().getLoadedGroups().stream()
                .map(group -> group.getName().toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    static String suffix(UUID uuid) {
        CachedMetaData m = meta(uuid);
        return m != null ? legacyToMini(m.getSuffix()) : "";
    }

    static String group(UUID uuid) {
        CachedMetaData m = meta(uuid);
        return m != null && m.getPrimaryGroup() != null ? m.getPrimaryGroup() : "";
    }

    /** Prefijo/sufijo de un GRUPO (no de un jugador), para el disfraz de /anonymous. */
    static String groupPrefix(String group) {
        CachedMetaData m = groupMeta(group);
        return m != null ? legacyToMini(m.getPrefix()) : "";
    }

    static String groupSuffix(String group) {
        CachedMetaData m = groupMeta(group);
        return m != null ? legacyToMini(m.getSuffix()) : "";
    }

    /**
     * Nombre para mostrar que el propio LuckPerms guarda para el grupo (meta
     * {@code displayname}), o "" si no tiene. Es la respuesta AUTORITATIVA a "como se llama
     * este rango": la escribio el dueño del servidor, no la adivina nadie.
     */
    static String groupDisplayName(String group) {
        LuckPerms lp = LuckPermsProvider.get();
        net.luckperms.api.model.group.Group g = lp.getGroupManager().getGroup(group);
        if (g == null) return "";
        String display = g.getDisplayName();
        return display == null ? "" : display;
    }

    private static CachedMetaData groupMeta(String group) {
        LuckPerms lp = LuckPermsProvider.get();
        net.luckperms.api.model.group.Group g = lp.getGroupManager().getGroup(group);
        return g != null ? g.getCachedData().getMetaData() : null;
    }

    static String metaValue(UUID uuid, String key) {
        CachedMetaData m = meta(uuid);
        if (m == null) return "";
        String v = m.getMetaValue(key);
        return v != null ? v : "";
    }

    // Etiqueta de color/gradiente (no negrita/cursiva) dentro de un texto MiniMessage.
    private static final java.util.regex.Pattern COLOR_TAG =
            java.util.regex.Pattern.compile(
                    "<(#[0-9a-f]{6}|gradient:[^>]+|rainbow(?::[^>]+)?|[a-z_]+)>",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Primer color del prefijo del jugador (ej. "<#FF4D6D>"), para pintar nombre y mensaje. */
    static String rankColor(UUID uuid) {
        return rankColorOf(prefix(uuid));
    }

    /** Mismo calculo pero sobre un prefijo YA obtenido, para no volver a consultarlo. */
    static String rankColorOf(String mini) {
        if (mini == null || mini.isEmpty()) return "";
        java.util.regex.Matcher m = COLOR_TAG.matcher(mini);
        while (m.find()) {
            if (isColor(m.group(1))) return m.group();
        }
        return "";
    }

    private static boolean isColor(String tag) {
        String normalized = tag.toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("#") || normalized.startsWith("gradient:")
                || normalized.startsWith("rainbow")) {
            return true;
        }
        return switch (normalized) {
            case "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
                 "gold", "gray", "grey", "dark_gray", "dark_grey", "blue", "green", "aqua",
                 "red", "light_purple", "yellow", "white" -> true;
            default -> false; // bold, italic, underlined, etc.
        };
    }

    // Detecta un tag MiniMessage <...> ya presente (para no escaparlo tratandolo como legacy).
    private static final java.util.regex.Pattern MINI_TAG =
            java.util.regex.Pattern.compile("<[a-zA-Z#][^<> ]*>");

    // Los prefijos pueden venir en legacy (&c) o ya en MiniMessage (<#FF4D6D>). Si ya son
    // MiniMessage los dejamos intactos; si son legacy los convertimos. Asi el formato los
    // parsea igual en ambos casos (antes, convertir un MiniMessage lo escapaba y salia literal).
    private static String legacyToMini(String prefix) {
        if (prefix == null || prefix.isEmpty()) return "";
        if (MINI_TAG.matcher(prefix).find()) {
            return prefix; // ya es MiniMessage
        }
        Component c = LegacyComponentSerializer.legacyAmpersand().deserialize(prefix.replace('§', '&'));
        return MiniMessage.miniMessage().serialize(c);
    }
}
