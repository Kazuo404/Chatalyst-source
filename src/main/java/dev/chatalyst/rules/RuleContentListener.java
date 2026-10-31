package dev.chatalyst.rules;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Aplica las reglas (rules.yml) tambien fuera del chat: comandos, carteles, libros y yunques.
 * Asi el anti-publicidad y demas filtros no se evaden escribiendo en un cartel o comando.
 * Se puede activar/desactivar por contexto en rules.yml (Apply_To).
 */
public final class RuleContentListener implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final RuleEngine rules;
    private final dev.chatalyst.swear.SwearFilter swear;

    public RuleContentListener(RuleEngine rules, dev.chatalyst.swear.SwearFilter swear) {
        this.rules = rules;
        this.swear = swear;
    }

    // True if the text hits a rule (deny) or the swear filter (anti-evasion word list).
    private boolean blocked(Player p, String text, RuleContext context) {
        RuleResult res = rules.check(p, text, context);
        if (res.cancelled) return true;
        if (!swearMatches(text)) return false;
        logSwear(p, context, text);
        return true;
    }

    // Auditoria de contenido bloqueado por groserias: el LogListener (MONITOR,
    // ignoreCancelled) nunca ve estos eventos, asi que sin esto no quedaria rastro.
    // Acceso perezoso (con guard de null): el LogManager se crea despues que este listener.
    private void logSwear(Player p, RuleContext context, String text) {
        dev.chatalyst.log.LogManager log = lazyLog();
        if (log != null && log.logsSwear()) {
            log.log("SWEAR", p.getName(),
                    "[blocked " + context.name().toLowerCase(java.util.Locale.ROOT) + "] " + text);
        }
    }

    private static dev.chatalyst.log.LogManager lazyLog() {
        dev.chatalyst.ChatalystPlugin chatalyst = dev.chatalyst.ChatalystPlugin.get();
        return chatalyst == null ? null : chatalyst.getLogManager();
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!rules.appliesToCommands()) return;
        String message = event.getMessage();
        if (rules.isIgnoredCommand(message)) return; // /login, /register, etc.
        RuleResult res = rules.check(event.getPlayer(), message, RuleContext.COMMAND);
        boolean swearHit = !res.cancelled && swearMatches(message);
        if (res.cancelled || swearHit) {
            // Nunca auditar comandos con credenciales (/login <pass>...), aunque "coincidan".
            if (swearHit) {
                dev.chatalyst.log.LogManager log = lazyLog();
                if (log != null && !log.isSensitiveCommand(message)) {
                    logSwear(event.getPlayer(), RuleContext.COMMAND, message);
                }
            }
            event.setCancelled(true);
        } else if (res.modified) {
            event.setMessage(res.message);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        if (!rules.appliesToSigns()) return;
        StringBuilder joined = new StringBuilder();
        for (Component line : event.lines()) {
            String text = PLAIN.serialize(line);
            if (!text.isBlank() && blocked(event.getPlayer(), text, RuleContext.SIGN)) {
                event.setCancelled(true);
                return;
            }
            if (!joined.isEmpty()) joined.append(' ');
            joined.append(text);
        }
        // Tambien las 4 lineas UNIDAS: un insulto partido linea a linea ("pu"/"ta") evade el
        // chequeo individual, pero unido con espacios lo caza la tolerancia a separadores.
        // Solo el filtro de groserias: re-ejecutar las reglas duplicaria multas/avisos de una
        // regla que ya coincidio linea a linea sin cancelar.
        String all = joined.toString().trim();
        if (!all.isBlank() && swearMatches(all)) {
            logSwear(event.getPlayer(), RuleContext.SIGN, all);
            event.setCancelled(true);
        }
    }

    private boolean swearMatches(String text) {
        return swear != null && swear.isEnabled() && swear.apply(text).matched();
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBook(PlayerEditBookEvent event) {
        if (!rules.appliesToBooks()) return;
        StringBuilder joined = new StringBuilder();
        // El titulo de un libro firmado lo ve cualquiera en el tooltip: pasa el mismo filtro.
        String title = dev.chatalyst.log.LogListener.signedTitle(event);
        if (title != null) {
            if (blocked(event.getPlayer(), title, RuleContext.BOOK)) {
                event.setCancelled(true);
                return;
            }
            joined.append(title);
        }
        for (Component page : event.getNewBookMeta().pages()) {
            String text = PLAIN.serialize(page);
            if (!text.isBlank() && blocked(event.getPlayer(), text, RuleContext.BOOK)) {
                event.setCancelled(true);
                return;
            }
            if (!joined.isEmpty()) joined.append(' ');
            joined.append(text);
        }
        // Igual que en carteles: paginas unidas, por si el insulto va partido entre paginas.
        String all = joined.toString().trim();
        if (!all.isBlank() && swearMatches(all)) {
            logSwear(event.getPlayer(), RuleContext.BOOK, all);
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onAnvil(PrepareAnvilEvent event) {
        if (!rules.appliesToAnvil()) return;
        ItemStack result = event.getResult();
        if (result == null || !result.hasItemMeta()) return;
        ItemMeta meta = result.getItemMeta();
        if (!meta.hasDisplayName()) return;
        String name = PLAIN.serialize(meta.displayName()).trim();
        if (name.isEmpty()) return;
        if (!(event.getView().getPlayer() instanceof Player p)) return;
        if (blocked(p, name, RuleContext.ANVIL)) {
            event.setResult(null); // no permite renombrarlo con ese texto
        }
    }
}
