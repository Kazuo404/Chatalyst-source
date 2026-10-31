package dev.chatalyst.marriage;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * La boda por PASOS, para que sea un evento al que se asiste y no un anuncio que llega tarde:
 *
 * <ol>
 *   <li><b>Convocatoria</b> — se avisa al servidor y se abre el boton de asistir ANTES de que
 *       empiece, para que los invitados lleguen a tiempo.</li>
 *   <li><b>Votos</b> — a cada novio le sale su boton de "acepto"; el servidor ve quien ya lo
 *       ha dicho.</li>
 *   <li><b>Sello</b> — con los dos "acepto" suena la marcha y ahi es cuando el matrimonio se
 *       guarda de verdad.</li>
 * </ol>
 *
 * <p>El matrimonio NO existe hasta el paso 3: si alguien se raja, se desconecta o se acaba el
 * tiempo, no queda nada a medias, solo una boda cancelada.
 *
 * <p>Solo hay UNA boda en curso a la vez en el servidor. Dos ceremonias solapadas
 * compartirian el boton de asistir y los anuncios se pisarian; quien llegue segundo se casa
 * igual, pero sin ceremonia.
 */
public final class WeddingRite implements Listener {

    private enum Stage { GATHERING, VOWS }

    private static final class Rite {
        private final long id;
        private final UUID a;
        private final UUID b;
        private final String nameA;
        private final String nameB;
        private volatile Stage stage = Stage.GATHERING;
        private volatile boolean vowA;
        private volatile boolean vowB;
        /**
         * Una boda termina UNA sola vez. Sin esto: dos "acepto" simultaneos (en Folia cada
         * uno corre en su region) veian los dos votos puestos y sellaban DOS veces — la
         * segunda fallaba al escribir y anunciaba "la boda se ha cancelado" justo despues
         * de casarlos. Y un timeout que entrara a la vez que el ultimo voto los casaba
         * despues de haber cancelado la ceremonia.
         */
        private final java.util.concurrent.atomic.AtomicBoolean finished =
                new java.util.concurrent.atomic.AtomicBoolean();

        private Rite(long id, UUID a, UUID b, String nameA, String nameB) {
            this.id = id;
            this.a = a;
            this.b = b;
            this.nameA = nameA;
            this.nameB = nameB;
        }

        private boolean involves(UUID who) {
            return a.equals(who) || b.equals(who);
        }
    }

    private final JavaPlugin plugin;
    private final MarriageManager marriages;
    private final MarriageCeremony ceremony;

    /**
     * Identificador de la boda en curso. Las tareas aplazadas (paso 2, timeouts) capturan el
     * suyo y comprueban que siga siendo el actual: sin esto, el timeout de una boda cancelada
     * tumbaria la SIGUIENTE.
     */
    private final AtomicLong sequence = new AtomicLong();
    /** Atomica y no volatil: dos aceptaciones a la vez no pueden crear dos bodas. */
    private final java.util.concurrent.atomic.AtomicReference<Rite> current =
            new java.util.concurrent.atomic.AtomicReference<>();

    private volatile long gatheringSeconds = 30L;
    private volatile long vowSeconds = 60L;

    public WeddingRite(JavaPlugin plugin, MarriageManager marriages, MarriageCeremony ceremony) {
        this.plugin = plugin;
        this.marriages = marriages;
        this.ceremony = ceremony;
    }

    public void load() {
        gatheringSeconds = Math.max(5,
                plugin.getConfig().getInt("Marriage.Ceremony.Gathering_Seconds", 30));
        vowSeconds = Math.max(10,
                plugin.getConfig().getInt("Marriage.Ceremony.Vow_Seconds", 60));
    }

    /** True si hay una boda en curso (bloquea empezar otra). */
    public boolean busy() {
        return current.get() != null;
    }

    /** True si este jugador esta ahora mismo en una ceremonia. */
    public boolean involves(UUID who) {
        Rite rite = current.get();
        return rite != null && rite.involves(who);
    }

    /**
     * Paso 1: convoca la boda. Devuelve false si ya hay otra en curso (el llamador casa a la
     * pareja sin ceremonia en vez de dejarlos colgados).
     */
    public boolean begin(Player a, Player b, String nameA, String nameB) {
        long id = sequence.incrementAndGet();
        Rite rite = new Rite(id, a.getUniqueId(), b.getUniqueId(), nameA, nameB);
        // CAS: si otra pareja acaba de empezar la suya, esta no arranca (el llamador los
        // casa sin ceremonia). Un "if (current != null)" suelto dejaba pasar a las dos.
        if (!current.compareAndSet(null, rite)) return false;

        // La invitacion se abre YA y dura toda la ceremonia: el sentido de este paso es que
        // los invitados lleguen ANTES del "si", no despues.
        long openSeconds = gatheringSeconds + vowSeconds;
        ceremony.openInvitation(b, nameA + " & " + nameB, openSeconds * 1000L);
        String seconds = String.valueOf(gatheringSeconds);
        boolean invite = ceremony.guestTeleportEnabled();
        UUID idA = a.getUniqueId();
        UUID idB = b.getUniqueId();
        FoliaTasks.onlinePlayers(plugin, viewer -> {
            viewer.sendMessage(Lang.get(viewer, "marriage.ceremony-summon",
                    "a", nameA, "b", nameB, "seconds", seconds));
            // La invitacion es para los INVITADOS. Los novios ya estan en su boda.
            boolean groom = viewer.getUniqueId().equals(idA) || viewer.getUniqueId().equals(idB);
            if (invite && !groom) {
                viewer.sendMessage(Lang.get(viewer, "marriage.ceremony-invite",
                        "seconds", String.valueOf(openSeconds)));
            }
        });
        FoliaTasks.globalLater(plugin, () -> openVows(id), gatheringSeconds * 20L);
        return true;
    }

    /** Paso 2: se piden los votos. */
    private void openVows(long id) {
        Rite rite = current.get();
        if (rite == null || rite.id != id) return; // esta boda ya no es la actual
        Player a = MarriageManager.online(rite.a);
        Player b = MarriageManager.online(rite.b);
        if (a == null || b == null) {
            cancel(id, "marriage.ceremony-cancelled-left");
            return;
        }
        rite.stage = Stage.VOWS;
        FoliaTasks.onlinePlayers(plugin, viewer -> viewer.sendMessage(
                Lang.get(viewer, "marriage.ceremony-vows", "a", rite.nameA, "b", rite.nameB)));
        promptVow(a);
        promptVow(b);
        FoliaTasks.globalLater(plugin, () -> timeout(id), vowSeconds * 20L);
    }

    private void promptVow(Player who) {
        FoliaTasks.entity(plugin, who, p ->
                p.sendMessage(Lang.get(p, "marriage.ceremony-vow-prompt")));
    }

    /**
     * Paso 3: uno de los novios dice "acepto". Con los dos, la boda se sella. Devuelve la
     * clave del mensaje para quien lo ejecuta.
     */
    public String vow(Player who) {
        Rite rite = current.get();
        if (rite == null || !rite.involves(who.getUniqueId())) {
            return "marriage.ceremony-no-vow";
        }
        if (rite.stage != Stage.VOWS) {
            return "marriage.ceremony-too-soon";
        }
        boolean isA = rite.a.equals(who.getUniqueId());
        if (isA ? rite.vowA : rite.vowB) {
            return "marriage.ceremony-vow-repeat";
        }
        if (isA) {
            rite.vowA = true;
        } else {
            rite.vowB = true;
        }
        String name = isA ? rite.nameA : rite.nameB;
        FoliaTasks.onlinePlayers(plugin, viewer -> viewer.sendMessage(
                Lang.get(viewer, "marriage.ceremony-vow-said", "player", name)));
        if (rite.vowA && rite.vowB) {
            seal(rite);
            return null; // el sello ya manda sus propios mensajes
        }
        return "marriage.ceremony-vow-waiting";
    }

    /** Sella el matrimonio: aqui, y no antes, es cuando la pareja queda casada de verdad. */
    private void seal(Rite rite) {
        // OJO con el orden: current se limpia ANTES de revalidar, porque proposeError()
        // rechaza a quien esta en una ceremonia — y estos dos lo estan. Moverlo abajo haria
        // que TODA boda por pasos se cancelara a si misma en el ultimo paso.
        // Reclama el final del rito: si un timeout o una desconexion ya lo cerraron, este
        // sello llega tarde y no debe casar a nadie.
        if (!rite.finished.compareAndSet(false, true)) return;
        current.compareAndSet(rite, null);
        Player a = MarriageManager.online(rite.a);
        Player b = MarriageManager.online(rite.b);
        if (a == null || b == null) {
            broadcast("marriage.ceremony-cancelled-left", rite);
            return;
        }
        // Se revalida al final: en los 90 segundos de ceremonia cualquiera de los dos pudo
        // acabar casado por otra via.
        String error = marriages.proposeError(rite.a, rite.b);
        if (error != null) {
            a.sendMessage(Lang.get(a, error));
            b.sendMessage(Lang.get(b, error));
            broadcast("marriage.ceremony-cancelled", rite);
            return;
        }
        if (!marriages.marry(rite.a, a.getName(), rite.b, b.getName())) {
            a.sendMessage(Lang.get(a, "marriage.error"));
            b.sendMessage(Lang.get(b, "marriage.error"));
            broadcast("marriage.ceremony-cancelled", rite);
            return;
        }
        a.sendMessage(Lang.get(a, "marriage.married", "player", rite.nameB));
        b.sendMessage(Lang.get(b, "marriage.married", "player", rite.nameA));
        // La marcha y el anuncio final. NO se reabre la invitacion: lleva abierta desde la
        // convocatoria, y reabrirla mandaba el mensaje del boton por segunda vez.
        marriages.announceSealed(a, b);
    }

    /** El tiempo de los votos se agoto sin que los dos dijeran que si. */
    private void timeout(long id) {
        Rite rite = current.get();
        if (rite == null || rite.id != id) return;
        cancel(id, "marriage.ceremony-cancelled-timeout");
    }

    private void cancel(long id, String reasonKey) {
        Rite rite = current.get();
        if (rite == null || rite.id != id) return;
        // Misma reclamacion que en el sello: gana quien llegue primero.
        if (!rite.finished.compareAndSet(false, true)) return;
        current.compareAndSet(rite, null);
        ceremony.clear();
        broadcast(reasonKey, rite);
    }

    private void broadcast(String key, Rite rite) {
        FoliaTasks.onlinePlayers(plugin, viewer -> viewer.sendMessage(
                Lang.get(viewer, key, "a", rite.nameA, "b", rite.nameB)));
    }

    /** Si un novio se va a mitad, la boda se cancela: no hay con quien casarse. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Rite rite = current.get();
        if (rite != null && rite.involves(event.getPlayer().getUniqueId())) {
            cancel(rite.id, "marriage.ceremony-cancelled-left");
        }
    }

    /** Corta cualquier boda en curso (reload o apagado). */
    public void shutdown() {
        Rite rite = current.get();
        if (rite != null) {
            cancel(rite.id, "marriage.ceremony-cancelled");
        }
    }
}
