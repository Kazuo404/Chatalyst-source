package dev.chatalyst.discord;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Optional bridge to a login/authentication plugin, resolved entirely by reflection so
 * Chatalyst keeps zero compile-time or runtime dependency on any of them.
 *
 * <p>The point is ordering: on a cracked/mixed server the player is present but not yet
 * authenticated during {@code PlayerJoinEvent}, and the auth plugin already restrains them.
 * Prompting for a Discord link there would collide with the login screen, so the gate waits
 * for the auth plugin's own login event instead. When no supported plugin is installed the
 * hook reports itself absent and the gate arms right after join.
 */
final class AuthHook {

    /**
     * Plugin name -&gt; candidate "player finished logging in" Bukkit events, in detection
     * order. Several candidates per plugin because these API packages get renamed between
     * major versions; the first one that resolves wins and the rest are simply absent.
     */
    private static final Map<String, List<String>> KNOWN_EVENTS = new LinkedHashMap<>();

    static {
        KNOWN_EVENTS.put("AuthMe", List.of("fr.xephi.authme.events.LoginEvent"));
        // nLogin's package is "event" (singular). The plural spelling shipped earlier here
        // and silently never bound, which sent every nLogin server to the fallback timer.
        KNOWN_EVENTS.put("nLogin", List.of(
                "com.nickuc.login.api.event.bukkit.auth.LoginEvent",
                "com.nickuc.login.api.events.bukkit.auth.LoginEvent"));
        KNOWN_EVENTS.put("LibreLogin", List.of(
                "xyz.kyngs.librelogin.api.event.events.AuthenticatedEvent"));
        KNOWN_EVENTS.put("OpeNLogin", List.of(
                "com.lielamar.auth.bukkit.events.PlayerAuthenticateEvent"));
        KNOWN_EVENTS.put("JPremium", List.of("pl.gmjw.jpremium.api.event.PlayerLoginEvent"));
    }

    private final JavaPlugin plugin;
    private volatile String detectedPlugin = "";

    AuthHook(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Name of the auth plugin whose login event was bound, or empty when none is active. */
    String detectedPlugin() {
        return detectedPlugin;
    }

    boolean isActive() {
        return !detectedPlugin.isEmpty();
    }

    /**
     * Binds {@code onAuthenticated} to the installed auth plugin's login event. The callback
     * runs on whichever thread that plugin fires the event on, so the caller must hop to the
     * player's region thread before touching player APIs.
     *
     * @param customEventClass administrator-provided event class for an unlisted auth plugin;
     *                         it wins over auto-detection when it resolves.
     * @return true when an event was bound.
     */
    boolean bind(Listener owner, String customEventClass, Consumer<Player> onAuthenticated) {
        detectedPlugin = "";
        if (customEventClass != null && !customEventClass.isBlank()) {
            for (Plugin candidate : plugin.getServer().getPluginManager().getPlugins()) {
                if (register(owner, candidate, customEventClass.trim(), onAuthenticated)) {
                    detectedPlugin = candidate.getName();
                    return true;
                }
            }
            plugin.getLogger().warning("Discord link gate: the configured Auth_Login_Event '"
                    + customEventClass + "' was not found in any installed plugin;"
                    + " falling back to auto-detection.");
        }
        for (var entry : KNOWN_EVENTS.entrySet()) {
            Plugin candidate = plugin.getServer().getPluginManager().getPlugin(entry.getKey());
            if (candidate == null || !candidate.isEnabled()) continue;
            boolean bound = false;
            for (String className : entry.getValue()) {
                if (register(owner, candidate, className, onAuthenticated)) {
                    bound = true;
                    break;
                }
            }
            if (bound) {
                detectedPlugin = candidate.getName();
                plugin.getLogger().info("Discord link gate: waiting for " + entry.getKey()
                        + " to authenticate players before asking them to link.");
                return true;
            }
            plugin.getLogger().warning("Discord link gate: " + entry.getKey() + " is installed"
                    + " but its login event could not be bound. Set Discord.Linking.Required."
                    + "Auth_Login_Event to its event class, or the gate will arm on a timer.");
        }
        return false;
    }

    private boolean register(Listener owner, Plugin source, String className,
                             Consumer<Player> onAuthenticated) {
        Class<? extends Event> eventClass;
        Method accessor;
        try {
            Class<?> raw = Class.forName(className, false, source.getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(raw)) return false;
            eventClass = raw.asSubclass(Event.class);
            accessor = playerAccessor(eventClass);
            if (accessor == null) return false;
        } catch (ClassNotFoundException | LinkageError missing) {
            return false;
        }
        try {
            plugin.getServer().getPluginManager().registerEvent(eventClass, owner,
                    EventPriority.MONITOR,
                    (ignored, event) -> {
                        if (!eventClass.isInstance(event)) return;
                        Player player = readPlayer(accessor, event);
                        if (player != null) onAuthenticated.accept(player);
                    },
                    plugin, true);
            return true;
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("Discord link gate could not listen to " + className
                    + ": " + failure);
            return false;
        }
    }

    /** First zero-argument accessor that yields a Player; auth events name it differently. */
    private static Method playerAccessor(Class<?> eventClass) {
        for (String name : new String[]{"getPlayer", "player", "getWho"}) {
            try {
                Method method = eventClass.getMethod(name);
                if (method.getParameterCount() == 0
                        && Player.class.isAssignableFrom(method.getReturnType())) {
                    return method;
                }
            } catch (NoSuchMethodException ignored) {
                // Try the next naming convention.
            }
        }
        return null;
    }

    private Player readPlayer(Method accessor, Event event) {
        try {
            Object value = accessor.invoke(event);
            return value instanceof Player player ? player : null;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            plugin.getLogger().warning("Discord link gate could not read the player from "
                    + event.getEventName() + ": " + failure);
            return null;
        }
    }
}
