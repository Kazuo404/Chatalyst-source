package dev.chatalyst.messages;

import dev.chatalyst.hook.Placeholders;
import dev.chatalyst.toggle.ToggleManager;
import dev.chatalyst.toggle.ToggleType;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Sustituye los mensajes vanilla de entrada/salida/muerte/expulsion por los de messages.yml
 * y envia el MOTD al entrar. Soporta grupos condicionales (Groups en cada seccion: primer
 * grupo que aplica, mensaje al azar de su lista) y el toggle personal /toggle joinquit
 * (quien lo apaga deja de ver entradas y salidas de otros).
 */
public final class MessagesListener implements Listener {

    private final JavaPlugin plugin;
    private final ServerMessages messages;
    private final ToggleManager toggles;
    private final FancyJoin fancyJoin;
    // Expulsados hace un instante: su PlayerQuitEvent inminente no debe anunciar tambien.
    private final Set<UUID> justKicked = ConcurrentHashMap.newKeySet();
    // Quien esta en mitad de una transicion de /anonymous ya fue despedido del server. Se
    // inyecta como predicado para no atar los mensajes al modulo anonimo.
    private volatile java.util.function.Predicate<UUID> alreadyGone = id -> false;

    public MessagesListener(JavaPlugin plugin, ServerMessages messages, ToggleManager toggles,
                            FancyJoin fancyJoin) {
        this.plugin = plugin;
        this.messages = messages;
        this.toggles = toggles;
        this.fancyJoin = fancyJoin;
    }

    /** Quien esta despedido pero sigue conectado: su marcha real no se vuelve a anunciar. */
    public void setAlreadyGoneCheck(java.util.function.Predicate<UUID> check) {
        this.alreadyGone = check != null ? check : id -> false;
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        // Chatalyst always owns the vanilla line: Enabled=false means truly silent on this
        // backend, not "fall back to Minecraft's join message".
        event.joinMessage(null);
        boolean visible = !dev.chatalyst.util.Vanish.isVanished(p) && messages.joinEnabled();
        if (visible) {
            Runnable normalJoin = () -> announceJoin(p);
            if (fancyJoin == null) normalJoin.run();
            else fancyJoin.play(p, normalJoin);
            playJoinSound(p);
        }
        if (messages.motdEnabled()) {
            boolean firstTime = !p.hasPlayedBefore();
            List<Component> motd = messages.motd(p, firstTime);
            if (!motd.isEmpty()) {
                // Pequeno retardo para que el MOTD aparezca tras los mensajes de conexion.
                p.getScheduler().runDelayed(plugin, task -> {
                    if (p.isOnline()) motd.forEach(p::sendMessage);
                }, null, 20L);
            }
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        // Same master semantics as Join.Enabled: false suppresses vanilla and custom output.
        event.quitMessage(null);
        if (justKicked.remove(p.getUniqueId())) {
            // El anuncio ya lo hizo el kick; sin esto se anunciaria dos veces.
            return;
        }
        // Ya se le despidio: esta en el hueco de una transicion de /anonymous, donde para
        // todo el server ese jugador no esta. Contar la marcha otra vez la cuenta DOS veces,
        // y con dos nombres distintos — que es justo la pista que ata el alias a la persona.
        // Se consulta AQUI, en el evento: el estado se limpia un tick despues, y el mensaje
        // real se compone aun mas tarde.
        if (alreadyGone.test(p.getUniqueId())) return;
        if (dev.chatalyst.util.Vanish.isVanished(p) || !messages.quitEnabled()) return;
        // Quit.Tiers: el rango del jugador tiene su propia salida y sustituye a la normal.
        if (fancyJoin != null && fancyJoin.announceQuit(p)) return;
        announce(p, "Quit", null,
                viewer -> messages.quit(viewer, p),
                messages.quit(plugin.getServer().getConsoleSender(), p));
    }

    // HIGHEST + ignoreCancelled: si otro plugin cancela la expulsion, ni se anuncia ni se
    // marca justKicked (marcarlo silenciaria el proximo quit real del jugador).
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        Player p = event.getPlayer();
        justKicked.add(p.getUniqueId());
        event.leaveMessage(null);
        if (!messages.kickEnabled()) return;
        // Misma regla que la salida: en el hueco de /anonymous el server ya le despidio. Y
        // aqui es peor que en el quit, porque expulsar a quien se esta QUITANDO el disfraz
        // imprimiria su nombre REAL justo debajo de la despedida del alias. El castigo se
        // aplica igual; lo unico que se cae es la linea que ata los dos nombres.
        if (alreadyGone.test(p.getUniqueId())) return;
        if (!dev.chatalyst.util.Vanish.isVanished(p)) {
            announce(p, "Kick", null,
                    viewer -> messages.kick(viewer, p),
                    messages.kick(plugin.getServer().getConsoleSender(), p));
        }
    }

    private void announceJoin(Player player) {
        if (!player.isOnline() || dev.chatalyst.util.Vanish.isVanished(player)) return;
        announce(player, "Join", null,
                viewer -> messages.join(viewer, player),
                messages.join(plugin.getServer().getConsoleSender(), player));
    }

    /**
     * Simula una SALIDA con el ESTADO ACTUAL del jugador — para /anonymous: al ponerse
     * anonimo estando conectado, en vez de que su nombre cambie en sitio (y la gente ate
     * cabos), se anuncia que "salio" con su nombre de ahora y luego que "entro" el alias.
     *
     * <p>Clave: el mensaje se renderiza YA (el nombre se hornea en el componente) y se
     * difunde el componente FIJO. Si se difundiera con {@code messages.quit(viewer, player)}
     * diferido, la tarea por-espectador corre 2+ ticks despues —ya con el alias puesto— y
     * anunciaria el nombre equivocado (la misma "trampa del anuncio aplazado" del quit real).
     */
    public boolean broadcastSimulatedQuit(Player player) {
        if (!player.isOnline() || dev.chatalyst.util.Vanish.isVanished(player)
                || !messages.quitEnabled()) {
            return false;
        }
        snapshotAndBroadcast(player, "Quit",
                viewer -> messages.quit(viewer, player),
                messages.quit(plugin.getServer().getConsoleSender(), player));
        return true;
    }

    /**
     * Simula una ENTRADA con el estado actual (alias ya puesto), con su fancy join y sonido.
     *
     * <p>{@code atAnnouncement} corre en el instante EXACTO en que la linea sale al chat, que
     * no es cuando se llama a este metodo: el fancy join puede retrasarla sus Delay_Ticks. Lo
     * usa /anonymous para reaparecer justo entonces — si reaparecia antes, quien mirara veia
     * el cuerpo medio segundo antes de leer que ese jugador entraba.
     */
    public boolean broadcastSimulatedJoin(Player player, Runnable atAnnouncement) {
        if (!player.isOnline() || dev.chatalyst.util.Vanish.isVanished(player)
                || !messages.joinEnabled()) {
            return false;
        }
        Runnable message = () -> {
            if (atAnnouncement != null) atAnnouncement.run();
            snapshotAndBroadcast(player, "Join",
                    viewer -> messages.join(viewer, player),
                    messages.join(plugin.getServer().getConsoleSender(), player));
        };
        if (fancyJoin == null) message.run();
        else fancyJoin.play(player, message);
        playJoinSound(player);
        return true;
    }

    // Renderiza el mensaje de entrada/salida CON EL ESTADO ACTUAL (nombre horneado) y lo
    // difunde como componente fijo, respetando /toggle joinquit. Reproduce la logica de
    // announce() pero sin re-resolver en la tarea diferida.
    private void snapshotAndBroadcast(Player subject, String section,
                                      Function<Player, Component> perViewer, Component console) {
        String group = messages.groupMessage(subject, section, null);
        Component groupRendered = group != null
                ? MM.full(Placeholders.apply(subject, group)) : null;
        Map<UUID, Component> snapshot = new HashMap<>();
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            snapshot.put(viewer.getUniqueId(),
                    groupRendered != null ? groupRendered : perViewer.apply(viewer));
        }
        FoliaTasks.onlinePlayers(plugin, viewer -> {
            if (toggles != null && !toggles.isEnabled(viewer, ToggleType.JOINQUIT)) return;
            Component c = snapshot.get(viewer.getUniqueId());
            if (!isBlank(c)) viewer.sendMessage(c);
        });
        Component finalConsole = groupRendered != null ? groupRendered : console;
        if (isBlank(finalConsole)) return;
        FoliaTasks.global(plugin, () -> plugin.getServer().getConsoleSender()
                .sendMessage(finalConsole));
    }

    private void playJoinSound(Player player) {
        String sound = messages.joinSound();
        if (sound == null || sound.isEmpty()) return;
        try {
            player.playSound(net.kyori.adventure.sound.Sound.sound(
                    net.kyori.adventure.key.Key.key(sound),
                    net.kyori.adventure.sound.Sound.Source.MASTER, 1f, 1f));
        } catch (RuntimeException ignored) {
            // Invalid sound key: the join itself must keep working.
        }
    }

    // ignoreCancelled: una muerte que otro plugin anulo (arena, duelo con resurreccion) no
    // ocurrio, y aun asi se anunciaba a todo el server con su titulo de PvP incluido.
    @EventHandler(ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        // Muerto en el hueco de una transicion de /anonymous: para el server ese jugador ya no
        // esta, y su linea de muerte lo delataria igual de sola que hablar — un nombre que
        // acaba de salir, o el REAL justo despues de despedir al alias. Se comprueba antes que
        // Death.Enabled porque con la seccion apagada el mensaje que quedaria es el vanilla,
        // que tambien lleva el nombre. La muerte ocurre entera; solo se cae el anuncio.
        if (alreadyGone.test(event.getEntity().getUniqueId())) {
            event.deathMessage(null);
            return;
        }
        // Un vanisheado puede morir (el modo observador deja el daño recibido intacto a
        // proposito), y su linea de muerte anunciaba a todo el server que estaba conectado y
        // donde no debia verse. Misma regla que la entrada y la salida: en vanish no se anuncia.
        // Va antes de Death.Enabled por lo mismo que arriba: el mensaje vanilla tambien delata.
        if (dev.chatalyst.util.Vanish.isVanished(event.getEntity())) {
            event.deathMessage(null);
            return;
        }
        if (!messages.deathEnabled()) return;
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        boolean pvp = killer != null && !killer.equals(victim);

        // El show de PvP (title + sonido a asesino y victima) va aparte del mensaje elegido.
        if (pvp && messages.deathEffectsEnabled()) {
            playDeathEffects(killer, victim);
        }

        // 1) Muerte a manos de otro jugador con un item NOMBRADO (p. ej. /itemname): formato
        //    especial con el nombre del arma y su tooltip. Evento sync: inventario seguro.
        if (pvp) {
            org.bukkit.inventory.ItemStack weapon = killer.getInventory().getItemInMainHand();
            if (!weapon.getType().isAir() && weapon.hasItemMeta()
                    && weapon.getItemMeta().hasDisplayName()) {
                // El nombre lo pone quien tiene el item (creativo, otro plugin) y puede traer
                // un click propio: sin limpiarlo se volvia un boton vivo en el chat de todos,
                // igual que pasaba con [item].
                Component name = dev.chatalyst.chat.ChatRenderer.withoutInteraction(
                        weapon.getItemMeta().displayName()).hoverEvent(weapon);
                Component custom = messages.deathWithItem(plugin.getServer().getConsoleSender(), victim, killer, name);
                if (custom != null) {
                    event.deathMessage(null);
                    broadcastLocalized(viewer -> messages.deathWithItem(viewer, victim, killer, name), custom, true);
                    return;
                }
            }
        }

        event.deathMessage(null);
        String killerName = killerDescriptor(victim);

        // 2) Grupos condicionales (permiso / mundo / Require_Killer_Contains).
        String group = messages.groupMessage(victim, "Death", killerName);
        if (group != null) {
            Component rendered = MM.full(Placeholders.apply(victim,
                    group.replace("{killer}", killerShown(victim, killerName))
                            .replace("{killer_uuid}", killerId(victim))));
            broadcastLocalized(viewer -> rendered, rendered, true);
            return;
        }

        // 3) Linea por causa (PVP, MOB, FALL, LAVA...), en el idioma de cada espectador.
        var cause = victim.getLastDamageCause();
        String causeKey = ServerMessages.causeKey(
                pvp, cause instanceof EntityDamageByEntityEvent,
                cause != null ? cause.getCause().name() : null);
        String consoleLine = messages.deathCauseLineFor(plugin.getServer().getConsoleSender(), causeKey);
        if (consoleLine != null) {
            String shownKiller = killerShown(victim, killerName);
            broadcastLocalized(viewer -> {
                String line = messages.deathCauseLineFor(viewer, causeKey);
                if (line == null) line = consoleLine; // idioma sin la clave y sin fallback yaml
                return MM.full(Placeholders.apply(victim, line.replace("{killer}", shownKiller)
                        .replace("{killer_uuid}", killerId(victim))));
            }, MM.full(Placeholders.apply(victim, consoleLine.replace("{killer}", shownKiller)
                    .replace("{killer_uuid}", killerId(victim)))),
                    true);
            return;
        }

        // 4) Fallback generico (por idioma del cliente si Use_Player_Language).
        broadcastLocalized(viewer -> messages.death(viewer, victim),
                messages.death(plugin.getServer().getConsoleSender(), victim), true);
    }

    /** Title + sonido al asesino y a la victima (Death.Effects en messages.yml). */
    private void playDeathEffects(Player killer, Player victim) {
        // Nombres MOSTRADOS (apodo o disfraz de /anonymous): los titulos privados tampoco
        // deben revelar el nombre real del rival.
        String victimShown = shownPlainName(victim);
        String killerShown = shownPlainName(killer);
        showEffect(killer, "Killer", victimShown, killerShown);
        showEffect(victim, "Victim", victimShown, killerShown);
    }

    /** Nombre visible de un jugador, anonimizado y escapado para plantillas MiniMessage. */
    private static String shownPlainName(Player player) {
        return dev.chatalyst.nick.NickManager.safeName(player);
    }

    /**
     * {killer} para MOSTRAR: si el asesino es un jugador, su nombre visible (apodo o
     * disfraz); los tipos de mob salen tal cual. El matching de Require_Killer_Contains
     * sigue usando el nombre real ({@code killerDescriptor}), esto solo afecta al texto.
     */
    private static String displayKiller(Player victim, String killerName) {
        Player killer = victim.getKiller();
        if (killer != null && killer.getName().equals(killerName)) {
            return shownPlainName(killer);
        }
        return killerName;
    }

    private void showEffect(Player target, String who, String victimName, String killerName) {
        // Titulos en el idioma del que los ve; el sonido no se traduce.
        String title = messages.deathEffectFor(target, who, "Title");
        String subtitle = messages.deathEffectFor(target, who, "Subtitle");
        String sound = messages.deathEffect(who, "Sound");
        if (!title.isEmpty() || !subtitle.isEmpty()) {
            var t = net.kyori.adventure.title.Title.title(
                    MM.full(fill(title, victimName, killerName)),
                    MM.full(fill(subtitle, victimName, killerName)),
                    net.kyori.adventure.title.Title.Times.times(
                            java.time.Duration.ofMillis(250), java.time.Duration.ofSeconds(3),
                            java.time.Duration.ofMillis(750)));
            FoliaTasks.entity(plugin, target, p -> p.showTitle(t));
        }
        if (!sound.isEmpty()) {
            dev.chatalyst.util.SoundUtil.play(plugin, target,
                    dev.chatalyst.util.SoundUtil.sound(sound, sound, 1.0f, 1.0f));
        }
    }

    /** UUID del asesino si fue un jugador; vacio si murio por otra causa. */
    private static String killerId(Player victim) {
        Player killer = victim.getKiller();
        return killer == null ? "" : killer.getUniqueId().toString();
    }

    private static String fill(String template, String victimName, String killerName) {
        return template.replace("{victim}", victimName).replace("{killer}", killerName);
    }

    /** "ZOMBIE" -> "Zombie"; los nombres de jugador se dejan tal cual. */
    private static String pretty(String killerName) {
        if (!killerName.equals(killerName.toUpperCase(java.util.Locale.ROOT))) return killerName;
        String lower = killerName.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /**
     * Como se ESCRIBE el asesino en la linea de muerte.
     *
     * <p>Un mob con nombre propio se anuncia con SU nombre, no con el de su especie. Antes se
     * usaba siempre {@code getType().name()}, asi que un jefe de MythicMobs llamado "Goblin
     * Bruto" mataba y la linea decia "murio a manos de un Zombie": el trabajo de nombrar al
     * bicho no llegaba al unico sitio donde todo el server lo lee.
     *
     * <p>El nombre viaja como componente, asi que conserva su color tal cual se puso. Se
     * serializa a MiniMessage en vez de pegarse crudo, y eso es tambien lo que lo hace seguro:
     * el serializador emite etiquetas para el formato REAL y escapa el texto literal, asi que
     * un jugador que bautice a un lobo con "&lt;red&gt;algo" en un yunque ve ese texto tal
     * cual, sin que se interprete al renderizar la linea.
     */
    private static String killerShown(Player victim, String killerName) {
        Component named = customKillerName(victim);
        if (named != null) return MM.serialize(named);
        if (killerName == null) return "?";
        // Solo se "embellece" un tipo de mob ("ZOMBIE" -> "Zombie"). A un jugador con el
        // nombre o el apodo en mayusculas ("XD", "REY") se lo cambiaba por "Xd" y "Rey".
        Player killer = victim.getKiller();
        if (killer != null && killer.getName().equals(killerName)) {
            return displayKiller(victim, killerName);
        }
        return pretty(killerName);
    }

    /**
     * El nombre propio del que mato, o null si fue un jugador o un mob del monton.
     *
     * <p>Dos preguntas, en este orden. Primero Bukkit: una placa con nombre o un {@code
     * Display} que el plugin de turno si haya puesto en la entidad. Y si Bukkit no sabe nada,
     * MythicMobs — que es el caso normal de un jefe custom, y el unico posible cuando lo
     * dibuja ModelEngine: ahi el cuerpo real va invisible, el cartel lo pinta el modelo y la
     * entidad que te golpea puede no tener {@code customName} ninguno.
     */
    private static Component customKillerName(Player victim) {
        // Un jugador manda siempre: su nombre mostrado ya respeta apodo y /anonymous.
        if (victim.getKiller() != null) return null;
        if (!(victim.getLastDamageCause() instanceof EntityDamageByEntityEvent byEntity)) {
            return null;
        }
        org.bukkit.entity.Entity killer = shooterOf(byEntity.getDamager());
        Component named = killer.customName();
        return named != null ? named : dev.chatalyst.hook.MythicMobsHook.displayName(killer);
    }

    /**
     * Quien DISPARO, no lo disparado. Un jefe que mata a distancia deja como damager la flecha
     * o la bola de fuego, asi que sin esto la linea acusaba a "Arrow" en vez de al mob — y con
     * los mobs custom, que casi siempre pegan a distancia, era el caso mas frecuente.
     */
    private static org.bukkit.entity.Entity shooterOf(org.bukkit.entity.Entity damager) {
        if (damager instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof org.bukkit.entity.Entity shooter) {
            return shooter;
        }
        return damager;
    }

    /** Nombre del asesino para Require_Killer_Contains: jugador, o tipo de mob, o null. */
    private static String killerDescriptor(Player victim) {
        Player killer = victim.getKiller();
        if (killer != null) return killer.getName();
        var cause = victim.getLastDamageCause();
        if (cause instanceof EntityDamageByEntityEvent byEntity) {
            return byEntity.getDamager().getType().name();
        }
        return null;
    }

    /**
     * Grupos condicionales primero (mismo texto para todos, con placeholders del sujeto);
     * si ninguno aplica, el mensaje simple por idioma de siempre.
     */
    private void announce(Player subject, String section, String killerName,
                          Function<Player, Component> perViewer, Component consoleMessage) {
        String group = messages.groupMessage(subject, section, killerName);
        boolean toggleAware = section.equals("Join") || section.equals("Quit") || section.equals("Kick");
        if (group != null) {
            Component rendered = MM.full(Placeholders.apply(subject, group));
            broadcastLocalized(viewer -> rendered, rendered, !toggleAware);
        } else {
            broadcastLocalized(perViewer, consoleMessage, !toggleAware);
        }
    }

    private void broadcastLocalized(Function<Player, Component> messageForViewer,
                                    Component consoleMessage, boolean ignoreToggle) {
        FoliaTasks.onlinePlayers(plugin, viewer -> {
            // /toggle joinquit: quien lo apago no quiere ver entradas/salidas de otros.
            if (!ignoreToggle && toggles != null && !toggles.isEnabled(viewer, ToggleType.JOINQUIT)) return;
            Component message = messageForViewer.apply(viewer);
            if (!isBlank(message)) viewer.sendMessage(message);
        });
        if (isBlank(consoleMessage)) return;
        FoliaTasks.global(plugin, () -> plugin.getServer().getConsoleSender().sendMessage(consoleMessage));
    }

    /**
     * Una linea vacia es SILENCIO, no un renglon en blanco. Es lo que permite dejar
     * {@code Message: ""} para que solo se anuncien los rangos con tier (Join.Fancy.Tiers,
     * Quit.Tiers) y el resto entre y salga sin ensuciar el chat.
     */
    static boolean isBlank(Component message) {
        return message == null || net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(message).isBlank();
    }
}
