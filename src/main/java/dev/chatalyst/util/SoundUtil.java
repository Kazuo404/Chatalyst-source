package dev.chatalyst.util;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

/** Small safe wrapper for configurable Adventure sounds. */
public final class SoundUtil {

    private SoundUtil() {
    }

    public static Sound sound(String configured, String fallback, float volume, float pitch) {
        String key = configured == null || configured.isBlank() ? fallback : configured.trim();
        float safeVolume = normalizeVolume(volume);
        float safePitch = normalizePitch(pitch);
        try {
            String resolved = normalize(key);
            // Un nombre estilo enum que no existe: mejor el sonido por defecto que el silencio,
            // porque una clave inventada no falla en ningun sitio, simplemente no suena.
            if (resolved == null) resolved = normalize(fallback);
            // Varios sitios llaman con sound(clave, clave, ...): si la unica clave que hay es
            // invalida, hay que dar ALGO valido igualmente, no reventar el aviso entero.
            if (resolved == null) resolved = safeKey(fallback);
            return Sound.sound(Key.key(resolved), Sound.Source.MASTER, safeVolume, safePitch);
        } catch (RuntimeException ex) {
            // El ultimo recurso no puede volver a lanzar: con sound(clave, clave) y una clave
            // como "minecraft::ui.button.click", el fallback era la misma clave rota y la
            // excepcion cortaba todas las acciones que venian detras en la regla.
            Key last;
            try {
                last = Key.key(safeKey(fallback));
            } catch (RuntimeException alsoInvalid) {
                last = Key.key("minecraft", "ui.button.click");
            }
            return Sound.sound(last, Sound.Source.MASTER, safeVolume, safePitch);
        }
    }

    static float normalizeVolume(float configured) {
        return Float.isFinite(configured) ? Math.max(0.0f, Math.min(10.0f, configured)) : 1.0f;
    }

    static float normalizePitch(float configured) {
        return Float.isFinite(configured) ? Math.max(0.5f, Math.min(2.0f, configured)) : 1.0f;
    }

    public static void play(JavaPlugin plugin, Player player, Sound sound) {
        player.getScheduler().run(plugin, task -> player.playSound(sound), () -> {});
    }

    /**
     * Clave de sonido a partir de lo que escribe el dueño del server.
     *
     * <p>Acepta las dos formas que existen ahi fuera: la clave real
     * ({@code minecraft:block.beacon.activate}) y el nombre estilo enum de Bukkit
     * ({@code BLOCK_BEACON_ACTIVATE}), que es el que aparece en casi toda la documentacion
     * de Minecraft. La traduccion entre ambas NO es "bajar a minusculas y cambiar _ por .":
     * {@code BLOCK_NOTE_BLOCK_PLING} es {@code block.note_block.pling}. Por eso se pregunta a
     * Bukkit en vez de adivinar; una clave inventada no lanza ningun error, simplemente deja
     * de sonar sin decir nada.
     *
     * @return la clave, o {@code null} si parecia un nombre estilo enum y no existe
     */
    private static String normalize(String key) {
        String clean = key.trim();
        if (clean.indexOf(':') >= 0) return clean.toLowerCase(Locale.ROOT);
        if (clean.indexOf('.') >= 0) return "minecraft:" + clean.toLowerCase(Locale.ROOT);
        return legacyKey(clean);
    }

    /**
     * Clave sintacticamente valida pase lo que pase: {@link Key#key} rechaza mayusculas y
     * lanzaria, y esto es el ultimo recurso de un camino que solo hace un sonido.
     */
    private static String safeKey(String raw) {
        String clean = (raw == null ? "" : raw).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._:/-]", "_");
        if (clean.isBlank()) return "minecraft:ui.button.click";
        return clean.indexOf(':') >= 0 ? clean : "minecraft:" + clean;
    }

    /** {@code BLOCK_BEACON_ACTIVATE} -&gt; {@code minecraft:block.beacon.activate}. */
    private static String legacyKey(String name) {
        try {
            return org.bukkit.Sound.valueOf(name.toUpperCase(Locale.ROOT)).getKey().toString();
        } catch (Throwable ignored) {
            // Nombre desconocido, o un Bukkit donde Sound ya no es un enum / el registro aun
            // no esta cargado. Nada de esto debe romper la funcion que queria hacer un sonido.
            return null;
        }
    }
}
