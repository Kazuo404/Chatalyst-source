package dev.chatalyst.rules;

import dev.chatalyst.hook.Placeholders;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.CommandNames;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.YamlFiles;
import dev.chatalyst.warnings.WarningPoints;
import dev.chatalyst.db.StorageManager;
import dev.chatalyst.book.BookManager;
import dev.chatalyst.log.LogManager;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.PatternSyntaxException;

/**
 * Carga y aplica las reglas de chat (rules.yml) mas un anti-caps integrado.
 * Se evalua antes de formatear el mensaje: puede reemplazar texto, avisar, notificar
 * al staff o cancelar el mensaje.
 */
public final class RuleEngine {

    private static final int MAX_RULES = 512;

    private final JavaPlugin plugin;
    private final WarningPoints warnings;
    private final RuleAdvancedEngine advanced;
    // Para redactar comandos sensibles (/login <pass>...) en los avisos a staff. Se instala
    // tras crear el LogManager (bindLog); null hasta entonces (no hay reglas activas aun).
    private volatile dev.chatalyst.log.LogManager log;
    // Las listas son INMUTABLES y se reemplazan enteras en load() (swap del volatile), y los
    // escalares son volatile: el chat async lee reglas mientras /reload recarga — asi nunca
    // ve una lista a medio rellenar ni lanza ConcurrentModificationException al iterarla.
    private volatile List<Rule> rules = List.of();
    private volatile boolean enabled;
    // Reglas apagadas en caliente (/cht rule toggle). Solo memoria: sobrevive a /cht reload
    // (nombres que ya no existan simplemente no coinciden) pero no a un reinicio.
    private final java.util.Set<String> disabledRules = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Contextos donde ademas del chat se aplican las reglas.
    private volatile boolean applyCommands;
    private volatile boolean applySigns;
    private volatile boolean applyBooks;
    private volatile boolean applyAnvil;
    private volatile boolean applyNicks;
    private volatile List<String> ignoredCommands = List.of();

    // Anti-caps
    private volatile boolean capsEnabled;
    private volatile int capsMinLength;
    private volatile double capsPercentage;
    private volatile String capsWarn;
    private volatile String capsWarnEs;
    private volatile boolean capsLowercase;
    private volatile boolean capsDeny;
    private volatile String capsBypass;

    public RuleEngine(JavaPlugin plugin, WarningPoints warnings, StorageManager storage) {
        this.plugin = plugin;
        this.warnings = warnings;
        this.advanced = new RuleAdvancedEngine(plugin,
                new RuleDataStore(storage.store("rule-data")));
    }

    public void load() {
        slowRuleWarned.clear(); // tras un reload, una regla corregida (o aun rota) reavisa
        invalidReplacementWarned.clear();
        File file = new File(plugin.getDataFolder(), "rules.yml");
        if (!file.exists()) {
            plugin.saveResource("rules.yml", false);
        }
        YamlConfiguration yaml;
        try {
            yaml = YamlFiles.load(file);
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException ex) {
            plugin.getLogger().severe("Rules: rules.yml is invalid; keeping the previous rule"
                    + " snapshot. " + ex.getMessage());
            return;
        }
        enabled = yaml.getBoolean("Enabled", true);

        List<Rule> nextRules = new ArrayList<>();
        java.util.Set<String> loadedRuleNames = new HashSet<>();
        ConfigurationSection groups = yaml.getConfigurationSection("Groups");
        loadRuleSection(yaml.getConfigurationSection("Rules"), groups, nextRules,
                loadedRuleNames, "rules.yml");
        // @import: comparte reglas entre archivos. Cada nombre en Import: aporta su propia
        // seccion Rules (y opcionalmente Groups) y se anexa a las reglas del archivo base.
        java.util.Set<String> loadedImports = new LinkedHashSet<>();
        int importCount = 0;
        for (String importName : yaml.getStringList("Import")) {
            if (nextRules.size() >= MAX_RULES || importCount++ >= 128) break;
            File imported = resolveRuleImport(importName);
            if (imported == null) {
                plugin.getLogger().warning("Rules: ignored an invalid import name '" + importName + "'.");
                continue;
            }
            if (!imported.exists()) {
                plugin.getLogger().warning("Rules: import '" + importName + "' was not found; skipped.");
                continue;
            }
            String importKey;
            try {
                importKey = imported.getCanonicalPath().toLowerCase(Locale.ROOT);
            } catch (IOException ex) {
                plugin.getLogger().warning("Rules: import '" + importName + "' could not be"
                        + " resolved; skipped.");
                continue;
            }
            if (!loadedImports.add(importKey)) {
                plugin.getLogger().warning("Rules: duplicate import '" + importName + "' skipped.");
                continue;
            }
            YamlConfiguration importYaml;
            try {
                importYaml = YamlFiles.load(imported);
            } catch (IOException | org.bukkit.configuration.InvalidConfigurationException ex) {
                plugin.getLogger().warning("Rules: import '" + importName
                        + "' is invalid and was skipped. " + ex.getMessage());
                continue;
            }
            ConfigurationSection importGroups = importYaml.getConfigurationSection("Groups");
            ConfigurationSection effectiveGroups = mergeGroups(groups, importGroups);
            loadRuleSection(importYaml.getConfigurationSection("Rules"),
                    effectiveGroups, nextRules, loadedRuleNames, imported.getName());
        }
        nextRules.sort(Comparator.comparingInt((Rule r) -> r.priority).reversed().thenComparing(r -> r.name));
        rules = List.copyOf(nextRules); // publicacion atomica
        java.util.Set<String> loadedNames = nextRules.stream()
                .map(rule -> rule.name).collect(java.util.stream.Collectors.toUnmodifiableSet());
        disabledRules.retainAll(loadedNames);
        // Los delays pertenecen al snapshot anterior de reglas. Conservarlos despues de un
        // reload no solo aplicaba tiempos viejos a una regla editada: nombres rotativos iban
        // quedando en estos mapas para siempre.
        synchronized (delayLock) {
            ruleLastTrigger.clear();
            playerLastTrigger.clear();
        }
        long maxPd = 0;
        for (Rule r : nextRules) maxPd = Math.max(maxPd, r.extras.playerDelayMs());
        maxPlayerDelayMs = maxPd;

        ConfigurationSection apply = yaml.getConfigurationSection("Apply_To");
        applyCommands = apply != null && apply.getBoolean("Commands", false);
        applySigns = apply != null && apply.getBoolean("Signs", false);
        applyBooks = apply != null && apply.getBoolean("Books", false);
        applyAnvil = apply != null && apply.getBoolean("Anvil", false);
        applyNicks = apply != null && apply.getBoolean("Nicks", false);
        List<String> nextIgnored = new ArrayList<>();
        if (apply != null) {
            for (String c : apply.getStringList("Ignore_Commands")) {
                String label = CommandNames.label(c);
                if (!label.isEmpty()) nextIgnored.add(label);
            }
        }
        ignoredCommands = List.copyOf(nextIgnored); // publicacion atomica

        ConfigurationSection caps = yaml.getConfigurationSection("Anti_Caps");
        capsEnabled = caps != null && caps.getBoolean("Enabled", true);
        capsMinLength = normalizeCapsMinLength(caps != null ? caps.getInt("Min_Length", 8) : 8);
        capsPercentage = normalizeCapsPercentage(
                caps != null ? caps.getDouble("Percentage", 0.7) : 0.7);
        capsWarn = caps != null ? caps.getString("Warn") : null;
        capsWarnEs = caps != null ? caps.getString("Warn_es") : null;
        capsLowercase = caps != null && caps.getBoolean("To_Lowercase", true);
        capsDeny = caps != null && caps.getBoolean("Deny", false);
        capsBypass = caps != null ? caps.getString("Bypass_Permission", "chatalyst.bypass.caps") : "chatalyst.bypass.caps";
    }

    /** Carga las reglas de una seccion Rules (con sus Groups) y las anexa a {@code out}. */
    private void loadRuleSection(ConfigurationSection list, ConfigurationSection groups,
                                 List<Rule> out, java.util.Set<String> loadedNames,
                                 String source) {
        if (list == null) return;
        for (String key : list.getKeys(false)) {
            if (out.size() >= MAX_RULES) {
                plugin.getLogger().warning("Rules: keeping the first " + MAX_RULES
                        + " entries; additional rules were ignored.");
                break;
            }
            if (key == null || key.isBlank() || key.length() > 128) {
                plugin.getLogger().warning("Rules: ignored an empty or overlong rule name.");
                continue;
            }
            String normalizedName = key.toLowerCase(Locale.ROOT);
            if (!loadedNames.add(normalizedName)) {
                plugin.getLogger().warning("Rules: duplicate rule '" + key + "' in " + source
                        + " was skipped; the first definition wins.");
                continue;
            }
            ConfigurationSection sec = list.getConfigurationSection(key);
            if (sec == null) {
                loadedNames.remove(normalizedName);
                continue;
            }
            try {
                ConfigurationSection effective = RuleConfigMerger.merge(sec, groups,
                        warning -> plugin.getLogger().warning("Rule '" + key + "': " + warning));
                out.add(Rule.fromConfig(key, effective));
            } catch (PatternSyntaxException ex) {
                plugin.getLogger().warning("Rule '" + key + "' has invalid regex: " + ex.getMessage());
                loadedNames.remove(normalizedName);
            }
        }
    }

    /** Imported groups override same-named base fields while retaining every other base group. */
    private static ConfigurationSection mergeGroups(ConfigurationSection base,
                                                     ConfigurationSection imported) {
        if (base == null) return imported;
        if (imported == null) return base;
        YamlConfiguration merged = new YamlConfiguration();
        overlaySection(base, merged);
        overlaySection(imported, merged);
        return merged;
    }

    private static void overlaySection(ConfigurationSection source, YamlConfiguration target) {
        for (String key : source.getKeys(true)) {
            if (!source.isConfigurationSection(key)) target.set(key, source.get(key));
        }
    }

    /**
     * Resuelve un nombre de archivo de @import a un File DENTRO del dataFolder. Rechaza rutas,
     * "..", y cualquier cosa que tras canonicalizar escape del dataFolder (anti path traversal).
     */
    private File resolveRuleImport(String name) {
        if (name == null || name.isBlank() || name.length() > 128) return null;
        if (name.contains("/") || name.contains("\\") || name.contains("..")) return null;
        String fileName = name.endsWith(".yml") ? name : name + ".yml";
        File candidate = new File(plugin.getDataFolder(), fileName);
        try {
            java.nio.file.Path canonical = candidate.getCanonicalFile().toPath();
            java.nio.file.Path base = plugin.getDataFolder().getCanonicalFile().toPath();
            return canonical.startsWith(base) ? canonical.toFile() : null;
        } catch (java.io.IOException ex) {
            return null;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    static int normalizeCapsMinLength(int configured) {
        return Math.max(1, Math.min(1_024, configured));
    }

    static double normalizeCapsPercentage(double configured) {
        return Double.isFinite(configured)
                ? Math.max(0.0, Math.min(1.0, configured)) : 0.7;
    }

    public int ruleCount() {
        return rules.size();
    }

    public void bindScriptEvaluator(RuleScriptEvaluator evaluator) {
        advanced.bindScripts(evaluator);
    }

    public void bindLog(LogManager log) {
        this.log = log;
        advanced.bindLog(log);
    }

    public void bindBooks(BookManager books) {
        advanced.bindBooks(books);
    }

    /** Vista inmutable de las reglas cargadas (para /cht rule list). */
    public List<Rule> rules() {
        return rules;
    }

    /** True si la regla esta apagada en caliente via /cht rule toggle. */
    public boolean isRuleDisabled(String name) {
        return disabledRules.contains(name);
    }

    /**
     * Enciende/apaga una regla por nombre sin tocar rules.yml. Devuelve null si no existe;
     * si existe, true = quedo encendida, false = quedo apagada.
     */
    public Boolean toggleRule(String name) {
        for (Rule rule : rules) {
            if (rule.name.equalsIgnoreCase(name)) {
                if (disabledRules.remove(rule.name)) return true;
                disabledRules.add(rule.name);
                return false;
            }
        }
        return null;
    }

    public boolean appliesToCommands() {
        return applyCommands;
    }

    public boolean appliesToSigns() {
        return applySigns;
    }

    public boolean appliesToBooks() {
        return applyBooks;
    }

    public boolean appliesToAnvil() {
        return applyAnvil;
    }

    public boolean appliesToNicks() {
        return applyNicks;
    }

    /** True si este comando (ej. "/login") esta en la lista de ignorados. */
    public boolean isIgnoredCommand(String message) {
        return CommandNames.matches(message, ignoredCommands);
    }

    public RuleResult check(Player player, String original) {
        return check(player, original, RuleContext.CHAT);
    }

    public RuleResult check(Player player, String original, RuleContext context) {
        RuleResult res = new RuleResult(original);
        if (!enabled) return res;

        for (Rule rule : rules) {
            if (disabledRules.contains(rule.name)) continue; // apagada con /cht rule toggle
            if (!rule.appliesTo(context)) continue;
            if (!rule.ignorePermission.isEmpty() && player.hasPermission(rule.ignorePermission)) continue;
            // Las transformaciones existen solo en una vista de busqueda. Sus indices apuntan
            // al original para que censurar "pútà" no quite el acento de un "café" vecino, y
            // para que Ignore.Uuid nunca borre UUIDs del mensaje que finalmente se entrega.
            RuleTextView textView = RuleTextView.create(res.message,
                    rule.advanced.stripAccents(), rule.advanced.ignoreUuid());
            if (containsAny(textView.searchable(), rule.ignoreContains)) continue;
            if (!findWithBudget(rule, textView.searchable())) continue;
            // Ventana Begins/Expires, condiciones require/ignore y delays: si no toca,
            // la regla simplemente no se activa para este mensaje.
            if (!passesStaticGates(player, rule)) continue;
            if (rule.advanced.hasSimpleGates() && !passesSimpleGates(player, rule, context)) continue;
            if (rule.advanced.hasConditions()
                    && !advanced.passes(player, rule, res.message, context)) continue;
            var matchEvent = new dev.chatalyst.api.RuleMatchEvent(player, rule.name, context,
                    original, res.message);
            plugin.getServer().getPluginManager().callEvent(matchEvent);
            if (matchEvent.isCancelled()) continue;
            if (!acquireDelays(player, rule)) continue;

            if (rule.warn != null) warn(player, rule.warn, rule.warnEs, original, rule.name,
                    "rules." + rule.name + ".warn");
            if (rule.notifyPermission != null && rule.notifyMessage != null) {
                // El aviso va a STAFF/consola/Discord: si el mensaje era un comando sensible
                // (/login <pass>) se redacta, para que un notify con {original_message} no
                // filtre la credencial. El warn (que ve solo el propio jugador) queda intacto.
                notify(player, rule.notifyPermission, rule.notifyMessage, rule.notifyMessageEs,
                        redactSensitive(original, context), rule.name,
                        "rules." + rule.name + ".notify");
            }
            if (rule.points > 0 && warnings != null) {
                warnings.add(player, rule.points);
            }
            if (!rule.actions.isEmpty()) {
                runActions(player, rule, original, res.message, context);
            }
            if (!rule.advanced.playerCommands().isEmpty()) {
                runPlayerCommands(player, rule, original, res.message, context);
            }
            if (!rule.advanced.proxyCommands().isEmpty()) {
                runProxyCommands(player, rule, original, res.message, context);
            }
            runExtraActions(player, rule);
            if (rule.advanced.hasActions()) {
                advanced.execute(player, rule, original, context);
            }
            String prolong = rule.advanced.replaceProlong();
            if (rule.replace != null || !prolong.isEmpty()) {
                // Prolong (@prolong) censura cada match con un punto Unicode repetido hasta el
                // largo del fragmento original. El replace se proyecta desde la vista plegada
                // al mensaje real, por lo que el texto ajeno al match siempre queda intacto.
                // null = presupuesto anti-ReDoS agotado: se omite SOLO el reemplazo; deny y
                // stopAfter de la regla siguen aplicando (una regla censora no debe dejar
                // pasar el mensaje justo cuando su regex se porta peor).
                String replaced = replaceProjected(rule, textView, rule.replace,
                        rule.replaceRaw, prolong);
                if (replaced != null) {
                    var replaceEvent = new dev.chatalyst.api.RuleReplaceEvent(player, rule.name,
                            context, res.message, replaced);
                    plugin.getServer().getPluginManager().callEvent(replaceEvent);
                    if (!replaceEvent.isCancelled()) {
                        res.message = replaceEvent.getResult();
                        res.modified = true;
                    }
                }
            }
            if (!rule.advanced.rewriteInWorld().isEmpty()) {
                // then rewritein <mundo>: si el jugador esta en ese mundo, el mensaje entero se
                // sustituye. Mundo desde la cache del tracker (el chat async no toca getWorld()).
                String w = dev.chatalyst.channel.RegionPartyTracker.worldOf(player.getUniqueId());
                if (w != null) {
                    for (Map.Entry<String, String> e : rule.advanced.rewriteInWorld().entrySet()) {
                        if (e.getKey().equalsIgnoreCase(w)) {
                            var replaceEvent = new dev.chatalyst.api.RuleReplaceEvent(player,
                                    rule.name, context, res.message, e.getValue());
                            plugin.getServer().getPluginManager().callEvent(replaceEvent);
                            if (!replaceEvent.isCancelled()) {
                                res.message = replaceEvent.getResult();
                                res.modified = true;
                            }
                            break;
                        }
                    }
                }
            }
            if (rule.deny) {
                res.cancelled = true;
                res.silent = rule.extras.denySilently();
                return res;
            }
            if (rule.stopAfter) break;
        }

        applyAntiCaps(player, res);
        return res;
    }

    public RuleResult check(Player player, String original, String context) {
        RuleContext ctx = RuleContext.fromString(context);
        return check(player, original, ctx != null ? ctx : RuleContext.CHAT);
    }

    // ---- Presupuesto anti-ReDoS -----------------------------------------------------------
    // Las reglas las escribe el admin, pero el TEXTO lo aporta el jugador: un regex con
    // backtracking catastrofico + un mensaje diseñado clavarian el hilo de chat. SafeRegex
    // corta el matcheo; la regla lenta se salta (fail-open) y se avisa UNA vez por regla.

    private final java.util.Set<String> slowRuleWarned =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private boolean findWithBudget(Rule rule, String message) {
        try {
            return dev.chatalyst.util.SafeRegex.find(rule.pattern, message,
                    dev.chatalyst.util.SafeRegex.DEFAULT_BUDGET_NANOS);
        } catch (dev.chatalyst.util.SafeRegex.BudgetExceededException slow) {
            warnSlowRule(rule);
            return false;
        }
    }

    private void warnSlowRule(Rule rule) {
        if (slowRuleWarned.add(rule.name)) {
            plugin.getLogger().warning("Rule '" + rule.name + "' exceeded the regex time budget"
                    + " on a player message and was skipped. Its Match pattern likely has"
                    + " catastrophic backtracking (e.g. nested quantifiers); rewrite it.");
        }
    }

    private void applyAntiCaps(Player player, RuleResult res) {
        if (!capsEnabled) return;
        if (capsBypass != null && !capsBypass.isEmpty() && player.hasPermission(capsBypass)) return;
        String t = res.message;
        if (t.length() < capsMinLength) return;
        int letters = 0, upper = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) upper++;
            }
        }
        if (letters == 0) return;
        if ((double) upper / letters < capsPercentage) return;

        if (capsWarn != null) warn(player, capsWarn, capsWarnEs, res.message, "anti-caps",
                "rules.anti-caps.warn");
        if (capsLowercase) {
            res.message = t.toLowerCase(Locale.ROOT);
            res.modified = true;
        }
        if (capsDeny) res.cancelled = true;
    }

    private void warn(Player player, String template, String templateEs, String original, String ruleName,
                      String fallbackKey) {
        player.sendMessage(build(player, pick(player, template, templateEs, fallbackKey), original, ruleName));
    }

    private void notify(Player source, String permission, String template, String templateEs,
                        String original, String ruleName, String fallbackKey) {
        // notify() corre en el hilo async del chat (RuleChatListener): iterar jugadores y
        // consultar permisos aqui no es Folia-safe; se despacha por entidad.
        dev.chatalyst.util.FoliaTasks.onlinePlayers(plugin,
                staff -> staff.hasPermission(permission),
                staff -> staff.sendMessage(build(source,
                        pick(staff, template, templateEs, fallbackKey), original, ruleName)));
        Component comp = build(source, pick(plugin.getServer().getConsoleSender(), template, templateEs, fallbackKey),
                original, ruleName);
        dev.chatalyst.util.FoliaTasks.global(plugin,
                () -> plugin.getServer().getConsoleSender().sendMessage(comp));
        var chatalyst = dev.chatalyst.ChatalystPlugin.get();
        if (chatalyst != null && chatalyst.getStaffNotifier() != null) {
            chatalyst.getStaffNotifier().alert(
                    net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                            .plainText().serialize(comp));
        }
    }

    private String pick(CommandSender viewer, String template, String templateEs, String fallbackKey) {
        if (!Lang.isSpanish(viewer)) return template;
        if (templateEs != null && !templateEs.isEmpty()) return templateEs;
        String translated = Lang.raw(viewer, fallbackKey);
        return fallbackKey.equals(translated) ? template : translated;
    }

    /** Redacta los argumentos de un comando sensible (/login &lt;pass&gt;...) en avisos a staff. */
    private String redactSensitive(String message, RuleContext context) {
        if (context != RuleContext.COMMAND || message == null || message.isBlank()) return message;
        dev.chatalyst.log.LogManager current = log;
        return current != null
                ? current.redactSensitiveCommand(message)
                : dev.chatalyst.log.LogManager.redactAlwaysSensitiveCommand(message);
    }

    private Component build(Player player, String template, String original, String ruleName) {
        // El mensaje lo escribe el JUGADOR y esto acaba en MM.full() (que interpreta click,
        // hover, run_command...). Sin escapar, un jugador podria poner <click:run_command:...>
        // en su mensaje e inyectar un boton "vivo" en la notificacion que ve el staff. escape()
        // lo neutraliza a texto literal: el staff ve lo que el jugador tecleo, sin ejecutarlo.
        //
        // Y va como placeholder de MiniMessage SIN parsear, insertado DESPUES de resolver la
        // plantilla: antes entraba como {message} y luego PlaceholderAPI recorria la cadena
        // entera, asi que un "%player_ip%" escrito por el jugador se resolvia dentro del aviso.
        String safeTemplate = template
                .replace("{original_message}", "<chatalyst_rule_msg>")
                .replace("{message}", "<chatalyst_rule_msg>");
        Map<String, String> extra = Map.of("rule", ruleName);
        return MM.full(Placeholders.apply(player, safeTemplate, extra),
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(
                        "chatalyst_rule_msg", original == null ? "" : original));
    }

    // Ultima activacion por regla (Delay_Seconds) y por regla+jugador (Player_Delay_Seconds).
    private final Map<String, Long> ruleLastTrigger = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> playerLastTrigger = new java.util.concurrent.ConcurrentHashMap<>();
    private final Object delayLock = new Object();
    // Player_Delay mas largo entre todas las reglas: una entrada mas vieja que esto ya no
    // bloquea nada, asi que se puede podar sin afectar ningun delay.
    private volatile long maxPlayerDelayMs;
    // Solo se poda cuando el mapa crece: evita recorrerlo entero en cada activacion.
    private static final int PLAYER_DELAY_SOFT_CAP = 5000;

    /** Ventana de fechas y condiciones declarativas que no mutan estado. */
    private boolean passesStaticGates(Player player, Rule rule) {
        Rule.Extras x = rule.extras;
        long now = System.currentTimeMillis();
        if (!x.activeAt(now)) return false;
        return !x.hasConditions() || passesConditions(player, x);
    }

    /** Reserves global and per-player cooldowns together after every condition has passed. */
    private boolean acquireDelays(Player player, Rule rule) {
        Rule.Extras x = rule.extras;
        if (x.delayMs() <= 0 && x.playerDelayMs() <= 0) return true;
        long now = System.currentTimeMillis();
        String playerKey = rule.name + ":" + player.getUniqueId();
        synchronized (delayLock) {
            Long globalLast = ruleLastTrigger.get(rule.name);
            if (x.delayMs() > 0 && globalLast != null && now - globalLast < x.delayMs()) {
                return false;
            }
            Long playerLast = playerLastTrigger.get(playerKey);
            if (x.playerDelayMs() > 0 && playerLast != null
                    && now - playerLast < x.playerDelayMs()) {
                return false;
            }
            if (x.delayMs() > 0) ruleLastTrigger.put(rule.name, now);
            if (x.playerDelayMs() > 0) {
                pruneStalePlayerDelays(now);
                playerLastTrigger.put(playerKey, now);
            }
            return true;
        }
    }

    /**
     * Poda entradas de {@link #playerLastTrigger} de jugadores que ya no importan (la ventana
     * del Player_Delay mas largo ya paso), para que el mapa no crezca sin limite con la
     * rotacion de jugadores. Solo actua cuando el mapa se hace grande.
     */
    private void pruneStalePlayerDelays(long now) {
        if (playerLastTrigger.size() < PLAYER_DELAY_SOFT_CAP) return;
        long cutoff = now - Math.max(maxPlayerDelayMs, 60_000L);
        playerLastTrigger.values().removeIf(t -> t < cutoff);
    }

    private boolean passesConditions(Player player, Rule.Extras x) {
        if (!x.requirePermission().isEmpty() && !player.hasPermission(x.requirePermission())) return false;
        // Mundo y regiones desde la cache del tracker: el chat async no puede tocar getWorld().
        String world = dev.chatalyst.channel.RegionPartyTracker.worldOf(player.getUniqueId());
        String worldLow = world != null ? world.toLowerCase(Locale.ROOT) : null;
        if (!x.requireWorlds().isEmpty() && (worldLow == null || !x.requireWorlds().contains(worldLow))) return false;
        if (!x.ignoreWorlds().isEmpty() && worldLow != null && x.ignoreWorlds().contains(worldLow)) return false;
        String gm = player.getGameMode().name().toLowerCase(Locale.ROOT);
        if (!x.requireGamemodes().isEmpty() && !x.requireGamemodes().contains(gm)) return false;
        if (!x.ignoreGamemodes().isEmpty() && x.ignoreGamemodes().contains(gm)) return false;
        if (!x.requireChannels().isEmpty() || !x.ignoreChannels().isEmpty()) {
            var pl = dev.chatalyst.ChatalystPlugin.get();
            var channel = pl != null && pl.getChannelManager() != null
                    ? pl.getChannelManager().getWriteChannel(player) : null;
            String name = channel != null ? channel.name : "";
            if (!x.requireChannels().isEmpty() && !x.requireChannels().contains(name)) return false;
            if (!x.ignoreChannels().isEmpty() && x.ignoreChannels().contains(name)) return false;
        }
        if (!x.requireRegions().isEmpty() || !x.ignoreRegions().isEmpty()) {
            var regions = dev.chatalyst.channel.RegionPartyTracker.regionsOf(player.getUniqueId());
            boolean inRequired = false, inIgnored = false;
            for (String cached : regions) {
                // "chatalyst:mercado" y "mercado" (WorldGuard) se comparan por el nombre pelado.
                int colon = cached.indexOf(':');
                String bare = (colon >= 0 ? cached.substring(colon + 1) : cached).toLowerCase(Locale.ROOT);
                if (x.requireRegions().contains(bare)) inRequired = true;
                if (x.ignoreRegions().contains(bare)) inIgnored = true;
            }
            if (!x.requireRegions().isEmpty() && !inRequired) return false;
            if (inIgnored) return false;
        }
        return true;
    }

    /** Acciones extra de la regla (sonido, title, bossbar, toast, multa, kick). */
    private void runExtraActions(Player player, Rule rule) {
        Rule.Extras x = rule.extras;
        if (x.soundKey() != null && !x.soundKey().isEmpty()) {
            dev.chatalyst.util.SoundUtil.play(plugin, player,
                    dev.chatalyst.util.SoundUtil.sound(x.soundKey(), x.soundKey(), 1.0f, 1.0f));
        }
        if (x.title() != null || x.subtitle() != null) {
            FoliaTasks.entity(plugin, player, p -> p.showTitle(net.kyori.adventure.title.Title.title(
                    MM.full(x.title() != null ? x.title() : ""),
                    MM.full(x.subtitle() != null ? x.subtitle() : ""))));
        }
        if (x.actionbar() != null) {
            FoliaTasks.entity(plugin, player, p -> p.sendActionBar(MM.full(x.actionbar())));
        }
        if (x.bossbar() != null) {
            dev.chatalyst.util.Displays.bossbarTimed(plugin, java.util.List.of(player),
                    MM.full(x.bossbar()), net.kyori.adventure.bossbar.BossBar.Color.RED);
        }
        if (x.toastTitle() != null || x.toastDescription() != null) {
            dev.chatalyst.util.ToastUtil.show(plugin, player,
                    MM.strip(x.toastTitle() != null ? x.toastTitle() : ""),
                    MM.strip(x.toastDescription() != null ? x.toastDescription() : ""));
        }
        if (x.fine() > 0) {
            FoliaTasks.global(plugin, () -> {
                if (dev.chatalyst.hook.VaultHook.withdraw(player, x.fine())) {
                    player.sendMessage(Lang.get(player, "rules.fined",
                            "amount", dev.chatalyst.hook.VaultHook.format(x.fine())));
                }
            });
        }
        if (x.kickReason() != null && !x.kickReason().isEmpty()) {
            FoliaTasks.entity(plugin, player, p -> p.kick(MM.full(x.kickReason())));
        }
    }

    private void runActions(Player source, Rule rule, String original, String current, RuleContext context) {
        List<String> actions = rule.actions;
        if (actions.isEmpty()) return;
        FoliaTasks.global(plugin, () -> {
            for (String raw : actions) {
                if (raw == null || raw.isBlank()) continue;
                plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                        substitute(raw, source, current, original, rule, context));
            }
        });
    }

    /** then command: ejecuta los comandos COMO el jugador (sus permisos, no consola). */
    private void runPlayerCommands(Player source, Rule rule, String original, String current,
                                   RuleContext context) {
        List<String> cmds = rule.advanced.playerCommands();
        FoliaTasks.entity(plugin, source, p -> {
            for (String raw : cmds) {
                if (raw == null || raw.isBlank()) continue;
                String cmd = normalizeCommand(substitute(raw, p, current, original, rule, context));
                if (!cmd.isEmpty()) p.performCommand(cmd);
            }
        });
    }

    /** then proxyconsole: ejecuta los comandos como consola en los DEMAS servers de la red. */
    private void runProxyCommands(Player source, Rule rule, String original, String current,
                                  RuleContext context) {
        var chatalyst = dev.chatalyst.ChatalystPlugin.get();
        var proxy = chatalyst != null ? chatalyst.getProxyManager() : null;
        if (proxy == null || !proxy.isEnabled()) return;
        for (String raw : rule.advanced.proxyCommands()) {
            if (raw == null || raw.isBlank()) continue;
            String expanded = substitute(raw, source, current, original, rule, context).trim();
            int separator = firstWhitespace(expanded);
            if (separator <= 0) continue; // syntax: "<target-server> <command>"
            String target = expanded.substring(0, separator);
            String cmd = normalizeCommand(expanded.substring(separator + 1));
            if (!cmd.isEmpty()) proxy.forwardRuleCommand(target, cmd);
        }
    }

    private static String substitute(String raw, Player source, String current, String original,
                                     Rule rule, RuleContext context) {
        return raw
                .replace("{player}", source.getName())
                .replace("{message}", commandSafe(current))
                .replace("{original_message}", commandSafe(original))
                .replace("{rule}", rule.name)
                .replace("{context}", context != null ? context.name().toLowerCase(Locale.ROOT) : "chat");
    }

    /**
     * El mensaje del JUGADOR dentro de un comando de consola de la regla. Tal cual podia cerrar
     * comillas o llaves ("tellraw @a {...}"), meter saltos de linea o alargar el comando sin
     * limite. Se quitan esos caracteres y se acota la longitud: el comando sigue teniendo el
     * texto, pero ya no lo puede reescribir.
     */
    static String commandSafe(String message) {
        if (message == null) return "";
        String clean = message.replaceAll("[\"'{}\\\\\r\n\t§]", "").trim();
        return clean.length() > 200 ? clean.substring(0, 200) : clean;
    }

    /** Bukkit command dispatch APIs expect a trimmed command without the leading slash. */
    private static String normalizeCommand(String command) {
        if (command == null) return "";
        String normalized = command.trim();
        if (normalized.startsWith("/")) normalized = normalized.substring(1).trim();
        return normalized;
    }

    private static int firstWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) return i;
        }
        return -1;
    }

    // ---- Gates simples y plegado de texto  ---------------------------

    /** Gates sin lookups caros: origen Discord y muteo. */
    private boolean passesSimpleGates(Player player, Rule rule, RuleContext context) {
        Rule.Advanced a = rule.advanced;
        if (a.requireDiscord() && context != RuleContext.DISCORD) return false;
        if (a.ignoreDiscord() && context == RuleContext.DISCORD) return false;
        if (a.ignoreMuted() && isMuted(player)) return false;
        return true;
    }

    private boolean isMuted(Player player) {
        var chatalyst = dev.chatalyst.ChatalystPlugin.get();
        var mutes = chatalyst != null ? chatalyst.getMuteManager() : null;
        return mutes != null && mutes.isMuted(player.getUniqueId());
    }

    /**
     * Replaces matches found in a transformed view while copying untouched slices from the
     * original message. Raw replacements keep Java's $1/${name}/backslash semantics, but group
     * contents also come from the original text rather than from its accent-folded view.
     */
    private String replaceProjected(Rule rule, RuleTextView view, String replacement,
                                    boolean raw, String prolong) {
        try {
            Matcher m = rule.pattern.matcher(
                    dev.chatalyst.util.SafeRegex.budgeted(view.searchable(),
                            dev.chatalyst.util.SafeRegex.DEFAULT_BUDGET_NANOS));
            StringBuilder out = new StringBuilder(view.original().length());
            int lastOriginal = 0;
            while (m.find()) {
                int start = Math.max(lastOriginal, view.originalBoundary(m.start()));
                int end = Math.max(start, view.originalBoundary(m.end()));
                out.append(view.original(), lastOriginal, start);
                if (!prolong.isEmpty()) {
                    int count = view.original().codePointCount(start, end);
                    out.append(prolong.repeat(count));
                } else if (raw) {
                    out.append(expandRawReplacement(replacement, m, view));
                } else {
                    out.append(replacement);
                }
                lastOriginal = end;
            }
            out.append(view.original(), lastOriginal, view.original().length());
            return out.toString();
        } catch (dev.chatalyst.util.SafeRegex.BudgetExceededException slow) {
            warnSlowRule(rule);
            return null;
        } catch (IllegalArgumentException malformedReplacement) {
            if (invalidReplacementWarned.add(rule.name)) {
                plugin.getLogger().warning("Rule '" + rule.name + "' has an invalid Replace_Raw"
                        + " expression and its replacement was skipped: "
                        + malformedReplacement.getMessage());
            }
            return null;
        }
    }

    private final java.util.Set<String> invalidReplacementWarned =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static String expandRawReplacement(String replacement, Matcher matcher,
                                               RuleTextView view) {
        if (replacement == null) return "";
        StringBuilder out = new StringBuilder(replacement.length());
        for (int i = 0; i < replacement.length();) {
            char current = replacement.charAt(i++);
            if (current == '\\') {
                if (i == replacement.length()) {
                    throw new IllegalArgumentException("character to be escaped is missing");
                }
                out.append(replacement.charAt(i++));
                continue;
            }
            if (current != '$') {
                out.append(current);
                continue;
            }
            if (i == replacement.length()) {
                throw new IllegalArgumentException("group index is missing");
            }
            char next = replacement.charAt(i);
            if (next == '{') {
                int close = replacement.indexOf('}', i + 1);
                if (close < 0) throw new IllegalArgumentException("named group is missing '}'");
                String name = replacement.substring(i + 1, close);
                if (name.isEmpty()) throw new IllegalArgumentException("named group is empty");
                out.append(view.originalGroup(matcher, name));
                i = close + 1;
                continue;
            }
            if (next < '0' || next > '9') {
                throw new IllegalArgumentException("illegal group reference");
            }
            int group = next - '0';
            i++;
            if (group > matcher.groupCount()) {
                throw new IllegalArgumentException("No group " + group);
            }
            while (i < replacement.length() && replacement.charAt(i) >= '0'
                    && replacement.charAt(i) <= '9') {
                int candidate = group * 10 + replacement.charAt(i) - '0';
                if (candidate > matcher.groupCount()) break;
                group = candidate;
                i++;
            }
            out.append(view.originalGroup(matcher, group));
        }
        return out.toString();
    }

    private static boolean containsAny(String text, List<String> needles) {
        if (needles.isEmpty()) return false;
        String low = text.toLowerCase(Locale.ROOT);
        for (String n : needles) {
            if (!n.isEmpty() && low.contains(n)) return true;
        }
        return false;
    }
}
