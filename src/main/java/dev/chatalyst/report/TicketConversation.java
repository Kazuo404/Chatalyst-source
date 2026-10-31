package dev.chatalyst.report;

import dev.chatalyst.db.StorageManager;
import dev.chatalyst.db.Store;
import dev.chatalyst.db.StoreCas;

import java.util.ArrayList;
import java.util.List;

/**
 * Conversacion de un ticket (reporte): los mensajes que el reportante y el staff se
 * intercambian despues de abrirlo. Persiste en el {@link Store} "ticket_msgs" (una clave por
 * id de reporte), asi el hilo sobrevive reinicios y, con MySQL, es cross-server.
 */
public final class TicketConversation {

    public static final int MAX_TEXT_LENGTH = 1_024;
    private static final int MAX_MESSAGES = 200;

    /** Un mensaje del hilo. staff = lo escribio el equipo; si no, el reportante. */
    public record Message(String author, boolean staff, long time, String text) {
    }

    private static final char RS = ''; // separa mensajes
    private static final char US = ''; // separa campos

    private final Store store;

    public TicketConversation(StorageManager storage) {
        this.store = storage.store("ticket_msgs");
    }

    TicketConversation(Store store) {
        this.store = store;
    }

    public List<Message> get(int reportId) {
        return parse(store.get(String.valueOf(reportId)));
    }

    public int count(int reportId) {
        return get(reportId).size();
    }

    public boolean add(int reportId, String author, boolean staff, String text) {
        String bounded = dev.chatalyst.util.Sanitize.limit(
                text == null ? "" : text, MAX_TEXT_LENGTH);
        if (bounded.isBlank()) return false;
        Message message = new Message(dev.chatalyst.util.Sanitize.limit(
                author == null ? "" : author, 64), staff, System.currentTimeMillis(), bounded);
        String key = String.valueOf(reportId);
        // Append con CAS: dos mensajes simultaneos (p. ej. staff y reportante en servers
        // distintos) se encadenan en vez de pisarse.
        boolean applied = StoreCas.updateDurably(store, key, raw -> {
            List<Message> list = parse(raw);
            while (list.size() >= MAX_MESSAGES) list.remove(0);
            list.add(message);
            return serialize(list);
        });
        // Un get+put de emergencia pisaria respuestas cross-server. Ante contencion extrema
        // se informa al caller y el texto puede reenviarse sin corromper el hilo existente.
        return applied;
    }

    public boolean clear(int reportId) {
        String key = String.valueOf(reportId);
        String observed = store.get(key);
        return observed == null || store.compareAndRemoveDurably(key, observed);
    }

    public void shutdown() {
        store.flushNow();
    }

    // ---- Serializacion --------------------------------------------------------

    private static String serialize(List<Message> list) {
        StringBuilder sb = new StringBuilder();
        for (Message m : list) {
            if (sb.length() > 0) sb.append(RS);
            sb.append(clean(m.author())).append(US).append(m.staff()).append(US)
                    .append(m.time()).append(US).append(clean(m.text()));
        }
        return sb.toString();
    }

    private static List<Message> parse(String raw) {
        List<Message> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String entry : raw.split(String.valueOf(RS))) {
            String[] f = entry.split(String.valueOf(US), -1);
            if (f.length < 4) continue;
            long time;
            try {
                time = Long.parseLong(f[2]);
            } catch (NumberFormatException ex) {
                time = 0L;
            }
            out.add(new Message(f[0], Boolean.parseBoolean(f[1]), time, f[3]));
        }
        return out;
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace(RS, ' ').replace(US, ' ');
    }
}
