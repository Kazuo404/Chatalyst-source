package dev.chatalyst.hook;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.channel.Channel;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Exposes Chatalyst data to PlaceholderAPI so other plugins can read it:
 *   %chatalyst_channel%      active channel
 *   %chatalyst_nick%         nickname (or name)
 *   %chatalyst_nickcolor%    primary nick color as a legacy code (&c / &#RRGGBB)
 *   %chatalyst_muted%        yes / no
 *   %chatalyst_mute_reason%  mute reason
 *   %chatalyst_warnings%     warning points
 *   %chatalyst_chatcolor%    chosen chat color tag
 *   %chatalyst_prefix%       rank prefix; while /anonymous, the DISGUISE tag instead
 *   %chatalyst_suffix%       rank suffix; while /anonymous, the DISGUISE suffix instead
 *   %chatalyst_group%        primary group; while /anonymous, the DISGUISE group instead
 *   %chatalyst_anonymous%    yes / no
 *   %chatalyst_partner%      spouse's shown name (blank if single or /anonymous)
 *   %chatalyst_married%      yes / no
 *   %chatalyst_title_*%      equipped catalog-title identity and presentation
 *   %chatalyst_achievement_*% progression points and earned/total counts
 *   %chatalyst_collections_*% completed/total collection counts
 *   %chatalyst_fx_<name>%    current frame of an animated tag (see tagfx.yml)
 *   %chatalyst_fx_auto%      same, for the viewer's own rank; falls back to their prefix
 *
 * Isolated: this class extends a PlaceholderAPI type, so it is only touched when PAPI is
 * present ({@link #tryRegister} is called behind a plugin-presence check).
 */
public final class ChatalystExpansion extends PlaceholderExpansion {

    private final ChatalystPlugin plugin;

    private ChatalystExpansion(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    /** Registers the expansion. Call only when PlaceholderAPI is installed. */
    public static void tryRegister(ChatalystPlugin plugin) {
        try {
            new ChatalystExpansion(plugin).register();
            // El banner ya lista PlaceholderAPI en Hooks; este detalle va a debug.
            plugin.getLogger().fine("Registered PlaceholderAPI expansion (%chatalyst_...%).");
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not register PlaceholderAPI expansion: " + t.getMessage());
        }
    }

    @Override
    public @NotNull String getIdentifier() {
        return "chatalyst";
    }

    @Override
    public @NotNull String getAuthor() {
        return "DevKazuo";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // keep it registered across PAPI reloads
    }

    @Override
    public String onRequest(OfflinePlayer offline, @NotNull String params) {
        if (offline == null) return "";
        UUID id = offline.getUniqueId();
        Player online = offline.getPlayer();
        String parameter = params.toLowerCase(java.util.Locale.ROOT);

        switch (parameter) {
            case "nick": {
                String nick = plugin.getNickManager().getNick(id);
                // Cerrado: quien lo inserta (TAB, un scoreboard) lo pega junto a mas texto.
                return nick != null ? dev.chatalyst.util.MM.closed(nick) : offline.getName();
            }
            case "nickcolor":
                return Placeholders.nickColorLegacy(id);
            case "rank_anim":
                // Prefijo del rango ANIMADO (fotograma del momento) para scoreboards, hologramas, etc.
                return online != null ? rankAnim(online) : "";
            case "muted":
                return plugin.getMuteManager().isMuted(id) ? "yes" : "no";
            case "mute_reason": {
                var mute = plugin.getMuteManager().getMute(id);
                return mute != null ? mute.reason : "";
            }
            case "chatcolor":
                return plugin.getChatColorManager().getColorTag(id);
            case "channel": {
                if (online == null) return "";
                Channel ch = plugin.getChannelManager().getWriteChannel(online);
                return ch != null ? ch.name : "";
            }
            case "warnings":
                return online != null ? String.valueOf(plugin.getWarningPoints().get(online)) : "0";
            case "afk":
                return plugin.getAfkManager() != null && plugin.getAfkManager().isAfk(id) ? "yes" : "no";
            // Variantes CONSCIENTES del modo anonimo, para TAB/scoreboards de terceros:
            // %luckperms_prefix% delata el rango de un anonimo, %chatalyst_prefix% no.
            // Con un anonimo devuelven vacio (o su alias); con el resto, lo de siempre.
            case "prefix":
                return anonymous(id)
                        ? ItemsAdderHook.replaceEmojisUnchecked(
                                Placeholders.anonymousLook().prefix())
                        : ItemsAdderHook.replaceEmojisUnchecked(
                                LuckPermsHook.isAvailable() ? LuckPermsHook.prefix(id)
                                        : (online != null && VaultHook.isAvailable()
                                                ? VaultHook.prefix(online) : ""));
            case "suffix":
                return anonymous(id)
                        ? ItemsAdderHook.replaceEmojisUnchecked(
                                Placeholders.anonymousLook().suffix())
                        : ItemsAdderHook.replaceEmojisUnchecked(
                                LuckPermsHook.isAvailable() ? LuckPermsHook.suffix(id)
                                        : (online != null && VaultHook.isAvailable()
                                                ? VaultHook.suffix(online) : ""));
            case "group":
                return anonymous(id) ? Placeholders.anonymousLook().group()
                        : (LuckPermsHook.isAvailable()
                                ? LuckPermsHook.group(id)
                                : (online != null && VaultHook.isAvailable()
                                        ? VaultHook.group(online) : ""));
            case "anonymous":
                return anonymous(id) ? "yes" : "no";
            // Matrimonio. Con un anonimo van VACIOS: su pareja lo identifica igual de bien
            // que su propio nombre.
            case "partner":
                return plugin.getMarriageManager() == null || anonymous(id)
                        ? "" : plugin.getMarriageManager().partnerName(id);
            case "married":
                return plugin.getMarriageManager() != null && !anonymous(id)
                        && plugin.getMarriageManager().isMarried(id) ? "yes" : "no";
            default: {
                // Tag animado: devuelve el glifo del fotograma que toca ahora mismo.
                // No depende del jugador, asi que vale igual para un OfflinePlayer.
                // %chatalyst_smallcaps_{otro_placeholder}% o %chatalyst_smallcaps_texto%:
                // el resultado en versalitas, conservando sus colores. Para meter en el estilo
                // de letra pequeña del server textos que otros plugins no dan asi (el tag de
                // UltimateClans, por ejemplo). Con /anonymous no se resuelve: lo tipico es
                // pasarle datos de identidad (clan, rango) que el disfraz oculta.
                if (parameter.startsWith("smallcaps_")) {
                    if (anonymous(id)) return "";
                    String inner = params.substring("smallcaps_".length());
                    if (inner.indexOf('{') >= 0) {
                        try {
                            inner = me.clip.placeholderapi.PlaceholderAPI
                                    .setBracketPlaceholders(offline, inner);
                        } catch (Throwable unavailable) {
                            return "";
                        }
                    }
                    return dev.chatalyst.util.SmallCaps.convert(inner);
                }
                // %chatalyst_smallcaps_{otro_placeholder}% o %chatalyst_smallcaps_texto%:
                // el resultado en versalitas, conservando sus colores. Para meter en el estilo
                // de letra pequeña del server textos que otros plugins no dan asi (el tag de
                // UltimateClans, por ejemplo). Con /anonymous no se resuelve: lo tipico es
                // pasarle datos de identidad (clan, rango) que el disfraz oculta.
                if (parameter.startsWith("smallcaps_")) {
                    if (anonymous(id)) return "";
                    String inner = params.substring("smallcaps_".length());
                    if (inner.indexOf('{') >= 0) {
                        try {
                            inner = me.clip.placeholderapi.PlaceholderAPI
                                    .setBracketPlaceholders(offline, inner);
                        } catch (Throwable unavailable) {
                            return "";
                        }
                    }
                    return dev.chatalyst.util.SmallCaps.convert(inner);
                }
                // %chatalyst_counter_<clave>%: contador de titulos del jugador (regla COUNTER,
                // /tagcontador). Vale con el jugador desconectado; clave invalida = null.
                if (parameter.startsWith("counter_")) {
                    String key = parameter.substring("counter_".length());
                    if (!dev.chatalyst.tag.TitleCounters.validKey(key)) return null;
                    var counters = plugin.getTitleCounters();
                    return String.valueOf(counters == null ? 0L : counters.get(id, key));
                }
                if (parameter.startsWith("fx_")) {
                    var fx = plugin.getTagEffects();
                    if (fx == null) return "";
                    String name = parameter.substring(3);

                    // "auto" resuelve el rango de QUIEN mira. Con esto una sola linea de
                    // scoreboard vale para toda la red en vez de una por rango. Si su
                    // grupo no tiene animacion devuelve su prefijo normal, no vacio: una
                    // linea que se queda en blanco para la mitad de los rangos es peor
                    // que una sin animar.
                    if (name.equals("auto")) {
                        if (anonymous(id)) return "";
                        String grupo = LuckPermsHook.isAvailable() ? LuckPermsHook.group(id)
                                : (online != null && VaultHook.isAvailable()
                                        ? VaultHook.group(online) : "");
                        if (fx.has(grupo)) return fx.frame(grupo);
                        return ItemsAdderHook.replaceEmojisUnchecked(
                                LuckPermsHook.isAvailable() ? LuckPermsHook.prefix(id)
                                        : (online != null && VaultHook.isAvailable()
                                                ? VaultHook.prefix(online) : ""));
                    }
                    return fx.frame(name);
                }
                if ((parameter.startsWith("title_")
                        || parameter.startsWith("achievement_")
                        || parameter.startsWith("achievements_")
                        || parameter.startsWith("collections_"))
                        && plugin.getApi() != null) {
                    if (anonymous(id)) return "";
                    // Settings.Show_On_This_Server: false hides the title itself here; points and
                    // collection progress still resolve.
                    if (HIDDEN_TITLE_PARAMETERS.contains(parameter)
                            && plugin.getTagCatalog() != null
                            && !plugin.getTagCatalog().showOnThisServer()) return "";
                    return TitlePlaceholderValues.valueOf(
                            plugin.getApi().titles(), id, parameter);
                }
                return null; // unknown placeholder
            }
        }
    }

    private static final java.util.Set<String> HIDDEN_TITLE_PARAMETERS = java.util.Set.of(
            "title_id", "title_name", "title_text", "title_text_anim", "title_prefix", "title_suffix");

    /**
     * {rank_anim} con solo lo que necesita: grupo y prefijo, por las mismas fuentes y en el
     * mismo orden que {@link Placeholders#base} (disfraz del anonimo, LuckPerms, Vault). Un
     * scoreboard lo pide por jugador cada pocos ticks y construir el mapa entero (ping, party,
     * matrimonio, titulos...) para leer una clave era casi todo el coste.
     */
    private String rankAnim(Player online) {
        String group;
        String prefix;
        if (anonymous(online.getUniqueId())) {
            var look = Placeholders.anonymousLook();
            group = look.group();
            prefix = look.prefix();
        } else if (LuckPermsHook.isAvailable()) {
            String[] rank = LuckPermsHook.rankData(online.getUniqueId());
            group = rank[2];
            prefix = ItemsAdderHook.replaceEmojisUnchecked(rank[0]);
        } else if (VaultHook.isAvailable()) {
            group = VaultHook.group(online);
            prefix = ItemsAdderHook.replaceEmojisUnchecked(VaultHook.prefix(online));
        } else {
            group = "";
            prefix = "";
        }
        return dev.chatalyst.tablist.RankTagAnimator.frame(group, prefix);
    }

    private boolean anonymous(UUID id) {
        return plugin.getAnonymousManager() != null
                && plugin.getAnonymousManager().isAnonymous(id);
    }
}
