package dev.chatalyst.staff.autoclick;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAnimation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;

import java.util.UUID;

/**
 * Clics desde los paquetes, con la hora de LLEGADA (resolucion ~1 ms en vez de un tick).
 *
 * <p>AISLADA como {@code hook.PacketEventsBridge}: solo se carga cuando PacketEvents esta
 * instalado, asi que un servidor sin el nunca ve un NoClassDefFoundError. La clase no
 * implementa nada de PacketEvents; el listener es una clase interna que solo se crea aqui.
 *
 * <p>Corre en el hilo de red: solo busca el tracker del jugador (ya creado al entrar) y apunta
 * una marca. Nunca crea trackers, para que un paquete tardio tras salir no deje basura.
 */
final class AutoclickPacketSource {

    private final AutoclickManager manager;
    private PacketListenerCommon registered;

    AutoclickPacketSource(AutoclickManager manager) {
        this.manager = manager;
    }

    /** @return false si la API de PacketEvents no esta lista (plugin sin cargar del todo) */
    boolean start() {
        if (PacketEvents.getAPI() == null || !PacketEvents.getAPI().isInitialized()) return false;
        registered = PacketEvents.getAPI().getEventManager()
                .registerListener(new Listener(), PacketListenerPriority.MONITOR);
        return true;
    }

    void stop() {
        if (registered == null) return;
        try {
            if (PacketEvents.getAPI() != null) {
                PacketEvents.getAPI().getEventManager().unregisterListener(registered);
            }
        } catch (Throwable ignored) {
            // PacketEvents ya apagado: no queda nada que retirar.
        }
        registered = null;
    }

    private final class Listener implements PacketListener {
        @Override
        public void onPacketReceive(PacketReceiveEvent event) {
            var type = event.getPacketType();
            if (type != PacketType.Play.Client.ANIMATION
                    && type != PacketType.Play.Client.PLAYER_DIGGING
                    && type != PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT
                    && type != PacketType.Play.Client.USE_ITEM
                    && type != PacketType.Play.Client.INTERACT_ENTITY) {
                return;
            }
            if (event.isCancelled() || event.getUser() == null) return;
            UUID id = event.getUser().getUUID();
            ClickTracker tracker = id == null ? null : manager.tracker(id);
            if (tracker == null) return;
            long now = System.nanoTime();
            try {
                if (type == PacketType.Play.Client.ANIMATION) {
                    if (new WrapperPlayClientAnimation(event).getHand() == InteractionHand.MAIN_HAND) {
                        tracker.swing(now);
                    }
                } else if (type == PacketType.Play.Client.PLAYER_DIGGING) {
                    switch (new WrapperPlayClientPlayerDigging(event).getAction()) {
                        case START_DIGGING -> tracker.digStart(now);
                        case CANCELLED_DIGGING -> tracker.digStop(now, false);
                        case FINISHED_DIGGING -> tracker.digStop(now, true);
                        // Soltar con Q tambien balancea el brazo.
                        case DROP_ITEM, DROP_ITEM_STACK -> tracker.use(now);
                        default -> {
                        }
                    }
                } else if (type == PacketType.Play.Client.INTERACT_ENTITY) {
                    // El ataque ES el clic izquierdo; el resto (clic derecho a una entidad) no.
                    if (new WrapperPlayClientInteractEntity(event).getAction()
                            != WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                        tracker.use(now);
                    }
                } else {
                    tracker.use(now);
                }
            } catch (Throwable ignored) {
                // Un paquete con formato inesperado no puede tumbar la red del jugador.
            }
        }
    }
}
