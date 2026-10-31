package dev.chatalyst.translate;

import org.bukkit.configuration.ConfigurationSection;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Traduccion en vivo del chat a traves de un endpoint compatible con LibreTranslate
 * (POST JSON {@code {q, source:"auto", target}} -&gt; {@code {translatedText}}). Asincrono con el
 * HttpClient del JDK (nunca toca el hilo del servidor), con cache LRU por (idioma, texto) y
 * deduplicacion de peticiones en vuelo. Fail-open: si el servicio falla o esta apagado, devuelve
 * el texto ORIGINAL, asi un backend caido nunca rompe el chat.
 */
public final class TranslationService {

    private static final int MAX_PROVIDER_RESPONSE_BYTES = 64 * 1024;
    private static final int MAX_TRANSLATED_CHARS = 2_048;
    private static final int MAX_SOURCE_CHARS = 2_048;
    private static final int MAX_INFLIGHT = 512;

    private static final Pattern TRANSLATED =
            Pattern.compile("\"translatedText\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    private record Settings(boolean enabled, String url, String apiKey,
                            long timeoutMs, int minLength) {
    }

    private record TranslationResult(String text, boolean cacheable) {
    }

    // Una sola referencia volatile publica un snapshot coherente. Una peticion nunca puede
    // mezclar la URL anterior con la API key nueva durante /chatalyst reload.
    private volatile Settings settings = new Settings(false, "", "", 1500L, 3);

    private volatile Map<String, String> cache = lru(500);
    private final Map<String, CompletableFuture<String>> inflight = new ConcurrentHashMap<>();

    private static Map<String, String> lru(int max) {
        return Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > max;
            }
        });
    }

    public synchronized void load(ConfigurationSection sec) {
        if (sec == null) {
            settings = new Settings(false, "", "", 1500L, 3);
            cache = lru(500);
            inflight.clear();
            return;
        }
        Settings next = new Settings(
                sec.getBoolean("Enabled", false),
                sec.getString("Provider_Url", "").trim(),
                sec.getString("Api_Key", "").trim(),
                Math.max(300, Math.min(10_000, sec.getLong("Timeout_Ms", 1500))),
                Math.max(1, Math.min(512, sec.getInt("Min_Length", 3))));
        int size = Math.max(32, Math.min(10_000, sec.getInt("Cache_Size", 500)));
        cache = lru(size);
        inflight.clear();
        settings = next;
    }

    public boolean enabled() {
        Settings current = settings;
        return current.enabled() && !current.url().isEmpty();
    }

    public int minLength() {
        return settings.minLength();
    }

    /**
     * Traduce {@code text} al idioma {@code targetLang} (codigo de 2 letras: es, en, zh...).
     * Cacheado y deduplicado; devuelve el original si esta apagado o el servicio falla.
     */
    public synchronized CompletableFuture<String> translate(String text, String targetLang) {
        Settings config = settings;
        if (!config.enabled() || config.url().isEmpty() || text == null || text.isBlank()
                || targetLang == null || targetLang.isBlank()) {
            return CompletableFuture.completedFuture(text);
        }
        if (text.codePointCount(0, text.length()) > MAX_SOURCE_CHARS
                || targetLang.length() > 16
                || !targetLang.matches("[A-Za-z-]+")) {
            return CompletableFuture.completedFuture(text);
        }
        String key = targetLang + ":" + text;
        Map<String, String> requestCache = cache;
        String cached = requestCache.get(key);
        if (cached != null) return CompletableFuture.completedFuture(cached);

        // No conectar whenComplete dentro de computeIfAbsent: request() puede devolver una
        // future ya completada (p. ej. URL invalida), por lo que el callback intentaria
        // eliminar esta misma clave mientras ConcurrentHashMap aun la esta calculando y
        // lanzaria "Recursive update". La promesa intermedia se publica primero y solo el
        // hilo que gano el put inicia la peticion.
        CompletableFuture<String> pending = new CompletableFuture<>();
        // Un proveedor lento/caido no puede acumular una future y un body por cada mensaje
        // distinto. Las claves ya en vuelo aun se deduplican; las nuevas fallan abiertas.
        CompletableFuture<String> alreadyPending = inflight.get(key);
        if (alreadyPending != null) return alreadyPending;
        if (inflight.size() >= MAX_INFLIGHT) {
            return CompletableFuture.completedFuture(text);
        }
        CompletableFuture<String> existing = inflight.putIfAbsent(key, pending);
        if (existing != null) return existing;
        try {
            request(config, text, targetLang).whenComplete((res, err) -> {
                String result = err == null && res != null ? res.text() : text;
                // Fallos y respuestas sin translatedText no contaminan el LRU. Ademas una
                // respuesta iniciada antes de reload nunca entra en el cache nuevo.
                if (res != null && res.cacheable() && settings == config && result != null) {
                    requestCache.put(key, result);
                }
                pending.complete(result);
                inflight.remove(key, pending);
            });
        } catch (RuntimeException failure) {
            // Fail-open incluso si la construccion/puesta en marcha de la peticion falla
            // antes de devolver una future.
            pending.complete(text);
            inflight.remove(key, pending);
        }
        return pending;
    }

    private CompletableFuture<TranslationResult> request(
            Settings config, String text, String targetLang) {
        String body = "{\"q\":" + jsonString(text) + ",\"source\":\"auto\",\"target\":"
                + jsonString(targetLang) + ",\"format\":\"text\""
                + (config.apiKey().isEmpty() ? ""
                : ",\"api_key\":" + jsonString(config.apiKey())) + "}";
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(config.url()))
                    .timeout(Duration.ofMillis(config.timeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (RuntimeException badUrl) {
            return CompletableFuture.failedFuture(badUrl);
        }
        return http.sendAsync(request, dev.chatalyst.util.LimitedBodyHandlers.ofString(
                        MAX_PROVIDER_RESPONSE_BYTES, StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    if (resp.statusCode() / 100 != 2) {
                        return new TranslationResult(text, false);
                    }
                    String translated = extract(resp.body(), null);
                    return translated == null || translated.length() > MAX_TRANSLATED_CHARS
                            ? new TranslationResult(text, false)
                            : new TranslationResult(translated, true);
                });
    }

    static String extract(String responseBody, String fallback) {
        if (responseBody == null) return fallback;
        Matcher m = TRANSLATED.matcher(responseBody);
        return m.find() ? unescapeJson(m.group(1)) : fallback;
    }

    /** Escape minimo de JSON para una cadena (comillas, backslash y controles). */
    static String jsonString(String s) {
        StringBuilder out = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    static String unescapeJson(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'u' -> {
                        if (i + 4 < s.length()) {
                            try {
                                out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException ignore) {
                                out.append(n);
                            }
                        }
                    }
                    default -> out.append(n);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
