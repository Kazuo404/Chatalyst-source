package dev.chatalyst.chat;

import net.kyori.adventure.text.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ultimas lineas de chat que Chatalyst mostro a cada espectador, ya renderizadas para el.
 * Sirve para borrar mensajes SIN firma (online-mode=false, enforce-secure-profile=false):
 * como el cliente no admite quitarlos por firma, se limpia su chat y se le reenvia lo que
 * queda. Guarda tambien las lineas firmadas, para que el redibujado no se las coma.
 * Sin dependencias del servidor: el chat es async, cada cola se sincroniza por si misma.
 */
final class ViewerChatBuffer {

    record Line(String id, Component component) {
    }

    private final int capacity;
    private final Map<UUID, Deque<Line>> byViewer = new ConcurrentHashMap<>();

    ViewerChatBuffer(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity < 1");
        this.capacity = capacity;
    }

    /** Anota una linea mostrada al espectador; descarta la mas antigua al pasar del limite. */
    void record(UUID viewer, String id, Component rendered) {
        if (viewer == null || id == null || id.isEmpty() || rendered == null) return;
        Deque<Line> dq = byViewer.computeIfAbsent(viewer, u -> new ArrayDeque<>());
        synchronized (dq) {
            dq.addLast(new Line(id, rendered));
            while (dq.size() > capacity) dq.removeFirst();
        }
    }

    /** Quita las lineas con esos ids de todos los bufers. Devuelve que espectadores las tenian. */
    Set<UUID> remove(Collection<String> ids) {
        Set<UUID> affected = new HashSet<>();
        if (ids == null || ids.isEmpty()) return affected;
        for (Map.Entry<UUID, Deque<Line>> e : byViewer.entrySet()) {
            Deque<Line> dq = e.getValue();
            boolean changed;
            synchronized (dq) {
                changed = dq.removeIf(line -> ids.contains(line.id()));
            }
            if (changed) affected.add(e.getKey());
        }
        return affected;
    }

    /** Copia de las lineas del espectador, de la mas antigua a la mas nueva. */
    List<Component> lines(UUID viewer) {
        Deque<Line> dq = viewer == null ? null : byViewer.get(viewer);
        if (dq == null) return List.of();
        List<Component> out = new ArrayList<>();
        synchronized (dq) {
            for (Line line : dq) out.add(line.component());
        }
        return out;
    }

    void forget(UUID viewer) {
        if (viewer != null) byViewer.remove(viewer);
    }
}
