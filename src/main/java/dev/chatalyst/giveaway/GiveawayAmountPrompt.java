package dev.chatalyst.giveaway;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * La cantidad de un premio de dinero, escrita por el chat justo antes de lanzar el sorteo.
 *
 * <p>Existe porque una cifra fija en el fichero es la respuesta equivocada a "cuanto damos hoy".
 * Un sorteo de aniversario, uno de un martes cualquiera y uno para compensar una caida no pagan
 * lo mismo, y editar el YAML y recargar para cada uno convierte una decision de treinta segundos
 * en un tramite. La cantidad se decide donde se decide el sorteo: en el momento.
 *
 * <p>Se pide por chat y no por un menu de botones porque una cantidad es un numero libre. Con
 * botones de +1000 llegar a 137.500 son treinta clics, y el rango util va de mil a un millon.
 *
 * <p>Sigue el patron del editor de formatos: sesion por jugador, el mensaje se intercepta en
 * {@code LOWEST} y no llega al chat, y el permiso se vuelve a comprobar al aplicar — la sesion
 * sobrevive a que a alguien le quiten el rango entre que abre el menu y escribe.
 */
public final class GiveawayAmountPrompt implements Listener {


    /**
     * Cuanto se queda escuchando. Sin esto, quien abre el prompt y se olvida pierde su
     * SIGUIENTE mensaje de chat, sea dentro de un minuto o dentro de tres horas: se lo traga
     * el prompt, no llega al server y nadie entiende por que.
     */
    private static final long EXPIRES_AFTER_MILLIS = 120_000L;

    /** Que se esta preguntando. La pantalla es la misma; la respuesta se lee distinto. */
    private enum Asking { AMOUNT, DURATION, PERMISSION }

    /** Lo que hay que devolver a la pantalla de la que se salio para escribir. */
    private record Session(String prizeId, int warmupSeconds, long askedAt, Asking asking) {
        boolean expired() {
            return System.currentTimeMillis() - askedAt > EXPIRES_AFTER_MILLIS;
        }
    }

    private final ChatalystPlugin plugin;
    private final GiveawayManager giveaways;
    private final Map<UUID, Session> waiting = new ConcurrentHashMap<>();

    public GiveawayAmountPrompt(ChatalystPlugin plugin, GiveawayManager giveaways) {
        this.plugin = plugin;
        this.giveaways = giveaways;
    }

    /** Deja al jugador esperando a que escriba la cantidad de este premio. */
    void askAmount(Player player, GiveawayPrize prize, int warmupSeconds) {
        ask(player, prize, warmupSeconds, Asking.AMOUNT, "giveaway-gui.amount-prompt");
    }

    /** Igual, para cuanto dura lo que concede el premio. */
    void askDuration(Player player, GiveawayPrize prize, int warmupSeconds) {
        ask(player, prize, warmupSeconds, Asking.DURATION, "giveaway-gui.duration-prompt");
    }

    /**
     * Y para añadir un permiso a la lista del premio. Este NO vuelve a la pantalla de lanzar:
     * se esta editando el premio, no preparando un sorteo, y devolver al sitio equivocado es
     * como se acaba lanzando un sorteo sin querer.
     */
    void askPermission(Player player, GiveawayPrize prize) {
        ask(player, prize, -1, Asking.PERMISSION, "giveaway-gui.permission-prompt");
    }

    private void ask(Player player, GiveawayPrize prize, int warmupSeconds,
                     Asking asking, String promptKey) {
        waiting.put(player.getUniqueId(),
                new Session(prize.id(), warmupSeconds, System.currentTimeMillis(), asking));
        player.closeInventory();
        player.sendMessage(Lang.get(player, promptKey));
    }

    public boolean isWaiting(UUID player) {
        return waiting.containsKey(player);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncChatEvent event) {
        Player typist = event.getPlayer();
        Session session = waiting.remove(typist.getUniqueId());
        if (session == null) return;
        // Caducada: el mensaje es del jugador, no una respuesta. Se deja pasar al chat.
        if (session.expired()) return;

        event.setCancelled(true); // es una respuesta, no un mensaje para el server
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        FoliaTasks.entity(plugin, typist, player -> {
            // El MISMO que exige el menu, no el de usar el comando. Revalidar contra uno
            // mas debil que el de la pantalla de la que se salio no revalida nada.
            if (!player.hasPermission(GiveawayCommand.ADMIN_PERMISSION)) {
                player.sendMessage(Lang.get(player, "common.no-permission"));
                return;
            }
            if (session.asking() == Asking.PERMISSION) {
                addPermission(player, session, text);
                return;
            }
            boolean duration = session.asking() == Asking.DURATION;
            if (text.equalsIgnoreCase("cancelar") || text.equalsIgnoreCase("cancel")) {
                player.sendMessage(Lang.get(player, duration
                        ? "giveaway-gui.duration-cancelled" : "giveaway-gui.amount-cancelled"));
                apply(player, session, null);
                return;
            }
            Object typed = duration ? readDuration(text) : parse(text);
            if (typed == null) {
                player.sendMessage(Lang.get(player, duration
                        ? "giveaway-gui.duration-invalid" : "giveaway-gui.amount-invalid"));
                // Se vuelve a preguntar en vez de tirar al menu: quien se equivoca escribiendo
                // quiere reescribir, no volver a navegar hasta aqui.
                waiting.put(player.getUniqueId(), new Session(session.prizeId(),
                        session.warmupSeconds(), System.currentTimeMillis(), session.asking()));
                return;
            }
            apply(player, session, typed);
        });
    }

    private void apply(Player player, Session session, Object typed) {
        giveaways.prize(session.prizeId()).ifPresentOrElse(prize -> {
            GiveawayPrize updated = prize;
            if (typed instanceof GiveawayPrize.MoneyBand band) {
                updated = prize.withMoney(band);
                player.sendMessage(Lang.get(player, "giveaway-gui.amount-set",
                        "amount", GiveawayPrize.shown(band)));
            } else if (typed instanceof Long millis) {
                updated = prize.withDuration(millis);
                player.sendMessage(Lang.get(player, "giveaway-gui.duration-set",
                        "duration", GiveawayPrize.durationShown(millis, player)));
            }
            Menu.open(player, new GiveawayStartMenu(plugin, giveaways, updated,
                    session.warmupSeconds()));
        }, () -> player.sendMessage(Lang.get(player, "giveaway.no-prizes")));
    }

    /**
     * Añade el permiso escrito a la lista del premio y guarda el fichero.
     *
     * <p>Se valida ANTES de escribir, con la misma regla que usa el lector del fichero: una
     * linea con espacios no es un nodo, y guardarla dejaria en giveaway.yml una entrada que el
     * plugin descarta al leer — el staff la veria en el menu hasta el siguiente reload y luego
     * desapareceria sin explicacion.
     */
    private void addPermission(Player player, Session session, String text) {
        if (text.equalsIgnoreCase("cancelar") || text.equalsIgnoreCase("cancel")) {
            player.sendMessage(Lang.get(player, "giveaway-gui.permission-cancelled"));
            reopenPermissions(player, session.prizeId());
            return;
        }
        String node = text.trim().toLowerCase(Locale.ROOT);
        if (!GiveawayPrize.validPermission(node)) {
            player.sendMessage(Lang.get(player, "giveaway-gui.permission-invalid"));
            waiting.put(player.getUniqueId(), new Session(session.prizeId(),
                    session.warmupSeconds(), System.currentTimeMillis(), Asking.PERMISSION));
            return;
        }
        giveaways.prize(session.prizeId()).ifPresentOrElse(prize -> {
            java.util.List<String> nodes =
                    new java.util.ArrayList<>(prize.permissions(plugin.getConfig()));
            if (nodes.contains(node)) {
                player.sendMessage(Lang.get(player, "giveaway-gui.permission-already",
                        "permission", node));
            } else {
                nodes.add(node);
                player.sendMessage(giveaways.permissionsFile().save(prize.id(), nodes)
                        ? Lang.get(player, "giveaway-gui.permission-added", "permission", node)
                        : Lang.get(player, "giveaway-gui.permission-save-failed"));
            }
            reopenPermissions(player, session.prizeId());
        }, () -> player.sendMessage(Lang.get(player, "giveaway.no-prizes")));
    }

    private void reopenPermissions(Player player, String prizeId) {
        giveaways.prize(prizeId).ifPresent(fresh ->
                Menu.open(player, new GiveawayPermissionsMenu(plugin, giveaways, fresh)));
    }

    /**
     * Lee "7d", "30d", "12h", "1w" o "permanente".
     *
     * <p>Devuelve null si no se entiende, y NUNCA 0: cero es el valor con el que un premio dice
     * "no opino, manda el ajuste global", y confundirlo con una respuesta escrita convertiria
     * un error de tecleo en una duracion silenciosamente distinta a la que se quiso poner.
     */
    static Long readDuration(String raw) {
        long parsed = dev.chatalyst.util.TimeUtil.parseDuration(raw);
        return parsed == 0L ? null : parsed;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        waiting.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Lee "50000", "50.000", "50 000", "15000-35000" o "15000 a 35000".
     *
     * <p>Los puntos y las comas se tiran por igual, y no se admiten decimales. Es deliberado:
     * medio server escribe 50.000 queriendo decir cincuenta mil y el otro medio escribe 50,000
     * queriendo lo mismo, asi que interpretar cualquiera de los dos como separador decimal
     * convertiria un premio de cincuenta mil en uno de cincuenta delante de todo el mundo. Los
     * centimos en un premio no le importan a nadie; equivocarse por mil, si.
     */
    static GiveawayPrize.MoneyBand parse(String raw) {
        if (raw == null) return null;
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) return null;

        // Un rango se escribe "15000-35000" o "15000 a 35000". Se busca el separador y no se
        // usa una expresion regular: la entrada la escribe una persona en el chat, y un
        // partido en dos por un guion se lee de un vistazo y no tiene nada que memorizar.
        int cut = separator(text);
        if (cut < 0) {
            double only = number(text);
            return only <= 0d ? null : new GiveawayPrize.MoneyBand(only, only);
        }
        double low = number(text.substring(0, cut));
        double high = number(text.substring(cut + 1));
        if (low <= 0d || high <= 0d) return null;
        return low <= high ? new GiveawayPrize.MoneyBand(low, high)
                : new GiveawayPrize.MoneyBand(high, low);
    }

    /** Donde parte el rango, o -1 si no hay rango. Un guion inicial no separa nada. */
    private static int separator(String text) {
        for (int i = 1; i < text.length() - 1; i++) {
            char c = text.charAt(i);
            if (c == '-' || c == 'a') return i;
        }
        return -1;
    }

    private static double number(String group) {
        StringBuilder digits = new StringBuilder();
        for (char c = 0, i = 0; i < group.length(); i++) {
            c = group.charAt(i);
            if (c >= '0' && c <= '9') digits.append(c);
            // Los puntos, las comas y los espacios se tiran por igual, y no se admiten
            // decimales: medio server escribe 50.000 queriendo decir cincuenta mil y el otro
            // medio escribe 50,000 queriendo lo mismo. Leer cualquiera de los dos como coma
            // decimal convertiria un premio de cincuenta mil en uno de cincuenta delante de
            // todo el mundo. Los centimos de un premio no le importan a nadie; fallar por mil
            // veces, si.
            else if (c != '.' && c != ',' && c != ' ') return 0d;
        }
        if (digits.isEmpty() || digits.length() > 15) return 0d;
        try {
            return Math.min(dev.chatalyst.store.StoreProduct.maxMoney(),
                    Double.parseDouble(digits.toString()));
        } catch (NumberFormatException notANumber) {
            return 0d;
        }
    }
}
