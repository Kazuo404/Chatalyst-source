package dev.chatalyst.lang;

import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mensajes traducibles con idioma por jugador. Regla: cliente en espanol -&gt; es_ES;
 * cualquier otro idioma -&gt; en_US. Los mensajes a todos (broadcasts) usan el Locale
 * por defecto de config.yml.
 * <pre>
 *   player.sendMessage(Lang.get(player, player, "no-permission"));   // en el idioma del jugador
 *   Bukkit.broadcast(Lang.get("some.key"));                  // idioma por defecto
 * </pre>
 */
public final class Lang {

    private static JavaPlugin plugin;
    // volatile + swap de mapa completo (no clear() en sitio): el chat async lee Lang por-mensaje
    // (hover, formato), y un /reload que limpiara los mapas en sitio dejaria a esos lectores
    // viendo un mapa a medio construir (get durante resize / CME al iterar en yamlFor).
    private static volatile String defaultLocale = "es_ES";
    // Auto-deteccion: si true, cada jugador recibe el idioma de su cliente cuando existe un
    // archivo lang/<locale>.yml que encaje; si false, TODOS reciben defaultLocale (un solo idioma).
    private static volatile boolean autoDetect = true;
    private static volatile Map<String, YamlConfiguration> languages = Map.of();
    // Copias del jar (siempre completas): fallback cuando el archivo del usuario, mas viejo, no
    // tiene una clave nueva -> asi un update que añade mensajes no rompe langs ya generados.
    private static volatile Map<String, YamlConfiguration> defaults = Map.of();

    private Lang() {
    }

    public static void init(JavaPlugin p) {
        plugin = p;
        saveDefault("lang/es_ES.yml");
        saveDefault("lang/en_US.yml");
        load();
    }

    public static void load() {
        // Construye mapas NUEVOS y los publica de golpe (swap de los campos volatile) en vez de
        // limpiar/repopular en sitio: asi un lector concurrente ve el mapa viejo COMPLETO o el
        // nuevo COMPLETO, nunca uno a medio construir.
        Map<String, YamlConfiguration> newDefaults = new HashMap<>();
        loadDefault(newDefaults, "en_US");
        loadDefault(newDefaults, "es_ES");
        Map<String, YamlConfiguration> newLanguages = new HashMap<>();
        File dir = new File(plugin.getDataFolder(), "lang");
        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".yml"));
        if (files != null) {
            for (File f : files) {
                String name = f.getName().substring(0, f.getName().length() - 4);
                newLanguages.put(name, YamlConfiguration.loadConfiguration(f));
            }
        }
        defaults = newDefaults;
        languages = newLanguages;
        defaultLocale = plugin.getConfig().getString("Locale", "es_ES");
        autoDetect = plugin.getConfig().getBoolean("Auto_Detect_Language", true);
    }

    private static void loadDefault(Map<String, YamlConfiguration> target, String locale) {
        try (InputStream in = plugin.getResource("lang/" + locale + ".yml")) {
            if (in != null) {
                target.put(locale, YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (Exception ignore) {
            // sin el default del jar simplemente no hay fallback para ese idioma
        }
    }

    // ---- API ----------------------------------------------------------------

    /** Mensaje en el idioma del jugador (consola = idioma por defecto). */
    public static Component get(CommandSender who, String key, Object... replacements) {
        return MM.full(rawFor(localeFor(who), key, replacements));
    }

    /** Mensaje en el idioma por defecto (para broadcasts). */
    public static Component get(String key, Object... replacements) {
        return MM.full(rawFor(defaultLocale, key, replacements));
    }

    /** Texto crudo en el idioma por defecto. */
    public static String raw(String key, Object... replacements) {
        return rawFor(defaultLocale, key, replacements);
    }

    /** Texto crudo en el idioma del jugador (para concatenar dentro de GUIs). */
    public static String raw(CommandSender who, String key, Object... replacements) {
        return rawFor(localeFor(who), key, replacements);
    }

    /** Lista de textos crudos en el idioma por defecto. */
    public static List<String> rawList(String key, Object... replacements) {
        return rawListFor(defaultLocale, key, replacements);
    }

    /** Lista de textos crudos en el idioma del jugador (para mensajes multilinea). */
    public static List<String> rawList(CommandSender who, String key, Object... replacements) {
        return rawListFor(localeFor(who), key, replacements);
    }

    // ---- Interno ------------------------------------------------------------

    private static String rawFor(String locale, String key, Object... replacements) {
        String value = lookup(locale, key);
        if (value == null) value = key; // no esta ni en el lang del usuario ni en el default
        return applyReplacements(value, replacements);
    }

    private static List<String> rawListFor(String locale, String key, Object... replacements) {
        List<String> values = lookupList(locale, key);
        if (values.isEmpty()) return values;
        List<String> out = new ArrayList<>(values.size());
        for (String value : values) {
            out.add(applyReplacements(value, replacements));
        }
        return out;
    }

    // Sustitucion en UNA sola pasada: los valores insertados nunca se re-escanean. Con el
    // replace secuencial anterior, un valor sustituido temprano que contuviera el token de
    // un placeholder POSTERIOR ("{duration}" escrito dentro de un motivo) se expandia
    // tambien, dejando que texto de usuario inyectara valores de sistema en el mensaje.
    private static String applyReplacements(String value, Object... replacements) {
        if (replacements.length < 2 || value == null || value.indexOf('{') < 0) return value;
        Map<String, String> byToken = new java.util.HashMap<>();
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            byToken.putIfAbsent("{" + replacements[i] + "}",
                    String.valueOf(replacements[i + 1]));
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        int from = 0;
        while (true) {
            int open = value.indexOf('{', from);
            if (open < 0) break;
            int close = value.indexOf('}', open + 1);
            if (close < 0) break;
            String replacement = byToken.get(value.substring(open, close + 1));
            if (replacement != null) {
                out.append(value, from, open).append(replacement);
                from = close + 1;
            } else {
                // No es un placeholder nuestro ({default:mute}, llaves sueltas): se copia tal
                // cual y se sigue buscando desde el siguiente caracter.
                out.append(value, from, open + 1);
                from = open + 1;
            }
        }
        out.append(value, from, value.length());
        return out.toString();
    }

    // Busca la clave: archivo del usuario -> default del jar (mismo idioma) -> default en_US.
    private static String lookup(String locale, String key) {
        YamlConfiguration user = yamlFor(locale);
        if (user != null && user.isString(key)) return user.getString(key);
        Map<String, YamlConfiguration> defs = defaults; // una sola lectura del volatile
        YamlConfiguration def = defs.get(locale);
        if (def == null) def = defs.get(defaultLocale);
        if (def != null && def.isString(key)) return def.getString(key);
        YamlConfiguration en = defs.get("en_US");
        if (en != null && en.isString(key)) return en.getString(key);
        return null;
    }

    // Busca listas con la misma prioridad que lookup(): usuario -> default idioma -> default
    // configurado -> en_US. Devuelve copia defensiva para que el caller no mutile el YAML.
    private static List<String> lookupList(String locale, String key) {
        YamlConfiguration user = yamlFor(locale);
        if (user != null && user.isList(key)) return new ArrayList<>(user.getStringList(key));
        Map<String, YamlConfiguration> defs = defaults; // una sola lectura del volatile
        YamlConfiguration def = defs.get(locale);
        if (def == null) def = defs.get(defaultLocale);
        if (def != null && def.isList(key)) return new ArrayList<>(def.getStringList(key));
        YamlConfiguration en = defs.get("en_US");
        if (en != null && en.isList(key)) return new ArrayList<>(en.getStringList(key));
        return Collections.emptyList();
    }

    private static YamlConfiguration yamlFor(String locale) {
        Map<String, YamlConfiguration> langs = languages; // una sola lectura del volatile
        YamlConfiguration y = langs.get(locale);
        if (y == null) y = langs.get(defaultLocale);
        if (y == null && !langs.isEmpty()) y = langs.values().iterator().next();
        return y;
    }

    /** True if this sender's client is in Spanish (used for bilingual command aliases). */
    public static boolean isSpanish(CommandSender who) {
        // Cualquier variante de español (es_ES, es_MX, ...) usa el convenio _es de la config
        // (Reply_es, Message_es, etiquetas {$}). El idioma completo lo dan los archivos lang/.
        String locale = localeFor(who);
        return locale != null && locale.toLowerCase(java.util.Locale.ROOT).startsWith("es");
    }

    // Espanol (cualquier variante) -> es_ES; el resto -> en_US.
    private static String localeFor(CommandSender who) {
        // Sin auto-deteccion (o para la consola) TODOS reciben el idioma por defecto.
        if (!autoDetect || !(who instanceof Player p)) return defaultLocale;
        try {
            java.util.Locale loc = p.locale();
            String matched = bestMatch(loc.getLanguage(), loc.getCountry());
            return matched != null ? matched : defaultLocale;
        } catch (Throwable t) {
            return defaultLocale;
        }
    }

    /**
     * Mejor archivo de idioma cargado para el locale del CLIENTE. Escanea lang/*.yml (incluye
     * los que el dueño añada, p. ej. zh_CN.yml o fr_FR.yml). Prioridad:
     *   1) coincidencia exacta idioma_PAIS (pt_BR -&gt; pt_BR.yml),
     *   2) mismo idioma, cualquier pais (pt_BR -&gt; pt_PT.yml).
     * Null si no hay ninguno; el que llama cae a {@code defaultLocale}. Asi, con solo poner el
     * archivo del idioma, el plugin lo detecta y lo asocia automaticamente.
     */
    private static String bestMatch(String language, String country) {
        return bestMatch(languages.keySet(), language, country); // una sola lectura del volatile
    }

    /** Nucleo testeable: elige el mejor locale de {@code available} para language+country. */
    static String bestMatch(java.util.Collection<String> available, String language, String country) {
        if (language == null || language.isBlank()) return null;
        String lang = language.toLowerCase(java.util.Locale.ROOT);
        if (country != null && !country.isBlank()) {
            String full = lang + "_" + country.toLowerCase(java.util.Locale.ROOT);
            for (String key : available) {
                if (key.toLowerCase(java.util.Locale.ROOT).equals(full)) return key;
            }
        }
        String prefix = lang + "_";
        for (String key : available) {
            String lower = key.toLowerCase(java.util.Locale.ROOT);
            if (lower.equals(lang) || lower.startsWith(prefix)) return key;
        }
        return null;
    }

    private static void saveDefault(String resource) {
        File out = new File(plugin.getDataFolder(), resource);
        if (!out.exists()) {
            plugin.saveResource(resource, false);
        }
    }
}
