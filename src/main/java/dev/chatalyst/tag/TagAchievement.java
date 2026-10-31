package dev.chatalyst.tag;

import dev.chatalyst.util.Legacy;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.Sanitize;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Presentacion configurable del logro que acompaña al desbloqueo de un titulo.
 * Las plantillas admiten variables, MiniMessage seguro y limites estrictos.
 */
public record TagAchievement(
        boolean toastEnabled,
        String toastTitle,
        String toastDescription,
        Material toastIcon,
        TagAchievementFrame frame,
        boolean chatEnabled,
        String chatMessage,
        boolean soundEnabled,
        String soundKey,
        float soundVolume,
        float soundPitch
) {

    public static final int MAX_TITLE_LENGTH = 64;
    public static final int MAX_DESCRIPTION_LENGTH = 128;
    public static final int MAX_CHAT_LENGTH = 256;
    public static final int MAX_SOUND_KEY_LENGTH = 128;
    public static final String DEFAULT_SOUND = "minecraft:ui.toast.challenge_complete";

    public TagAchievement {
        toastTitle = styled(toastTitle, MAX_TITLE_LENGTH);
        toastDescription = styled(toastDescription, MAX_DESCRIPTION_LENGTH);
        toastIcon = validIcon(toastIcon) ? toastIcon : Material.PAPER;
        frame = frame == null ? TagAchievementFrame.TASK : frame;
        chatMessage = styled(chatMessage, MAX_CHAT_LENGTH);
        soundKey = normalizeSound(soundKey);
        soundVolume = Float.isFinite(soundVolume)
                ? tenth(Math.max(0.0f, Math.min(10.0f, soundVolume))) : 0.8f;
        soundPitch = Float.isFinite(soundPitch)
                ? tenth(Math.max(0.5f, Math.min(2.0f, soundPitch))) : 1.1f;
    }

    public static TagAchievement defaults(Material icon) {
        return new TagAchievement(true, "", "", icon, TagAchievementFrame.TASK,
                true, "", true, DEFAULT_SOUND, 0.8f, 1.1f);
    }

    public TagAchievement withToastEnabled(boolean value) {
        return copy(value, toastTitle, toastDescription, toastIcon, frame,
                chatEnabled, chatMessage, soundEnabled, soundKey, soundVolume, soundPitch);
    }

    public TagAchievement withToastTitle(String value) {
        return copy(toastEnabled, value, toastDescription, toastIcon, frame,
                chatEnabled, chatMessage, soundEnabled, soundKey, soundVolume, soundPitch);
    }

    public TagAchievement withToastDescription(String value) {
        return copy(toastEnabled, toastTitle, value, toastIcon, frame,
                chatEnabled, chatMessage, soundEnabled, soundKey, soundVolume, soundPitch);
    }

    public TagAchievement withToastIcon(Material value) {
        return copy(toastEnabled, toastTitle, toastDescription, value, frame,
                chatEnabled, chatMessage, soundEnabled, soundKey, soundVolume, soundPitch);
    }

    public TagAchievement withFrame(TagAchievementFrame value) {
        return copy(toastEnabled, toastTitle, toastDescription, toastIcon, value,
                chatEnabled, chatMessage, soundEnabled, soundKey, soundVolume, soundPitch);
    }

    public TagAchievement withChatEnabled(boolean value) {
        return copy(toastEnabled, toastTitle, toastDescription, toastIcon, frame,
                value, chatMessage, soundEnabled, soundKey, soundVolume, soundPitch);
    }

    public TagAchievement withChatMessage(String value) {
        return copy(toastEnabled, toastTitle, toastDescription, toastIcon, frame,
                chatEnabled, value, soundEnabled, soundKey, soundVolume, soundPitch);
    }

    public TagAchievement withSoundEnabled(boolean value) {
        return copy(toastEnabled, toastTitle, toastDescription, toastIcon, frame,
                chatEnabled, chatMessage, value, soundKey, soundVolume, soundPitch);
    }

    public TagAchievement withSoundKey(String value) {
        return copy(toastEnabled, toastTitle, toastDescription, toastIcon, frame,
                chatEnabled, chatMessage, soundEnabled, value, soundVolume, soundPitch);
    }

    public TagAchievement withSoundVolume(float value) {
        return copy(toastEnabled, toastTitle, toastDescription, toastIcon, frame,
                chatEnabled, chatMessage, soundEnabled, soundKey, value, soundPitch);
    }

    public TagAchievement withSoundPitch(float value) {
        return copy(toastEnabled, toastTitle, toastDescription, toastIcon, frame,
                chatEnabled, chatMessage, soundEnabled, soundKey, soundVolume, value);
    }

    public String render(String template, Player player, TagDefinition definition) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("{player}", MM.escape(player.getName()));
        values.put("{uuid}", player.getUniqueId().toString());
        values.put("{title}", MM.escape(definition.displayName()));
        values.put("{title_id}", definition.id());
        values.put("{category}", MM.escape(definition.category()));
        values.put("{rarity}", definition.rarity().name());
        values.put("{points}", String.valueOf(definition.points()));
        values.put("{season}", MM.escape(definition.season().id()));
        String source = template == null ? "" : template;
        StringBuilder out = new StringBuilder(source.length() + 32);
        int index = 0;
        while (index < source.length()) {
            int open = source.indexOf('{', index);
            if (open < 0) {
                out.append(source, index, source.length());
                break;
            }
            out.append(source, index, open);
            int close = source.indexOf('}', open + 1);
            if (close < 0) {
                out.append(source, open, source.length());
                break;
            }
            String token = source.substring(open, close + 1);
            out.append(values.getOrDefault(token, token));
            index = close + 1;
        }
        // Aqui, con todo sustituido: quita click/hover y cualquier tag no permitido. Los
        // valores ya van escapados, asi que siguen siendo texto.
        return MM.sanitizeInteraction(out.toString());
    }

    public static boolean validSound(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return value.length() <= MAX_SOUND_KEY_LENGTH
                && value.matches("(?:[a-z0-9._-]+:)?[a-z0-9/._-]+");
    }

    public static boolean validIcon(Material material) {
        if (TagDefinition.isAir(material)) return false;
        try {
            return material.isItem();
        } catch (Throwable registryUnavailable) {
            return true;
        }
    }

    private TagAchievement copy(boolean nextToastEnabled, String nextToastTitle,
                                String nextToastDescription, Material nextToastIcon,
                                TagAchievementFrame nextFrame, boolean nextChatEnabled,
                                String nextChatMessage, boolean nextSoundEnabled,
                                String nextSoundKey, float nextVolume, float nextPitch) {
        return new TagAchievement(nextToastEnabled, nextToastTitle, nextToastDescription,
                nextToastIcon, nextFrame, nextChatEnabled, nextChatMessage,
                nextSoundEnabled, nextSoundKey, nextVolume, nextPitch);
    }

    /**
     * Guarda la plantilla sin tags de interaccion pero SIN pasarla por sanitizeInteraction:
     * ese paso reescribe los gradientes como un color por letra, y un {player} dentro de un
     * gradiente quedaba partido en tags y ya no se sustituia (salia "{player}" literal). La
     * limpieza completa se hace en {@link #render}, sobre el texto final con los valores.
     */
    private static String styled(String raw, int maxPlainLength) {
        String safe = MM.stripNonStyleTags(
                Legacy.toMini(Sanitize.stripInvisible(raw == null ? "" : raw).trim()));
        String plain = MM.strip(MM.sanitizeInteraction(safe));
        if (plain.length() <= maxPlainLength) return safe;
        return MM.escape(Sanitize.limit(plain, maxPlainLength));
    }

    private static String normalizeSound(String raw) {
        if (!validSound(raw)) return DEFAULT_SOUND;
        String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.contains(":") ? normalized : "minecraft:" + normalized;
    }

    private static float tenth(float value) {
        return Math.round(value * 10.0f) / 10.0f;
    }
}
