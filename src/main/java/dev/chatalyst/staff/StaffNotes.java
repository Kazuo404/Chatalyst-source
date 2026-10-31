package dev.chatalyst.staff;

import dev.chatalyst.db.StorageManager;
import dev.chatalyst.db.Store;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Notas de staff por jugador ("avisado verbalmente, la proxima es mute"): memoria
 * compartida entre incidentes que no llegaron a castigo, y por eso NO viven en
 * {@link HistoryLog}. Mismo formato durable que el historial, con tope de {@value #MAX}
 * notas por persona (la mas vieja cae al anadir de mas).
 */
public final class StaffNotes {

    private static final int MAX = 20;
    private static final String ROW = "\u001e";
    private static final String FIELD = "\u001f";

    public record Note(long time, String staff, String text) {
    }

    private final Store store;

    StaffNotes(StorageManager storage) {
        this.store = storage.store("staff_notes");
    }

    /** Anade una nota. Devuelve false si el almacenamiento no pudo confirmarla. */
    public boolean add(UUID target, String staff, String text) {
        if (target == null || text == null || text.isBlank()) return false;
        Note note = new Note(System.currentTimeMillis(), sanitize(staff), sanitize(text));
        // CAS read-modify-write: en Folia (regiones en paralelo) o con varios servidores,
        // dos staff anotando a la vez ya no se pisan la lista (el perdedor reintenta).
        return dev.chatalyst.db.StoreCas.updateDurably(store, target.toString(), raw -> {
            List<Note> notes = decode(raw);
            notes.add(note);
            while (notes.size() > MAX) notes.remove(0);
            return encode(notes);
        });
    }

    /** Borra la nota con ese timestamp exacto. Devuelve false si no existia o no se guardo. */
    public boolean remove(UUID target, long time) {
        boolean[] removed = new boolean[1];
        boolean saved = dev.chatalyst.db.StoreCas.updateDurably(store, target.toString(),
                raw -> {
                    List<Note> notes = decode(raw);
                    removed[0] = notes.removeIf(note -> note.time() == time);
                    if (!removed[0]) return null; // nada que borrar: aborta sin escribir
                    return encode(notes);
                });
        return saved && removed[0];
    }

    public List<Note> list(UUID target) {
        return List.copyOf(decode(store.get(target.toString())));
    }

    private static List<Note> decode(String raw) {
        List<Note> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String row : raw.split(ROW, -1)) {
            String[] fields = row.split(FIELD, 3);
            if (fields.length < 3) continue;
            try {
                out.add(new Note(Long.parseLong(fields[0]), fields[1], fields[2]));
            } catch (NumberFormatException ignored) {
                // fila corrupta: se descarta sin romper el resto
            }
        }
        return out;
    }

    // Una lista vacia se codifica como "" (decode la trata igual que una clave ausente).
    private static String encode(List<Note> notes) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < notes.size(); i++) {
            if (i > 0) out.append(ROW);
            Note note = notes.get(i);
            out.append(note.time()).append(FIELD).append(note.staff())
                    .append(FIELD).append(note.text());
        }
        return out.toString();
    }

    void shutdown() {
        store.flushNow();
    }

    private static String sanitize(String value) {
        String safe = value == null ? "" : value;
        return safe.replace(ROW, " ").replace(FIELD, " ");
    }
}
