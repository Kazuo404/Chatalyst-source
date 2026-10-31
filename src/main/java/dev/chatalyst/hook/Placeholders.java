package dev.chatalyst.hook;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Resuelve placeholders en dos capas:
 * <ol>
 *   <li>Internos de Chatalyst con llaves: {player_name}, {world}, {ping}, ...</li>
 *   <li>PlaceholderAPI con porcentajes (%luckperms_prefix%, %vault_prefix%, ...) si esta instalado.</li>
 * </ol>
 * Nunca resuelve {message}: ese lo inserta el renderer como Component para evitar inyeccion.
 */
public final class Placeholders {

    // Estos estaticos se instalan en el hilo principal (enable/reload) pero apply() corre
    // tambien en el hilo async del chat: volatile para publicacion segura entre hilos.
    private static volatile Boolean papiPresent = null;
    // Proveedor de apodos (lo instala NickManager en onEnable). Devuelve el nick o null.
    private static volatile Function<UUID, String> nickProvider = id -> null;
    // Variables personalizadas de config (texto reutilizable): {server_name}, etc.
    private static volatile Map<String, String> customVariables = new HashMap<>();
    // Evaluador de scripts (lo instala ScriptManager). Recibe (script, player) -> resultado.
    private static volatile java.util.function.BiFunction<String, Player, String> scriptEvaluator = (s, p) -> "";

    public static void setCustomVariables(Map<String, String> vars) {
        customVariables = vars != null ? new HashMap<>(vars) : new HashMap<>();
    }

    public static void setScriptEvaluator(java.util.function.BiFunction<String, Player, String> ev) {
        scriptEvaluator = ev != null ? ev : (s, p) -> "";
    }

    private Placeholders() {
    }

    public static void setNickProvider(Function<UUID, String> provider) {
        nickProvider = provider != null ? provider : id -> null;
    }

    // Plantilla del tag de party ({party_tag}): se muestra solo si el jugador esta en un
    // party (Parties). Configurable en Chat.Party_Tag; puede usar {party} dentro.
    private static volatile String partyTagTemplate = "";

    public static void setPartyTagTemplate(String template) {
        partyTagTemplate = template != null ? template : "";
    }

    // Modo anonimo (/anonymous): con true, el rango real, los tags, el party y el nombre
    // real se sustituyen en TODOS los formatos (chat, tab, nametag, hovers y clicks) — el
    // rango por el del disfraz (ver anonymousLook), el resto por vacio.
    private static volatile java.util.function.Predicate<UUID> anonymousCheck = id -> false;

    public static void setAnonymousCheck(java.util.function.Predicate<UUID> check) {
        anonymousCheck = check != null ? check : id -> false;
    }

    /** Rango con el que se MUESTRA alguien: tag, sufijo y nombre de grupo. */
    public record RankLook(String prefix, String suffix, String group) {
        public static final RankLook NONE = new RankLook("", "", "");
    }

    // Rango APARENTE de un anonimo. Lo resuelve AnonymousManager desde Anonymous.Tag /
    // Anonymous.Disguise_Group: el disfraz lleva el tag de un jugador corriente, porque un
    // nombre "pelado" (sin nada delante) delata al anonimo tanto como su rango real.
    private static volatile java.util.function.Supplier<RankLook> anonymousLook =
            () -> RankLook.NONE;

    public static void setAnonymousLook(java.util.function.Supplier<RankLook> look) {
        anonymousLook = look != null ? look : () -> RankLook.NONE;
    }

    /** Disfraz actual (nunca null), para el resto de integraciones (TAB, PAPI...). */
    public static RankLook anonymousLook() {
        RankLook look = anonymousLook.get();
        return look != null ? look : RankLook.NONE;
    }

    /**
     * True si el jugador esta en modo anonimo. Lo consulta tambien el renderer del chat:
     * el color/degradado personal de /chatcolor es un identificador tan reconocible como
     * el nombre — con el puesto, sus mensajes se distinguen a simple vista.
     */
    public static boolean isAnonymous(UUID id) {
        return anonymousCheck.test(id);
    }

    // Proveedor del tag AFK ({afk_tag}): devuelve el sufijo si el jugador esta AFK, o "".
    private static volatile Function<UUID, String> afkTagProvider = id -> "";

    public static void setAfkTagProvider(Function<UUID, String> provider) {
        afkTagProvider = provider != null ? provider : id -> "";
    }

    // Proveedores del titulo de /tag ({tag_prefix}/{tag_suffix}); TagManager decide el lado
    // LEFT/RIGHT y conserva como fallback el prefijo/sufijo personal historico.
    private static volatile Function<UUID, String> tagPrefixProvider = id -> "";
    private static volatile Function<UUID, String> tagSuffixProvider = id -> "";

    public static void setTagProviders(Function<UUID, String> prefix, Function<UUID, String> suffix) {
        tagPrefixProvider = prefix != null ? prefix : id -> "";
        tagSuffixProvider = suffix != null ? suffix : id -> "";
    }

    // Proveedores del titulo flotante y los iconos izq/der del nametag fancy
    // ({nametag_title}, {nametag_icon_left}, {nametag_icon_right}).
    private static volatile Function<UUID, String> nametagTitleProvider = id -> "";
    private static volatile Function<UUID, String> nametagIconLeftProvider = id -> "";
    private static volatile Function<UUID, String> nametagIconRightProvider = id -> "";

    public static void setNametagProviders(Function<UUID, String> title,
                                           Function<UUID, String> iconLeft,
                                           Function<UUID, String> iconRight) {
        nametagTitleProvider = title != null ? title : id -> "";
        nametagIconLeftProvider = iconLeft != null ? iconLeft : id -> "";
        nametagIconRightProvider = iconRight != null ? iconRight : id -> "";
    }

    /** Pareja y tag ya resueltos: los dos salen de la MISMA pasada. */
    public record MarriageView(String partner, String tag) {
        public static final MarriageView NONE = new MarriageView("", "");
    }

    // Matrimonio ({married_to} = nombre de la pareja, {married_tag} = tag decorado). Ambos
    // vacios con la feature apagada, con el jugador soltero o con la pareja en modo anonimo.
    // Un solo proveedor para los dos: resolver el apodo de la pareja cuesta un strip y un
    // escape de MiniMessage, y base() corre por cada mensaje del chat.
    private static volatile Function<UUID, MarriageView> marriageProvider =
            id -> MarriageView.NONE;

    public static void setMarriageProvider(Function<UUID, MarriageView> provider) {
        marriageProvider = provider != null ? provider : id -> MarriageView.NONE;
    }

    private static boolean hasPapi() {
        if (papiPresent == null) {
            papiPresent = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        }
        return papiPresent;
    }

    public static void reset() {
        papiPresent = null;
    }

    /**
     * Resuelve UN placeholder de PlaceholderAPI y devuelve el valor crudo (colores incluidos),
     * o "" si PAPI no esta instalado o nadie reconoce el placeholder. Lo usa la regla de
     * titulo PLACEHOLDER para leer un numero.
     */
    public static String papiRaw(Player p, String placeholder) {
        if (p == null || placeholder == null || placeholder.isBlank() || !hasPapi()) return "";
        String value = PapiBridge.raw(p, placeholder);
        return value.equals(placeholder) ? "" : value;
    }

    /** Comando configurado de la tienda de rangos, con la barra ya puesta. */
    private static String ranksCommand() {
        dev.chatalyst.ChatalystPlugin plugin = dev.chatalyst.ChatalystPlugin.get();
        String configured = plugin == null ? null
                : plugin.getConfig().getString("Tebex.Shop.Command", "ranks");
        String label = configured == null || configured.isBlank() ? "ranks" : configured.trim();
        return "/" + label.toLowerCase(java.util.Locale.ROOT);
    }

    /** Placeholders internos base de un jugador (sin incluir {message}). */
    public static Map<String, String> base(Player p) {
        Map<String, String> map = new HashMap<>();
        map.put("player", p.getName());
        map.put("player_name", p.getName());
        map.put("player_uuid", p.getUniqueId().toString());
        map.put("health", health(p.getHealth()));
        // {player_nick}: apodo con formato si lo tiene, o el nombre normal.
        String nick = nickProvider.apply(p.getUniqueId());
        // Cerrado: el apodo se guarda con su color/gradiente abierto y, insertado en un formato,
        // teñia (y estiraba el gradiente sobre) todo lo que viniera detras.
        String shownNick = (nick != null && !nick.isEmpty())
                ? dev.chatalyst.util.MM.closed(nick) : p.getName();
        map.put("player_nick", shownNick);
        // {nick_color}: color primario del apodo como tag MiniMessage ("" si no tiene).
        map.put("nick_color", nickColorTag(p.getUniqueId()));
        // Adventure displayName() serializado a texto plano: sin codigos de color ni el
        // "formatting bleed" (negrita/cursiva colandose) que causaba getDisplayName() legacy.
        map.put("player_display", PlainTextComponentSerializer.plainText().serialize(p.displayName()));
        // {ranks_command}: el comando de la tienda de rangos TAL COMO SE LLAMA AHORA. Escribir
        // "/ranks" a mano en el MOTD o en un formato manda a los jugadores a un comando que
        // deja de existir en cuanto el dueño lo renombra en Tebex.Shop.Command.
        map.put("ranks_command", ranksCommand());
        // Rank/prefix/suffix: LuckPerms if present, otherwise Vault (GroupManager/PEX/etc.).
        // El prefix/suffix del rango lo escribe el STAFF: sus :tokens: de ItemsAdder (ej.
        // el icono del rango) se convierten sin chequeo de permisos.
        if (LuckPermsHook.isAvailable()) {
            // Los cuatro valores salen de UNA consulta: por separado eran cuatro lecturas de
            // LuckPerms por mensaje, y el color repetia la del prefijo y su conversion.
            String[] rank = LuckPermsHook.rankData(p.getUniqueId());
            map.put("player_prefix", ItemsAdderHook.replaceEmojisUnchecked(rank[0]));
            map.put("player_suffix", ItemsAdderHook.replaceEmojisUnchecked(rank[1]));
            map.put("player_group", rank[2]);
            map.put("rank_color", rank[3]);
        } else if (VaultHook.isAvailable()) {
            String prefix = ItemsAdderHook.replaceEmojisUnchecked(VaultHook.prefix(p));
            map.put("player_prefix", prefix);
            map.put("player_suffix", ItemsAdderHook.replaceEmojisUnchecked(VaultHook.suffix(p)));
            map.put("player_group", VaultHook.group(p));
            map.put("rank_color", firstColorTag(prefix));
        } else {
            map.put("player_prefix", "");
            map.put("player_suffix", "");
            map.put("player_group", "");
            map.put("rank_color", "");
        }
        // {rank_nick}: el nick visible. Si el jugador eligio color para su nick (/nickcolor),
        // ese color manda; si el nick NO tiene color propio, hereda el del rango como fallback.
        map.put("rank_nick", rankNick(shownNick, map.get("rank_color")));
        map.put("world", p.getWorld().getName());
        int ping = p.getPing();
        map.put("ping", String.valueOf(ping));
        // {ping_ms}: numero coloreado por calidad ("<#3DFFA8>23ms"); el default de los formatos.
        map.put("ping_ms", pingMs(ping));
        // {ping_bars}: barras de señal coloreadas (rayitas), por si se prefieren al numero.
        map.put("ping_bars", pingBars(ping));
        // {ping_quality}: identificador de texto (Excellent/Good/Fair/Slow/Laggy).
        map.put("ping_quality", pingQuality(ping));
        // Party (Parties): {party} = nombre o "", {party_tag} = plantilla decorada o "".
        // Se lee de la cache del tracker (main thread), segura desde el chat async.
        String party = dev.chatalyst.channel.RegionPartyTracker.partyName(p.getUniqueId());
        map.put("party", party);
        map.put("party_tag", party.isEmpty() ? ""
                : partyTagTemplate.replace("{party}", party));
        // AFK: {afk_tag} = sufijo si esta AFK (o ""), {afk} = yes/no.
        String afkTag = afkTagProvider.apply(p.getUniqueId());
        map.put("afk_tag", afkTag);
        map.put("afk", afkTag.isEmpty() ? "no" : "yes");
        // /tag: titulo de catalogo equipado (LEFT/RIGHT) o tag historico como fallback.
        map.put("tag_prefix", tagPrefixProvider.apply(p.getUniqueId()));
        map.put("tag_suffix", tagSuffixProvider.apply(p.getUniqueId()));
        // Nametag fancy: titulo flotante y los dos iconos, con sus :tokens: de ItemsAdder ya
        // convertidos aqui para que el cartel los pinte sin re-escanear (misma via que el
        // prefix del rango). Vacios si el jugador no tiene puesto ninguno.
        map.put("nametag_title",
                ItemsAdderHook.replaceEmojisUnchecked(nametagTitleProvider.apply(p.getUniqueId())));
        map.put("nametag_icon_left",
                ItemsAdderHook.replaceEmojisUnchecked(nametagIconLeftProvider.apply(p.getUniqueId())));
        map.put("nametag_icon_right",
                ItemsAdderHook.replaceEmojisUnchecked(nametagIconRightProvider.apply(p.getUniqueId())));
        // Matrimonio: {married_to} = pareja (ya en texto plano y escapado), {married_tag} =
        // la linea decorada de Marriage.Tag, o "" si esta soltero.
        MarriageView marriage = marriageProvider.apply(p.getUniqueId());
        map.put("married_to", marriage.partner());
        // {rank_color} DENTRO de Marriage.Tag: la resolucion general es de una sola pasada
        // (un valor insertado no se re-escanea, para que nadie inyecte placeholders desde su
        // nick), asi que la linea del matrimonio no podria teñirse como el resto del hover.
        // Se sustituye aqui, acotado a ESTE token y a un valor que escribe el dueño en la
        // config — nunca texto de un jugador.
        map.put("married_tag",
                marriage.tag().replace("{rank_color}", map.getOrDefault("rank_color", "")));
        // {msg_command}: comando que rellena el click sobre un nombre. Con los MP apagados
        // (o Chat.Name_Click_Prefer: mail, el default) sugiere el correo; solo sugiere /msg
        // si los MP estan activos y el dueño lo prefiere.
        map.put("msg_command", suggestedMessageCommand(p.getName()));
        // Modo anonimo: rango, tags, party y NOMBRE REAL fuera. {player_name} pasa a ser el
        // apodo aleatorio para que hovers y clicks (/msg, /realname del staff aparte) no
        // filtren la identidad; NickManager resuelve /msg <apodo> sin problema.
        if (anonymousCheck.test(p.getUniqueId())) {
            String plainNick = dev.chatalyst.util.MM.strip(shownNick);
            // El rango REAL se sustituye por el del disfraz (el tag de un miembro
            // corriente), no por un hueco: un nombre sin nada delante en una lista donde
            // todos llevan tag señala al anonimo igual de bien que su rango.
            // El disfraz ya viene con los :tokens: de ItemsAdder resueltos y cacheados.
            RankLook look = anonymousLook();
            String anonColor = firstColorTag(look.prefix());
            map.put("player", plainNick);
            map.put("player_name", plainNick);
            map.put("player_display", plainNick);
            map.put("player_prefix", look.prefix());
            map.put("player_suffix", look.suffix());
            map.put("player_group", look.group());
            map.put("rank_color", anonColor);
            map.put("rank_nick", rankNick(shownNick, anonColor));
            map.put("tag_prefix", "");
            map.put("tag_suffix", "");
            map.put("party", "");
            map.put("party_tag", "");
            // La pareja identifica al anonimo tanto como su propio nombre: quien conozca al
            // matrimonio sabe quien es el que falta.
            map.put("married_to", "");
            map.put("married_tag", "");
            // Un titulo "REY DE TODO" o un icono de guardian flotando sobre un anonimo lo
            // señala igual que su rango: fuera mientras dure el disfraz.
            map.put("nametag_title", "");
            map.put("nametag_icon_left", "");
            map.put("nametag_icon_right", "");
            map.put("msg_command", suggestedMessageCommand(plainNick));
        }
        // {rank_tag}: el tag del rango (ej. "[MVP++]") con el nombre del grupo como
        // respaldo para grupos sin prefijo. Lo usan los mensajes de entrada: {player_group}
        // suelto imprime el id interno del grupo ("mvpplusplus") en vez del tag.
        String tag = map.get("player_prefix");
        map.put("rank_tag", tag != null && !tag.isBlank() ? tag.trim() : map.get("player_group"));
        // {rank_anim}: el prefijo del rango ANIMADO (fotograma del momento, ver RankTagAnimator); el
        // prefijo fijo si su grupo no anima. Despues del bloque anonimo: con disfraz anima el del disfraz.
        map.put("rank_anim", dev.chatalyst.tablist.RankTagAnimator.frame(map.get("player_group"), map.get("player_prefix")));
        // {rank_chat}: la etiqueta ANIMADA para el chat (sprites que anima el cliente, ver ChatRankTags).
        map.put("rank_chat", dev.chatalyst.chat.ChatRankTags.render(map.get("player_group"), map.get("player_prefix")));
        // {rank_gradient}: barra de neon a juego con el color del rango (ver neonGradient).
        // Se calcula del rank_color YA definitivo (el del disfraz si esta anonimo).
        map.put("rank_gradient", neonGradient(map.get("rank_color")));
        return map;
    }

    /**
     * Degradado "neon" simetrico —borde oscuro -> glow vivo -> borde oscuro— derivado del
     * color PRIMARIO del rango. Detecta el color asignado al rango sea cual sea su forma
     * (hex {@code <#RRGGBB>}, color con nombre {@code <red>} o el primer stop de un
     * {@code <gradient:...>}) y genera un {@code <gradient:...>} a juego, para que cada rango
     * tenga su propia barra de neon sin configurar nada.
     *
     * <p>El tono se conserva y la saturacion se sube a neon; el centro va a brillo maximo y
     * los bordes a un tono muy oscuro del mismo color, que es justo lo que da el efecto de
     * tubo encendido. Un rango SIN color cae al morado de la casa; uno gris/blanco (sin tono)
     * usa un neon plateado, porque un degradado sobre un gris no diria nada.
     *
     * <p>Devuelve SIEMPRE un {@code <gradient:...>} (nunca un color suelto), asi el
     * {@code </gradient>} de cierre en los formatos siempre casa.
     */
    static String neonGradient(String rankColorTag) {
        final String houseFallback = "<gradient:#240046:#9D4EDD:#E0AAFF>";
        if (rankColorTag == null || rankColorTag.isBlank()) return houseFallback;
        // El caracter de muestra da texto que colorear: asi un tag suelto (sin contenido)
        // tambien entrega su color, y de un gradiente sale el color de su primera letra.
        net.kyori.adventure.text.format.TextColor primary =
                dev.chatalyst.util.MM.firstColor(rankColorTag + "█");
        if (primary == null) return houseFallback;
        float[] hsb = java.awt.Color.RGBtoHSB(
                primary.red(), primary.green(), primary.blue(), null);
        float hue = hsb[0];
        float sat = hsb[1];
        // Sin tono apreciable (grises/blancos): neon plateado en vez de un degradado turbio.
        if (sat < 0.12f) return "<gradient:#2B2B2B:#F5F5F5:#2B2B2B>";
        float neonSat = Math.min(1f, Math.max(sat, 0.85f));
        String glow = hex(java.awt.Color.HSBtoRGB(hue, neonSat, 1.0f));
        String edge = hex(java.awt.Color.HSBtoRGB(hue, neonSat, 0.16f));
        return "<gradient:#" + edge + ":#" + glow + ":#" + edge + ">";
    }

    /** RGB (int de java.awt) a hex de 6 digitos en mayusculas, sin canal alfa. */
    private static String hex(int rgb) {
        return String.format(java.util.Locale.ROOT, "%06X", rgb & 0xFFFFFF);
    }

    /** Comando de mensajeo sugerido al clicar el nombre de {@code name}. */
    public static String suggestedMessageCommand(String name) {
        var pl = dev.chatalyst.ChatalystPlugin.get();
        boolean pms = pl != null && dev.chatalyst.pm.PrivateMessages.systemEnabled(pl);
        String prefer = pl != null
                ? pl.getConfig().getString("Chat.Name_Click_Prefer", "mail") : "mail";
        if (pms && prefer.equalsIgnoreCase("msg")) {
            return "/msg " + name + " ";
        }
        return "/inbox send " + name + " ";
    }

    /** Aplica los {internos} y luego PlaceholderAPI (%...%) si esta disponible. */
    public static String apply(Player p, String input, Map<String, String> extra) {
        if (input == null || input.isEmpty()) return "";
        return applyPrepared(p, input, merged(p, extra));
    }

    /**
     * Base + extra fusionados UNA sola vez, para reutilizarlos en todas las partes de un
     * mismo render con {@link #applyPrepared}. base(p) hace ~25 entradas y varios lookups
     * (LuckPerms, party, AFK, ping...); reconstruirlo por cada parte/hover/click del formato
     * multiplicaba ese coste 15-20 veces POR MENSAJE en el hilo del chat.
     */
    public static Map<String, String> merged(Player p, Map<String, String> extra) {
        Map<String, String> vars = base(p);
        if (extra != null) vars.putAll(extra);
        return vars;
    }

    /** Como {@link #apply}, con las variables ya fusionadas por {@link #merged} (hot path). */
    public static String applyPrepared(Player p, String input, Map<String, String> vars) {
        if (input == null || input.isEmpty()) return "";
        if (!hasPapi()) return resolveTokens(input, vars, p);
        java.util.List<String> held = new java.util.ArrayList<>(2);
        String out = resolveTokens(input, vars, p, held);
        if (out.indexOf('%') >= 0) {
            // Modo anonimo: los placeholders de PAPI ligados a la IDENTIDAD del sujeto se
            // vacian ANTES de resolver. El formato por defecto usa %luckperms_prefix%, que
            // PAPI resuelve por su cuenta y se saltaba la anulacion interna del rango.
            if (anonymousCheck.test(p.getUniqueId())) {
                // El prefijo/sufijo de PAPI se sustituye por el del DISFRAZ (el formato de
                // chat por defecto pinta el rango con %luckperms_prefix%); el resto de
                // placeholders de identidad se vacian.
                RankLook look = anonymousLook();
                out = ANONYMOUS_PAPI_PREFIX.matcher(out).replaceAll(
                        java.util.regex.Matcher.quoteReplacement(look.prefix()));
                out = ANONYMOUS_PAPI_SUFFIX.matcher(out).replaceAll(
                        java.util.regex.Matcher.quoteReplacement(look.suffix()));
                out = ANONYMOUS_PAPI.matcher(out).replaceAll("");
            }
            if (out.indexOf('%') >= 0) {
                out = PapiBridge.set(p, out);
            }
        }
        return restoreHeld(out, held);
    }

    /** Vida exacta a un decimal, sin depender del idioma de la maquina: 19.5, 20. */
    static String health(double value) {
        long tenths = Math.round(value * 10);
        return tenths % 10 == 0 ? Long.toString(tenths / 10)
                : tenths / 10 + "." + Math.abs(tenths % 10);
    }

    /** Valor crudo de un placeholder de PlaceholderAPI, o "" si no hay PAPI. */
    public static String papiValue(Player p, String placeholder) {
        return hasPapi() ? PapiBridge.raw(p, placeholder) : "";
    }

    // Marcadores: Unicode reserva estos codepoints para uso interno, no son texto.
    private static final char HELD_START = '\uFDD0';
    private static final char HELD_END = '\uFDD1';

    /**
     * Lo que escribio el staff pasa por PlaceholderAPI; lo que escribio un jugador, no. Un valor
     * con '%' se aparta antes de la fase PAPI y vuelve despues, intacto: asi un mensaje o un
     * apodo con {@code %server_name%} se queda en texto. Los prefijos de rango los pone el staff
     * y pueden llevar placeholders a proposito.
     */
    private static final java.util.Set<String> PAPI_TRUSTED_VARS =
            java.util.Set.of("player_prefix", "player_suffix");

    private static String hold(String value, java.util.List<String> held) {
        held.add(value);
        return "" + HELD_START + (held.size() - 1) + HELD_END;
    }

    static String restoreHeld(String text, java.util.List<String> held) {
        if (held.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length() + 32);
        int i = 0;
        while (i < text.length()) {
            int start = text.indexOf(HELD_START, i);
            int end = start < 0 ? -1 : text.indexOf(HELD_END, start);
            if (end < 0) break;
            out.append(text, i, start);
            int index = heldIndex(text, start + 1, end);
            if (index >= 0 && index < held.size()) out.append(held.get(index));
            i = end + 1;
        }
        return out.append(text, i, text.length()).toString();
    }

    private static int heldIndex(String text, int from, int to) {
        if (to <= from || to - from > 4) return -1;
        int value = 0;
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') return -1;
            value = value * 10 + c - '0';
        }
        return value;
    }

    // %luckperms_*%, %vault_prefix/suffix/rank/group%, %player_name/displayname...%,
    // %essentials_nickname%, %cmi_user_*%: todo lo que describe la identidad o el rango del
    // emisor. El resto de placeholders (%server_tps%, economia, etc.) sigue funcionando.
    // Los que pintan el TAG del rango: se cambian por el del disfraz, no se borran (se
    // resuelven antes que ANONYMOUS_PAPI, que si no se los llevaria por delante).
    private static final java.util.regex.Pattern ANONYMOUS_PAPI_PREFIX =
            java.util.regex.Pattern.compile(
                    "%(?:luckperms_prefix|vault_(?:prefix|groupprefix))%",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern ANONYMOUS_PAPI_SUFFIX =
            java.util.regex.Pattern.compile(
                    "%(?:luckperms_suffix|vault_(?:suffix|groupsuffix))%",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.regex.Pattern ANONYMOUS_PAPI = java.util.regex.Pattern.compile(
            "%(?:luckperms|vault_(?:prefix|suffix|rank|group|groupprefix|groupsuffix)"
                    + "|player_(?:name|displayname|list_name|custom_name)"
                    + "|essentials_nickname|cmi_user)[^%\\s]*%",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    public static String apply(Player p, String input) {
        return apply(p, input, null);
    }

    static String rankNick(String shownNick, String rankColor) {
        if (shownNick == null) return "";
        if (rankColor == null || rankColor.isBlank()) return shownNick;
        // Si el jugador YA eligio un color para su nick (/nickcolor, o un <color> escrito en
        // /nick), esa eleccion MANDA y se respeta tal cual. El color del rango solo se aplica
        // como fallback a un nick SIN color propio (para que un simple renombrado herede el
        // color del rango). Antes el rango pisaba SIEMPRE el color del nick: en tab y en la
        // preview se veia el color elegido (usan el nick crudo), pero el chat usa {rank_nick}
        // y ahi el color elegido desaparecia — justo el sintoma reportado.
        if (dev.chatalyst.util.MM.firstColor(shownNick) != null) return shownNick;
        String normalized = rankColor.toLowerCase(java.util.Locale.ROOT);
        String close = normalized.startsWith("<gradient:") ? "</gradient>"
                : normalized.startsWith("<rainbow") ? "</rainbow>" : "";
        return rankColor + dev.chatalyst.util.MM.stripColors(shownNick) + close;
    }

    /**
     * Sustituye los {tokens} internos y las variables personalizadas en UNA sola pasada
     * izquierda-a-derecha, emitiendo cada valor VERBATIM (nunca se re-escanea).
     *
     * <p>El bucle anterior hacia {@code out.replace("{key}", value)} por cada entrada de un
     * HashMap: si un valor controlado por el jugador (p. ej. un nick puesto a
     * {@code "{player_group}"}) contenia otro token, una iteracion posterior lo resolvia —
     * inyeccion de placeholders, y con resultado dependiente del orden de iteracion del mapa.
     * En una sola pasada, el valor insertado se copia tal cual y no puede disparar una segunda
     * resolucion. Un {@code {token}} desconocido se deja literal.
     */
    static String resolveTokens(String input, Map<String, String> vars, Player player) {
        return resolveTokens(input, vars, player, null);
    }

    static String resolveTokens(String input, Map<String, String> vars, Player player,
                                java.util.List<String> held) {
        if (input.indexOf('{') < 0) return input;
        StringBuilder out = new StringBuilder(input.length() + 16);
        int i = 0;
        int n = input.length();
        while (i < n) {
            char c = input.charAt(i);
            if (c != '{') {
                out.append(c);
                i++;
                continue;
            }
            int close = input.indexOf('}', i + 1);
            if (close < 0) { // no cierra: el resto es literal
                out.append(input, i, n);
                break;
            }
            String name = input.substring(i + 1, close);
            String value = resolveToken(name, vars, player);
            if (value != null) {
                // verbatim: no se vuelve a escanear
                boolean userValue = held != null && value.indexOf('%') >= 0
                        && vars.containsKey(name) && !PAPI_TRUSTED_VARS.contains(name);
                out.append(userValue ? hold(value, held) : value);
                i = close + 1;
            } else {
                out.append('{'); // token desconocido: '{' literal y seguimos escaneando
                i++;
            }
        }
        return out.toString();
    }

    /** Valor de un token (base/extra primero, luego variable personalizada), o null si no existe. */
    private static String resolveToken(String name, Map<String, String> vars, Player player) {
        String v = vars.get(name);
        if (v != null) return v;
        if (customVariables.containsKey(name)) {
            String value = customVariables.get(name);
            if (value != null && value.startsWith("js:")) {
                return scriptEvaluator.apply(value.substring(3).trim(), player);
            }
            return value == null ? "" : value;
        }
        return null;
    }

    /**
     * Color primario del apodo del jugador como codigo legacy ("&c" / "&#RRGGBB"), o "" si
     * no tiene apodo o el apodo no lleva color. Lo consumen TAB (%chatalyst_nickcolor% al
     * final de tagprefix pinta el nombre sobre la cabeza) y PlaceholderAPI.
     */
    public static String nickColorLegacy(UUID id) {
        String nick = nickProvider.apply(id);
        if (nick == null || nick.isEmpty()) return "";
        return dev.chatalyst.util.Legacy.code(dev.chatalyst.util.MM.firstColor(nick));
    }

    /** Igual que {@link #nickColorLegacy} pero como tag MiniMessage ("<#FF4D6D>" / "<#RRGGBB>"). */
    private static String nickColorTag(UUID id) {
        String nick = nickProvider.apply(id);
        if (nick == null || nick.isEmpty()) return "";
        net.kyori.adventure.text.format.TextColor color = dev.chatalyst.util.MM.firstColor(nick);
        if (color == null) return "";
        net.kyori.adventure.text.format.NamedTextColor named =
                net.kyori.adventure.text.format.NamedTextColor.namedColor(color.value());
        return "<" + (named != null ? named.toString() : color.asHexString()) + ">";
    }

    // First color/gradient tag in a MiniMessage string (used to derive {rank_color} from a
    // Vault prefix). Mirrors LuckPermsBridge.rankColor for the non-LuckPerms path.
    private static final java.util.regex.Pattern COLOR_TAG =
            java.util.regex.Pattern.compile("<(#[0-9a-fA-F]{6}|gradient:[^>]+|[a-zA-Z_]+)>");

    private static String firstColorTag(String mini) {
        if (mini == null || mini.isEmpty()) return "";
        java.util.regex.Matcher m = COLOR_TAG.matcher(mini);
        while (m.find()) {
            String t = m.group(1);
            if (t.startsWith("#") || t.startsWith("gradient:") || isNamedColor(t)) return m.group();
        }
        return "";
    }

    // Color por calidad de conexion, compartido por {ping_ms}, {ping_bars} y {ping_quality}
    // para que los tres cuenten la misma historia.
    private static String pingColor(int ping) {
        if (ping < 0) return "<#7B68A0>";
        if (ping < 100) return "<#3DFFA8>";
        if (ping < 150) return "<#FFD166>";
        if (ping < 250) return "<#FF9F1C>";
        if (ping < 400) return "<#FF4D6D>";
        return "<#8B0000>";
    }

    /** Latencia como numero coloreado por calidad ("<#3DFFA8>23ms"), o "—" si es desconocida. */
    public static String pingMs(int ping) {
        if (ping < 0) return "<#7B68A0>—";
        return pingColor(ping) + ping + "ms";
    }

    // Señal de conexion como 5 barras (▰ lleno, ▱ vacio) coloreadas por calidad del ping.
    public static String pingBars(int ping) {
        int filled;
        if (ping < 0) filled = 0;            // desconocido
        else if (ping < 50) filled = 5;
        else if (ping < 100) filled = 4;
        else if (ping < 150) filled = 3;
        else if (ping < 250) filled = 2;
        else filled = 1;
        StringBuilder sb = new StringBuilder(pingColor(ping));
        for (int i = 0; i < filled; i++) sb.append("▰");
        if (filled < 5) sb.append("<#7B68A0>");
        for (int i = filled; i < 5; i++) sb.append("▱");
        return sb.toString();
    }

    // Etiqueta de texto para quien prefiera una palabra en vez del numero.
    public static String pingQuality(int ping) {
        if (ping < 0) return "<#7B68A0>?";
        if (ping < 50) return "<#3DFFA8>Excellent";
        if (ping < 100) return "<#3DFFA8>Good";
        if (ping < 150) return "<#FFD166>Fair";
        if (ping < 250) return "<#FF9F1C>Slow";
        return "<#FF4D6D>Laggy";
    }

    private static boolean isNamedColor(String tag) {
        return switch (tag.toLowerCase()) {
            case "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
                 "gold", "gray", "grey", "dark_gray", "dark_grey", "blue", "green", "aqua",
                 "red", "light_purple", "yellow", "white" -> true;
            default -> false;
        };
    }
}
