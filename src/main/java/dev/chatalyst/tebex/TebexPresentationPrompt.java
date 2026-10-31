package dev.chatalyst.tebex;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.lang.LocalizedConfig;
import org.bukkit.command.CommandSender;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Secure chat-input bridge for the text fields in the Tebex presentation editor. */
public final class TebexPresentationPrompt implements Listener {

    private static final int MAX_TEXT_LENGTH = 1_024;
    private static final Pattern SOUND = Pattern.compile(
            "(?:[a-z0-9_.-]+:)?[a-z0-9_./-]{1,128}");
    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    public enum Field {
        CHAT_MESSAGE("Chat.Message", true),
        TOAST_TITLE("Toast.Title", true),
        TOAST_DESCRIPTION("Toast.Description", true),
        TOAST_ICON("Toast.Icon", false),
        TITLE("Title.Title", true),
        SUBTITLE("Title.Subtitle", true),
        ACTIONBAR("ActionBar.Message", true),
        SOUND_KEY("Sound.Key", false);

        private final String path;
        private final boolean translatable;

        Field(String path, boolean translatable) {
            this.path = path;
            this.translatable = translatable;
        }

        /**
         * La clave que edita este administrador: la suya, no siempre la inglesa.
         *
         * <p>El menú le enseña el texto en el idioma de su cliente, así que escribir sobre la
         * clave base le cambiaría un texto que no es el que está leyendo. Un editor en español
         * escribe {@code Chat.Message_es} y deja intacto el inglés. Un material o una clave de
         * sonido no son idioma y no llevan sufijo.
         */
        String path(CommandSender editor) {
            return translatable ? path + LocalizedConfig.suffix(editor) : path;
        }
    }

    private record Pending(String rankId, int returnPage, Field field) {
    }

    private final ChatalystPlugin plugin;
    private final TebexPresentationService presentations;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public TebexPresentationPrompt(ChatalystPlugin plugin,
                                   TebexPresentationService presentations) {
        this.plugin = plugin;
        this.presentations = presentations;
    }

    public void begin(Player player, String rankId, int returnPage, Field field) {
        if (!allowed(player)) {
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        if (rankId != null && !presentations.rankIds().contains(rankId)) {
            player.sendMessage(Lang.get(player, "tebex-presentation.rank-gone"));
            return;
        }
        pending.put(player.getUniqueId(), new Pending(rankId, returnPage, field));
        player.closeInventory();
        player.sendMessage(Lang.get(player, "tebex-presentation.prompt-"
                + key(field)));
        // El aviso va en el idioma del editor y habla de ese mismo idioma: quien lo lee en
        // español está, precisamente, escribiendo la versión en español.
        if (field.translatable) {
            player.sendMessage(Lang.get(player, "tebex-presentation.prompt-language"));
        }
        player.sendMessage(Lang.get(player, "tebex-presentation.prompt-help"));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncChatEvent event) {
        Pending edit = pending.remove(event.getPlayer().getUniqueId());
        if (edit == null) return;
        event.setCancelled(true);
        String input = PLAIN.serialize(event.message()).trim();
        FoliaTasks.entity(plugin, event.getPlayer(),
                player -> apply(player, edit, input));
    }

    private void apply(Player player, Pending edit, String input) {
        if (!allowed(player)) {
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        if (cancelled(input)) {
            player.sendMessage(Lang.get(player,
                    "tebex-presentation.prompt-cancelled"));
            reopen(player, edit);
            return;
        }
        Object value = validated(player, edit.field(), input);
        if (value != null) {
            presentations.set(player, edit.rankId(), edit.field().path(player), value, true);
        }
        reopen(player, edit);
    }

    private Object validated(Player player, Field field, String input) {
        if (input.length() > MAX_TEXT_LENGTH) {
            player.sendMessage(Lang.get(player,
                    "tebex-presentation.prompt-too-long",
                    "max", MAX_TEXT_LENGTH));
            return null;
        }
        return switch (field) {
            case TOAST_ICON -> {
                Material material = Material.matchMaterial(input);
                if (material == null || material.isAir() || !material.isItem()) {
                    player.sendMessage(Lang.get(player,
                            "tebex-presentation.prompt-invalid-material"));
                    yield null;
                }
                yield material.name();
            }
            case SOUND_KEY -> {
                String sound = input.toLowerCase(Locale.ROOT);
                if (!SOUND.matcher(sound).matches()) {
                    player.sendMessage(Lang.get(player,
                            "tebex-presentation.prompt-invalid-sound"));
                    yield null;
                }
                yield sound.indexOf(':') >= 0 ? sound : "minecraft:" + sound;
            }
            default -> MM.sanitizeInteraction(input);
        };
    }

    private void reopen(Player player, Pending edit) {
        Menu.open(player, new TebexPresentationEditorMenu(
                plugin, presentations, edit.rankId(), edit.returnPage()));
    }

    private static boolean cancelled(String input) {
        return input.isBlank() || input.equalsIgnoreCase("cancel")
                || input.equalsIgnoreCase("cancelar");
    }

    private static String key(Field field) {
        return field.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static boolean allowed(Player player) {
        return player.hasPermission("chatalyst.admin")
                || player.hasPermission("chatalyst.admin.tebex.presentation");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
