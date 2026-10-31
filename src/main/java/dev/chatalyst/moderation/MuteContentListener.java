package dev.chatalyst.moderation;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

/**
 * Extiende el alcance del mute mas alla del chat: carteles, libros y una lista configurable
 * de comandos (config.yml, seccion Mute).
 *
 * <p>Los soft mutes no bloquean nada de esto: el shadow mute solo afecta al chat publico
 * y no debe delatarse (notifyIfMuted ya los ignora).
 */
public final class MuteContentListener implements Listener {

    private final JavaPlugin plugin;
    private final MuteManager mutes;
    // Opcional: el libro-y-pluma de redaccion de correo lo mutea el propio flujo de correo
    // (Mute.Block_Mail); cancelarlo aqui duplicaria el aviso y rompe la limpieza de sesion.
    private final dev.chatalyst.mail.MailComposeManager mailCompose;

    public MuteContentListener(JavaPlugin plugin, MuteManager mutes,
                               dev.chatalyst.mail.MailComposeManager mailCompose) {
        this.plugin = plugin;
        this.mutes = mutes;
        this.mailCompose = mailCompose;
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        if (!config().getBoolean("Mute.Block_Signs", true)) return;
        if (mutes.notifyIfMuted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /**
     * Un nombre de yunque lo lee cualquiera que vea el item (en la mano, tirado, en un cofre o
     * enseñado con [item]): es escribir en publico por otra via. Se corta al SACAR el resultado,
     * no al teclear (PrepareAnvilEvent salta con cada letra y repetiria el aviso), y solo si
     * pone un nombre nuevo: reparar o combinar un item ya nombrado sigue permitido.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAnvilTake(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (!config().getBoolean("Mute.Block_Anvil", true)) return;
        if (event.getRawSlot() != 2
                || !(event.getView() instanceof org.bukkit.inventory.view.AnvilView view)
                || !(event.getWhoClicked() instanceof org.bukkit.entity.Player player)) {
            return;
        }
        var left = view.getTopInventory().getItem(0);
        String current = left != null && left.hasItemMeta() && left.getItemMeta().hasDisplayName()
                ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(left.getItemMeta().displayName())
                : null;
        if (!dev.chatalyst.log.ContentText.isRename(view.getRenameText(), current)) return;
        if (mutes.notifyIfMuted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBookEdit(PlayerEditBookEvent event) {
        if (!config().getBoolean("Mute.Block_Books", true)) return;
        if (mailCompose != null && mailCompose.get(event.getPlayer().getUniqueId()) != null) return;
        if (mutes.notifyIfMuted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        var blocked = config().getStringList("Mute.Blocked_Commands");
        if (blocked.isEmpty()) return;
        if (matchesBlocked(event.getMessage(), blocked) || resolvesToBlocked(event.getMessage(), blocked)) {
            if (mutes.notifyIfMuted(event.getPlayer())) {
                event.setCancelled(true);
            }
        }
    }

    /**
     * Lo mismo, pero mirando el comando REAL al que va la etiqueta: con "msg" en la lista,
     * "/whisper" o "/emsg" (alias del msg de Essentials) tambien se bloquean, sin tener que
     * enumerar cada alias de cada plugin.
     */
    private static boolean resolvesToBlocked(String commandLine, java.util.List<String> blocked) {
        String cmd = commandLine.startsWith("/") ? commandLine.substring(1) : commandLine;
        int space = cmd.indexOf(' ');
        String label = (space >= 0 ? cmd.substring(0, space) : cmd).toLowerCase(Locale.ROOT);
        org.bukkit.command.Command resolved =
                org.bukkit.Bukkit.getServer().getCommandMap().getCommand(label);
        if (resolved == null) return false;
        java.util.List<String> names = new java.util.ArrayList<>(resolved.getAliases());
        names.add(resolved.getName());
        for (String name : names) {
            if (matchesBlocked("/" + name, blocked)) return true;
        }
        return false;
    }

    /**
     * True si la linea de comando ("/me hola") esta en la lista de bloqueados. Compara el
     * nombre tal cual y sin namespace ("minecraft:me" cuenta como "me": el namespace no debe
     * servir para evadir el mute). Visible en el paquete para poder testearlo.
     */
    static boolean matchesBlocked(String commandLine, java.util.List<String> blocked) {
        String cmd = commandLine.startsWith("/") ? commandLine.substring(1) : commandLine;
        int space = cmd.indexOf(' ');
        if (space >= 0) cmd = cmd.substring(0, space);
        cmd = cmd.toLowerCase(Locale.ROOT);
        int colon = cmd.indexOf(':');
        String bare = colon >= 0 ? cmd.substring(colon + 1) : cmd;
        for (String entry : blocked) {
            String e = entry.toLowerCase(Locale.ROOT);
            if (e.equals(cmd) || e.equals(bare)) return true;
        }
        return false;
    }
}
