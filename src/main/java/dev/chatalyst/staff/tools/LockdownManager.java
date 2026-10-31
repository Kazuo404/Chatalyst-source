package dev.chatalyst.staff.tools;

import dev.chatalyst.db.Store;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.staff.StaffModerationModule;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Lockdown de la red: mientras esta activo, solo entra quien tenga {@value #BYPASS} (el
 * staff). El resto ve una pantalla con el motivo y vuelve a intentarlo despues. Es lo que se
 * usa durante un ataque de bots, una migracion o una cuenta de staff comprometida.
 *
 * <p>Vive en el Store compartido, asi que un {@code /lockdown} en el Lobby cierra tambien
 * Survival, y sobrevive a reinicios hasta un {@code /lockdown end}. Solo bloquea ENTRADAS:
 * quien ya esta dentro sigue jugando.
 */
public final class LockdownManager implements Listener {

    public static final String BYPASS = "chatalyst.lockdown.bypass";
    private static final String KEY = "network";

    private final StaffModerationModule module;
    private final Store store;

    public LockdownManager(StaffModerationModule module, Store store) {
        this.module = module;
        this.store = store;
    }

    /** El lockdown activo, o null. Lee la cache del Store: vale en el hilo del login. */
    public LockdownState current() {
        return LockdownState.decode(store.get(KEY));
    }

    public boolean start(String storedReason, String staff) {
        return store.putDurably(KEY,
                new LockdownState(storedReason, staff, System.currentTimeMillis()).encode());
    }

    public boolean end() {
        return store.removeDurably(KEY);
    }

    /**
     * HIGH: despues de lo que decide si alguien puede entrar en absoluto, antes de MONITOR.
     * Si otro motivo ya lo dejo fuera (ban, whitelist), no se pisa su pantalla.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onLogin(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) return;
        // Con la suite de staff apagada /lockdown no existe: un cierre que siguiera aplicando
        // dejaria a todo el mundo fuera sin ninguna forma de abrir desde el juego.
        if (!module.enabled()) return;
        LockdownState state = current();
        if (state == null) return;
        Player player = event.getPlayer();
        if (player.hasPermission(BYPASS)) return;
        event.disallow(PlayerLoginEvent.Result.KICK_OTHER, screen(player, state));
        module.plugin().getLogger().info("[Lockdown] Blocked " + player.getName() + ".");
    }

    /** Pantalla de expulsion con la estetica del plugin, en el idioma del jugador. */
    Component screen(Player viewer, LockdownState state) {
        List<String> lines = Lang.rawList(viewer, "staff-tools.lockdown-screen");
        if (lines.isEmpty() || (lines.size() == 1 && lines.get(0).startsWith("staff-tools."))) {
            lines = fallback(Lang.isSpanish(viewer));
        }
        Component reason = module.formatter().reasonComponent(viewer, state.reason());
        String since = module.formatter().expiresText(viewer, state.since());
        List<Component> out = new ArrayList<>(lines.size());
        for (String raw : lines) {
            String template = raw.replace("{reason}", "<lockdown_reason>")
                    .replace("{since}", MM.escape(since));
            try {
                out.add(MM.full(template, Placeholder.component("lockdown_reason", reason)));
            } catch (RuntimeException broken) {
                out.add(Component.text(MM.strip(raw)));
            }
        }
        return Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), out);
    }

    /** Si el idioma aun no tiene la pantalla, esta: nunca una pantalla vacia o con claves. */
    static List<String> fallback(boolean spanish) {
        String bar = "<#5A4775>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━";
        return spanish
                ? List.of(bar,
                "<gradient:#240046:#9D4EDD:#E0AAFF><bold>✦ RED EN MANTENIMIENTO ✦</bold></gradient>",
                bar, "",
                "<#C0B3D9>La entrada al servidor está cerrada temporalmente.", "",
                "<#C0B3D9>Motivo <#7B68A0>» <#FBF2FF>{reason}",
                "<#C0B3D9>Desde <#7B68A0>» <#B56CFF>{since}", "",
                "<#E0AAFF>Vuelve a intentarlo en unos minutos.", bar)
                : List.of(bar,
                "<gradient:#240046:#9D4EDD:#E0AAFF><bold>✦ NETWORK LOCKDOWN ✦</bold></gradient>",
                bar, "",
                "<#C0B3D9>Joining this server is temporarily closed.", "",
                "<#C0B3D9>Reason <#7B68A0>» <#FBF2FF>{reason}",
                "<#C0B3D9>Since <#7B68A0>» <#B56CFF>{since}", "",
                "<#E0AAFF>Please try again in a few minutes.", bar);
    }

    public void shutdown() {
        store.flushNow();
    }
}
