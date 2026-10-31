package dev.chatalyst.chat;

import dev.chatalyst.format.ChatFormat;
import dev.chatalyst.format.FormatCondition;
import dev.chatalyst.format.FormatPart;
import dev.chatalyst.hook.Placeholders;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import org.bukkit.command.CommandSender;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Convierte un {@link ChatFormat} en un Component listo para enviar.
 * Se llama una vez por espectador (viewer), asi los permisos de visibilidad y los
 * hover/click se resuelven por-quien-lo-ve.
 */
public final class ChatRenderer {

    // Tag interno improbable de colisionar donde inyectamos el mensaje del jugador.
    private static final String MSG_TAG = "chatalyst_message";
    private static final String BODY_TAG = "chatalyst_body";
    private static final String COLOR_PERMISSION = "chatalyst.chat.color";

    // Estos estaticos se escriben en el hilo principal (enable/reload) y se leen desde el
    // hilo async del chat: volatile para publicacion segura (sin el, un reload podria dejar
    // a los hilos de chat viendo un mapa/patron a medio construir).
    // Proveedor del color de chat elegido (lo instala ChatColorManager). Devuelve el tag o "".
    private static volatile Function<UUID, String> chatColorProvider = id -> "";

    // Resaltado de menciones (se aplica por espectador sobre el mensaje ya renderizado).
    private static volatile boolean mentionHighlight = false;
    private static volatile net.kyori.adventure.text.format.TextColor highlightColor =
            net.kyori.adventure.text.format.NamedTextColor.YELLOW;
    private static volatile boolean highlightBold = true;
    private static volatile boolean mentionRequireAt = false;

    // Auto-linkify: turns URLs in a player's message into clickable links (needs permission).
    private static final String LINK_PERMISSION = "chatalyst.chat.links";
    private static volatile boolean clickableLinks = false;
    private static final java.util.regex.Pattern URL = java.util.regex.Pattern.compile(
            "(?i)\\b((?:https?://|www\\.)[-a-z0-9@:%._+~#=]{1,256}\\.[a-z0-9()]{1,6}\\b[-a-z0-9()@:%_+.~#?&/=]*)");

    public static void setClickableLinks(boolean enabled) {
        clickableLinks = enabled;
    }

    // [item] en el chat: muestra el item en mano con su tooltip (lee la cache de HeldItemTracker).
    private static final String ITEM_PERMISSION = "chatalyst.chat.item";
    private static volatile boolean itemPlaceholder = false;
    private static volatile boolean itemCustomIds = true;
    private static final java.util.regex.Pattern ITEM_TOKEN =
            java.util.regex.Pattern.compile("(?i)\\[(item|i)\\]");

    public static void setItemPlaceholder(boolean enabled) {
        itemPlaceholder = enabled;
    }

    public static void setItemCustomIds(boolean enabled) {
        itemCustomIds = enabled;
    }

    // [inv] / [ec] en el chat: el jugador comparte su inventario o ender chest con un boton
    // "Abrir" que cualquiera puede clicar. Solo se registra el CONSENTIMIENTO (async-safe);
    // los items se leen en vivo al abrir, en la region del dueno.
    private static final String SHARE_PERMISSION = "chatalyst.chat.shareinv";
    private static volatile boolean inventoryShare = false;
    private static volatile InventoryShareManager shareManager;
    private static final java.util.regex.Pattern INV_TOKEN =
            java.util.regex.Pattern.compile("(?i)\\[(inv|inventory|inventario)\\]");
    private static final java.util.regex.Pattern EC_TOKEN =
            java.util.regex.Pattern.compile("(?i)\\[(ec|ender|enderchest)\\]");

    public static void setInventoryShare(boolean enabled, InventoryShareManager manager) {
        inventoryShare = enabled;
        shareManager = manager;
    }

    private static final String PIXEL_PERMISSION = "chatalyst.chat.pixel";
    private static volatile boolean pixelImages = false;
    // Todos los tokens en UN solo patron (una pasada por mensaje) + el mapa nombre->component.
    private static volatile java.util.regex.Pattern pixelPattern = null;
    private static volatile java.util.Map<String, Component> pixelComponents = java.util.Map.of();

    // Emojis :nombre: -> simbolo Unicode. Set por defecto (EmojiRegistry) + custom de config.
    private static final String EMOJI_PERMISSION = "chatalyst.chat.emoji";
    private static volatile boolean emojis = false;
    private static volatile java.util.regex.Pattern emojiPattern = null;
    private static volatile java.util.Map<String, String> emojiMap = java.util.Map.of();

    public static void setEmojis(boolean enabled, ConfigurationSection customSection) {
        emojis = enabled;
        if (!enabled) {
            emojiPattern = null;
            emojiMap = java.util.Map.of();
            return;
        }
        java.util.Map<String, String> map = new java.util.HashMap<>(EmojiRegistry.defaults());
        if (customSection != null) {
            for (String key : customSection.getKeys(false)) {
                String v = customSection.getString(key);
                if (v != null && !v.isEmpty()) map.put(key.toLowerCase(java.util.Locale.ROOT), v);
            }
        }
        List<String> names = new ArrayList<>();
        for (String name : map.keySet()) names.add(java.util.regex.Pattern.quote(name));
        emojiPattern = java.util.regex.Pattern.compile("(?i):(" + String.join("|", names) + "):");
        emojiMap = java.util.Map.copyOf(map);
    }

    /**
     * Variante sobre String para texto escrito por staff (anuncios, formatos): mismo mapa
     * de emojis del chat (defaults + custom), sin chequeo de permisos por emoji.
     */
    public static String applyEmojis(String text) {
        if (emojiPattern == null || text == null || text.isEmpty()) return text;
        var matcher = emojiPattern.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String sym = emojiMap.get(matcher.group(1).toLowerCase(java.util.Locale.ROOT));
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
                    sym != null ? sym : matcher.group()));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    // Una pasada: reemplaza cualquier :nombre: por su simbolo, heredando el estilo del texto.
    private static Component replaceEmojis(Component comp) {
        if (emojiPattern == null) return comp;
        return comp.replaceText(cfg -> cfg.match(emojiPattern).replacement((res, b) -> {
            String name = res.group(1);
            String sym = name != null ? emojiMap.get(name.toLowerCase(java.util.Locale.ROOT)) : null;
            return sym != null ? b.content(sym) : b;
        }));
    }

    public static void setPixelImages(boolean enabled, ConfigurationSection section) {
        pixelImages = enabled;
        if (!enabled || section == null) {
            pixelPattern = null;
            pixelComponents = java.util.Map.of();
            return;
        }
        java.util.Map<String, Component> components = new java.util.HashMap<>();
        List<String> names = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection token = section.getConfigurationSection(key);
            if (token == null) continue;
            Material material = Material.matchMaterial(token.getString("Material", ""));
            if (material == null || !material.isItem()) continue;
            String display = token.getString("Display", "<#3DFFA8>[" + key + "]");
            String hover = token.getString("Hover", display);
            ItemStack item = new ItemStack(material);
            item.editMeta(meta -> meta.displayName(MM.full(hover)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
            Component component = MM.full(display)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
                    .hoverEvent(item);
            components.put(key.toLowerCase(java.util.Locale.ROOT), component);
            names.add(java.util.regex.Pattern.quote(key));
        }
        if (names.isEmpty()) {
            pixelPattern = null;
            pixelComponents = java.util.Map.of();
            return;
        }
        // (?i) \[(name1|name2|...)\]  o  :(name1|name2|...):
        String alt = String.join("|", names);
        pixelPattern = java.util.regex.Pattern.compile(
                "(?i)(?:\\[(" + alt + ")\\]|:(" + alt + "):)");
        pixelComponents = java.util.Map.copyOf(components);
    }

    // Etiquetas en el idioma del ESPECTADOR (hover, botones): en el formato se escriben como
    // {$clave} y se resuelven contra lang "chat.labels.<clave>" por quien lo ve. El token
    // sobrevive intacto al prepare (no es placeholder ni tag MiniMessage) y se traduce recien
    // en assemble()/render(), que ya corren por espectador. Si la clave no existe, se deja el
    // token tal cual (asi un jugador que escriba {$x} no rompe nada).
    private static final java.util.regex.Pattern LABEL_TOKEN =
            java.util.regex.Pattern.compile("\\{\\$([a-z0-9_-]+)\\}");

    // {married_tag} cuenta como etiqueta: su VALOR (Marriage.Tag) lleva {$married} dentro,
    // asi que un hover que solo tuviera esa linea se quedaria sin traducir y enseñaria el
    // token crudo. Es el unico placeholder que puede expandirse a una etiqueta.
    private static final String LABEL_BEARING = "{married_tag}";

    static boolean hasLabels(FormatPart part) {
        if (part.message != null
                && (part.message.contains("{$") || part.message.contains(LABEL_BEARING))) {
            return true;
        }
        for (String line : part.hover) {
            if (line != null && (line.contains("{$") || line.contains(LABEL_BEARING))) return true;
        }
        return false;
    }

    /** Traduce los tokens {$clave} de un componente ya armado al idioma del espectador. */
    static Component localize(Component component, CommandSender viewer) {
        return localize(component, key -> {
            String langKey = "chat.labels." + key;
            String label = Lang.raw(viewer, langKey);
            return label.equals(langKey) ? null : label; // clave inexistente: se deja el token
        });
    }

    /**
     * Patron de un solo token ([token] o :token:). No lo usa el render (que va con el patron
     * combinado de una pasada), pero SI la prueba que fija esa doble sintaxis.
     */
    static java.util.regex.Pattern tokenPattern(String token) {
        String quoted = java.util.regex.Pattern.quote(token);
        return java.util.regex.Pattern.compile("(?i)(?:\\[(" + quoted + ")\\]|:(" + quoted + "):)");
    }

    /** Nucleo testeable: {@code labels} devuelve el texto de la etiqueta o null para dejar el token. */
    static Component localize(Component component, Function<String, String> labels) {
        return component.replaceText(cfg -> cfg.match(LABEL_TOKEN).replacement((res, b) -> {
            String label = labels.apply(res.group(1));
            return b.content(label == null ? res.group() : label);
        }));
    }

    private ChatRenderer() {
    }

    /**
     * Si el color/estilo de chat guardado sigue permitido: hace falta {@code chatalyst.chat.color}, y
     * para un degradado ademas {@code chatalyst.gradient.*} o {@code chatalyst.gradient.custom}.
     */
    static boolean puedeUsarColorGuardado(org.bukkit.permissions.Permissible sender, String colorTag) {
        if (!sender.hasPermission(COLOR_PERMISSION)) return false;
        if (colorTag.contains("<gradient")) {
            return sender.hasPermission("chatalyst.gradient.*")
                    || sender.hasPermission(dev.chatalyst.gui.ChatGradientBuilderMenu.PERMISSION);
        }
        return true;
    }

    public static void setChatColorProvider(Function<UUID, String> provider) {
        chatColorProvider = provider != null ? provider : id -> "";
    }

    public static void setMentionHighlight(boolean enabled, String color, boolean bold, boolean requireAt) {
        mentionHighlight = enabled;
        highlightBold = bold;
        mentionRequireAt = requireAt;
        net.kyori.adventure.text.format.TextColor c =
                net.kyori.adventure.text.format.NamedTextColor.NAMES.value(color == null ? "" : color.toLowerCase());
        highlightColor = c != null ? c : net.kyori.adventure.text.format.NamedTextColor.YELLOW;
    }

    public static Component render(ChatFormat format, Player sender, Component playerMessage,
                                   Player viewer, Map<String, String> extra) {
        Component out = Component.empty();
        // Variables resueltas UNA sola vez para todas las partes (antes, cada parte, hover y
        // click reconstruia el mapa base entero con sus lookups a LuckPerms/party/AFK/ping).
        Map<String, String> vars = Placeholders.merged(sender, extra);
        for (FormatPart part : format.parts) {
            if (skipsWithoutMessageId(part, extra)) continue;
            if (!part.senderCondition.matches(sender, sender, extra)) continue;
            if (!part.receiverCondition.matches(viewer, sender, extra)) continue;
            Component c = renderPart(part, sender, playerMessage, vars);
            if (hasLabels(part)) {
                c = localize(c, viewer);
            }
            out = out.append(c);
        }
        return out;
    }

    public static Component render(ChatFormat format, Player sender, Component playerMessage, Player viewer) {
        return render(format, sender, playerMessage, viewer, null);
    }

    /**
     * Una parte ya renderizada junto al permiso que hace falta para verla. Se calcula una sola
     * vez por mensaje (el contenido depende del emisor, no de quien lo ve) y luego se ensambla
     * por espectador con {@link #assemble}. Evita re-resolver placeholders por cada jugador.
     * {@code messagePart} marca la parte que contiene el mensaje escrito (donde se resalta la
     * mencion, para no pintar tambien el nombre del emisor).
     */
    public record RenderedPart(Component component, FormatCondition receiverCondition,
            Map<String, String> context, boolean messagePart, boolean localizable) {
    }

    record HighlightRange(int start, int end) {
        boolean contains(int index) {
            return index >= start && index < end;
        }
    }

    /** Pre-renderiza todas las partes de un mensaje una sola vez (contenido del emisor). */
    public static List<RenderedPart> prepare(ChatFormat format, Player sender, Component playerMessage,
                                             Map<String, String> extra) {
        List<RenderedPart> out = new ArrayList<>(format.parts.size());
        Map<String, String> snapshot = new java.util.HashMap<>();
        if (extra != null) snapshot.putAll(extra);
        // El assemble se ejecuta en la region del espectador. Copiamos aqui los datos del
        // emisor para no conservar/tocar su Entity desde otra region.
        snapshot.put("sender", sender.getName());
        snapshot.put("sender_name", sender.getName());
        snapshot.put("sender_uuid", sender.getUniqueId().toString());
        Map<String, String> context = java.util.Collections.unmodifiableMap(snapshot);
        Map<String, String> vars = Placeholders.merged(sender, extra);
        // Una linea de chat ya enviada no se puede editar, pero cada mensaje captura el
        // cuadro/fase vigente del apodo. El mismo snapshot se reutiliza en todas sus partes.
        dev.chatalyst.ChatalystPlugin chatalyst = dev.chatalyst.ChatalystPlugin.get();
        if (chatalyst != null && chatalyst.getFancyNametags() != null) {
            chatalyst.getFancyNametags().applyChatSnapshot(sender, vars);
        }
        for (FormatPart part : format.parts) {
            if (skipsWithoutMessageId(part, extra)) continue;
            if (!part.senderCondition.matches(sender, sender, context)) continue;
            out.add(new RenderedPart(renderPart(part, sender, playerMessage, vars),
                    part.receiverCondition, context, part.isMessagePart(), hasLabels(part)));
        }
        return out;
    }

    /**
     * Una parte cuyo click depende de {message_uuid} (la X de borrar) se omite cuando Paper
     * no entregó una firma utilizable para esa línea, o en rutas de sistema como /staffchat:
     * el botón dispararía un comando incompleto que no puede borrar nada. El modo de la cuenta
     * por sí solo no determina esta disponibilidad.
     */
    private static boolean skipsWithoutMessageId(FormatPart part, Map<String, String> extra) {
        if (part.runCommand == null || !part.runCommand.contains("{message_uuid}")) return false;
        String id = extra == null ? null : extra.get("message_uuid");
        return id == null || id.isBlank();
    }

    /** Ensambla el mensaje para un espectador concreto, respetando los permisos de vista. */
    public static Component assemble(List<RenderedPart> parts, Player viewer) {
        Component out = Component.empty();
        for (RenderedPart rp : parts) {
            // Los placeholders del emisor ya estan en context; pasar null evita acceso
            // cross-region. Los scripts de receptor deben tratar sender como snapshot/context.
            if (!rp.receiverCondition().matches(viewer, null, rp.context())) continue;
            Component c = rp.component();
            // El resaltado de mencion solo va en la parte del mensaje escrito, nunca en el
            // prefix ni en el nombre del emisor.
            if (rp.messagePart() && mentionHighlight && viewer != null) {
                c = highlightName(c, dev.chatalyst.mention.MentionListener.bareName(viewer.getName()));
                // Tambien por el APODO: es el unico nombre que los demas ven. El sonido de la
                // mencion ya saltaba al escribirlo, pero el mensaje llegaba sin resaltar.
                String nick = dev.chatalyst.nick.NickManager.suggestedName(viewer);
                if (nick != null && !nick.isBlank() && !nick.equalsIgnoreCase(viewer.getName())) {
                    c = highlightName(c, nick);
                }
            }
            // Traduce {$clave} al idioma del espectador SOLO en las partes que las usan (hover
            // de rango/nombre): no recorre el cuerpo escrito por el jugador en cada espectador.
            if (rp.localizable()) {
                c = localize(c, viewer);
            }
            out = out.append(c);
        }
        // Bedrock no entiende los sprites de {rank_chat}: ahi vuelve la etiqueta fija.
        return ChatRankTags.forViewer(out, viewer);
    }

    static Component highlightName(Component comp, String name) {
        String plain = PlainTextComponentSerializer.plainText().serialize(comp);
        List<HighlightRange> ranges = mentionRanges(plain, name, mentionRequireAt);
        return ranges.isEmpty() ? comp : highlightRanges(comp, ranges, new int[]{0});
    }

    /**
     * Rangos donde aparece el nombre como palabra completa, equivalente a
     * {@code (?i)(?<!\w)@?nombre(?!\w)} (o con @ obligatoria). Escaneo manual: la version
     * regex compilaba un Pattern por ESPECTADOR y por MENSAJE (100 jugadores con el
     * resaltado activo = 100 compilaciones por linea de chat, en el hilo async del chat).
     * regionMatches en vez de toLowerCase del mensaje: bajar todo el string puede cambiar
     * su longitud con ciertos caracteres Unicode y desalinear los rangos del component.
     */
    static List<HighlightRange> mentionRanges(String plain, String name, boolean requireAt) {
        List<HighlightRange> ranges = new ArrayList<>();
        int nameLen = name.length();
        if (nameLen == 0) return ranges;
        int from = 0;
        while (from + nameLen <= plain.length()) {
            if (!plain.regionMatches(true, from, name, 0, nameLen)) {
                from++;
                continue;
            }
            int end = from + nameLen;
            boolean afterOk = end >= plain.length() || !isWordChar(plain.charAt(end));
            if (afterOk) {
                char before = from > 0 ? plain.charAt(from - 1) : 0;
                if (before == '@' && (from - 1 == 0 || !isWordChar(plain.charAt(from - 2)))) {
                    ranges.add(new HighlightRange(from - 1, end)); // la @ se resalta tambien
                    from = end;
                    continue;
                }
                if (!requireAt && (from == 0 || !isWordChar(before))) {
                    ranges.add(new HighlightRange(from, end));
                    from = end;
                    continue;
                }
            }
            from++;
        }
        return ranges;
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    private static Component highlightRanges(Component comp, List<HighlightRange> ranges, int[] cursor) {
        List<Component> children = new ArrayList<>();
        Component base;
        if (comp instanceof TextComponent text) {
            children.addAll(splitText(text.content(), text.style(), ranges, cursor));
            base = text.content("");
        } else {
            base = comp.children(List.of());
        }
        for (Component child : comp.children()) {
            children.add(highlightRanges(child, ranges, cursor));
        }
        return base.children(children);
    }

    private static List<Component> splitText(String content, Style style,
                                             List<HighlightRange> ranges, int[] cursor) {
        List<Component> pieces = new ArrayList<>();
        if (content.isEmpty()) {
            return pieces;
        }
        int absoluteStart = cursor[0];
        int segmentStart = 0;
        boolean highlighted = isHighlighted(absoluteStart, ranges);
        for (int i = 1; i <= content.length(); i++) {
            boolean atEnd = i == content.length();
            boolean nextHighlighted = !atEnd && isHighlighted(absoluteStart + i, ranges);
            if (atEnd || nextHighlighted != highlighted) {
                Component piece = Component.text(content.substring(segmentStart, i)).style(style);
                if (highlighted) {
                    piece = piece.color(highlightColor);
                    if (highlightBold) {
                        piece = piece.decorate(net.kyori.adventure.text.format.TextDecoration.BOLD);
                    }
                }
                pieces.add(piece);
                segmentStart = i;
                highlighted = nextHighlighted;
            }
        }
        cursor[0] += content.length();
        return pieces;
    }

    private static boolean isHighlighted(int index, List<HighlightRange> ranges) {
        for (HighlightRange range : ranges) {
            if (range.contains(index)) {
                return true;
            }
        }
        return false;
    }

    private static Component renderPart(FormatPart part, Player sender, Component playerMessage,
                                        Map<String, String> vars) {
        Component base;
        // Los :tokens: de ItemsAdder de la PLANTILLA se convierten sin chequeo de permisos
        // (la escribe el staff) — ANTES de resolver placeholders, para que un valor que
        // controla un jugador (su nick) nunca pase por la variante sin permisos. El prefix
        // del rango se convierte en Placeholders.base; el mensaje del jugador pasa por la
        // variante CON permisos en styledPlayerMessage.
        String template = dev.chatalyst.hook.ItemsAdderHook.replaceEmojisUnchecked(part.message);
        if (part.isMessagePart()) {
            // Resolvemos placeholders alrededor de {message} pero {message} lo insertamos
            // como Component ya construido para que un jugador no pueda inyectar tags.
            String tpl = Placeholders.applyPrepared(sender, template, vars)
                    .replace("{message}", "<" + MSG_TAG + ">");
            Component body = styledPlayerMessage(sender, playerMessage);
            // Lo que tecleo el jugador se marca para que el guardian de anonimos no lo
            // reescriba cuando este mensaje viaja como mensaje de sistema (chat traducido,
            // historial de canal). Con una insercion configurada en esta parte no se marca:
            // la marca es una insercion vacia y taparia la del dueño.
            if (part.insertion == null) {
                body = dev.chatalyst.anonymous.AnonymousNameGuard.authored(body);
            }
            base = MM.full(tpl, Placeholder.component(MSG_TAG, body));
        } else {
            base = MM.full(Placeholders.applyPrepared(sender, template, vars));
        }

        if (!part.hover.isEmpty()) {
            base = base.hoverEvent(HoverEvent.showText(buildHover(part.hover, sender, vars)));
        }
        ClickEvent click = buildClick(part, sender, vars);
        if (click != null) {
            base = base.clickEvent(click);
        }
        if (part.insertion != null) {
            base = base.insertion(Placeholders.applyPrepared(sender, part.insertion, vars));
        }
        return base;
    }

    // El mensaje del jugador: texto plano por defecto; con permiso, colores/decoraciones seguras.
    private static Component styledPlayerMessage(Player sender, Component playerMessage) {
        String raw = PlainTextComponentSerializer.plainText().serialize(playerMessage);
        // Emojis / font-images de ItemsAdder (:nombre:) segun permisos, antes de aplicar color.
        raw = dev.chatalyst.hook.ItemsAdderHook.replaceEmojis(sender, raw);
        Component body;
        if (sender.hasPermission(COLOR_PERMISSION)) {
            // Acepta codigos legacy (&6) ademas de MiniMessage.
            body = MM.safe(dev.chatalyst.util.Legacy.toMini(raw),
                    dev.chatalyst.util.PlayerText.canUseHex(sender),
                    dev.chatalyst.util.PlayerText.canUseGradients(sender));
        } else {
            body = Component.text(raw);
        }

        // Color/gradiente de chat elegido por el jugador: envuelve el mensaje. En modo
        // anonimo NO se aplica: un color propio identifica los mensajes de alguien tan
        // bien como su nombre, y el disfraz se caeria a la primera frase.
        String colorTag = Placeholders.isAnonymous(sender.getUniqueId())
                ? null : chatColorProvider.apply(sender.getUniqueId());
        // Solo mientras conserve el permiso: un rango que vence (la prueba de Nova de 3 dias, un
        // rango de la tienda de 30) se llevaba el color guardado para siempre (2026-10-16). Lo
        // elegido no se borra: si recupera el rango, vuelve.
        if (colorTag != null && !colorTag.isEmpty() && !puedeUsarColorGuardado(sender, colorTag)
                && !dev.chatalyst.gui.ChatColorPalette.ownsPreset(sender, colorTag)) {
            colorTag = null;
        }
        if (colorTag != null && !colorTag.isEmpty()) {
            body = MM.full(colorTag + "<" + BODY_TAG + ">", Placeholder.component(BODY_TAG, body));
        }
        // Auto-linkify URLs (only for players with permission, to avoid phishing spam).
        if (clickableLinks && sender.hasPermission(LINK_PERMISSION)) {
            body = linkify(body);
        }
        // [item]: muestra el item en mano (cacheado en el hilo principal; el chat es async).
        if (itemPlaceholder && sender.hasPermission(ITEM_PERMISSION)) {
            body = replaceItemToken(body, sender.getUniqueId());
        }
        if (inventoryShare && shareManager != null && sender.hasPermission(SHARE_PERMISSION)) {
            body = replaceShareTokens(body, sender);
        }
        if (pixelImages && sender.hasPermission(PIXEL_PERMISSION)) {
            body = replacePixelTokens(body);
        }
        if (emojis && sender.hasPermission(EMOJI_PERMISSION)) {
            body = replaceEmojis(body);
        }
        return body;
    }

    // Una sola pasada: el patron combinado matchea cualquier token y el replacement resuelve
    // el component por su nombre (grupo 1 = forma [token], grupo 2 = forma :token:).
    private static Component replacePixelTokens(Component comp) {
        if (pixelPattern == null) return comp;
        return comp.replaceText(cfg -> cfg.match(pixelPattern).replacement((res, b) -> {
            String name = res.group(1) != null ? res.group(1) : res.group(2);
            Component c = name != null ? pixelComponents.get(name.toLowerCase(java.util.Locale.ROOT)) : null;
            return c != null ? c : b.build();
        }));
    }


    // [inv]/[ec] -> etiqueta con boton "Abrir". El grant se registra al construir el tag,
    // asi que solo un mensaje realmente enviado con el token habilita la vista.
    private static Component replaceShareTokens(Component comp, Player sender) {
        comp = comp.replaceText(cfg -> cfg.match(INV_TOKEN).replacement((res, b) ->
                shareTag(sender, InventoryShareManager.Kind.INVENTORY)));
        comp = comp.replaceText(cfg -> cfg.match(EC_TOKEN).replacement((res, b) ->
                shareTag(sender, InventoryShareManager.Kind.ENDER_CHEST)));
        return comp;
    }

    private static Component shareTag(Player sender, InventoryShareManager.Kind kind) {
        String token = shareManager.share(sender.getUniqueId(), kind);
        // Nombre MOSTRADO (apodo si tiene): con /anonymous o /nick el tag no debe filtrar
        // el nombre real; y el click lleva un token opaco, nunca el UUID.
        String shown = dev.chatalyst.nick.NickManager.plainName(sender);
        String key = kind == InventoryShareManager.Kind.ENDER_CHEST
                ? "chat.share-ec-tag" : "chat.share-inv-tag";
        return dev.chatalyst.util.MM.full(dev.chatalyst.lang.Lang.raw(key,
                "player", dev.chatalyst.util.MM.escape(shown),
                "token", token));
    }

    private static Component replaceItemToken(Component comp, UUID senderId) {
        org.bukkit.inventory.ItemStack held = HeldItemTracker.held(senderId);
        if (held == null) return comp; // mano vacia: el texto [item] se queda tal cual
        return comp.replaceText(cfg -> cfg.match(ITEM_TOKEN).replacement(itemTag(held)));
    }

    /**
     * Etiqueta clicable de un item: [Nombre xN] en aqua con el tooltip completo al pasar el
     * raton. La usan el token [item] del chat y los anuncios del mercado.
     */
    public static Component itemTag(ItemStack item) {
        return itemTag(item, net.kyori.adventure.text.format.NamedTextColor.AQUA);
    }

    /**
     * Como {@link #itemTag(ItemStack)} pero con un color de tag concreto (highlight del
     * mercado por rango del vendedor). El color tiñe todo el tag; un item con nombre custom
     * conserva su propio estilo dentro.
     */
    public static Component itemTag(ItemStack item, net.kyori.adventure.text.format.TextColor color) {
        // Nombre del item: el custom si lo tiene, si no el traducible del material.
        // (No usamos ItemStack.effectiveName(): solo existe desde Paper 1.21.4.)
        // El nombre custom lo pone quien tiene el item (yunque, creativo, otro plugin) y puede
        // traer eventos propios: un clickEvent dentro del nombre se volvia un boton vivo en el
        // chat de todos. Se conserva el aspecto y se quitan click, hover e insertion.
        Component itemName = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                ? withoutInteraction(item.getItemMeta().displayName())
                : Component.translatable(item.getType().translationKey());
        return Component.text()
                .content("[")
                .append(itemName)
                .append(item.getAmount() > 1
                        ? Component.text(" x" + item.getAmount())
                        : Component.empty())
                .append(customItemSuffix(item))
                .append(Component.text("]"))
                .color(color)
                .hoverEvent(item)
                .build();
    }

    /** El componente con su aspecto intacto pero sin click, hover ni insertion, recursivo. */
    public static Component withoutInteraction(Component component) {
        if (component == null) return Component.empty();
        Component clean = component.clickEvent(null).hoverEvent(null).insertion(null);
        if (clean.children().isEmpty()) return clean;
        java.util.List<Component> children = new java.util.ArrayList<>(clean.children().size());
        for (Component child : clean.children()) children.add(withoutInteraction(child));
        return clean.children(children);
    }

    private static Component customItemSuffix(ItemStack held) {
        if (!itemCustomIds) return Component.empty();
        String id = dev.chatalyst.hook.NexoHook.itemId(held);
        String source = "nexo";
        if (id == null) {
            id = dev.chatalyst.hook.ItemsAdderHook.itemId(held);
            source = "itemsadder";
        }
        if (id == null || id.isBlank()) return Component.empty();
        return Component.text(" · " + source + ":" + id)
                .color(net.kyori.adventure.text.format.NamedTextColor.DARK_AQUA);
    }

    private static Component linkify(Component comp) {
        return comp.replaceText(cfg -> cfg.match(URL).replacement((res, b) -> {
            String url = res.group();
            String href = url.regionMatches(true, 0, "http", 0, 4) ? url : "https://" + url;
            return b.color(net.kyori.adventure.text.format.NamedTextColor.AQUA)
                    .decorate(net.kyori.adventure.text.format.TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.openUrl(href))
                    .hoverEvent(HoverEvent.showText(MM.full("<#C0B3D9>Open <#FBF2FF>" + url)));
        }));
    }

    private static Component buildHover(List<String> lines, Player sender, Map<String, String> vars) {
        Component hover = Component.empty();
        boolean first = true;
        for (String line : lines) {
            // Plantilla del staff: convertir :tokens: de ItemsAdder ANTES de los placeholders.
            String template = dev.chatalyst.hook.ItemsAdderHook.replaceEmojisUnchecked(line);
            String resolved = Placeholders.applyPrepared(sender, template, vars);
            // Una linea que se queda EXACTAMENTE vacia era solo un placeholder opcional
            // ({married_tag} de un soltero): se omite en vez de dejar un renglon en blanco.
            // Se compara con isEmpty, no isBlank: los separadores decorativos son espacios.
            if (resolved.isEmpty()) continue;
            if (!first) hover = hover.append(Component.newline());
            first = false;
            hover = hover.append(MM.full(resolved));
        }
        return hover;
    }

    private static ClickEvent buildClick(FormatPart part, Player sender, Map<String, String> vars) {
        if (part.runCommand != null) {
            return ClickEvent.runCommand(Placeholders.applyPrepared(sender, part.runCommand, vars));
        }
        if (part.suggestCommand != null) {
            return ClickEvent.suggestCommand(Placeholders.applyPrepared(sender, part.suggestCommand, vars));
        }
        if (part.openUrl != null) {
            return ClickEvent.openUrl(Placeholders.applyPrepared(sender, part.openUrl, vars));
        }
        if (part.copyToClipboard != null) {
            return ClickEvent.copyToClipboard(Placeholders.applyPrepared(sender, part.copyToClipboard, vars));
        }
        return null;
    }
}
