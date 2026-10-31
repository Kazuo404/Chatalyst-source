package dev.chatalyst.staff;

import dev.chatalyst.db.Store;
import dev.chatalyst.db.StoreCas;
import dev.chatalyst.staff.lookup.SeenList;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Rellena el registro de identidad (/dupeip, /iphistory, /namehistory) con lo que el servidor
 * ya sabia ANTES de que Chatalyst llevara la cuenta: las entradas de los logs diarios
 * ({@code logs/*.log.gz}: nombre, IP y hora de cada login) y la ultima IP de Essentials.
 * Asi un jugador que no ha vuelto desde que se instalo el registro aparece igual.
 *
 * <p>Solo suma: la primera vez de cada fila retrocede y la ultima avanza
 * ({@link SeenList#merge}); nada de lo que ya estaba se pisa. Repetirlo no duplica nada.
 */
public final class AltImport {

    /** Un login visto: quien, con que nombre, desde que IP y cuando (epoch ms). */
    public record Login(UUID id, String name, String ip, long time) {
    }

    /** Cuanto se importo, para el log y para el staff. */
    public record Result(int files, int logins, int accounts, int connections) {
    }

    private static final Pattern UUID_LINE = Pattern.compile(
            "^\\[(\\d{2}):(\\d{2}):(\\d{2})] \\[[^\\]]+/INFO]: UUID of player (\\S+) is ([0-9a-fA-F-]{36})");
    private static final Pattern LOGIN_LINE = Pattern.compile(
            "^\\[(\\d{2}):(\\d{2}):(\\d{2})] \\[Server thread/INFO]: (\\S+)\\[/([0-9a-fA-F.:]+):\\d+] logged in with entity id");
    private static final Pattern FILE_DATE = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})-\\d+\\.log(\\.gz)?$");

    private AltImport() {
    }

    /**
     * Logins de un log ya leido. La linea "UUID of player" que Paper escribe justo antes da el
     * UUID exacto; si falta, {@code resolver} lo busca por nombre (o el login se salta).
     */
    static List<Login> parseLog(List<String> lines, LocalDate day, ZoneId zone,
                                Function<String, UUID> resolver) {
        List<Login> out = new ArrayList<>();
        Map<String, UUID> seen = new HashMap<>();
        for (String line : lines) {
            Matcher uuid = UUID_LINE.matcher(line);
            if (uuid.find()) {
                try {
                    seen.put(uuid.group(4).toLowerCase(Locale.ROOT), UUID.fromString(uuid.group(5)));
                } catch (IllegalArgumentException ignored) {
                    // UUID roto: se resolvera por nombre
                }
                continue;
            }
            Matcher login = LOGIN_LINE.matcher(line);
            if (!login.find()) continue;
            String name = login.group(4);
            UUID id = seen.get(name.toLowerCase(Locale.ROOT));
            if (id == null && resolver != null) id = resolver.apply(name);
            if (id == null) continue;
            LocalTime time = LocalTime.of(Integer.parseInt(login.group(1)),
                    Integer.parseInt(login.group(2)), Integer.parseInt(login.group(3)));
            long when = day.atTime(time).atZone(zone).toInstant().toEpochMilli();
            out.add(new Login(id, name, login.group(5), when));
        }
        return out;
    }

    /** Fecha de un log rotado ("2026-09-20-3.log.gz"), o null si no lo es. */
    static LocalDate dateOf(String fileName) {
        Matcher m = FILE_DATE.matcher(fileName);
        if (!m.matches()) return null;
        try {
            return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)));
        } catch (java.time.DateTimeException bad) {
            return null;
        }
    }

    /** Lee todos los logs de {@code logsDir} (rotados y latest.log). */
    static List<Login> readLogs(File logsDir, ZoneId zone, Function<String, UUID> resolver,
                                int[] filesRead) {
        List<Login> out = new ArrayList<>();
        File[] files = logsDir.listFiles();
        if (files == null) return out;
        for (File file : files) {
            String name = file.getName();
            LocalDate day = dateOf(name);
            if (day == null && name.equals("latest.log")) {
                day = Instant.ofEpochMilli(file.lastModified()).atZone(zone).toLocalDate();
            }
            if (day == null || !file.isFile()) continue;
            try (InputStream raw = Files.newInputStream(file.toPath());
                 InputStream in = name.endsWith(".gz") ? new GZIPInputStream(raw) : raw;
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(in, StandardCharsets.UTF_8))) {
                List<String> lines = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    // Solo las dos lineas que interesan: un log de un dia puede ser grande.
                    if (line.contains("UUID of player") || line.contains("logged in with entity id")) {
                        lines.add(line);
                    }
                }
                out.addAll(parseLog(lines, day, zone, resolver));
                filesRead[0]++;
            } catch (IOException unreadable) {
                // log corrupto o a medio escribir: se salta
            }
        }
        return out;
    }

    /**
     * Ultima conexion que guarda Essentials ({@code userdata/<uuid>.yml}): sirve para quien
     * entro antes de los logs que se conservan. Se lee a mano (cuatro claves) para no cargar
     * un YAML entero por jugador.
     */
    static List<Login> readEssentials(File userdataDir) {
        List<Login> out = new ArrayList<>();
        File[] files = userdataDir.listFiles((dir, n) -> n.endsWith(".yml"));
        if (files == null) return out;
        for (File file : files) {
            UUID id;
            try {
                id = UUID.fromString(file.getName().substring(0, file.getName().length() - 4));
            } catch (IllegalArgumentException notAPlayer) {
                continue;
            }
            try {
                Login login = parseEssentials(id, Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
                if (login != null) out.add(login);
            } catch (IOException unreadable) {
                // se salta
            }
        }
        return out;
    }

    static Login parseEssentials(UUID id, List<String> lines) {
        String ip = null;
        String name = null;
        long login = 0;
        long logout = 0;
        for (String line : lines) {
            if (line.startsWith("ip-address:")) ip = value(line);
            else if (line.startsWith("last-account-name:")) name = value(line);
            else if (line.startsWith("  login:")) login = number(value(line));
            else if (line.startsWith("  logout:")) logout = number(value(line));
        }
        long when = Math.max(login, logout);
        if (ip == null || ip.isBlank() || name == null || name.isBlank() || when <= 0) return null;
        return new Login(id, name, ip, when);
    }

    private static String value(String line) {
        String v = line.substring(line.indexOf(':') + 1).trim();
        if (v.length() >= 2 && (v.startsWith("'") && v.endsWith("'") || v.startsWith("\"") && v.endsWith("\""))) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static long number(String v) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException bad) {
            return 0;
        }
    }

    /**
     * Escribe los logins en el registro. Se agrupan antes (un jugador con 300 logins desde
     * casa son 4 escrituras, no 1200) y cada fila se mezcla con CAS sobre lo que ya hay.
     */
    static Result apply(Store store, List<Login> logins, int files) {
        record Span(String label, long first, long last) {
            Span with(String name, long t) {
                return new Span(t >= last ? name : label, Math.min(first, t), Math.max(last, t));
            }
        }
        Map<String, Span> acc = new LinkedHashMap<>();   // acc:<uuid> -> hash
        Map<String, Span> conn = new LinkedHashMap<>();  // conn:<hash> -> uuid
        Map<String, Span> names = new LinkedHashMap<>(); // names:<uuid> -> nombre
        Map<UUID, String[]> latest = new HashMap<>();    // uuid -> {hash, nombre, tiempo}
        Map<String, String> ips = new HashMap<>();
        for (Login login : logins) {
            String hash = AltTracker.hashIp(login.ip());
            if (hash == null) continue;
            String ip = AltTracker.canonicalIp(login.ip());
            ips.putIfAbsent(hash, ip);
            String id = login.id().toString();
            acc.merge(id + "\u0000" + hash, new Span("", login.time(), login.time()),
                    (a, b) -> a.with("", login.time()));
            conn.merge(hash + "\u0000" + id, new Span(login.name(), login.time(), login.time()),
                    (a, b) -> a.with(login.name(), login.time()));
            names.merge(id + "\u0000" + login.name(), new Span("", login.time(), login.time()),
                    (a, b) -> a.with("", login.time()));
            String[] prev = latest.get(login.id());
            if (prev == null || Long.parseLong(prev[2]) <= login.time()) {
                latest.put(login.id(), new String[]{hash, login.name(), Long.toString(login.time())});
            }
        }
        acc.forEach((k, s) -> {
            String[] p = k.split("\u0000", 2);
            StoreCas.update(store, "acc:" + p[0], raw -> SeenList.merge(raw, p[1], s.label(),
                    s.first(), s.last(), AltTracker.MAX_CONNECTIONS_PER_ACCOUNT));
        });
        conn.forEach((k, s) -> {
            String[] p = k.split("\u0000", 2);
            StoreCas.update(store, "conn:" + p[0], raw -> SeenList.merge(raw, p[1], s.label(),
                    s.first(), s.last(), AltTracker.MAX_ACCOUNTS_PER_CONNECTION));
        });
        names.forEach((k, s) -> {
            String[] p = k.split("\u0000", 2);
            StoreCas.update(store, "names:" + p[0], raw -> SeenList.merge(raw, p[1], s.label(),
                    s.first(), s.last(), AltTracker.MAX_NAMES_PER_ACCOUNT));
        });
        ips.forEach((hash, ip) -> {
            if (ip != null && store.get("addr:" + hash) == null) store.put("addr:" + hash, ip);
        });
        // Lo vivo manda: "ultima conexion" e indice de nombres solo se rellenan si no existen.
        latest.forEach((id, l) -> {
            if (store.get("last:" + id) == null) store.put("last:" + id, l[0]);
            String key = dev.chatalyst.nick.OfflineNameIndex.key(l[1]);
            if (key != null && store.get("name:" + key) == null && store.get("nameof:" + id) == null) {
                store.put("name:" + key, id.toString());
                store.put("nameof:" + id, key);
            }
        });
        return new Result(files, logins.size(), latest.size(), ips.size());
    }
}
