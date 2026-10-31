package dev.chatalyst.messages;

import dev.chatalyst.hook.LuckPermsHook;
import dev.chatalyst.hook.VaultHook;
import dev.chatalyst.toggle.ToggleManager;
import dev.chatalyst.util.FoliaTasks;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.Predicate;

/**
 * Entradas fancy: el "show" configurable que suena y se ve cuando entra alguien de rango
 * alto (Join.Fancy en messages.yml). El primer tier (orden del YAML) que coincida con el
 * grupo primario o permiso del jugador gana; cada tier define mensaje, sonido global (rayo
 * por defecto), rayo visual y title, todos opcionales. Se administra desde FancyJoinMenu.
 */
public final class FancyJoin {

    /** Fallback si el sonido configurado no es una clave valida. */
    static final String DEFAULT_SOUND = "minecraft:entity.lightning_bolt.thunder";

    private final JavaPlugin plugin;
    private final ServerMessages messages;
    private final FancyJoinPlayback playback;

    public FancyJoin(JavaPlugin plugin, ServerMessages messages, ToggleManager toggles) {
        this.plugin = plugin;
        this.messages = messages;
        this.playback = new FancyJoinPlayback(plugin, toggles);
    }

    /**
     * Lanza el show para un jugador que acaba de entrar. Cuando Replace_Normal_Message esta
     * activo, la linea normal se usa solo como fallback para jugadores sin entrada fancy.
     * Llamar desde el listener de join, NUNCA para staff vanisheado.
     */
    public void play(Player p, Runnable normalJoin) {
        ConfigurationSection fancy = messages.fancyJoin();
        if (fancy == null || !fancy.getBoolean("Enabled", false)) {
            normalJoin.run();
            return;
        }
        boolean normalSent = !replacesNormalMessage(fancy);
        if (normalSent) normalJoin.run();
        long delay = normalizeDelayTicks(fancy.getLong("Delay_Ticks", 10L));
        p.getScheduler().runDelayed(plugin,
                task -> playNow(p, normalJoin, normalSent), null, delay);
    }

    private void playNow(Player player, Runnable normalJoin, boolean normalSent) {
        if (!player.isOnline() || dev.chatalyst.util.Vanish.isVanished(player)) return;

        // Permission metadata can settle after PlayerJoinEvent; select the tier here, after
        // the configured delay, so preview and real joins see the same LuckPerms state.
        ConfigurationSection current = messages.fancyJoin();
        if (current == null || !current.getBoolean("Enabled", false)) {
            if (!normalSent) normalJoin.run();
            return;
        }
        boolean shown = showMatchingEntrance(player, current);
        if (!normalSent && (!shown || !replacesNormalMessage(current))) normalJoin.run();
    }

    private boolean showMatchingEntrance(Player player, ConfigurationSection current) {
        String group = primaryGroup(player);
        // Un anonimo entra SIEMPRE con el tier generico: aparte de vaciar su grupo (primaryGroup),
        // sus permisos tampoco cuentan aqui — un tier gateado por Permission (no por Groups)
        // delataria su rango igual de bien. Sin esto, un anonimo VIP entraba con la entrada VIP.
        java.util.function.Predicate<String> perms =
                isAnonymous(player) ? p -> false : player::hasPermission;
        ConfigurationSection tiers = current.getConfigurationSection("Tiers");
        String tierKey = pickTier(tiers, perms, group);
        if (tierKey != null) {
            playback.show(player, current.getConfigurationSection("Tiers." + tierKey), null);
            return true;
        }

        // An explicit tier (even disabled) suppresses the automatic fallback for that group.
        ConfigurationSection auto = current.getConfigurationSection("Auto_Detect_LuckPerms");
        if (shouldAutoDetect(auto, tiers, group, LuckPermsHook.isAvailable())) {
            playback.show(player, auto, null);
            return true;
        }
        return false;
    }

    /**
     * Salida anunciada por rango (Quit.Tiers): mismo criterio de seleccion que las entradas.
     * Devuelve true si un tier se hizo cargo, y entonces la linea normal de salida no sale.
     * Llamar desde el evento de salida, NUNCA para staff vanisheado.
     *
     * <p>El texto se renderiza aqui, con el jugador aun conectado: la difusion por espectador
     * corre despues, cuando sus placeholders ya no se pueden resolver.
     */
    public boolean announceQuit(Player player) {
        ConfigurationSection tiers = messages.quitTiers();
        if (tiers == null) return false;
        // Igual que en la entrada: un anonimo no puede delatar su rango al irse.
        Predicate<String> perms = isAnonymous(player) ? p -> false : player::hasPermission;
        String tierKey = pickTier(tiers, perms, primaryGroup(player));
        if (tierKey == null) return false;
        playback.show(player, tiers.getConfigurationSection(tierKey), null);
        return true;
    }

    static boolean replacesNormalMessage(ConfigurationSection fancy) {
        return fancy == null || fancy.getBoolean("Replace_Normal_Message", true);
    }

    static long normalizeDelayTicks(long configured) {
        // Cinco minutos ya cubren cargas de permisos muy tardias sin entregar al scheduler
        // un valor extremo que algunas implementaciones rechazan por overflow interno.
        return Math.max(1L, Math.min(6_000L, configured));
    }

    /** Muestra el show de un tier SOLO al admin que lo prueba desde la GUI. */
    public void preview(Player admin, String tierKey) {
        ConfigurationSection tier = tierSection(tierKey);
        if (tier != null) {
            FoliaTasks.entity(plugin, admin, a -> playback.show(a, tier, a));
        }
    }

    /** Preview de un estilo visual suelto (para la GUI de estilos), en la posicion del admin. */
    public void previewStyle(Player admin, String styleId) {
        FoliaTasks.entity(plugin, admin, a -> FancyJoinStyle.byId(styleId).play(a));
    }

    /** Preview de un sonido suelto (para la GUI de sonidos) al admin que lo elige. */
    public void previewSound(Player admin, String soundKey, double pitch) {
        if (soundKey == null || soundKey.isEmpty()) return;
        float safePitch = clamp(pitch, 0.5f, 2.0f);
        net.kyori.adventure.sound.Sound sound =
                dev.chatalyst.util.SoundUtil.sound(soundKey, DEFAULT_SOUND, 1.0f, safePitch);
        FoliaTasks.entity(plugin, admin, a -> a.playSound(sound));
    }

    /** Preview del efecto generico usado para los grupos LuckPerms autodetectados. */
    public void previewAuto(Player admin) {
        ConfigurationSection fancy = messages.fancyJoin();
        ConfigurationSection auto = fancy == null
                ? null : fancy.getConfigurationSection("Auto_Detect_LuckPerms");
        if (auto != null) {
            FoliaTasks.entity(plugin, admin, a -> playback.show(a, auto, a));
        }
    }

    /**
     * Compatibilidad para seleccionar solo por permiso (usada por configuraciones antiguas
     * y pruebas). Un tier sin Permission no coincide si no se proporciona tambien un grupo.
     */
    static String pickTier(ConfigurationSection tiers, Predicate<String> hasPermission) {
        return FancyJoinMatcher.pickTier(tiers, hasPermission, "");
    }

    /**
     * Selecciona el primer tier que coincida por permiso O por grupo primario. {@code Groups}
     * permite aliases (por ejemplo [admin, staff]); si no se configura, la propia clave del
     * tier funciona como nombre de grupo. Asi un grupo VIP/staff no necesita recibir ademas un
     * permiso artificial solo para que su entrada se reproduzca.
     */
    static String pickTier(ConfigurationSection tiers, Predicate<String> hasPermission,
            String primaryGroup) {
        return FancyJoinMatcher.pickTier(tiers, hasPermission, primaryGroup);
    }

    /** Decide si un grupo sin tier debe usar la entrada generica de autodeteccion. */
    static boolean shouldAutoDetect(ConfigurationSection auto, ConfigurationSection tiers,
            String primaryGroup, boolean luckPermsAvailable) {
        return FancyJoinMatcher.shouldAutoDetect(auto, tiers, primaryGroup, luckPermsAvailable);
    }

    /** True si el jugador esta en modo anonimo (para no delatar su rango en la entrada fancy). */
    private static boolean isAnonymous(Player player) {
        dev.chatalyst.ChatalystPlugin pl = dev.chatalyst.ChatalystPlugin.get();
        return pl != null && pl.getAnonymousManager() != null
                && pl.getAnonymousManager().isAnonymous(player.getUniqueId());
    }

    private static String primaryGroup(Player player) {
        // Modo anonimo: la entrada fancy POR RANGO (sonido/estilo VIP...) delata el grupo
        // aunque el nombre sea falso. Un anonimo entra siempre con el tier generico.
        if (isAnonymous(player)) {
            return "";
        }
        if (LuckPermsHook.isAvailable()) {
            return LuckPermsHook.group(player.getUniqueId());
        }
        return VaultHook.isAvailable() ? VaultHook.group(player) : "";
    }

    private ConfigurationSection tierSection(String tierKey) {
        ConfigurationSection fancy = messages.fancyJoin();
        return fancy == null ? null : fancy.getConfigurationSection("Tiers." + tierKey);
    }

    /** Acota un valor de config a un rango sano (el cliente igual recorta pitch a 0.5-2). */
    static float clamp(double value, float min, float max) {
        return FancyJoinView.clamp(value, min, max);
    }

    /** Variante _es si corresponde; Message/Title/Subtitle base sirve como ingles/fallback. */
    static String localizedValue(ConfigurationSection tier, String field, boolean spanish) {
        return FancyJoinView.localizedValue(tier, field, spanish);
    }
}
