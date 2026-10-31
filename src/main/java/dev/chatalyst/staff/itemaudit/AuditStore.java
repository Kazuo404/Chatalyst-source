package dev.chatalyst.staff.itemaudit;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * El registro en disco: un archivo por dia en {@code plugins/Chatalyst/logs/}
 * ({@code itemaudit-YYYY-MM-DD.log}), igual que hace el registro de chat.
 *
 * <p>No va en el {@code Store} clave-valor del plugin a proposito: ese guarda cada clave como
 * un valor entero que se reescribe en cada cambio (ver HistoryLog), y un registro que crece
 * con cada clic en creativo lo convertiria en reescribir megas por fila. Un archivo
 * append-only por dia escribe solo lo nuevo, se borra por dias enteros al caducar y se lee
 * con un editor o con grep si el plugin no esta.
 *
 * <p>Las filas se encolan en memoria (cualquier hilo) y se vuelcan en bloque fuera del hilo
 * principal; al apagar se vuelca lo pendiente de forma sincrona para no perder nada.
 */
final class AuditStore {

    static final String PREFIX = "itemaudit-";
    static final String SUFFIX = ".log";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;
    /** Disco caido durante horas: se sacrifica lo mas viejo antes que la memoria del server. */
    private static final int MAX_PENDING = 50_000;

    private final Path dir;
    private final Logger logger;
    private final ZoneId zone;
    private final ConcurrentLinkedQueue<AuditEntry> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingSize = new AtomicInteger();
    private final Object fileLock = new Object();

    AuditStore(Path dir, Logger logger, ZoneId zone) {
        this.dir = dir;
        this.logger = logger;
        this.zone = zone;
    }

    void append(AuditEntry entry) {
        if (entry == null) return;
        pending.add(entry);
        if (pendingSize.incrementAndGet() > MAX_PENDING && pending.poll() != null) {
            pendingSize.decrementAndGet();
        }
    }

    /** Vuelca lo pendiente. Bloquea: llamar desde el scheduler asincrono o al apagar. */
    void flush() {
        synchronized (fileLock) {
            List<AuditEntry> batch = new ArrayList<>();
            AuditEntry next;
            while ((next = pending.poll()) != null) batch.add(next);
            if (batch.isEmpty()) return;
            // Una tanda que cruza la medianoche va a dos archivos: cada fila al de su dia.
            Map<LocalDate, StringBuilder> byDay = new LinkedHashMap<>();
            for (AuditEntry entry : batch) {
                byDay.computeIfAbsent(day(entry.time()), ignored -> new StringBuilder())
                        .append(entry.encode()).append('\n');
            }
            try {
                Files.createDirectories(dir);
                for (Map.Entry<LocalDate, StringBuilder> chunk : byDay.entrySet()) {
                    Files.writeString(dir.resolve(fileName(chunk.getKey())), chunk.getValue(),
                            StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND);
                }
                pendingSize.addAndGet(-batch.size());
            } catch (IOException failure) {
                // Se reintenta en el siguiente volcado. Puede repetir el dia que si se escribio
                // si falla a medias: mejor una fila duplicada que una perdida.
                List<AuditEntry> retry = new ArrayList<>(batch);
                pending.forEach(retry::add);
                pending.clear();
                pending.addAll(retry);
                logger.warning("Item audit: could not write the log: " + failure.getMessage());
            }
        }
    }

    /**
     * Filas que cumplen la consulta, de la mas nueva a la mas vieja, como mucho {@code limit}.
     * Bloquea (lee disco): llamar fuera del hilo principal.
     */
    List<AuditEntry> query(AuditQuery query, int limit) {
        flush();
        List<AuditEntry> out = new ArrayList<>();
        if (limit <= 0) return out;
        for (Path file : filesNewestFirst()) {
            List<AuditEntry> matches = new ArrayList<>();
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    AuditEntry entry = AuditEntry.decode(line);
                    if (entry != null && query.matches(entry)) matches.add(entry);
                }
            } catch (IOException failure) {
                logger.warning("Item audit: could not read " + file.getFileName() + ": "
                        + failure.getMessage());
                continue;
            }
            Collections.reverse(matches);
            for (AuditEntry entry : matches) {
                out.add(entry);
                if (out.size() >= limit) return out;
            }
        }
        return out;
    }

    /** Borra los dias mas viejos que {@code keepDays} (0 = guardarlo todo). */
    int cleanup(LocalDate today, int keepDays) {
        if (keepDays <= 0 || !Files.isDirectory(dir)) return 0;
        int deleted = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, PREFIX + "*" + SUFFIX)) {
            for (Path file : files) {
                LocalDate date = dateOf(file.getFileName().toString());
                if (date == null || !expired(date, today, keepDays)) continue;
                try {
                    if (Files.deleteIfExists(file)) deleted++;
                } catch (IOException failure) {
                    logger.warning("Item audit: could not delete " + file.getFileName());
                }
            }
        } catch (IOException failure) {
            logger.warning("Item audit: could not clean old logs: " + failure.getMessage());
        }
        return deleted;
    }

    int pending() {
        return Math.max(0, pendingSize.get());
    }

    private List<Path> filesNewestFirst() {
        List<Map.Entry<LocalDate, Path>> dated = new ArrayList<>();
        if (!Files.isDirectory(dir)) return List.of();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, PREFIX + "*" + SUFFIX)) {
            for (Path file : files) {
                LocalDate date = dateOf(file.getFileName().toString());
                if (date != null) dated.add(Map.entry(date, file));
            }
        } catch (IOException failure) {
            logger.warning("Item audit: could not list the logs: " + failure.getMessage());
        }
        dated.sort(Map.Entry.<LocalDate, Path>comparingByKey().reversed());
        return dated.stream().map(Map.Entry::getValue).toList();
    }

    private LocalDate day(long time) {
        return Instant.ofEpochMilli(time).atZone(zone).toLocalDate();
    }

    static String fileName(LocalDate date) {
        return PREFIX + DAY.format(date) + SUFFIX;
    }

    /** Null si el nombre no es de un dia del registro (se deja en paz). */
    static LocalDate dateOf(String fileName) {
        if (fileName == null || !fileName.startsWith(PREFIX) || !fileName.endsWith(SUFFIX)) {
            return null;
        }
        try {
            return LocalDate.parse(fileName.substring(PREFIX.length(),
                    fileName.length() - SUFFIX.length()), DAY);
        } catch (DateTimeParseException notADay) {
            return null;
        }
    }

    /** Con 90 dias, hoy y los 90 anteriores se guardan; el 91 se borra. */
    static boolean expired(LocalDate date, LocalDate today, int keepDays) {
        if (keepDays <= 0) return false;
        return date.isBefore(today.minusDays(Math.min(keepDays, 36_500)));
    }
}
